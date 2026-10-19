package net.sunbird.mod.i18n;

import net.minecraft.server.network.ServerPlayerEntity;
import net.sunbird.mod.SunbirdConfig;
import net.sunbird.mod.SunbirdMod;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Multi-language support for the whole mod, on two levels:
 *
 * 1. UI strings - every player sees chat feedback / whispers / haunts in THEIR OWN
 *    client language. We track each player's locale (from ServerPlayNetworkHandler)
 *    and translate via our own lang tables, because server-side Text.translatable
 *    only auto-resolves for vanilla keys reliably across mods/versions.
 *
 * 2. AI replies - we append a per-request language instruction to the system prompt
 *    so the hosted llama.cpp model answers each player in the language that player's
 *    client uses (auto), or in a forced server-wide language (config). Small local
 *    models handle EN/FR/ES/DE/PT/IT/NL/PL/RU/JA/ZH/KO well; anything else falls
 *    back gracefully because the instruction is plain English.
 */
public final class LangService {
    /** player UUID string -> locale like "fr_fr". Updated on join & periodically. */
    private static final Map<String, String> LOCALES = new ConcurrentHashMap<>();

    /** Locales we ship full translations for; others fall back to en_us. */
    public static final String[] SUPPORTED = {"en_us", "fr_fr", "es_es", "de_de", "pt_br", "it_it"};

    /** locale -> (key -> string), loaded lazily from assets/sunbird/lang/<locale>.json */
    private static final Map<String, Map<String, String>> TABLES = new ConcurrentHashMap<>();

    private LangService() {}

    public static void remember(ServerPlayerEntity player) {
        if (player == null) return;
        // 1.20.4 Yarn: ServerPlayerEntity.getClientOptions() -> SyncedClientOptions.language()
        String loc = null;
        try {
            var opts = player.getClientOptions();
            if (opts != null) loc = opts.language();
        } catch (Throwable ignored) {}
        if (loc == null || loc.isBlank()) loc = "en_us";
        LOCALES.put(player.getUuidAsString(), loc.toLowerCase(Locale.ROOT).replace('-', '_'));
    }

    public static void forget(ServerPlayerEntity player) {
        if (player != null) LOCALES.remove(player.getUuidAsString());
    }

    public static String localeOf(ServerPlayerEntity player) {
        if (player == null) return "en_us";
        String forced = SunbirdConfig.get().forcedLanguage.toLowerCase(Locale.ROOT);
        if (!forced.isBlank()) return forced;
        return LOCALES.getOrDefault(player.getUuidAsString(), "en_us");
    }

    /** Translate a key for this player's client language. Falls back to en_us, then to the key itself. */
    public static String tr(ServerPlayerEntity player, String key) {
        String loc = localeOf(player);
        String s = lookup(loc, key);
        if (s == null) s = lookup("en_us", key);
        return s != null ? s : key;
    }

    /** Translate for whoever should hear it; null player => server default (en_us or forced). */
    public static String tr(String key) {
        String forced = SunbirdConfig.get().forcedLanguage.toLowerCase(Locale.ROOT);
        String s = forced.isBlank() ? null : lookup(forced, key);
        if (s == null) s = lookup("en_us", key);
        return s != null ? s : key;
    }

    /**
     * Instruction appended to the system prompt for a request made on behalf of `player`.
     * Tells the model which language to answer in. Returns "" when nothing is needed.
     */
    public static String aiLanguageInstruction(ServerPlayerEntity player) {
        SunbirdConfig cfg = SunbirdConfig.get();
        if (!cfg.multilingual) return "";
        String loc = localeOf(player);
        String name = LANGUAGE_NAMES.getOrDefault(loc.split("_")[0], "English");
        return "\nIMPORTANT: The player you are replying to speaks " + name
                + ". Answer ONLY in " + name + ", matching their language, even if earlier"
                + " messages were in another language. Keep Minecraft terms natural.";
    }

    /** Server-wide digest line used for autonomous chatter (no specific player). */
    public static String aiLanguageDigest() {
        SunbirdConfig cfg = SunbirdConfig.get();
        if (!cfg.multilingual) return "";
        if (!cfg.forcedLanguage.isBlank()) {
            String name = LANGUAGE_NAMES.getOrDefault(cfg.forcedLanguage.toLowerCase(Locale.ROOT).split("_")[0], cfg.forcedLanguage);
            return "\nAlways speak in " + name + ".";
        }
        return "\nThe players on this server speak many languages ("
                + String.join(", ", distinctNames()) + "). When replying to a specific player,"
                + " use their language; for general announcements use English.";
    }

    private static java.util.List<String> distinctNames() {
        java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
        for (String loc : LOCALES.values()) {
            set.add(LANGUAGE_NAMES.getOrDefault(loc.split("_")[0], loc));
        }
        if (set.isEmpty()) set.add("English");
        return new java.util.ArrayList<>(set);
    }

    private static String lookup(String locale, String key) {
        Map<String, String> table = TABLES.computeIfAbsent(locale, LangService::loadTable);
        return table.get(key);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> loadTable(String locale) {
        Map<String, String> map = new ConcurrentHashMap<>();
        String path = "assets/sunbird/lang/" + locale + ".json";
        try (InputStream in = LangService.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) return map;
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            // Minimal flat JSON parser: our lang files are simple "key": "value" objects.
            int i = 0;
            while ((i = json.indexOf('"', i)) >= 0) {
                int k1 = i + 1;
                int k2 = json.indexOf('"', k1);
                if (k2 < 0) break;
                int c = json.indexOf(':', k2);
                if (c < 0) break;
                int v1 = json.indexOf('"', c);
                if (v1 < 0) break;
                StringBuilder val = new StringBuilder();
                int p = v1 + 1;
                while (p < json.length()) {
                    char ch = json.charAt(p);
                    if (ch == '\\' && p + 1 < json.length()) {
                        char nx = json.charAt(p + 1);
                        val.append(switch (nx) {
                            case 'n' -> '\n';
                            case 't' -> '\t';
                            default -> nx;
                        });
                        p += 2;
                    } else if (ch == '"') {
                        break;
                    } else {
                        val.append(ch);
                        p++;
                    }
                }
                map.put(json.substring(k1, k2), val.toString());
                i = p + 1;
            }
        } catch (Exception e) {
            SunbirdMod.LOGGER.warn("Failed loading lang table {}", path, e);
        }
        return map;
    }

    private static final Map<String, String> LANGUAGE_NAMES = Map.ofEntries(
            Map.entry("en", "English"), Map.entry("fr", "French"), Map.entry("es", "Spanish"),
            Map.entry("de", "German"), Map.entry("pt", "Portuguese"), Map.entry("it", "Italian"),
            Map.entry("nl", "Dutch"), Map.entry("pl", "Polish"), Map.entry("ru", "Russian"),
            Map.entry("uk", "Ukrainian"), Map.entry("ja", "Japanese"), Map.entry("zh", "Chinese"),
            Map.entry("ko", "Korean"), Map.entry("tr", "Turkish"), Map.entry("sv", "Swedish"),
            Map.entry("no", "Norwegian"), Map.entry("da", "Danish"), Map.entry("fi", "Finnish"),
            Map.entry("cs", "Czech"), Map.entry("el", "Greek"), Map.entry("he", "Hebrew"),
            Map.entry("hu", "Hungarian"), Map.entry("id", "Indonesian"), Map.entry("ro", "Romanian"));
}
