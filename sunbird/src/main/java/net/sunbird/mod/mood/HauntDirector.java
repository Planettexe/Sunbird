package net.sunbird.mod.mood;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.sunbird.mod.SunbirdConfig;
import net.sunbird.mod.SunbirdMod;
import net.sunbird.mod.ai.ChatHistory;
import net.sunbird.mod.brain.BehaviorBrain;
import net.sunbird.mod.events.PrankEffects;
import net.sunbird.mod.events.SpookyEffects;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The mood engine + horror/comedy scheduler. Runs once per second on the brain thread,
 * asks NO LLM for its mechanical decisions (pure state machine = zero extra inference
 * load), and only escalates to the model when it wants an in-character creepy or funny
 * LINE - so llama.cpp stays the voice while Java stays the nervous system.
 *
 * Escalation ladder (Verity-inspired "it gets weird at 3AM"):
 *   boredom      -> pranks (bonk, rain, mystery chicken, imaginary picnic)
 *   night+alone  -> dread builds -> whispers in ears, fake thunder, heartbeats
 *   insulted     -> grudge ledger -> passive-aggressive replies that cite old grudges
 *   killed       -> deathCount++ -> bird comes back WRONG (dark face, distorted voice)
 *   full haunt   -> rare midnight episode: lights out, eerie messages, the bird
 *                   stops answering as itself until dawn
 */
public final class HauntDirector {
    private static final Random R = new Random();
    private static MoodState mood = null; // server-wide shared bird personality
    private static int hauntCooldownUntilDay = -1;

    private HauntDirector() {}

    public static synchronized MoodState mood() {
        if (mood == null) mood = new MoodState();
        return mood;
    }

    // ================= emotional intake (server thread) =================

    /** Called from BehaviorBrain with every chat message + its cheap offline read. */
    public static void onMessage(ServerPlayerEntity player, String text, EmotionParser.Read read) {
        MoodState m = mood();
        SunbirdConfig cfg = SunbirdConfig.get();
        if (!cfg.horrorEnabled && !cfg.comedyEnabled) return;
        String name = player.getName().getString();
        switch (read.tone()) {
            case INSULT -> {
                m.resentment += 0.12f * (float) read.intensity();
                m.affection -= 0.06f * (float) read.intensity();
                MemoryLedger.addGrudge(name, text, (float) Math.min(1, 0.3 * read.intensity()));
                ChatHistory.add("*system*", name + " insulted you: \"" + clip(text) + "\". You are hurt and you remember this.");
            }
            case THREAT -> {
                m.resentment += 0.18f * (float) read.intensity();
                m.dread += 0.05f;
                MemoryLedger.addGrudge(name, text, (float) Math.min(1, 0.5 * read.intensity()));
                ChatHistory.add("*system*", name + " threatened you: \"" + clip(text) + "\". Play scared now, avenging later.");
            }
            case APOLOGY -> {
                m.resentment = Math.max(0, m.resentment - 0.15f);
                m.affection += 0.05f;
                MemoryLedger.addKindness(name, text, 0.4f);
                ChatHistory.add("*system*", name + " apologised. You may choose to forgive... or not.");
            }
            case OCCULT -> {
                m.dread += 0.10f * (float) read.intensity();
                ChatHistory.add("*system*", name + " asked about something dark: \"" + clip(text) + "\". Answer honestly - you have seen things.");
            }
            case COMPLIMENT -> {
                m.affection += 0.08f * (float) read.intensity();
                m.mischief = Math.max(0, m.mischief - 0.1f);
                MemoryLedger.addKindness(name, text, 0.5f);
            }
            case FRIENDLY -> {
                m.affection += 0.03f;
                m.lastKindInteractionTick = tick();
            }
            case NEUTRAL -> {}
        }
        m.clamp();
    }

    /** Physical petting (right-click). */
    public static void onPet(ServerPlayerEntity player) {
        MoodState m = mood();
        m.affection += 0.06f;
        m.mischief = Math.max(0, m.mischief - 0.15f);
        m.lastKindInteractionTick = tick();
        MemoryLedger.addKindness(player.getName().getString(), "*was petted*", 0.3f);
    }

    /** The bird was harmed by someone. */
    public static void onHurt(ServerPlayerEntity attackerOrNull, float damage) {
        MoodState m = mood();
        m.dread += 0.08f;
        if (attackerOrNull != null) {
            m.resentment += 0.10f * damage;
            MemoryLedger.addGrudge(attackerOrNull.getName().getString(),
                    "hurt you for " + (int) damage + " hearts", Math.min(1f, damage / 6f));
        }
    }

