package net.sunbird.mod.net;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side record of which connected modded clients want to hear the bird's
 * synthesised voice. Defaults to true for anyone who can receive the channel;
 * /aivoice off flips it per player (server-authoritative, survives reloads via
 * the config mute list). Vanilla clients are never in this map at all.
 */
public final class VoicePrefs {
    private static final Map<UUID, Boolean> WANTS = new ConcurrentHashMap<>();

    private VoicePrefs() {}

    public static void set(UUID player, boolean wants) {
        WANTS.put(player, wants);
    }

    public static boolean wants(UUID player) {
        return WANTS.getOrDefault(player, true);
    }

    public static void forget(UUID player) {
        WANTS.remove(player);
    }
}
