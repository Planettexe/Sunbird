package net.sunbird.mod.brain;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.sunbird.mod.SunbirdConfig;
import net.sunbird.mod.SunbirdMod;
import net.sunbird.mod.ai.AiBridge;
import net.sunbird.mod.ai.ChatHistory;
import net.sunbird.mod.ai.SunbirdPersona;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The heart: a single-threaded request queue in front of the llama.cpp backend.
 * One inference slot = no server lag spikes even on weak hardware, and every
 * player on the server shares the same brain (this is the big upgrade over
 * Verity's per-client model loading).
 */
public final class BehaviorBrain {
    private static final Queue<AiJob> QUEUE = new LinkedList<>();
    private static final Map<String, Long> PLAYER_LAST_REQUEST = new HashMap<>();
    private static long GLOBAL_LAST_REQUEST = 0L;
    private static boolean processing = false;
    private static ScheduledExecutorService scheduler;

    private record AiJob(String kind, String prompt, String replyTarget /* null = global chat */) {}

    private BehaviorBrain() {}

    public static void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sunbird-brain");
            t.setDaemon(true);
            return t;
        });
        SunbirdConfig cfg = SunbirdConfig.get();
        if (cfg.autonomousChatter) {
            scheduler.scheduleWithFixedDelay(BehaviorBrain::maybeAutonomousThought,
                    cfg.autonomousEverySeconds, cfg.autonomousEverySeconds, TimeUnit.SECONDS);
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
        boolean mentioned = cfg.listenToAllChat;
        if (!mentioned) {
            for (String wake : cfg.wakeWords.split(",")) {
                if (!wake.isBlank() && lower.contains(wake.trim().toLowerCase())) {
                    mentioned = true;
                    break;
                }
            }
        }
        if (!mentioned) return;

        long now = System.currentTimeMillis();
        Long last = PLAYER_LAST_REQUEST.get(player.getName().getString());
        if (last != null && now - last < cfg.perPlayerCooldownSeconds * 1000L) return;
        PLAYER_LAST_REQUEST.put(player.getName().getString(), now);

        ChatHistory.add(player.getName().getString(), message);
        enqueue("chat", player.getName().getString() + " says: " + message, null);
    }

    public static void onJoin(ServerPlayerEntity player) {
        if (!SunbirdConfig.get().reactToEvents) return;
        ChatHistory.add("*system*", player.getName().getString() + " joined the server.");
        enqueue("event", "* " + player.getName().getString() + " just joined the server. Say something welcoming or curious.", null);
    }

    public static void onLeave(ServerPlayerEntity player) {
        if (!SunbirdConfig.get().reactToEvents) return;
        ChatHistory.add("*system*", player.getName().getString() + " left the server.");
    }

    public static void onRespawn(ServerPlayerEntity player) {
        if (!SunbirdConfig.get().reactToEvents) return;
        ChatHistory.add("*system*", player.getName().getString() + " died and respawned.");
        enqueue("event", "* " + player.getName().getString() + " just died and came back. React like a concerned friend.", null);
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

    private static synchronized void enqueue(String kind, String prompt, String target) {
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
            context.add(new AiBridge.Turn("user", job.prompt()));

            try {
                String reply = AiBridge.ask(context).get(cfg.requestTimeoutSeconds + 5, TimeUnit.SECONDS);
                MinecraftServer server = SunbirdMod.SERVER;
                if (server == null || reply == null || reply.isBlank()) continue;
                if (job.replyTarget() != null) {
                    var player = server.getPlayerManager().getPlayer(job.replyTarget());
                    SunbirdPersona.whisper(server, player, reply);
                } else {
                    SunbirdPersona.say(server, reply);
                }
            } catch (Exception e) {
                SunbirdMod.LOGGER.debug("[Sunbird] AI job failed: {}", e.toString());
            }
        }
    }
}