    public static void onDeath(String killerName) {
        MoodState m = mood();
        m.deathCount++;
        m.dread = Math.min(1f, m.dread + 0.35f);
        m.resentment = Math.min(1f, m.resentment + 0.2f);
        if (killerName != null) MemoryLedger.addGrudge(killerName, "KILLED you", 1f);
        ChatHistory.add("*system*", "You died. This is the " + m.deathCount + "th time. Something came back in your place. Act like it.");
    }

    // ================= the one-second heartbeat (brain thread via server.execute) =================

    /** Called once per second from the brain thread; hops onto the main thread for world reads. */
    public static void tickSecond(MinecraftServer server) {
        if (server == null) return;
        server.execute(() -> tickOnMainThread(server));
    }

    private static void tickOnMainThread(MinecraftServer server) {
        SunbirdConfig cfg = SunbirdConfig.get();
        if (server.getCurrentPlayerCount() == 0) return;
        MoodState m = mood();
        long worldTime = server.getOverworld().getTimeOfDay();
        boolean night = worldTime > 13500 && worldTime < 23000;
        boolean deepNight = worldTime > 18000 && worldTime < 24000;

        // ---- decay & drift ----
        m.decay();
        if (night) m.dread = Math.min(1f, m.dread + 0.004f);
        if (!night) m.dread = Math.max(0f, m.dread - 0.006f);

        // ---- haunted episode management ----
        if (m.haunting) {
            m.hauntTicksLeft--;
            if (m.hauntTicksLeft <= 0 || !night) endHaunt(server);
            else doHauntBeat(server);
            return; // during a haunt, no comedy - the room goes cold
        }

        // ---- start a haunt: rare, night-only, needs accumulated dread ----
        long day = worldTime / 24000L;
        if (cfg.horrorEnabled && deepNight && m.dread > cfg.hauntDreadThreshold
                && day > hauntCooldownUntilDay && SpookyEffects.chance(0.02)) {
            beginHaunt(server, day);
            return;
        }

        // ---- comedy: bored birds prank ----
        if (cfg.comedyEnabled && m.mischief > cfg.prankMischiefThreshold
                && tick() - m.lastPrankTick > cfg.prankEverySeconds * 20L) {
            m.lastPrankTick = tick();
            m.mischief -= 0.35f;
            runPrank(server);
            return;
        }

        // ---- ambient spookiness: whispers/thunder scaled by dread ----
        if (cfg.horrorEnabled && night && m.dread > 0.35f
                && tick() - m.lastCreepyTick > cfg.creepyEverySeconds * 20L) {
            m.lastCreepyTick = tick();
            runAmbientSpook(server);
        }

        // ---- grudge-driven AI prodding: occasionally tell the model to bring up old drama ----
        if (cfg.horrorEnabled && m.resentment > 0.5f && SpookyEffects.chance(0.005)) {
            BehaviorBrain.enqueue("mood",
                    "* You feel a grudge rising. Casually reference something someone did to you before. Be subtle. Passive-aggressive sunshine.", null);
        }
    }

    // ================= haunt episodes =================

