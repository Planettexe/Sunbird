package net.sunbird.mod.entity;

import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.model.EntityModelLayer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RotationAxis;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

/**
 * Renders the Sunbird as a glowing round ball with a face, plus two mood layers:
 *  - dread >= ~0.5: dark "static" speckles orbit the ball (horror creeping in)
 *  - haunted:       the whole ball tints toward black-purple and flickers
 *
 * All drawing uses plain VertexConsumer calls - no shader or texture dependencies
 * beyond the one 32x32 skin, so it works on vanilla clients too (they just won't
 * see us; this renderer only runs when the mod is installed client-side).
 */
public class SunbirdRenderer extends EntityRenderer<SunbirdEntity> {

    public static final EntityModelLayer SUNBIRD_LAYER =
            new EntityModelLayer(new Identifier("sunbird", "sunbird"), "main");

    private static final Identifier SKIN = new Identifier("sunbird", "textures/entity/sunbird.png");
    private final SunbirdModel model;

    public SunbirdRenderer(EntityRendererFactory.Context ctx) {
        super(ctx);
        this.model = new SunbirdModel(ctx.getPart(SUNBIRD_LAYER));
        this.shadowRadius = 0.5f;
    }

    @Override
    public Identifier getTexture(SunbirdEntity entity) {
        return SKIN;
    }

    @Override
    protected int getBlockLight(SunbirdEntity entity, BlockPos pos) {
        // The bird always glows a little, even in a dark cave.
        return Math.max(super.getBlockLight(entity, pos), 14 << 4);
    }

    @Override
    public void render(SunbirdEntity entity, float yaw, float tickDelta, MatrixStack matrices,
                       VertexConsumerProvider vertexConsumers, int light) {
        // Drive the talk animation from the tracked state.
        model.talkAmount = entity.isTalking()
                ? 0.5f + 0.5f * (float) Math.sin((entity.age + tickDelta) * 0.9f) : 0f;

        matrices.push();
        // Idle hover bob - it's a floating ball.
        float bob = (float) Math.sin((entity.age + tickDelta) * 0.06f) * 0.08f;
        matrices.translate(0.0, 0.1 + bob, 0.0);
        // Face turns toward where the head is pointed.
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-yaw));

        float dread = entity.getDread();
        boolean haunted = entity.isHauntedVisual();
        // Mood tint: happy = bright yellow-white, haunted = near-black purple.
        float r = 1f - dread * 0.85f;
        float g = 1f - dread * 0.95f;
        float b = haunted ? 0.9f : (1f - dread * 0.4f);
        if (haunted && (entity.age % 17) < 3) { r *= 0.3f; g *= 0.3f; b *= 0.3f; } // flicker

        VertexConsumer consumer = vertexConsumers.getBuffer(RenderLayer.getEntitySolid(SKIN));
        model.render(matrices, consumer, light, OverlayTexture.DEFAULT_UV, r, g, b, 1f);

        // --- Horror layer: static speckles orbit when dread rises ---
        if (dread > 0.45f) {
            drawStatic(matrices, vertexConsumers, entity, dread);
        }

        matrices.pop();
        super.render(entity, yaw, tickDelta, matrices, vertexConsumers, light);
    }

    /** Cheap orbiting "tv static" dots - pure geometry, no shaders needed. */
    private void drawStatic(MatrixStack matrices, VertexConsumerProvider providers,
                            SunbirdEntity entity, float dread) {
        VertexConsumer vc = providers.getBuffer(RenderLayer.getLineStrip());
        Matrix4f m = matrices.peek().getPositionMatrix();
        Matrix3f nm = matrices.peek().getNormalMatrix();
        int count = 4 + (int) (dread * 10);
        for (int i = 0; i < count; i++) {
            float t = (entity.age + i * 7L) * 0.05f + i * 2.4f;
            float rad = 0.45f;
            float x = (float) Math.cos(t) * rad;
            float y = (float) Math.sin(t * 1.7f) * 0.35f + 0.45f;
            float z = (float) Math.sin(t) * rad;
            vc.vertex(m, x, y, z).color(0, 0, 0, 200).normal(nm, 0f, 1f, 0f);
            vc.vertex(m, x + 0.02f, y + 0.02f, z).color(0, 0, 0, 200).normal(nm, 0f, 1f, 0f);
        }
    }
}
