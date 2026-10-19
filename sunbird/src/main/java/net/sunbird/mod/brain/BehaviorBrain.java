package net.sunbird.mod.brain;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.sunbird.mod.SunbirdConfig;
import net.sunbird.mod.SunbirdMod;
import net.sunbird.mod.ai.AiBridge;
import net.sunbird.mod.ai.ChatHistory;
import net.sunbird.mod.ai.SunbirdPersona;
import net.sunbird.mod.mood.EmotionParser;
import net.sunbird.mod.mood.HauntDirector;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The heart: a single-threaded request queue in front of the llama.cpp backend.
 * One inference slot = no server lag spikes even on weak hardware, and every
 * player on the server shares the same brain (this is the big upgrade over
 * Verity's per-client model loading).
 *
 * The HauntDirector piggybacks on this thread for its 1-second mood heartbeat -
 * horror/comedy decisions are pure Java state machine work and cost ZERO extra
 * inference unless the bird actually wants to say an in-character line.
 */
public final class BehaviorBrain {
    private static final Queue<AiJob> QUEUE = new LinkedList<>();
    private static final Map<String, Long> PLAYER_LAST_REQUEST = new HashMap<>();
    private static long GLOBAL_LAST_REQUEST = 0L;
    private static boolean processing = false;
    private static ScheduledExecutorService scheduler;
    /** Set by HauntDirector during a haunt episode; prepended to every system prompt. */
    private static final AtomicReference<String> VOICE_OVERRIDE = new AtomicReference<>(null);

    private record AiJob(String kind, String prompt, String replyTarget /* null = global chat */) {}

    private BehaviorBrain() {}

    public static void setVoiceOverride(String instructionOrNull) {
        VOICE_OVERRIDE.set(instructionOrNull);
    }

    public static void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sunbird-brain");
            t.setDaemon(true);
            return t;
        });
        net.sunbird.mod.tts.TtsBridge.init(SunbirdConfig.get());
        SunbirdConfig cfg = SunbirdConfig.get();
        if (cfg.autonomousChatter) {
            scheduler.scheduleWithFixedDelay(BehaviorBrain::maybeAutonomousThought,
                    cfg.autonomousEverySeconds, cfg.autonomousEverySeconds, TimeUnit.SECONDS);
        }
        // Mood heartbeat: 1 second, off the tick loop, hops onto it only when effects fire.
        if (cfg.horrorEnabled || cfg.comedyEnabled) {
            scheduler.scheduleWithFixedDelay(() -> {
                try { HauntDirector.tickSecond(SunbirdMod.SERVER); }
                catch (Exception e) { SunbirdMod.LOGGER.debug("[Sunbird] mood tick failed", e); }
            }, 5, 1, TimeUnit.SECONDS);
        }
    }

    public static void stop() {
        if (scheduler != null) scheduler.shutdownNow();
    }

    /** Prime the llama.cpp context at boot so the first player question isn't slow. */
    public static void warmUp() {
        if (scheduler == null) return;
        scheduler.submit(() -> {
            try {
                AiBridge.ask(java.util.List.of(
                        new AiBridge.Turn("system", "You are a sunbird warming up. Reply with exactly: ok"),
                        new AiBridge.Turn("user", "ping"))).get(60, TimeUnit.SECONDS);
                SunbirdMod.LOGGER.info("[Sunbird] Brain warmed up.");
            } catch (Exception e) {
                SunbirdMod.LOGGER.warn("[Sunbird] Warm-up failed (backend offline?): {}", e.toString());
            }
        });
    }

    // ---------------- entry points (server thread) ----------------

    public static void onPlayerChat(ServerPlayerEntity player, String message) {
        SunbirdConfig cfg = SunbirdConfig.get();
        String lower = message.toLowerCase();
        boolean mentioned = false;
        for (String wake : cfg.wakeWords.split(",")) {
            if (!wake.isBlank() && lower.contains(wake.trim().toLowerCase())) {
                mentioned = true;
                break;
            }
        }

        // Cheap offline emotion read: feeds the mood engine whether or not we ask the LLM.
        EmotionParser.Read read = EmotionParser.read(message);
        HauntDirector.onMessage(player, message, read);

        boolean listen = cfg.listenToAllChat || mentioned
                || EmotionParser.worthAsking(read, mentioned);
        if (!listen) return;

        long now = System.currentTimeMillis();
        Long last = PLAYER_LAST_REQUEST.get(player.getName().getString());
        if (last != null && now - last < cfg.perPlayerCooldownSeconds * 1000L) return;
        PLAYER_LAST_REQUEST.put(player.getName().getString(), now);

        ChatHistory.add(player.getName().getString(), message);
        String flavour = switch (read.tone()) {
            case INSULT -> " (they just insulted you - react in character)";
            case THREAT -> " (this felt like a threat - you noticed, you always notice)";
            case APOLOGY -> " (they apologised - decide if you forgive them)";
            case OCCULT -> " (the question was dark - answer honestly)";
            case COMPLIMENT -> " (they complimented you - you like that)";
            default -> "";
        };
        enqueue("chat", player.getName().getString() + " says: " + message + flavour, null);
    }

    public static void onJoin(ServerPlayerEntity player) {
        net.sunbird.mod.i18n.LangService.remember(player);
        if (!SunbirdConfig.get().reactToEvents) return;
        ChatHistory.add("*system*", player.getName().getString() + " joined the server.");
        enqueue("event", "* " + player.getName().getString() + " just joined the server. Say something welcoming or curious.", null);
    }

    public static void onLeave(ServerPlayerEntity player) {
        net.sunbird.mod.i18n.LangService.forget(player);
        if (!SunbirdConfig.get().reactToEvents) return;
        ChatHistory.add("*system*", player.getName().getString() + " left the server.");
    }

    public static void onRespawn(ServerPlayerEntity player) {
        if (!SunbirdConfig.get().reactToEvents) return;
        ChatHistory.add("*system*", player.getName().getString() + " died and respawned.");
        enqueue("event", "* " + player.getName().getString() + " just died and came back. React like a concerned friend.", null);
    }

    /** A sunbird entity was killed - the mood engine remembers who did it, forever. */
    public static void onBirdDeath(String killerName) {
        HauntDirector.onDeath(killerName);
        enqueue("horror", "* One of your bodies just died. You feel yourself coming back WRONG. Say something unsettling about death and returning.", null);
    }

    /** Direct question from /ai command. */
    public static void askDirectly(ServerPlayerEntity player, String question) {
        ChatHistory.add(player.getName().getString() + " (private)", question);
        enqueue("direct", question, player.getName().getString());
    }

    /** Feed an observation (from PerceptionService) into the model stream. */
    public static void enqueueSystemNote(String note) {
        ChatHistory.add("*system*", note);
    }

    public static void maybeAutonomousThought() {
        MinecraftServer server = SunbirdMod.SERVER;
        if (server == null || server.getCurrentPlayerCount() == 0) return;
        String perception = PerceptionService.describeWorld(server);
        enqueue("autonomous",
                "* You glance around: " + perception + ". Optionally share one short thought with everyone.", null);
    }

    // ---------------- queue pump ----------------

    /** Mood engine (HauntDirector) schedules jobs from its own heartbeat thread via this. */
    public static synchronized void enqueue(String kind, String prompt, String target) {
        if (scheduler == null) return; // brain not started yet (server not up)
        QUEUE.add(new AiJob(kind, prompt, target));
        if (!processing) {
            processing = true;
            scheduler.submit(BehaviorBrain::pump);
        }
    }

    private static void pump() {
        while (true) {
            AiJob job;
            synchronized (BehaviorBrain.class) {
                job = QUEUE.poll();
                if (job == null) {
                    processing = false;
                    return;
                }
            }
            SunbirdConfig cfg = SunbirdConfig.get();
            // Global rate limit keeps CPU inference boxes responsive
            long wait = GLOBAL_LAST_REQUEST + cfg.globalCooldownSeconds * 1000L - System.currentTimeMillis();
            if (wait > 0) {
                try { TimeUnit.MILLISECONDS.sleep(wait); } catch (InterruptedException e) { return; }
            }
            GLOBAL_LAST_REQUEST = System.currentTimeMillis();

            var context = ChatHistory.context(null);
            String override = VOICE_OVERRIDE.get();
            if (override != null && !override.isBlank()) {
                context.add(0, new AiBridge.Turn("system", "VOICE OVERRIDE: " + override));
            }
            // Multi-language: whisper replies go in the asking player's client language.
            if (job.replyTarget() != null && SunbirdMod.SERVER != null) {
                var tgt = SunbirdMod.SERVER.getPlayerManager().getPlayer(job.replyTarget());
                String lang = net.sunbird.mod.i18n.LangService.aiLanguageInstruction(tgt);
                if (!lang.isBlank()) context.add(0, new AiBridge.Turn("system", lang));
            } else {
                String lang = net.sunbird.mod.i18n.LangService.aiLanguageDigest();
                if (!lang.isBlank()) context.add(0, new AiBridge.Turn("system", lang));
            }
            context.add(new AiBridge.Turn("user", job.prompt()));

            try {
                String reply = AiBridge.ask(context).get(cfg.requestTimeoutSeconds + 5, TimeUnit.SECONDS);
                MinecraftServer server = SunbirdMod.SERVER;
                if (server == null || reply == null || reply.isBlank()) continue;
                boolean haunted = VOICE_OVERRIDE.get() != null;
                if (job.replyTarget() != null) {
                    var player = server.getPlayerManager().getPlayer(job.replyTarget());
                    SunbirdPersona.whisper(server, player, reply);
                } else {
                    SunbirdPersona.say(server, reply, haunted);
                }
            } catch (Exception e) {
                SunbirdMod.LOGGER.debug("[Sunbird] AI job failed: {}", e.toString());
            }
        }
    }
}
