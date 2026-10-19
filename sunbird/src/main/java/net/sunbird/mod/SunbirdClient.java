package net.sunbird.mod;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.sunbird.mod.entity.SunbirdRenderer;
import net.sunbird.mod.net.SunbirdClientNetworking;

/**
 * Client side: registers the procedural sphere renderer and listens for the bird's
 * lines to show action-bar echoes + chirps. Purely cosmetic - if a player skips this
 * (vanilla client), the server-side chat still works perfectly.
 */
public class SunbirdClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        EntityRendererRegistry.register(SunbirdFeaturesClient.ENTITY_TYPE, SunbirdRenderer::new);

        ClientPlayNetworking.registerGlobalReceiver(SunbirdClientNetworking.BUBBLE_S2C,
                (client, handler, buf, responseSender) -> {
                    String text = buf.readString();
                    client.execute(() -> {
                        MinecraftClient.getInstance().inGameHud.setOverlayMessage(
                                Text.literal("\u2600 ").formatted(Formatting.GOLD)
                                        .append(Text.literal(text).formatted(Formatting.YELLOW)), false);
                    });
                });

        ClientPlayNetworking.registerGlobalReceiver(SunbirdClientNetworking.CHIRP_S2C,
                (client, handler, buf, responseSender) ->
                        client.execute(() -> client.getSoundManager().play(
                                PositionedSoundInstance.master(SoundEvents.ENTITY_PARROT_AMBIENT, 1.7f, 0.5f))));
    }

    /** Lazy accessor holder so the client file doesn't import the whole registry twice. */
    private static class SunbirdFeaturesClient {
        static final net.minecraft.entity.EntityType<?> ENTITY_TYPE =
                net.sunbird.mod.world.SunbirdFeatures.SUNBIRD_ENTITY_TYPE;
    }
}
