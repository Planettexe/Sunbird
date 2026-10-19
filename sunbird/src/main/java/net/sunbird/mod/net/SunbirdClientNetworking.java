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

    private SunbirdClientNetworking() {}

    public static void registerServerPayloadHandlers() {
        // S2C only - no C2S handlers needed since all AI runs on the server.
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
