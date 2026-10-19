package net.sunbird.mod.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.sunbird.mod.SunbirdConfig;
import net.sunbird.mod.ai.AiBridge;
import net.sunbird.mod.ai.ChatHistory;
import net.sunbird.mod.brain.BehaviorBrain;
import net.sunbird.mod.i18n.LangService;

/**
 * /ai <question>       - ask the bird directly (whisper reply)
 * /aiconfig show|set|reload|test - manage the backend without editing files blind
 * /aiclear             - wipe conversation memory
 * /aiwake              - force an autonomous thought right now
 * /aivoice on|off|mute|unmute|test - control the streamed TTS voice
 */
public final class SunbirdCommands {

    private SunbirdCommands() {}

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("ai")
                .requires(src -> src.hasPermissionLevel(0))
                .then(CommandManager.argument("question", StringArgumentType.greedyString())
                        .executes(SunbirdCommands::ask)))
            .register(CommandManager.literal("aiclear")
                .requires(src -> src.hasPermissionLevel(2))
                .executes(ctx -> {
                    ctx.getSource().sendFeedback(Text.literal(LangService.tr(ctx.getSource().getEntity(), "sunbird.prefix") + LangService.tr(ctx.getSource().getEntity(), "sunbird.cmd.aiclear")).formatted(Formatting.GOLD), true);
                    return 1;
                }))
            .register(CommandManager.literal("aiwake")
                .requires(src -> src.hasPermissionLevel(2))
                .executes(ctx -> {
                    BehaviorBrain.maybeAutonomousThought();
                    ctx.getSource().sendFeedback(Text.literal(LangService.tr(ctx.getSource().getEntity(), "sunbird.prefix") + LangService.tr(ctx.getSource().getEntity(), "sunbird.cmd.thinking")).formatted(Formatting.YELLOW), false);
                    return 1;
                }))
            .register(CommandManager.literal("aivoice")
                .requires(src -> src.hasPermissionLevel(0))
                .then(CommandManager.literal("on").executes(ctx -> voicePref(ctx, true)))
                .then(CommandManager.literal("off").executes(ctx -> voicePref(ctx, false)))
                .then(CommandManager.literal("status").executes(SunbirdCommands::voiceStatus))
                .then(CommandManager.literal("test")
                        .requires(src -> src.hasPermissionLevel(2))
                        .then(CommandManager.argument("text", StringArgumentType.greedyString())
                                .executes(SunbirdCommands::voiceTest)))
                .then(CommandManager.literal("mute")
                        .requires(src -> src.hasPermissionLevel(2))
                        .then(CommandManager.argument("player", StringArgumentType.word())
                                .executes(ctx -> voiceMute(ctx, true))))
                .then(CommandManager.literal("unmute")
                        .requires(src -> src.hasPermissionLevel(2))
                        .then(CommandManager.argument("player", StringArgumentType.word())
                                .executes(ctx -> voiceMute(ctx, false)))))
            .register(CommandManager.literal("aiconfig")
                .requires(src -> src.hasPermissionLevel(2))
                .then(CommandManager.literal("show").executes(SunbirdCommands::show))
                .then(CommandManager.literal("reload").executes(ctx -> {
                    SunbirdConfig.reload();
                    ctx.getSource().sendFeedback(Text.literal(LangService.tr(ctx.getSource().getEntity(), "sunbird.prefix")
            + LangService.tr(ctx.getSource().getEntity(), "sunbird.cmd.reload").replace("%s", AiBridge.describe())).formatted(Formatting.GREEN), true);
                    return 1;
                }))
                .then(CommandManager.literal("test").executes(SunbirdCommands::test))
                .then(CommandManager.literal("set")
                        .then(CommandManager.argument("key", StringArgumentType.word())
                                .then(CommandManager.argument("value", StringArgumentType.greedyString())
                                        .executes(SunbirdCommands::set)))));
    }

    private static int ask(CommandContext<ServerCommandSource> ctx) {
        String q = StringArgumentType.getString(ctx, "question");
        ServerPlayerEntity player = ctx.getSource().getEntity();
        if (player == null) {
            ctx.getSource().sendError(Text.literal(LangService.tr(null, "sunbird.cmd.playersonly")));
            return 0;
        }
        BehaviorBrain.askDirectly(player, q);
        player.sendMessage(Text.literal(LangService.tr(player, "sunbird.cmd.thinking_dots")).formatted(Formatting.DARK_GRAY), false);
        return 1;
    }

    private static int show(CommandContext<ServerCommandSource> ctx) {
        SunbirdConfig cfg = SunbirdConfig.get();
        ctx.getSource().sendFeedback(Text.literal("apiBaseUrl = " + cfg.apiBaseUrl).formatted(Formatting.YELLOW), false);
        ctx.getSource().sendFeedback(Text.literal("model = " + cfg.model + "  style = " + cfg.endpointStyle).formatted(Formatting.YELLOW), false);
        ctx.getSource().sendFeedback(Text.literal("temperature = " + cfg.temperature + "  maxTokens = " + cfg.maxTokens).formatted(Formatting.YELLOW), false);
        ctx.getSource().sendFeedback(Text.literal("listenToAllChat = " + cfg.listenToAllChat + "  memoryTurns = " + cfg.memoryTurns).formatted(Formatting.YELLOW), false);
        ctx.getSource().sendFeedback(Text.literal("config file: config/sunbird/config.json").formatted(Formatting.GRAY), false);
        return 1;
    }

    private static int test(CommandContext<ServerCommandSource> ctx) {
        var source = ctx.getSource();
        source.sendFeedback(Text.literal(LangService.tr(source.getEntity(), "sunbird.prefix") + LangService.tr(source.getEntity(), "sunbird.cmd.probing").replace("%s", AiBridge.describe())).formatted(Formatting.GRAY), false);
        AiBridge.healthCheck().whenComplete((res, err) -> {
            var entry = res.entrySet().iterator().next();
            source.getServer().execute(() ->
                    source.sendFeedback(Text.literal("[Sunbird] " + entry.getValue())
                            .formatted(entry.getKey() ? Formatting.GREEN : Formatting.RED), false));
        });
        return 1;
    }

    private static int set(CommandContext<ServerCommandSource> ctx) {
        String key = StringArgumentType.getString(ctx, "key");
        String value = StringArgumentType.getString(ctx, "value");
        SunbirdConfig cfg = SunbirdConfig.get();
        try {
            var field = SunbirdConfig.class.getField(key);
            Class<?> t = field.getType();
            if (t == String.class) field.set(cfg, value);
            else if (t == double.class) field.setDouble(cfg, Double.parseDouble(value));
            else if (t == int.class) field.setInt(cfg, Integer.parseInt(value));
            else if (t == boolean.class) field.setBoolean(cfg, Boolean.parseBoolean(value));
            else {
                ctx.getSource().sendError(Text.literal(LangService.tr(null, "sunbird.cmd.badfield").replace("%s", key)));
                return 0;
            }
            SunbirdConfig.save();
            SunbirdConfig.reload();
            ctx.getSource().sendFeedback(Text.literal(LangService.tr(ctx.getSource().getEntity(), "sunbird.prefix") + LangService.tr(ctx.getSource().getEntity(), "sunbird.cmd.saved").replace("%s", key).replace("%s", value))
                    .formatted(Formatting.GREEN), true);
            return 1;
        } catch (NoSuchFieldException e) {
            ctx.getSource().sendError(Text.literal(LangService.tr(null, "sunbird.cmd.unknownkey").replace("%s", key)
                    + " (see /aiconfig show or config/sunbird/config.json)"));
            return 0;
        } catch (Exception e) {
            ctx.getSource().sendError(Text.literal(LangService.tr(null, "sunbird.cmd.failed").replace("%s", String.valueOf(e.getMessage()))));
            return 0;
        }
    }

    // ---------------- voice (/aivoice) ----------------

    /**
     * on/off flips the SERVER-side gate for this player. We keep it server-authoritative
     * so no C2S channel is needed and vanilla clients simply get nothing extra ever.
     */
    private static int voicePref(CommandContext<ServerCommandSource> ctx, boolean on) {
        ServerPlayerEntity player = ctx.getSource().getEntity();
        if (player == null) {
            ctx.getSource().sendError(Text.literal(LangService.tr(null, "sunbird.cmd.playersonly.voice")));
            return 0;
        }
        SunbirdConfig cfg = SunbirdConfig.get();
        String name = player.getName().getString();
        boolean muted = !on;
        if (muted && !cfg.ttsMutedPlayers.contains(name)) cfg.ttsMutedPlayers.add(name);
        if (!muted) cfg.ttsMutedPlayers.remove(name);
        SunbirdConfig.save();
        net.sunbird.mod.net.VoicePrefs.set(player.getUuid(), on);
        ctx.getSource().sendFeedback(Text.literal(LangService.tr(player, "sunbird.prefix") + LangService.tr(player, on ? "sunbird.cmd.voice_on" : "sunbird.cmd.voice_off")).formatted(on ? Formatting.GREEN : Formatting.GRAY), false);
        return 1;
    }

    private static int voiceStatus(CommandContext<ServerCommandSource> ctx) {
        var source = ctx.getSource();
        source.sendFeedback(Text.literal("[Sunbird] TTS: "
                + net.sunbird.mod.tts.TtsBridge.describe()).formatted(Formatting.YELLOW), false);
        SunbirdConfig cfg = SunbirdConfig.get();
        source.sendFeedback(Text.literal("engine=" + cfg.ttsEngine + " voice=" + cfg.ttsVoice
                + " speed=" + cfg.ttsSpeed + " volume=" + cfg.ttsVolume
                + " warps=" + cfg.ttsHorrorWarps).formatted(Formatting.GRAY), false);
        if (!cfg.ttsMutedPlayers.isEmpty()) {
            source.sendFeedback(Text.literal("muted: " + String.join(", ", cfg.ttsMutedPlayers))
                    .formatted(Formatting.GRAY), false);
        }
        return 1;
    }

    /** Synthesise a test clip right now and send it ONLY to the sender at their head. */
    private static int voiceTest(CommandContext<ServerCommandSource> ctx) {
        ServerPlayerEntity player = ctx.getSource().getEntity();
        if (player == null) {
            ctx.getSource().sendError(Text.literal("Only players can hear the test."));
            return 0;
        }
        if (!net.sunbird.mod.tts.TtsBridge.enabled()) {
            ctx.getSource().sendError(Text.literal(LangService.tr(null, "sunbird.cmd.tts_disabled")));
            return 0;
        }
        String text = StringArgumentType.getString(ctx, "text");
        ctx.getSource().sendFeedback(Text.literal(LangService.tr(player, "sunbird.prefix") + LangService.tr(player, "sunbird.cmd.voicing")
                .replace("%s", text).replace("%s", net.sunbird.mod.tts.TtsBridge.describe())).formatted(Formatting.GRAY), false);
        final int[] anchor = {(int) (player.getX() * 8), (int) ((player.getY() + 1.5) * 8), (int) (player.getZ() * 8)};
        net.sunbird.mod.tts.TtsBridge.synthesizeNow(text).whenComplete((wav, err) -> {
            if (err != null || wav == null || wav.length == 0) {
                player.getServer().execute(() -> player.sendMessage(
                        Text.literal(LangService.tr(player, "sunbird.prefix") + LangService.tr(player, "sunbird.cmd.voicetest_failed")
                                .replace("%s", err != null ? String.valueOf(err.getMessage()) : "empty audio")).formatted(Formatting.RED), false));
                return;
            }
            player.getServer().execute(() -> {
                boolean ok = net.sunbird.mod.net.SunbirdClientNetworking.sendVoiceTo(
                        player, -1, anchor[0], anchor[1], anchor[2],
                        SunbirdConfig.get().ttsVolume, wav);
                player.sendMessage(Text.literal(ok
                        ? "[Sunbird] Voice test delivered (" + wav.length / 1024 + " KB)."
                        : "[Sunbird] Your client can't receive audio - chat-only mode.").formatted(
                        ok ? Formatting.GREEN : Formatting.GRAY), false);
            });
        });
        return 1;
    }

    private static int voiceMute(CommandContext<ServerCommandSource> ctx, boolean mute) {
        String target = StringArgumentType.getString(ctx, "player");
        SunbirdConfig cfg = SunbirdConfig.get();
        if (mute) {
            if (!cfg.ttsMutedPlayers.contains(target)) cfg.ttsMutedPlayers.add(target);
        } else {
            cfg.ttsMutedPlayers.remove(target);
        }
        SunbirdConfig.save();
        ctx.getSource().sendFeedback(Text.literal(LangService.tr(ctx.getSource().getEntity(), "sunbird.prefix") + target + " " + LangService.tr(ctx.getSource().getEntity(), mute ? "sunbird.cmd.target_voice_off" : "sunbird.cmd.target_voice_on")).formatted(Formatting.GOLD), true);
        return 1;
    }
}
