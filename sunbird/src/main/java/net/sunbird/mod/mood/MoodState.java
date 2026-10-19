package net.sunbird.mod.mood;

/**
 * The bird's emotional state. Everything the horror/comedy system does is driven
 * by these numbers - they are tracked per sunbird entity, synced to clients for
 * rendering (face morphs, glow flicker), and fed into the AI prompt so the model
 * actually *role-plays* its mood instead of us faking it with canned lines only.
 */
public class MoodState {
    /** 0..1 - how much the bird likes each nearby player. Decays toward baseline. */
    public float affection = 0.4f;
    /** 0..1 - grudge meter. High affection + high grudge = the creepy "it loves you" phase. */
    public float resentment = 0.0f;
    /** 0..1 - current spookiness level. Drives dark mode, whispers, face changes. */
    public float dread = 0.0f;
    /** 0..1 - boredom. Bored birds do stupid funny things to get attention. */
    public float mischief = 0.2f;
    /** In-game ticks since the bird last received a kind word / interaction. */
    public long lastKindInteractionTick = 0;
    /** True while the bird is in a full haunted episode. */
    public boolean haunting = false;
    /** Ticks remaining in the current haunt episode. */
    public int hauntTicksLeft = 0;
    /** How many times this bird has "died" and come back wrong. */
    public int deathCount = 0;
    /** Tick timestamp of the last prank performed (cooldown). */
    public long lastPrankTick = 0;
    /** Tick timestamp of the last creepy line delivered (cooldown). */
    public long lastCreepyTick = 0;

    public void clamp() {
        affection = Math.max(0f, Math.min(1f, affection));
        resentment = Math.max(0f, Math.min(1f, resentment));
        dread = Math.max(0f, Math.min(1f, dread));
        mischief = Math.max(0f, Math.min(1f, mischief));
    }

    /** Dominant emotional flavour handed to the LLM system prompt. */
    public String describe() {
        if (haunting) return "HAUNTED - something else is wearing this bird tonight";
        if (dread > 0.7f) return "deeply unsettled, seeing things that aren't there";
        if (resentment > 0.6f && affection > 0.5f) return "toxicly attached: loves the players AND holds grudges, passive-aggressive";
        if (resentment > 0.6f) return "bitter and unforgiving, keeps score";
        if (mischief > 0.7f) return "extremely bored and scheming attention-seeking chaos";
        if (affection > 0.75f) return "adoring, almost too adoring";
        if (affection < 0.2f) return "cold, distant, quietly judging";
        return "warm and curious, normal sunny self";
    }

    /** Nudge everything gently back toward "fine" over time. */
    public void decay() {
        dread *= 0.985f;
        resentment *= 0.999f;
        affection += (0.4f - affection) * 0.002f; // gravity toward neutral-friendly
        mischief = Math.min(1f, mischief + 0.0006f); // gets bored standing around
        clamp();
    }
}
