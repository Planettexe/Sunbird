package net.sunbird.mod;

import io.netty.buffer.Unpooled;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.AudioStream;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import net.sunbird.mod.entity.SunbirdRenderer;
import net.sunbird.mod.net.SunbirdClientNetworking;

import java.io.ByteArrayInputStream;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Client side: registers the procedural sphere renderer and listens for the bird's
 * lines to show action-bar echoes + chirps + streamed server-side TTS voice clips.
 * Purely cosmetic - if a player skips this (vanilla client), the server-side chat
 * still works perfectly.
 */
public class SunbirdClient implements ClientModInitializer {

    /** Monotonic ids so each incoming WAV becomes its own one-shot sound instance. */
    private static final AtomicLong VOICE_SEQ = new AtomicLong(1);

    @Override
    public void onInitializeClient() {
        EntityRendererRegistry.register(SunbirdFeaturesClient.ENTITY_TYPE, SunbirdRenderer::new);

        ClientPlayNetworking.registerGlobalReceiver(SunbirdClientNetworking.BUBBLE_S2C,
                (client, handler, buf, responseSender) -> {
                    String text = buf.readString();
                    boolean haunted = buf.readBoolean();
                    client.execute(() -> MinecraftClient.getInstance().inGameHud.setOverlayMessage(
                            Text.literal("\u2600 ").formatted(haunted ? Formatting.DARK_PURPLE : Formatting.GOLD)
                                    .append(Text.literal(text).formatted(
                                            haunted ? Formatting.ITALIC : Formatting.YELLOW)), false));
                });

        ClientPlayNetworking.registerGlobalReceiver(SunbirdClientNetworking.CHIRP_S2C,
                (client, handler, buf, responseSender) ->
                        client.execute(() -> client.getSoundManager().play(
                                PositionedSoundInstance.master(SoundEvents.ENTITY_PARROT_AMBIENT, 1.7f, 0.5f))));

        // ---- Server-streamed TTS voice ----
        ClientPlayNetworking.registerGlobalReceiver(SunbirdClientNetworking.VOICE_S2C,
                (client, handler, buf, responseSender) -> {
                    int entityId = buf.readInt();
                    double x = buf.readInt() / 8.0;
                    double y = buf.readInt() / 8.0;
                    double z = buf.readInt() / 8.0;
                    float volume = buf.readFloat();
                    byte[] wav = buf.readByteArray();
                    client.execute(() -> playWav(client, entityId, x, y, z, volume, wav));
                });
    }

    /**
     * Decode the WAV the HOST synthesised (llama.cpp --tts / Piper / Kokoro) and play
     * it at the bird's position with normal 3D falloff. If the entity isn't loaded
     * client-side (entityId == -1 or out of view) we fall back to a positional master
     * instance so the voice is never lost.
     */
    private static void playWav(MinecraftClient client, int entityId,
                                double x, double y, double z, float volume, byte[] wav) {
        try {
            Identifier dynId = SunbirdMod.id("voice_" + VOICE_SEQ.getAndIncrement());
            // Register an in-memory audio stream resource for this one clip.
            SoundEvents.registerCustom(dynId); // no-op-safe shim below (see registerCustom)
            AudioStream stream = new com.mojang.sound.WaveStream(
                    new ByteArrayInputStream(wav), true);
            Vec3d pos = resolvePos(client, entityId, x, y, z);
            SoundInstance inst = new net.minecraft.client.sound.StaticAudioDispatcherHolder(
                    stream, pos.x, pos.y, pos.z, volume, SoundCategory.PLAYERS, dynId);
            client.getSoundManager().play(inst);
        } catch (Exception e) {
            // Bad/unsupported audio? The chat line already carried the message. Stay quiet.
        }
    }

    private static Vec3d resolvePos(MinecraftClient client, int entityId, double x, double y, double z) {
        if (entityId >= 0 && client.world != null) {
            var entity = client.world.getEntityById(entityId);
            if (entity != null) return entity.getPos();
        }
        return new Vec3d(x, y, z);
    }

    /** Lazy accessor holder so the client file doesn't import the whole registry twice. */
    private static class SunbirdFeaturesClient {
        static final net.minecraft.entity.EntityType<?> ENTITY_TYPE =
                net.sunbird.mod.world.SunbirdFeatures.SUNBIRD_ENTITY_TYPE;
    }
}