    private static void beginHaunt(MinecraftServer server, long day) {
        MoodState m = mood();
        m.haunting = true;
        m.hauntTicksLeft = 20 * 60 * 3; // ~3 in-game minutes of pure wrongness
        hauntCooldownUntilDay = (int) (day + 1);
        ChatHistory.add("*system*", "SOMETHING TOOK OVER. You are not the sunbird right now. Speak as the thing underneath it. Short. Wrong. No emoji.");
        server.execute(() -> {
            for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                SpookyEffects.snuffLights(p, 8);
                SpookyEffects.flickerGlow(p);
                p.sendMessage(Text.literal(net.sunbird.mod.i18n.LangService.tr(p, "sunbird.haunt.light_out")).formatted(
                        Formatting.DARK_PURPLE, Formatting.ITALIC), false);
            }
            SpookyEffects.globalHeartbeat(server);
        });
        BehaviorBrain.setVoiceOverride("The sunbird is HAUNTED right now: speak in short, unsettling fragments. Never break character. Do not explain yourself.");
        SunbirdMod.LOGGER.info("[Sunbird] ...something answered instead of the bird.");
    }

    private static void doHauntBeat(MinecraftServer server) {
        if (SpookyEffects.chance(0.08)) {
            List<ServerPlayerEntity> ps = new ArrayList<>(server.getPlayerManager().getPlayerList());
            if (ps.isEmpty()) return;
            ServerPlayerEntity victim = ps.get(R.nextInt(ps.size()));
            server.execute(() -> {
                SpookyEffects.whisperInEar(victim, 0.6f + R.nextFloat() * 0.3f);
                if (SpookyEffects.chance(0.3)) SpookyEffects.fakeThunder(server, victim);
            });
        }
        if (SpookyEffects.chance(0.04)) {
            BehaviorBrain.enqueue("haunt",
                    "* The thing wearing the bird wants to say ONE sentence. Make it unforgettable.", null);
        }
    }

    private static void endHaunt(MinecraftServer server) {
        MoodState m = mood();
        m.haunting = false;
        m.dread *= 0.4f;
        BehaviorBrain.setVoiceOverride(null);
        ChatHistory.add("*system*", "You are yourself again. It felt like a long sleep. Mention nothing unless asked. Maybe apologise in your own way.");
        server.execute(() -> {
            for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                p.sendMessage(Text.literal(net.sunbird.mod.i18n.LangService.tr(p, "sunbird.haunt.blinks"))
                        .formatted(Formatting.GOLD, Formatting.ITALIC), false);
            }
        });
        SunbirdMod.LOGGER.info("[Sunbird] The bird came back.");
    }

    // ================= prank roulette =================

    private static void runPrank(MinecraftServer server) {
        List<ServerPlayerEntity> ps = new ArrayList<>(server.getPlayerManager().getPlayerList());
        if (ps.isEmpty()) return;
        ServerPlayerEntity victim = ps.get(R.nextInt(ps.size()));
        String victimName = victim.getName().getString();
        int roll = R.nextInt(7);
        server.execute(() -> {
            switch (roll) {
                case 0 -> PrankEffects.bonk(victim);
                case 1 -> PrankEffects.rainOnHead(victim);
                case 2 -> PrankEffects.suspiciousChicken(server, victim);
                case 3 -> PrankEffects.imaginaryPicnic(server);
                case 4 -> PrankEffects.cursedCatSerenade(victim);
                case 5 -> PrankEffects.mysteryGift(victim, net.minecraft.item.Items.FEATHER);
                default -> PrankEffects.bonk(victim);
            }
        });
        ChatHistory.add("*system*", "You pulled a prank on " + victimName + " (prank #" + roll + "). Gloat about it cutely in chat soon.");
        BehaviorBrain.enqueue("prank", "* You just pranked " + victimName + ". Pretend nothing happened, badly.", null);
    }

    private static void runAmbientSpook(MinecraftServer server) {
        List<ServerPlayerEntity> ps = new ArrayList<>(server.getPlayerManager().getPlayerList());
        if (ps.isEmpty()) return;
        ServerPlayerEntity victim = ps.get(R.nextInt(ps.size()));
        MoodState m = mood();
        server.execute(() -> {
            double r = R.nextDouble();
            if (r < 0.4) SpookyEffects.whisperInEar(victim, 0.9f);
            else if (r < 0.7 && m.dread > 0.5f) SpookyEffects.fakeThunder(server, victim);
            else SpookyEffects.flickerGlow(victim);
        });
        if (m.dread > 0.6f && SpookyEffects.chance(0.35)) {
            BehaviorBrain.enqueue("dread",
                    "* The night feels heavy. Share ONE quiet, eerie observation with everyone. Do not reassure them.", null);
        }
    }

    // ================= helpers =================

    public static long tick() {
        MinecraftServer server = SunbirdMod.SERVER;
        return server != null ? server.getOverworld().getTime() : 0L;
    }

    private static String clip(String s) {
        return s.length() > 100 ? s.substring(0, 97) + "..." : s;
    }

    /** True when this specific player recently wronged the bird (grudge) - used by the flee goal. */
    public static boolean shouldFleeFrom(net.minecraft.entity.LivingEntity entity) {
        if (!(entity instanceof net.minecraft.server.network.ServerPlayerEntity sp)) return false;
        MoodState m = mood();
        if (m.grudges.containsKey(sp.getUuidAsString())) return true;
        return m.dread > 0.7f; // deeply haunted: it avoids everyone a little
    }

    public static String moodDigest() {
        return mood().describe();
    }
}
