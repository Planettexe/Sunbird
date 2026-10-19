package net.sunbird.mod;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

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
    }

    private static SunbirdConfig defaults() {
        return new SunbirdConfig();
    }
}
