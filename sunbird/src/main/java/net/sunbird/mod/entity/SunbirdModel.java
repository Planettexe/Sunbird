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

    @Override
    public void setAngles(MobEntity entity, float movementTickDelta, float animationProgress,
                          float headYaw, float headPitch, float socialInteractionsProgress) {
        // The ball does not animate its parts - the renderer bobs and turns the face instead.
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
