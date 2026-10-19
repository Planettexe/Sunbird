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

/**
 * /ai <question>       - ask the bird directly (whisper reply)
 * /aiconfig show|set|reload|test - manage the backend without editing files blind
 * /aiclear             - wipe conversation memory
 * /aiwake              - force an autonomous thought right now
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
                    ctx.getSource().sendFeedback(Text.literal("[Sunbird] Memory wiped.").formatted(Formatting.GOLD), true);
                    return 1;
                }))
            .register(CommandManager.literal("aiwake")
                .requires(src -> src.hasPermissionLevel(2))
                .executes(ctx -> {
                    BehaviorBrain.maybeAutonomousThought();
                    ctx.getSource().sendFeedback(Text.literal("[Sunbird] Thinking...").formatted(Formatting.YELLOW), false);
                    return 1;
                }))
            .register(CommandManager.literal("aiconfig")
                .requires(src -> src.hasPermissionLevel(2))
                .then(CommandManager.literal("show").executes(SunbirdCommands::show))
                .then(CommandManager.literal("reload").executes(ctx -> {
                    SunbirdConfig.reload();
                    ctx.getSource().sendFeedback(Text.literal("[Sunbird] Config reloaded. Backend: "
                            + AiBridge.describe()).formatted(Formatting.GREEN), true);
                    return 1;
                }))
                .then(CommandManager.literal("test").executes(SunbirdCommands::test))
                .then(CommandManager.literal("set")
                        .then(CommandManager.argument("key", StringArgumentType.word())
                                .then(CommandManager.argument("value", StringArgumentType.greedyString())
                                        .executes(SunbirdCommands::set))))));
    }

    private static int ask(CommandContext<ServerCommandSource> ctx) {
        String q = StringArgumentType.getString(ctx, "question");
        ServerPlayerEntity player = ctx.getSource().getEntity();
        if (player == null) {
            ctx.getSource().sendError(Text.literal("Only players can talk to the bird."));
            return 0;
        }
        BehaviorBrain.askDirectly(player, q);
        player.sendMessage(Text.literal("[Sunbird is thinking...]").formatted(Formatting.DARK_GRAY), false);
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
        source.sendFeedback(Text.literal("[Sunbird] Probing " + AiBridge.describe() + "...").formatted(Formatting.GRAY), false);
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
                ctx.getSource().sendError(Text.literal("Unsupported field type for " + key));
                return 0;
            }
            SunbirdConfig.save();
            SunbirdConfig.reload();
            ctx.getSource().sendFeedback(Text.literal("[Sunbird] " + key + " = " + value + " (saved & applied)")
                    .formatted(Formatting.GREEN), true);
            return 1;
        } catch (NoSuchFieldException e) {
            ctx.getSource().sendError(Text.literal("Unknown config key: " + key
                    + " (see /aiconfig show or config/sunbird/config.json)"));
            return 0;
        } catch (Exception e) {
            ctx.getSource().sendError(Text.literal("Failed: " + e.getMessage()));
            return 0;
        }
    }
}
