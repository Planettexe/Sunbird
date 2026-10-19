package net.sunbird.mod.net;

import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import net.sunbird.mod.SunbirdMod;

/**
 * Server -> client payloads. Everything degrades gracefully: vanilla clients simply
 * never receive these packets (they only see the chat messages), modded clients get
 * the extra sparkle (speech-bubble events + chirp sounds near birds).
 */
public final class SunbirdClientNetworking {
    public static final Identifier BUBBLE_S2C = SunbirdMod.id("s2c_bubble");
    public static final Identifier CHIRP_S2C = SunbirdMod.id("s2c_chirp");
    public static final Identifier VOICE_S2C = SunbirdMod.id("s2c_voice");

    private SunbirdClientNetworking() {}

    public static void registerServerPayloadHandlers() {
        // All Sunbird traffic is S2C; voice toggling is done server-side via /aivoice.
        // This keeps the mod safe for vanilla clients joining a modded server.
    }

    /** Broadcast "the bird just spoke" so modded clients can animate nearby birds. */
    public static void broadcastBubble(MinecraftServer server, String text, boolean haunted) {
        sendToAll(server, BUBBLE_S2C, buf -> {
            buf.writeString(text);
            buf.writeBoolean(haunted);
        });
    }

    public static void broadcastChirp(MinecraftServer server) {
        sendToAll(server, CHIRP_S2C, buf -> {});
    }

    /**
     * Stream a synthesised WAV clip from the SERVER to every modded client that
     * opted in. Vanilla clients never receive this (canSend check) and are none
     * the wiser. Payload: entity id (-1 = free-floating), x*8/y*8/z*8, volume, wav.
     */
    public static void broadcastVoice(MinecraftServer server, int entityId,
                                      int x8, int y8, int z8,
                                      float volume, byte[] wav) {
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            if (!sendVoiceTo(player, entityId, x8, y8, z8, volume, wav)) {
                // Player's client lacks the mod - chat-only mode for them. Perfectly fine.
            }
        }
    }

    /** Send one voice clip to exactly one player (private whispers). */
    public static boolean sendVoiceTo(ServerPlayerEntity player, int[] anchor,
                                      float volume, byte[] wav) {
        return sendVoiceTo(player, anchor[0], anchor[1], anchor[2], anchor[3], volume, wav);
    }

    public static boolean sendVoiceTo(ServerPlayerEntity player, int entityId,
                                      int x8, int y8, int z8, float volume, byte[] wav) {
        try {
            if (!VoicePrefs.wants(player.getUuid())) return false;
            if (net.sunbird.mod.SunbirdConfig.get().ttsMutedPlayers
                    .contains(player.getName().getString())) return false;
            if (!ServerPlayNetworking.canSend(player, VOICE_S2C)) return false;
            PacketByteBuf buf = PacketByteBufs.create();
            buf.writeInt(entityId);
            buf.writeInt(x8);
            buf.writeInt(y8);
            buf.writeInt(z8);
            buf.writeFloat(volume);
            buf.writeByteArray(wav);
            ServerPlayNetworking.send(player, VOICE_S2C, buf);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static void sendToAll(MinecraftServer server, Identifier channel,
                                  java.util.function.Consumer<PacketByteBuf> writer) {
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            try {
                if (ServerPlayNetworking.canSend(player, channel)) {
                    PacketByteBuf buf = PacketByteBufs.create();
                    writer.accept(buf);
                    ServerPlayNetworking.send(player, channel, buf);
                }
            } catch (Exception ignored) {
                // Player's client lacks the mod - chat-only mode for them. Perfectly fine.
            }
        }
    }
}
