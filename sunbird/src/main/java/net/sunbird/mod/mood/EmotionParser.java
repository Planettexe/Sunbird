package net.sunbird.mod.mood;

import java.util.List;
import java.util.Locale;

/**
 * Lightweight, offline sentiment/nickname detection for player chat. We do NOT burn
 * an LLM call on every message - this keyword pass decides whether a message is worth
 * escalating to the model at all, and how it emotionally landed. Deliberately blunt:
 * local models on weak CPUs shouldn't be wasted on "hi". Also detects petting words,
 * threats, apologies, nicknames the bird gives players, and "weird" questions that
 * raise dread (occult topics pull the bird toward its darker side - Verity style).
 */
public final class EmotionParser {

    public enum Tone { NEUTRAL, FRIENDLY, INSULT, THREAT, APOLOGY, OCCULT, COMPLIMENT }

    public record Read(Tone tone, double intensity) {}

    private static final List<String> FRIENDLY = List.of(
            "hello", "hi ", "hey", "good morning", "good night", "thanks", "thank you",
            "how are you", "friend", "buddy", "love you", "miss you", "welcome", "cute", "sunbird");

    private static final List<String> COMPLIMENT = List.of(
            "good bird", "pretty", "smart", "amazing", "awesome", "best bird", "brilliant",
            "funny", "i love you", "you're cool", "you are cool", "sweetheart");

    private static final List<String> INSULT = List.of(
            "stupid", "dumb", "ugly", "shut up", "annoying", "trash", "worst", "hate you",
            "useless", "idiot", "loser", "go away", "nobody likes you", "bad bird", "fake");

    private static final List<String> THREAT = List.of(
            "kill", "hit", "punch", "kick", "break", "burn", "destroy", "lava", "sword",
            "bow", "shoot", "crush", "smash", "eat you", "cage");

    private static final List<String> APOLOGY = List.of(
            "sorry", "apolog", "my bad", "didn't mean", "didnt mean", "forgive", "was wrong", "i was wrong");

    private static final List<String> OCCULT = List.of(
            "ghost", "haunt", "dead", "die", "death", "shadow", "herobrine", "entity 303",
            "not real", "who are you really", "what are you", "behind me", "midnight", "blood",
            "underground", "voice", "dream", "sleep paralysis", "ritual", "summon", "possess");

    private EmotionParser() {}

    public static Read read(String raw) {
        String s = raw.toLowerCase(Locale.ROOT);
        if (containsAny(s, THREAT)) return new Read(Tone.THREAT, weight(s, THREAT));
        if (containsAny(s, INSULT)) return new Read(Tone.INSULT, weight(s, INSULT));
        if (containsAny(s, APOLOGY)) return new Read(Tone.APOLOGY, weight(s, APOLOGY));
        if (containsAny(s, OCCULT)) return new Read(Tone.OCCULT, weight(s, OCCULT));
        if (containsAny(s, COMPLIMENT)) return new Read(Tone.COMPLIMENT, weight(s, COMPLIMENT));
        if (containsAny(s, FRIENDLY)) return new Read(Tone.FRIENDLY, weight(s, FRIENDLY));
        return new Read(Tone.NEUTRAL, 0.5);
    }

    /** Does this message even deserve an LLM slot? Cheap gate for CPU inference boxes. */
    public static boolean worthAsking(Read r, boolean mentionedBird) {
        if (mentionedBird) return true;
        return switch (r.tone()) {
            case INSULT, THREAT, APOLOGY, OCCULT -> true;   // emotional events always processed
            case COMPLIMENT -> true;
            case FRIENDLY -> r.intensity() >= 1.0;           // only repeated/strong greetings
            case NEUTRAL -> false;                            // ignore idle chatter noise
        };
    }

    private static boolean containsAny(String s, List<String> words) {
        for (String w : words) if (s.contains(w)) return true;
        return false;
    }

    private static double weight(String s, List<String> words) {
        int hits = 0;
        for (String w : words) if (s.contains(w)) hits++;
        return Math.min(3.0, 0.6 + hits * 0.8);
    }
}
