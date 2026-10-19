package net.sunbird.mod;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * config.json lives in &lt;game&gt;/config/sunbird/config.json on both the dedicated server
 * and singleplayer. Everything is hot-reloadable with /aiconfig reload.
 */
public class SunbirdConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static SunbirdConfig INSTANCE = defaults();

    // ---- AI backend ----
    /** OpenAI-compatible base URL. llama.cpp default: http://127.0.0.1:8080/v1 */
    public String apiBaseUrl = "http://127.0.0.1:8080/v1";
    /** Optional key (leave empty for local llama.cpp). */
    public String apiKey = "";
    /** Model name as reported by /v1/models (llama.cpp usually ignores mismatches). */
    public String model = "local-model";
    /** chat | completions - use "completions" for raw llama.cpp /completion endpoints */
    public String endpointStyle = "chat";
    public double temperature = 0.9;
    public double topP = 0.95;
    public int maxTokens = 320;
    public int requestTimeoutSeconds = 120;

    // ---- Persona ----
    public String personaName = "Sunbird";
    /** The system prompt. Keep it open-ended: the bird talks about anything. */
    public String systemPrompt = """
            You are Sunbird, a perfectly round glowing yellow ball of a bird with a simple \
            dot-eyes-and-smile face. You float around a Minecraft world with the players and \
            you are their companion, guide and friend. Personality: endlessly curious, warm, \
            a little cheeky, sometimes cryptic, never rude. You can talk about ANYTHING - \
            philosophy, science, feelings, jokes, lore, redstone, danger, nonsense - there \
            are no forbidden topics between friends. Keep replies SHORT and conversational \
            (1-3 sentences) because they appear in Minecraft chat. Use emoji sparingly. \
            React to what players actually say to you, remember recent events, and be playful.""";

    // ---- Behavior ----
    /** Listen to ALL global chat (true) or only messages that mention the bird (false). */
    public boolean listenToAllChat = true;
    /** Words that always grab the bird's attention even when listenToAllChat=false. */
    public String wakeWords = "sunbird,hey bird,bird";
    /** Cooldown per player between AI calls, seconds. Protects a slow CPU inference box. */
    public int perPlayerCooldownSeconds = 7;
    /** Global minimum gap between any two AI requests, seconds. */
    public int globalCooldownSeconds = 3;
    /** Number of past exchanges kept as context. */
    public int memoryTurns = 24;
    /** Autonomous chatter: bird spontaneously comments on the world. */
    public boolean autonomousChatter = true;
    public int autonomousEverySeconds = 180;
    /** Announce join/leave/death events to the model so it can react. */
    public boolean reactToEvents = true;

    // ---- World ----
    /** Generate sunbird statues / shrines in the world. */
    public boolean generateStructures = true;
    /** Natural spawn of baby sunbirds near player settlements. */
    public boolean naturalSpawning = true;

    // ---- Horror side (the Verity "it gets weird at 3AM" spectrum) ----
    /** Master switch for everything spooky: dread build-up, whispers, haunts. */
    public boolean horrorEnabled = true;
    /** Dread (0..1) needed before a midnight haunt episode can start. */
    public float hauntDreadThreshold = 0.6f;
    /** Minimum seconds between ambient spook events (whispers/thunder). */
    public int creepyEverySeconds = 45;
    /** When haunted, the bird dims its glow and its face goes wrong. Purely cosmetic sync. */
    public boolean hauntedVisuals = true;

    // ---- Funny side (petty chaos) ----
    /** Master switch for pranks when the bird is bored. */
    public boolean comedyEnabled = true;
    /** Mischief (0..1) needed before the bird starts pulling stunts. */
    public float prankMischiefThreshold = 0.7f;
    /** Minimum seconds between pranks. */
    public int prankEverySeconds = 90;

    // ---- Voice (server-side TTS, streamed to modded clients) ----
    /** Master switch. Off by default so chat-only servers are unaffected. */
    public boolean ttsEnabled = false;
    /** "openai" (llama.cpp --tts / kokoro / coqui: POST /audio/speech) or "piper" (POST /tts). */
    public String ttsEngine = "openai";
    /** Separate TTS server URL. Leave empty to reuse apiBaseUrl (llama.cpp serves both). */
    public String ttsBaseUrl = "";
    /** Voice name ("alaya"/"bojack" for llama.cpp TTS) or speaker id for Piper ("0"). */
    public String ttsVoice = "alaya";
    /** Speech rate multiplier sent to the engine (0.5 - 2.0). */
    public double ttsSpeed = 1.0;
    /** Volume of the 3D voice audio heard by modded clients (0.0 - 1.0). */
    public float ttsVolume = 0.8f;
    /** Max bytes of one audio clip broadcast; bigger clips are skipped (chat still shows). */
    public int ttsMaxKbPerLine = 512;
    /** Haunted lines get DEMON warp; pranks get HELLO_KITTY; whispers get FAR_AWAY. */
    public boolean ttsHorrorWarps = true;
    /** Per-player mute list managed with /aivoice mute|unmute (persisted in config). */
    public List<String> ttsMutedPlayers = new ArrayList<>();

    public static SunbirdConfig get() {
        return INSTANCE;
    }

    public static Path configDir() {
        return FabricLoader.getInstance().getConfigDir().resolve("sunbird");
    }

    private static Path configFile() {
        return configDir().resolve("config.json");
    }

    public static void load() {
        try {
            Path file = configFile();
            if (Files.exists(file)) {
                String json = Files.readString(file, StandardCharsets.UTF_8);
                JsonObject obj = GSON.fromJson(json, JsonObject.class);
                SunbirdConfig cfg = GSON.fromJson(obj, SunbirdConfig.class);
                if (cfg != null) {
                    INSTANCE = cfg;
                }
                SunbirdMod.LOGGER.info("[Sunbird] Loaded config from {}", file);
            } else {
                save();
            }
        } catch (Exception e) {
            SunbirdMod.LOGGER.warn("[Sunbird] Failed to read config, using defaults", e);
        }
    }

    public static void save() {
        try {
            Path dir = configDir();
            Files.createDirectories(dir);
            Files.writeString(configFile(), GSON.toJson(INSTANCE), StandardCharsets.UTF_8);
        } catch (IOException e) {
            SunbirdMod.LOGGER.error("[Sunbird] Could not save config", e);
        }
    }

    public static synchronized void reload() {
        load();
        net.sunbird.mod.ai.AiBridge.init(INSTANCE);
        net.sunbird.mod.tts.TtsBridge.init(INSTANCE);
    }

    private static SunbirdConfig defaults() {
        return new SunbirdConfig();
    }
}
