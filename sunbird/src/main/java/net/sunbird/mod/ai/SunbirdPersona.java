package net.sunbird.mod.ai;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.sunbird.mod.SunbirdConfig;
import net.sunbird.mod.SunbirdMod;
import net.sunbird.mod.brain.BehaviorBrain;
import net.sunbird.mod.net.SunbirdClientNetworking;
import net.sunbird.mod.tts.VoiceDirector;

/**
 * Delivers model output to players. Works with vanilla clients (chat messages) and
 * upgrades automatically for clients that also run Sunbird (entity chat bubbles +
 * chirps + server-synthesised TTS voice streamed from the host).
 */
public final class SunbirdPersona {
    private static long lastGreetingDay = -1;

    private SunbirdPersona() {}

    public static String name() {
        return SunbirdConfig.get().personaName;
    }

    /** Say something to the whole server as the bird. Thread-safe: schedules onto main thread. */
    public static void say(MinecraftServer server, String text) {
        say(server, text, false);
    }

    /** Haunted variant: dark formatting + distorted pitch so modded clients hear it too. */
    public static void say(MinecraftServer server, String text, boolean haunted) {
        if (text == null || text.isBlank()) return;
        String clean = sanitize(text);
        ChatHistory.add(name(), clean);
        server.execute(() -> {
            Text msg = Text.literal("[" + name() + "] ")
                    .formatted(haunted ? net.minecraft.util.Formatting.DARK_PURPLE : net.minecraft.util.Formatting.GOLD)
                    .append(Text.literal(clean).formatted(
                            haunted ? net.minecraft.util.Formatting.ITALIC : net.minecraft.util.Formatting.YELLOW));
            server.getPlayerManager().broadcast(msg, false);
            // Tell modded clients which nearby bird should show a speech bubble / chirp
            SunbirdClientNetworking.broadcastBubble(server, clean, haunted);
        });
        // Voice is queued on the TTS worker thread - never blocks the tick loop.
        // Haunted lines come out DEMON-warped; normal lines in the plain hosted voice.
        VoiceDirector.voiceGlobal(server, clean, haunted && SunbirdConfig.get().ttsHorrorWarps);
    }

    /** Whisper a reply privately to one player (used by right-click conversations). */
    public static void whisper(MinecraftServer server, ServerPlayerEntity player, String text) {
        if (text == null || text.isBlank() || player == null) return;
        String clean = sanitize(text);
        server.execute(() -> player.sendMessage(
                Text.literal(name() + " whispers: ").formatted(net.minecraft.util.Formatting.GOLD)
                        .append(Text.literal(clean).formatted(net.minecraft.util.Formatting.ITALIC)),
                false));
        VoiceDirector.voiceWhisper(player, clean);
    }

    /** Rotate a fresh "morning" greeting prompt each in-game day so the bird feels alive. */
    public static void maybeRotateGreeting() {
        MinecraftServer server = SunbirdMod.SERVER;
        if (server == null) return;
        long day = server.getOverworld().getTimeOfDay() / 24000L;
        if (day != lastGreetingDay) {
            lastGreetingDay = day;
            BehaviorBrain.enqueueSystemNote("A new day has begun in the world. You may greet everyone or muse about the morning light.");
        }
    }

    private static String sanitize(String text) {
        // One line, capped length - chat is not a blog
        String oneLine = text.replace("\r", "").replace("\n", " ").trim();
        return oneLine.length() > 280 ? oneLine.substring(0, 277) + "..." : oneLine;
    }
}
