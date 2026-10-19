package net.sunbird.mod.entity;

import net.minecraft.client.model.Dilation;
import net.minecraft.client.model.ModelData;
import net.minecraft.client.model.ModelPartBuilder;
import net.minecraft.client.model.ModelPartData;
import net.minecraft.client.model.ModelTransform;
import net.minecraft.client.model.TexturedModelData;
import net.minecraft.client.render.entity.model.SinglePartEntityModel;
import net.minecraft.client.model.ModelPart;

/**
 * A perfectly round yellow ball with a face.
 *
 * The body is built from three stacked cuboid slices with dilation, which reads as a
 * sphere at Minecraft's pixel scale. The face layer sits on the front: two eyes + beak,
 * and the mouth opens while TTS talks (talkAmount driven by the renderer).
 */
public class SunbirdModel extends SinglePartEntityModel<SunbirdEntity> {

    private final ModelPart root;
    private final ModelPart body;
    private final ModelPart face;
    private final ModelPart mouth;

    /** Set by the renderer from the entity's tracked talk state (0..1 openness). */
    public float talkAmount = 0f;

    public SunbirdModel(ModelPart root) {
        this.root = root;
        this.body = root.getChild("body");
        this.face = body.getChild("face");
        this.mouth = face.getChild("mouth");
    }

    public static TexturedModelData getTexturedModelData() {
        ModelData data = new ModelData();
        ModelPartData root = data.getRoot();

        // Three slices -> looks like a ball. UV box 32x32 for a chunky retro face.
        ModelPartData body = root.addChild(
                "body",
                ModelPartBuilder.create()
                        .uv(0, 0)
                        .cuboid(-4f, -8f, -4f, 8f, 5f, 8f, new Dilation(1.6f))
                        .uv(0, 14)
                        .cuboid(-5f, -5f, -5f, 10f, 4f, 10f, new Dilation(1.2f))
                        .uv(0, 28)
                        .cuboid(-4f, -3f, -4f, 8f, 3f, 8f, new Dilation(1.6f)),
                ModelTransform.of(0f, 8f, 0f, 0f, 0f, 0f));

        ModelPartData face = body.addChild(
                "face",
                ModelPartBuilder.create()
                        .uv(20, 4)
                        .cuboid(-3.2f, -2.2f, -4.9f, 1.2f, 1.2f, 0.6f)   // left eye
                        .uv(24, 4)
                        .cuboid(2.0f, -2.2f, -4.9f, 1.2f, 1.2f, 0.6f)    // right eye
                        .uv(16, 2)
                        .cuboid(-0.8f, -0.6f, -5.2f, 1.6f, 1.0f, 1.0f),  // beak
                ModelTransform.NONE);

        face.addChild(
                "mouth",
                ModelPartBuilder.create()
                        .uv(16, 6)
                        .cuboid(-1.0f, 0.6f, -4.9f, 2.0f, 0.6f, 0.4f),
                ModelTransform.NONE);

        return TexturedModelData.of(data, 32, 32);
    }

    @Override
    public ModelPart getPart() {
        return root;
    }

    @Override
    public void setAngles(SunbirdEntity entity, float limbAngle, float limbDistance,
                          float animationProgress, float headYaw, float headPitch) {
        face.yaw = headYaw * ((float) Math.PI / 180F);
        face.pitch = headPitch * ((float) Math.PI / 180F);
        // Idle wobble so it never looks frozen.
        body.roll = (float) Math.sin(animationProgress * 0.05f) * 0.04f;
        // Talk animation: squash & stretch + mouth open/close driven by talkAmount.
        float open = talkAmount;
        mouth.yScale = 1f + open * 2.2f;
        mouth.pitch = open * 0.35f;
        body.yScale = 1f + open * 0.06f;
        body.xScale = 1f - open * 0.03f;
        body.zScale = 1f - open * 0.03f;
    }
}
