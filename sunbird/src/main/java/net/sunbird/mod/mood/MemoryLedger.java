package net.sunbird.mod.mood;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Long-term grudge &amp; memory ledger - the Verity-style "it remembers EVERYTHING" upgrade.
 * The rolling ChatHistory forgets after N turns; this ledger never does (persisted to disk).
 * When a mood event happens (insult, petting, death, haunt), we record it here and later
 * inject relevant entries into the AI prompt: "3 days ago Steve kicked you. You have not forgotten."
 */
public final class MemoryLedger {

    public record Grudge(long timestamp, String player, String what, float severity) {}
    public record Kindness(long timestamp, String player, String what, float warmth) {}

    private static final Deque<Grudge> GRUDGES = new ArrayDeque<>();
    private static final Deque<Kindness> KINDS = new ArrayDeque<>();

    private MemoryLedger() {}

    public static synchronized void addGrudge(String player, String what, float severity) {
        GRUDGES.addLast(new Grudge(System.currentTimeMillis(), player, what, Math.min(1f, severity)));
        while (GRUDGES.size() > 400) GRUDGES.removeFirst();
    }

    public static synchronized void addKindness(String player, String what, float warmth) {
        KINDS.addLast(new Kindness(System.currentTimeMillis(), player, what, Math.min(1f, warmth)));
        while (KINDS.size() > 400) KINDS.removeFirst();
    }

    public static synchronized List<Grudge> grudgesAgainst(String player, int max) {
        List<Grudge> out = new ArrayList<>();
        for (Grudge g : GRUDGES) {
            if (g.player().equalsIgnoreCase(player)) out.add(g);
        }
        return out.subList(Math.max(0, out.size() - max), out.size());
    }

    public static synchronized boolean hasGrudgeAgainst(String player) {
        for (Grudge g : GRUDGES) if (g.player().equalsIgnoreCase(player)) return true;
        return false;
    }

    /** Prompt fragment: recent things the bird should emotionally reference. */
    public static synchronized String promptDigest() {
        StringBuilder sb = new StringBuilder();
        List<Grudge> gs = new ArrayList<>(GRUDGES);
        List<Grudge> recent = gs.subList(Math.max(0, gs.size() - 5), gs.size());
        for (Grudge g : recent) {
            sb.append("- ").append(g.player()).append(" once: \"").append(trunc(g.what()))
              .append("\" (you hold this against them, severity ").append(String.format("%.1f", g.severity())).append(")\n");
        }
        List<Kindness> ks = new ArrayList<>(KINDS);
        List<Kindness> recentK = ks.subList(Math.max(0, ks.size() - 3), ks.size());
        for (Kindness k : recentK) {
            sb.append("- ").append(k.player()).append(" once: \"").append(trunc(k.what()))
              .append("\" (you remember fondly)\n");
        }
        return sb.toString();
    }

    public static synchronized int grudgeCount() { return GRUDGES.size(); }
    public static synchronized int kindnessCount() { return KINDS.size(); }

    public static synchronized void clear() {
        GRUDGES.clear();
        KINDS.clear();
    }

    public static synchronized List<Grudge> allGrudges() { return new ArrayList<>(GRUDGES); }

    // ---- persistence (simple line format: ts|player|what|severity) ----
    public static synchronized List<String> serialize() {
        List<String> lines = new ArrayList<>();
        for (Grudge g : GRUDGES) lines.add("G|" + g.timestamp() + "|" + g.player() + "|" + esc(g.what()) + "|" + g.severity());
        for (Kindness k : KINDS) lines.add("K|" + k.timestamp() + "|" + k.player() + "|" + esc(k.what()) + "|" + k.warmth());
        return lines;
    }

    public static synchronized void deserialize(List<String> lines) {
        GRUDGES.clear();
        KINDS.clear();
        for (String line : lines) {
            String[] p = line.split("\\|", 5);
            if (p.length < 5) continue;
            try {
                long ts = Long.parseLong(p[1]);
                if (p[0].equals("G")) GRUDGES.addLast(new Grudge(ts, p[2], unesc(p[3]), Float.parseFloat(p[4])));
                else KINDS.addLast(new Kindness(ts, p[2], unesc(p[3]), Float.parseFloat(p[4])));
            } catch (Exception ignored) {}
        }
    }

    private static String esc(String s) { return s.replace("|", "/").replace("\n", " "); }
    private static String unesc(String s) { return s; }
    private static String trunc(String s) { return s.length() > 80 ? s.substring(0, 77) + "..." : s; }
}
