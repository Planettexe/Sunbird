package net.sunbird.mod;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Identifier;
import net.sunbird.mod.ai.AiBridge;
import net.sunbird.mod.ai.ChatHistory;
import net.sunbird.mod.ai.SunbirdPersona;
import net.sunbird.mod.brain.BehaviorBrain;
import net.sunbird.mod.brain.PerceptionService;
import net.sunbird.mod.commands.SunbirdCommands;
import net.sunbird.mod.entity.SunbirdEntity;
import net.sunbird.mod.net.SunbirdClientNetworking;
import net.sunbird.mod.world.SunbirdFeatures;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sunbird - a Verity-inspired AI companion for Minecraft, rebuilt to be:
 *  - server-hosted: one llama.cpp / OpenAI-compatible endpoint serves EVERYBODY on the server
 *  - client-optional: vanilla clients can join and still talk to the bird (chat only)
 *  - fully local: no cloud, no API keys required, talks about anything (bring your own uncensored GGUF)
 */
public class SunbirdMod implements ModInitializer {
    public static final String MOD_ID = "sunbird";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    /** The running server, if any (integrated or dedicated). */
    public static MinecraftServer SERVER = null;

    public static Identifier id(String path) {
        return new Identifier(MOD_ID, path);
    }

    @Override
    public void onInitialize() {
        LOGGER.info("[Sunbird] Warming up the sunbird...");

        // Config + AI bridge
        SunbirdConfig.load();
        AiBridge.init(SunbirdConfig.get());

        // Entities, items, blocks
        SunbirdFeatures.register();
        FabricDefaultAttributeRegistry.register(SunbirdFeatures.SUNBIRD_ENTITY_TYPE,
                SunbirdEntity.createAttributes());

        // Networking (S2C payloads for speech bubbles / chirps; optional-client safe)
        SunbirdClientNetworking.registerServerPayloadHandlers();

        // Commands: /ai /aiconfig /aiwake /aiclear
        CommandRegistrationCallback.EVENT.register(
                (dispatcher, registryAccess, environment) -> SunbirdCommands.register(dispatcher));

        // Chat pipeline: player chat goes to the model queue, replies come back in chat.
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, paramSet) -> {
            BehaviorBrain.onPlayerChat(sender, message.signedBody().getString());
            return true; // never cancel normal chat
        });

        // Greetings / farewells / deaths
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                BehaviorBrain.onJoin(handler.player));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                BehaviorBrain.onLeave(handler.player));
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) ->
                BehaviorBrain.onRespawn(newPlayer));

        // (Right-click handling lives in SunbirdEntity#interactMob - no callback needed.)

        // Lifecycle: load memory, start brain & perception off the tick loop
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            SERVER = server;
            ChatHistory.load(server);
            BehaviorBrain.start();
            PerceptionService.start();
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            BehaviorBrain.stop();
            PerceptionService.stop();
            ChatHistory.save(server);
            SERVER = null;
        });
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            SunbirdPersona.maybeRotateGreeting();
            BehaviorBrain.warmUp();
        });

        LOGGER.info("[Sunbird] Ready. Backend: {}", AiBridge.describe());
    }
}
