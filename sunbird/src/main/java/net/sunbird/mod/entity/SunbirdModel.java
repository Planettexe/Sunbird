package net.sunbird.mod.entity;

import net.minecraft.client.model.Dilation;
import net.minecraft.client.model.ModelData;
import net.minecraft.client.model.ModelPartBuilder;
import net.minecraft.client.model.ModelPartData;
import net.minecraft.client.model.ModelTransform;
import net.minecraft.client.model.TexturedModelData;
import net.minecraft.client.render.entity.model.EntityModelPartNames;
import net.minecraft.client.render.entity.model.SinglePartEntityModel;
import net.minecraft.entity.mob.MobEntity;

/**
 * Minimal model: one cube at the origin. SunbirdRenderer draws the real sphere + face
 * procedurally on top of it, so this only has to exist for MobEntityRenderer's API.
 */
public class SunbirdModel extends SinglePartEntityModel<MobEntity> {
    public SunbirdModel() {
        this.rootPart = getTexturedModelData().createModel();
    }

    /** 0..1 while the bird is voicing a TTS clip; drives the talking squash-stretch. */
    public float talk = 0f;

    @Override
    public void setAngles(MobEntity entity, float movementTickDelta, float animationProgress,
                          float headYaw, float headPitch, float socialInteractionsProgress) {
        // The ball does not animate its parts - the renderer bobs and turns the face instead.
        // ...EXCEPT when it's talking: gentle squash-and-stretch pulse so you can see
        // WHO is speaking even with the chat log closed. Purely visual, zero packets.
        if (talk > 0.001f) {
            float t = System.currentTimeMillis() * 0.02f;
            float pulse = (float) Math.abs(Math.sin(t * 1.7)) * talk;
            rootPart.scale(1f + pulse * 0.05f, 1f - pulse * 0.04f, 1f + pulse * 0.05f);
        }
    }

    public static TexturedModelData getTexturedModelData() {
        ModelData modelData = new ModelData();
        ModelPartData modelPartData = modelData.getRoot();
        modelPartData.addChild(EntityModelPartNames.BODY,
                ModelPartBuilder.create(),
                ModelTransform.pivot(0.0F, 12.0F, 0.0F));
        return TexturedModelData.of(modelData, 16, 16);
    }
}
