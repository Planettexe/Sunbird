package net.sunbird.mod.entity;

import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.MobEntityRenderer;
import net.minecraft.client.texture.SpriteAtlasTexture;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Matrix4f;
import net.minecraft.util.math.Quaternion;
import net.minecraft.util.math.Vec3d;
import org.joml.Vector3f;

/**
 * The Sunbird is literally a sphere: rendered procedurally, no model or texture
 * files needed. Perfectly round glowing yellow ball + two dot eyes and a smile.
 * The face always rotates toward the camera, so it seems to watch whoever looks
 * at it (a nod to Verity's ever-present companion vibe).
 */
public class SunbirdRenderer<T extends MobEntity> extends MobEntityRenderer<T> {
    private static final Identifier ATLAS = SpriteAtlasTexture.BLOCK_ATLAS_TEXTURE;
    private static final Vector3f BODY = new Vector3f(1.00f, 0.84f, 0.20f);  // sunny yellow
    private static final Vector3f INK  = new Vector3f(0.10f, 0.08f, 0.05f);  // face ink
    private static final float R = 0.32f;

    public SunbirdRenderer(EntityRendererFactory.Context ctx) {
        super(ctx, new SunbirdModel(), 0.25f);
    }

    @Override
    public Identifier getTexture(T entity) {
        return ATLAS;
    }

    @Override
    public void render(T entity, float yaw, float tickDelta, MatrixStack matrices,
                       VertexConsumerProvider vertexConsumers, int light) {
        matrices.push();
        float bob = (float) Math.sin((entity.age + tickDelta) * 0.1f) * 0.05f;
        matrices.translate(0.0, 0.42 + bob, 0.0);

        VertexBufferSink buffer = new VertexBufferSink(
                vertexConsumers.getBuffer(RenderLayer.getEntitySolid(ATLAS)));

        drawSphere(buffer, light, R, 20, 12, BODY);

        // Face orientation: front of the sphere turns toward the camera
        Vec3d cam = this.camera.getPos().subtract(entity.getLerpedPos(tickDelta));
        float faceYaw = (float) Math.toDegrees(Math.atan2(cam.x, cam.z)) + 90f;

        matrices.multiply(new Quaternion(new Vector3f(0f, 1f, 0f), faceYaw, true));
        // Eyes: small dark squares standing proud of the surface at lat +/- positions
        stampFeature(matrices, buffer, light, 12f, 22f, 0.05f, INK);   // right eye
        stampFeature(matrices, buffer, light, 12f, -22f, 0.05f, INK);  // left eye
        stampFeature(matrices, buffer, light, -16f, 0f, 0.09f, INK);   // smile patch
        matrices.pop();

        super.render(entity, yaw, tickDelta, matrices, vertexConsumers, light);
    }

    /** Thin adapter so the quad helpers below stay readable. */
    private record VertexBufferSink(VertexConsumer inner) {
        void vertex(Matrix4f m, Vector3f v, Vector3f col, int light) {
            inner.vertex(m, v.x, v.y, v.z)
                 .color(col.x(), col.y(), col.z(), 1f)
                 .texture(0.5f, 0.5f)
                 .overlay(15728880)
                 .light(light)
                 .normal(v.x, v.y, v.z);
        }
    }

    private void drawSphere(VertexBufferSink out, int light, float r, int slices, int stacks, Vector3f col) {
        Matrix4f identity = new Matrix4f();
        for (int i = 0; i < stacks; i++) {
            float phi0 = (float) Math.PI * (-0.5f + (float) i / stacks);
            float phi1 = (float) Math.PI * (-0.5f + (float) (i + 1) / stacks);
            for (int j = 0; j < slices; j++) {
                float t0 = 2f * (float) Math.PI * j / slices;
                float t1 = 2f * (float) Math.PI * (j + 1) / slices;
                quad(out, identity, light, col,
                        sph(r, phi0, t0), sph(r, phi1, t0), sph(r, phi1, t1), sph(r, phi0, t1));
            }
        }
    }

    /**
     * Place a tiny flat square patch on the sphere surface at (latDeg, lonDeg),
     * oriented along the outward normal. Used for eyes and mouth.
     */
    private void stampFeature(MatrixStack matrices, VertexBufferSink out, int light,
                              float latDeg, float lonDeg, float size, Vector3f col) {
        matrices.push();
        matrices.multiply(new Quaternion(new Vector3f(0f, 1f, 0f), lonDeg, true));
        matrices.multiply(new Quaternion(new Vector3f(1f, 0f, 0f), -latDeg, true));
        matrices.translate(0f, 0f, R * 1.001f);
        Matrix4f m = matrices.peek().getPositionMatrix();
        float s = size;
        quad(out, m, light, col,
                new Vector3f(-s, -s, 0f), new Vector3f(s, -s, 0f),
                new Vector3f(s, s, 0f), new Vector3f(-s, s, 0f));
        matrices.pop();
    }

    private static Vector3f sph(float r, float latRad, float lonRad) {
        return new Vector3f(r * (float) Math.cos(latRad) * (float) Math.sin(lonRad),
                r * (float) Math.sin(latRad),
                r * (float) Math.cos(latRad) * (float) Math.cos(lonRad));
    }

    private static void quad(VertexBufferSink out, Matrix4f m, int light, Vector3f col, Vector3f... vs) {
        for (Vector3f v : vs) out.vertex(m, v, col, light);
    }

    @Override
    protected int getBlockLight(T entity, Vec3d pos) {
        // self-glow like a little sun
        return Math.max(super.getBlockLight(entity, pos), 14 << 4);
    }
}
