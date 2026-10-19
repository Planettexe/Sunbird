package net.sunbird.mod.tts;

import com.google.gson.JsonObject;
import net.sunbird.mod.SunbirdConfig;
import net.sunbird.mod.SunbirdMod;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Server-side text-to-speech gateway. One worker thread (same philosophy as the
 * LLM queue: one slot = no CPU spikes on the hosting box). Talks to ANY of:
 *
 *   - llama.cpp server with --tts  -> POST {base}/audio/speech   (OpenAI-compatible)
 *   - Piper                        -> POST {ttsBaseUrl}/tts      {"text":..., "speaker":...}
 *   - Kokoro-fastapi / coqui / etc -> POST {ttsBaseUrl}/v1/audio/speech
 *
 * The server synthesises WAV once, caches it per line, and ships the bytes to every
 * modded client that can hear this bird (3D positional). Vanilla clients just get
 * the chat message like always - nothing breaks for them.
 *
 * Horror/funny bonus: after synthesis we warp the PCM (pitch shift, reverse,
 * ring-mod "demonic", low-pass "far away") so a HAUNTED sunbird literally sounds
 * wrong even when the TTS model itself is cheerful. Zero extra inference cost -
 * pure Java DSP.
 */
public final class TtsBridge {
    private static SunbirdConfig cfg;
    private static ExecutorService workers;

    // Small cache so repeated lines (haunt loops, pranks) don't re-infer.
    private static final Deque<String> CACHE_ORDER = new ArrayDeque<>();
    private static final Map<String, byte[]> CACHE = new HashMap<>();
    private static final int CACHE_MAX = 48;

    private TtsBridge() {}

