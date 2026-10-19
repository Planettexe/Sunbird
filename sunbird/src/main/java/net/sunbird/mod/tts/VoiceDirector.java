package net.sunbird.mod.tts;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Box;
import net.sunbird.mod.SunbirdConfig;
import net.sunbird.mod.SunbirdMod;
import net.sunbird.mod.entity.SunbirdEntity;
import net.sunbird.mod.net.SunbirdClientNetworking;

/**
 * Bridges finished TTS clips into the world. The server synthesises once (TtsBridge),
 * then ships the SAME bytes to every opted-in modded client with the bird's
 * position, so everyone hears the yellow ball talk from exactly where it floats -
 * 3D falloff included. Vanilla clients receive nothing and lose nothing.
 *
 * Warp selection is where horror/comedy meets audio:
 *   haunted line   -> DEMON (slowed, pitch-down, wobbling) / rare REVERSED
 *   prank gloating -> HELLO_KITTY (chipmunk)
 *   whisper        -> FAR_AWAY (muffled)
 */
public final class VoiceDirector {
    private static final java.util.Random R = new java.util.Random();

    private VoiceDirector() {}

    public static void voiceGlobal(MinecraftServer server, String text, boolean haunted) {
        if (!TtsBridge.enabled() || server == null) return;
        SunbirdConfig cfg = SunbirdConfig.get();
        TtsBridge.Warp warp = chooseWarp(cfg, haunted, false);
        int[] anchor = nearestBirdAnchor(server);
        TtsBridge.speak("g:" + warp.name() + ":" + text, text, warp, wav -> {
            if (wav.length > cfg.ttsMaxKbPerLine * 1024) {
                SunbirdMod.LOGGER.debug("[Sunbird] TTS clip too big ({} KB), chat-only",
                        wav.length / 1024);
                return;
            }
            server.execute(() -> SunbirdClientNetworking.broadcastVoice(
                    server, anchor[0], anchor[1], anchor[2], anchor[3],
                    cfg.ttsVolume, wav));
        });
    }

    /** Private reply: only the asked player hears it, at the bird's location. */
    public static void voiceWhisper(ServerPlayerEntity target, String text) {
        MinecraftServer server = SunbirdMod.SERVER;
        if (!TtsBridge.enabled() || server == null || target == null) return;
        SunbirdConfig cfg = SunbirdConfig.get();
        if (cfg.ttsMutedPlayers.contains(target.getName().getString())) return;
        TtsBridge.Warp warp = cfg.ttsHorrorWarps ? TtsBridge.Warp.FAR_AWAY : TtsBridge.Warp.NONE;
        int[] anchor = nearestBirdAnchor(server);
        TtsBridge.speak("w:" + target.getName().getString() + ":" + text, text, warp, wav -> {
            if (wav.length > cfg.ttsMaxKbPerLine * 1024) return;
            server.execute(() -> SunbirdClientNetworking.sendVoiceTo(target, anchor,
                    cfg.ttsVolume * 0.7f, wav));
        });
    }

    /** Prank gloating gets the chipmunk treatment when warps are on. */
    public static void voicePrank(MinecraftServer server, String text) {
        if (!TtsBridge.enabled() || server == null) return;
        SunbirdConfig cfg = SunbirdConfig.get();
        TtsBridge.Warp warp = chooseWarp(cfg, false, true);
        int[] anchor = nearestBirdAnchor(server);
        TtsBridge.speak("p:" + text, text, warp, wav -> {
            if (wav.length > cfg.ttsMaxKbPerLine * 1024) return;
            server.execute(() -> SunbirdClientNetworking.broadcastVoice(
                    server, anchor[0], anchor[1], anchor[2], anchor[3],
                    cfg.ttsVolume, wav));
        });
    }

    private static TtsBridge.Warp chooseWarp(SunbirdConfig cfg, boolean haunted, boolean prank) {
        if (!cfg.ttsHorrorWarps) return TtsBridge.Warp.NONE;
        if (haunted) {
            // Deep in a haunt, some lines come out backwards. You'll hear it before you get it.
            return R.nextDouble() < 0.25 ? TtsBridge.Warp.REVERSED : TtsBridge.Warp.DEMON;
        }
        if (prank) return TtsBridge.Warp.HELLO_KITTY;
        return TtsBridge.Warp.NONE;
    }

    /**
     * Returns [entityId, x*8, y*8, z*8] of the sunbird closest to any online player.
     * Falls back to [-1, above the first player's head] so audio still plays
     * somewhere sane if no entity is loaded yet.
     */
    private static int[] nearestBirdAnchor(MinecraftServer server) {
        try {
            for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                var nearby = p.getWorld().getEntitiesByClass(SunbirdEntity.class,
                        new Box(p.getBlockPos()).expand(64), SunbirdEntity::isAlive);
                if (!nearby.isEmpty()) {
                    SunbirdEntity b = nearby.get(0);
                    double best = Double.MAX_VALUE;
                    for (SunbirdEntity c : nearby) {
                        double d = c.squaredDistanceTo(p);
                        if (d < best) { best = d; b = c; }
                    }
                    return new int[]{b.getId(),
                            (int) (b.getX() * 8), (int) (b.getY() * 8), (int) (b.getZ() * 8)};
                }
            }
        } catch (Exception ignored) {}
        ServerPlayerEntity p = server.getPlayerManager().getPlayerList().stream()
                .findFirst().orElse(null);
        if (p != null) {
            return new int[]{-1,
                    (int) (p.getX() * 8), (int) ((p.getY() + 1.5) * 8), (int) (p.getZ() * 8)};
        }
        return new int[]{-1, 0, 800, 0}; // (0,100,0): harmless nowhere-land default
    }
}
