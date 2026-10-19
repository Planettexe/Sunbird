package net.sunbird.mod.ai;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.server.MinecraftServer;
import net.sunbird.mod.SunbirdConfig;
import net.sunbird.mod.SunbirdMod;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Rolling conversation memory, persisted to config/sunbird/memory.json so the bird
 * remembers yesterday's adventures across server restarts. One shared timeline for
 * the whole server (like Verity's single continuous "life"), plus per-entity memories.
 */
public final class ChatHistory {
    public record Entry(long timestamp, String speaker, String text) {}

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Deque<Entry> GLOBAL = new ArrayDeque<>();
    private static int cap = 200;

    private ChatHistory() {}

    public static synchronized void add(String speaker, String text) {
        GLOBAL.addLast(new Entry(System.currentTimeMillis(), speaker, text));
        while (GLOBAL.size() > cap) {
            GLOBAL.removeFirst();
        }
    }

    /** Build the message list: system prompt + last N turns formatted for the model. */
    public static synchronized List<AiBridge.Turn> context(String extraInstruction) {
        SunbirdConfig cfg = SunbirdConfig.get();
        List<AiBridge.Turn> turns = new ArrayList<>();
        String sys = cfg.systemPrompt
                + "\nYou are talking to players on a Minecraft server. Recent events:";
        // Mood + grudges: the bird's emotional continuity lives here (works with small local models)
        if (cfg.horrorEnabled || cfg.comedyEnabled) {
            sys += "\nYour current mood: " + net.sunbird.mod.mood.HauntDirector.moodDigest() + ".";
            String digest = net.sunbird.mod.mood.MemoryLedger.promptDigest();
            if (!digest.isBlank()) {
                sys += "\nThings you remember and feel about:\n" + digest;
            }
        }
        if (extraInstruction != null && !extraInstruction.isBlank()) {
            sys += "\n" + extraInstruction;
        }
        turns.add(new AiBridge.Turn("system", sys));

        List<Entry> entries = new ArrayList<>(GLOBAL);
        int from = Math.max(0, entries.size() - cfg.memoryTurns);
        for (Entry e : entries.subList(from, entries.size())) {
            String role = e.speaker().equalsIgnoreCase(cfg.personaName) ? "assistant" : "user";
            String text = role.equals("user") ? e.speaker() + ": " + e.text() : e.text();
            turns.add(new AiBridge.Turn(role, text));
        }
        return turns;
    }

    private static Path file(MinecraftServer server) {
        // Config dir works identically on dedicated servers and singleplayer worlds,
        // and survives world folder moves. One brain, one memory file.
        return net.sunbird.mod.SunbirdConfig.configDir().resolve("memory.json");
    }

    public static synchronized void load(MinecraftServer server) {
        try {
            Path f = file(server);
            if (Files.exists(f)) {
                Type t = new TypeToken<List<Entry>>() {}.getType();
                List<Entry> list = GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), t);
                GLOBAL.clear();
                if (list != null) {
                    for (Entry e : list) {
                        GLOBAL.addLast(e);
                    }
                }
                SunbirdMod.LOGGER.info("[Sunbird] Restored {} memory entries.", GLOBAL.size());
            }
        } catch (Exception e) {
            SunbirdMod.LOGGER.warn("[Sunbird] Could not load memory", e);
        }
    }

    public static synchronized void save(MinecraftServer server) {
        try {
            Files.writeString(file(server), GSON.toJson(new ArrayList<>(GLOBAL)), StandardCharsets.UTF_8);
        } catch (Exception e) {
            SunbirdMod.LOGGER.warn("[Sunbird] Could not save memory", e);
        }
    }
}