    public static void init(SunbirdConfig config) {
        cfg = config;
        if (workers == null) {
            workers = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "sunbird-tts");
                t.setDaemon(true);
                return t;
            });
        }
    }

    public static boolean enabled() {
        return cfg != null && cfg.ttsEnabled;
    }

    public static String describe() {
        if (cfg == null) return "uninitialized";
        if (!cfg.ttsEnabled) return "disabled";
        String base = cfg.ttsBaseUrl.isBlank() ? trim(cfg.apiBaseUrl) : trim(cfg.ttsBaseUrl);
        return base + " (style=" + cfg.ttsEngine + ", voice=" + cfg.ttsVoice + ")";
    }

    /**
     * Queue a line for voicing. Delivered async; failures are silent (chat still works).
     *
     * @param key     cache key (usually the spoken line)
     * @param text    what to say
     * @param warping DSP flavour applied AFTER synthesis
     * @param onReady receives finished, warped WAV bytes on the worker thread
     */
    public static void speak(String key, String text, Warp warping, Consumer<byte[]> onReady) {
        if (!enabled() || text == null || text.isBlank()) return;
        String cacheKey = key + "|" + warping.name();
        synchronized (TtsBridge.class) {
            byte[] hit = CACHE.get(cacheKey);
            if (hit != null) { onReady.accept(hit); return; }
        }
        workers.submit(() -> {
            try {
                byte[] wav = synthesize(text.trim());
                if (wav == null || wav.length == 0) return;
                wav = applyWarp(wav, warping);
                synchronized (TtsBridge.class) {
                    CACHE.put(cacheKey, wav);
                    CACHE_ORDER.addLast(cacheKey);
                    while (CACHE_ORDER.size() > CACHE_MAX) {
                        String evicted = CACHE_ORDER.pollFirst();
                        if (evicted != null) CACHE.remove(evicted);
                    }
                }
                onReady.accept(wav);
            } catch (Exception e) {
                SunbirdMod.LOGGER.debug("[Sunbird] TTS failed for \"{}\": {}", clip(text), e.toString());
            }
        });
    }

    /** Immediate synthesis used by /aitalk diagnostics. */
    public static CompletableFuture<byte[]> synthesizeNow(String text) {
        if (!enabled()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("TTS disabled (set ttsEnabled=true in config)"));
        }
        CompletableFuture<byte[]> fut = new CompletableFuture<>();
        workers.submit(() -> {
            try { fut.complete(synthesize(text)); }
            catch (Exception e) { fut.completeExceptionally(e); }
        });
        return fut;
    }

    // ================= engines =================

    private static byte[] synthesize(String text) throws Exception {
        String style = cfg.ttsEngine.toLowerCase();
        return switch (style) {
            case "piper" -> piperRequest(text);
            default -> openAiAudioSpeech(text); // "openai", "llamacpp", "kokoro", "coqui" all use /audio/speech
        };
    }

    /** OpenAI-compatible: POST {base}/audio/speech  {model,input,voice,response_format:"wav"} */
    private static byte[] openAiAudioSpeech(String text) throws Exception {
        String base = cfg.ttsBaseUrl.isBlank() ? trim(cfg.apiBaseUrl) : trim(cfg.ttsBaseUrl);
        JsonObject body = new JsonObject();
        body.addProperty("model", cfg.model);
        body.addProperty("input", text);
        body.addProperty("voice", cfg.ttsVoice);
        body.addProperty("response_format", "wav");
        body.addProperty("speed", cfg.ttsSpeed);
        return postForBytes(base + "/audio/speech", body.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** Piper HTTP server: POST {base}/tts  {"text":...,"speaker":...} -> raw WAV */
    private static byte[] piperRequest(String text) throws Exception {
        String base = cfg.ttsBaseUrl.isBlank() ? "http://127.0.0.1:5000" : trim(cfg.ttsBaseUrl);
        JsonObject body = new JsonObject();
        body.addProperty("text", text);
        try {
            body.addProperty("speaker", Integer.parseInt(cfg.ttsVoice.replaceAll("\\D+", "")));
        } catch (Exception ignored) {
            body.addProperty("speaker", 0);
        }
        return postForBytes(base + "/tts", body.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] postForBytes(String url, byte[] body) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(cfg.requestTimeoutSeconds * 1000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        if (cfg.apiKey != null && !cfg.apiKey.isBlank()) {
            conn.setRequestProperty("Authorization", "Bearer " + cfg.apiKey);
        }
        try (var os = conn.getOutputStream()) { os.write(body); }
        int code = conn.getResponseCode();
        InputStream in = code / 100 == 2 ? conn.getInputStream() : conn.getErrorStream();
        if (in == null) throw new java.io.IOException("HTTP " + code + " from " + url);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        in.transferTo(bos);
        in.close();
        if (code / 100 != 2) {
            SunbirdMod.LOGGER.warn("[Sunbird] TTS backend HTTP {}: {}", code,
                    clip(bos.toString(StandardCharsets.UTF_8)));
            throw new java.io.IOException("TTS HTTP " + code);
        }
        return bos.toByteArray();
    }

    // ================= DSP warping (the horror/funny voice) =================

    public enum Warp {
        NONE,
        /** Haunted: slowed + pitch-down + ring-modulation wobble. Sounds WRONG. */
        DEMON,
        /** Reverse the whole clip. Uncanny valley in audio form. */
        REVERSED,
        /** Prank mode: chipmunk pitch-up. */
        HELLO_KITTY,
        /** Distant/underwater mumble for ambient whispers. */
        FAR_AWAY
    }

    /**
     * Operates directly on PCM16 WAV bytes (what llama.cpp --tts, Piper and most
     * local engines return). If the payload isn't a recognisable PCM16 WAV we pass
     * it through untouched rather than corrupting it.
     */
    static byte[] applyWarp(byte[] wav, Warp warp) {
        if (warp == Warp.NONE || wav == null || wav.length < 44) return wav;
        try {
            WavInfo info = parsePcm16Mono(wav);
            if (info == null) return wav; // not something we can safely edit
            short[] pcm = info.pcm;
            short[] out = switch (warp) {
                case DEMON -> demon(pcm, info.sampleRate);
                case REVERSED -> reversed(pcm);
                case HELLO_KITTY -> resample(pcm, 1.45f);
                case FAR_AWAY -> lowpass(pcm, 0.12f);
                default -> pcm;
            };
            return rebuildWav(out, info.sampleRate);
        } catch (Exception e) {
            return wav;
        }
    }

    private record WavInfo(short[] pcm, int sampleRate) {}

    private static WavInfo parsePcm16Mono(byte[] wav) {
        if (wav.length < 44) return null;
        if (wav[0] != 'R' || wav[1] != 'I' || wav[2] != 'F' || wav[3] != 'F') return null;
        if (wav[8] != 'W' || wav[9] != 'A' || wav[10] != 'V' || wav[11] != 'E') return null;
        int channels = le16(wav, 22);
        int rate = le32(wav, 24);
        int bits = le16(wav, 34);
        if (bits != 16) return null;
        // locate the data chunk properly (some servers insert metadata chunks)
        int pos = 12;
        int dataStart = -1, dataLen = -1;
        while (pos + 8 <= wav.length) {
            String id = new String(wav, pos, 4, StandardCharsets.US_ASCII);
            int sz = le32(wav, pos + 4);
            if (id.equals("data")) { dataStart = pos + 8; dataLen = sz; break; }
            pos += 8 + sz + (sz % 2);
        }
        if (dataStart < 0 || dataStart + dataLen > wav.length) return null;
        int samples = dataLen / 2;
        short[] pcm = new short[samples];
        for (int i = 0; i < samples; i++) {
            pcm[i] = (short) (((wav[dataStart + i * 2 + 1] & 0xFF) << 8) | (wav[dataStart + i * 2] & 0xFF));
        }
        if (channels == 2) { // crude stereo->mono fold
            short[] mono = new short[samples / 2];
            for (int i = 0; i < mono.length; i++) {
                mono[i] = (short) ((pcm[i * 2] + pcm[i * 2 + 1]) / 2);
            }
            pcm = mono;
        } else if (channels != 1) {
            return null;
        }
        return new WavInfo(pcm, rate);
    }

    private static byte[] rebuildWav(short[] pcm, int sampleRate) {
        int dataLen = pcm.length * 2;
        byte[] out = new byte[44 + dataLen];
        out[0] = 'R'; out[1] = 'I'; out[2] = 'F'; out[3] = 'F';
        putLe32(out, 4, 36 + dataLen);
        out[8] = 'W'; out[9] = 'A'; out[10] = 'V'; out[11] = 'E';
        out[12] = 'f'; out[13] = 'm'; out[14] = 't'; out[15] = ' ';
        putLe32(out, 16, 16);
        putLe16(out, 20, (short) 1);              // PCM
        putLe16(out, 22, (short) 1);              // mono
        putLe32(out, 24, sampleRate);
        putLe32(out, 28, sampleRate * 2);         // byte rate
        putLe16(out, 32, (short) 2);              // block align
        putLe16(out, 34, (short) 16);             // bits
        out[36] = 'd'; out[37] = 'a'; out[38] = 't'; out[39] = 'a';
        putLe32(out, 40, dataLen);
        for (int i = 0; i < pcm.length; i++) {
            out[44 + i * 2] = (byte) (pcm[i] & 0xFF);
            out[44 + i * 2 + 1] = (byte) ((pcm[i] >> 8) & 0xFF);
        }
        return out;
    }

    /** Pitch DOWN via naive resample at 0.78x, then ring-mod wobble. */
    private static short[] demon(short[] pcm, int rate) {
        short[] slowed = resample(pcm, 0.78f);
        float lfoHz = 4.5f;
        for (int i = 0; i < slowed.length; i++) {
            float lfo = (float) Math.sin(2 * Math.PI * lfoHz * i / rate);
            int v = (int) (slowed[i] * (0.82f + 0.18f * lfo));
            slowed[i] = (short) Math.max(-32768, Math.min(32767, v));
        }
        return slowed;
    }

    private static short[] reversed(short[] pcm) {
        short[] out = new short[pcm.length];
        for (int i = 0; i < pcm.length; i++) out[i] = pcm[pcm.length - 1 - i];
        return out;
    }

    /** Naive linear-interpolation resampler: factor>1 speeds up AND raises pitch. */
    private static short[] resample(short[] pcm, float factor) {
        int newLen = Math.max(1, (int) (pcm.length / factor));
        short[] out = new short[newLen];
        for (int i = 0; i < newLen; i++) {
            float src = i * factor;
            int i0 = Math.min((int) src, pcm.length - 1);
            int i1 = Math.min(i0 + 1, pcm.length - 1);
            float frac = src - (int) src;
            out[i] = (short) (pcm[i0] * (1 - frac) + pcm[i1] * frac);
        }
        return out;
    }

    /** One-pole low-pass = muffled "from another room" whisper. */
    private static short[] lowpass(short[] pcm, float alpha) {
        short[] out = new short[pcm.length];
        float acc = 0;
        for (int i = 0; i < pcm.length; i++) {
            acc += (pcm[i] - acc) * alpha;
            out[i] = (short) acc;
        }
        return out;
    }

    private static int le16(byte[] b, int o) { return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8); }
    private static void putLe16(byte[] b, int o, short v) {
        b[o] = (byte) (v & 0xFF); b[o + 1] = (byte) ((v >> 8) & 0xFF);
    }
    private static int le32(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24);
    }
    private static void putLe32(byte[] b, int o, int v) {
        b[o] = (byte) v; b[o + 1] = (byte) (v >> 8); b[o + 2] = (byte) (v >> 16); b[o + 3] = (byte) (v >> 24);
    }

    private static String trim(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String clip(String s) {
        return s.length() > 120 ? s.substring(0, 117) + "..." : s;
    }
}
