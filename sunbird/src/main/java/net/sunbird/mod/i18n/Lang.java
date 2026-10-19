package net.sunbird.mod.i18n;

import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * Tiny localization helper. All user-facing mod strings go through here so the
 * game translates them using the player's client language (assets/sunbird/lang/*.json).
 */
public final class Lang {
    private Lang() {}

    public static Text t(String key) {
        return Text.translatable(key);
    }

    public static Text t(String key, Formatting color) {
        return Text.translatable(key).formatted(color);
    }

    public static Text t(String key, Object... args) {
        return Text.translatable(key, args);
    }
}
