package net.sunbird.mod;

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
import net.minecraft.util.Formatting;
import net.minecraft.util.math.Vec3d;
import net.sunbird.mod.entity.SunbirdRenderer;
import net.sunbird.mod.net.SunbirdClientNetworking;
import net.sunbird.mod.world.SunbirdFeatures;

import javax.sound.sampled.AudioFormat;
import java.io.ByteArrayInputStream;
import java.io.InputStream;

/**
 * Client side: registers the procedural sphere renderer and listens for the bird's
 * lines to show action-bar echoes + chirps + streamed server-side TTS voice clips.
 * Purely cosmetic - if a player skips this (vanilla client), the server-side chat
 * still works perfectly.
 */
public class SunbirdClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        EntityRendererRegistry.register(SunbirdFeatures.SUNBIRD_ENTITY_TYPE, SunbirdRenderer::new);

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
     * client-side (entityId == -1 or out of view) we fall back to the send position.
     */
    private static void playWav(MinecraftClient client, int entityId,
                                double x, double y, double z, float volume, byte[] wav) {
        try {
            AudioStream stream = new WavAudioStream(new ByteArrayInputStream(wav));
            Vec3d pos = resolvePos(client, entityId, x, y, z);
            RawWavPlayer.play(client, stream, pos, volume);
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

    /**
     * Minimal PCM WAV decoder implementing Minecraft's AudioStream interface.
     * Handles 16-bit signed little-endian PCM mono/stereo (what Piper/Kokoro/
     * llama.cpp TTS tools emit). Anything fancier is rejected gracefully.
     */
    private static final class WavAudioStream implements AudioStream {
        private final InputStream source;
        private final AudioFormat format;

        WavAudioStream(InputStream in) throws Exception {
            this.source = in;
            in.mark(1 << 24);
            readExpected("RIFF");
            skipFully(4);                       // file size
            readExpected("WAVE");
            int channels = 1, sampleRate = 22050, bitsPerSample = 16;
            while (true) {
                String id = new String(readExactly(4));
                int size = readIntLE();
                if (id.equals("fmt ")) {
                    readIntLE();                // audio format (1 = PCM)
                    channels = readShortLE();
                    sampleRate = readIntLE();
                    readIntLE();                // byte rate
                    readShortLE();              // block align
                    bitsPerSample = readShortLE();
                    skipFully(Math.max(0, size - 16));
                } else if (id.equals("data")) {
                    break;                      // samples start right here
                } else {
                    skipFully(size + (size & 1));
                }
            }
            if (bitsPerSample != 16) throw new IllegalArgumentException("only 16-bit PCM supported");
            this.format = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED,
                    sampleRate, 16, channels, channels * 2, sampleRate, false);
        }

        @Override public AudioFormat getFormat() { return format; }

        @Override
        public java.nio.ByteBuffer getBuffer(int frames) throws java.io.IOException {
            int bytes = frames * format.getFrameSize();
            byte[] buf = new byte[bytes];
            int done = 0;
            while (done < bytes) {
                int n = source.read(buf, done, bytes - done);
                if (n <= 0) break;
                done += n;
            }
            if (done <= 0) return null; // end of stream
            return java.nio.ByteBuffer.wrap(buf, 0, done).asReadOnlyBuffer();
        }

        @Override public void close() throws java.io.IOException { source.close(); }

        private void readExpected(String s) throws Exception {
            String got = new String(readExactly(4));
            if (!got.equals(s)) throw new IllegalArgumentException("not a WAV (" + got + ")");
        }

        private byte[] readExactly(int n) throws java.io.EOFException {
            byte[] b = new byte[n];
            int off = 0;
            while (off < n) {
                int r = source.read(b, off, n - off);
                if (r < 0) throw new java.io.EOFException();
                off += r;
            }
            return b;
        }

        private void skipFully(long n) throws java.io.IOException {
            while (n > 0) { long s = source.skip(n); if (s <= 0) break; n -= s; }
        }

        private int readIntLE() throws java.io.IOException {
            byte[] b = readExactly(4);
            return (b[0] & 0xff) | (b[1] & 0xff) << 8 | (b[2] & 0xff) << 16 | (b[3] & 0xff) << 24;
        }

        private int readShortLE() throws java.io.IOException {
            byte[] b = readExactly(2);
            return (b[0] & 0xff) | (b[1] & 0xff) << 8;
        }
    }

    /**
     * Plays an AudioStream directly through the engine's channel/source API using
     * one-time reflection lookups (fields are private in 1.20.4 but stable within
     * this exact yarn build). If reflection fails we stay silent - chat still works.
     */
    private static final class RawWavPlayer {
        private static java.lang.reflect.Field F_SYSTEM, F_CHANNEL, F_ENGINE;
        private static java.lang.reflect.Method M_CREATE_SOURCE, M_RUN, M_SET_STREAM,
                M_SET_POS, M_SET_VOL, M_SET_PITCH, M_SET_ATT, M_SET_REL, M_PLAY, M_CLOSE;
        private static Object RUN_MODE_STREAMING;
        private static boolean initTried, ready;

        static void play(net.minecraft.client.MinecraftClient client, AudioStream stream,
                         Vec3d pos, float volume) {
            try {
                if (!initTried) { init(client); initTried = true; }
                if (!ready) { stream.close(); return; }
                Object soundSystem = F_SYSTEM.get(client.getSoundManager());
                Object channel = F_CHANNEL.get(soundSystem);
                Object engine = F_ENGINE.get(soundSystem);
                Object runMode = RUN_MODE_STREAMING;
                @SuppressWarnings("unchecked")
                java.util.concurrent.CompletableFuture<Object> fut =
                        (java.util.concurrent.CompletableFuture<Object>) M_CREATE_SOURCE.invoke(channel, runMode);
                Object sourceManager = fut.get(2, java.util.concurrent.TimeUnit.SECONDS);
                M_RUN.invoke(sourceManager, (java.util.function.Consumer<Object>) sm -> {
                    Object source = null;
                    try {
                        // SourceManager.run(Consumer<Source>) already hands us the source via its field;
                        // instead use Channel.execute-style: the consumer param IS the Source.
                        source = sm;
                        M_SET_STREAM.invoke(source, stream);
                        M_SET_POS.invoke(source, pos);
                        M_SET_VOL.invoke(source, volume);
                        M_SET_PITCH.invoke(source, 1.0f);
                        M_SET_ATT.invoke(source, 16.0f);
                        M_SET_REL.invoke(source, false);
                        M_PLAY.invoke(source);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
            } catch (Throwable t) {
                try { stream.close(); } catch (Exception ignored) {}
            }
        }

        private static void init(net.minecraft.client.MinecraftClient client) throws Exception {
            Class<?> sysCls = Class.forName("net.minecraft.client.sound.SoundSystem");
            Class<?> chanCls = Class.forName("net.minecraft.client.sound.Channel");
            Class<?> engCls = Class.forName("net.minecraft.client.sound.SoundEngine");
            Class<?> srcCls = Class.forName("net.minecraft.client.sound.Source");
            Class<?> smCls = Class.forName("net.minecraft.client.sound.Channel$SourceManager");
            Class<?> rmCls = Class.forName("net.minecraft.client.sound.SoundEngine$RunMode");

            F_SYSTEM = net.minecraft.client.sound.SoundManager.class.getDeclaredField("soundSystem");
            F_SYSTEM.setAccessible(true);
            for (java.lang.reflect.Field f : sysCls.getDeclaredFields()) {
                f.setAccessible(true);
                if (chanCls.isAssignableFrom(f.getType())) F_CHANNEL = f;
                if (engCls.isAssignableFrom(f.getType())) F_ENGINE = f;
            }
            M_CREATE_SOURCE = chanCls.getDeclaredMethod("createSource", rmCls);
            M_CREATE_SOURCE.setAccessible(true);
            M_RUN = smCls.getDeclaredMethod("run", java.util.function.Consumer.class);
            M_RUN.setAccessible(true);
            M_SET_STREAM = srcCls.getDeclaredMethod("setStream", AudioStream.class);
            M_SET_POS = srcCls.getDeclaredMethod("setPosition", Vec3d.class);
            M_SET_VOL = srcCls.getDeclaredMethod("setVolume", float.class);
            M_SET_PITCH = srcCls.getDeclaredMethod("setPitch", float.class);
            M_SET_ATT = srcCls.getDeclaredMethod("setAttenuation", float.class);
            M_SET_REL = srcCls.getDeclaredMethod("setRelative", boolean.class);
            M_PLAY = srcCls.getDeclaredMethod("play");
            M_CLOSE = smCls.getDeclaredMethod("close");
            M_CLOSE.setAccessible(true);

            RUN_MODE_STREAMING = null;
            Object[] modes = rmCls.getEnumConstants();
            if (modes != null) for (Object m : modes) if (m.toString().equals("STREAMING")) RUN_MODE_STREAMING = m;
            ready = F_CHANNEL != null && F_ENGINE != null && RUN_MODE_STREAMING != null;
        }
    }
}
