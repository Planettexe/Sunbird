package net.sunbird.mod.entity;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.ai.goal.FollowEntityGoal;
import net.minecraft.entity.ai.goal.LookAtEntityGoal;
import net.minecraft.entity.ai.goal.WanderAroundFarGoal;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.world.World;
import net.sunbird.mod.ai.ChatHistory;
import net.sunbird.mod.brain.BehaviorBrain;

/**
 * The Sunbird: a perfectly round, glowing yellow ball of a bird with a simple face.
 * Floats around, follows nearby players, chirps, and is the physical anchor for the
 * server-hosted AI brain. The intelligence lives in BehaviorBrain on the server -
 * this entity is just its body, so even vanilla clients benefit from it.
 */
public class SunbirdEntity extends MobEntity {

    public SunbirdEntity(EntityType<? extends MobEntity> type, World world) {
        super(type, world);
        this.experienceValue = 0;
    }

    public static EntityType.Builder<MobEntity> buildEntityType() {
        return EntityType.Builder.create(SunbirdEntity::new, SpawnGroup.CREATURE)
                .dimensions(0.6f, 0.6f)   // a sphere-ish hitbox for a sphere-body
                .maxDistance(48)
                .fireImmune();
    }

    public static DefaultAttributeContainer.Builder createAttributes() {
        return MobEntity.createMobAttributes()
                .add(EntityAttributes.MAX_HEALTH, 10.0)
                .add(EntityAttributes.MOVEMENT_SPEED, 0.30)
                .add(EntityAttributes.FLYING_SPEED, 0.45);
    }

    @Override
    protected void initGoals() {
        // Gentle wander + follow; all "thinking" happens server-side in BehaviorBrain
        this.goalSelector.add(1, new LookAtEntityGoal(this, PlayerEntity.class, 8.0f));
        this.goalSelector.add(2, new FollowEntityGoal<>(this, PlayerEntity.class, 8.0f, 1.15, 16.0f));
        this.goalSelector.add(3, new WanderAroundFarGoal(this, 0.9));
    }

    /** It's a ball - it bobs instead of walking awkwardly, and hums now and then. */
    @Override
    public void tick() {
        super.tick();
        if (!this.getWorld().isClient && this.age % 200 == 0 && this.getRandom().nextFloat() < 0.25f) {
            this.getWorld().playSound(null, this.getBlockPos(), SoundEvents.ENTITY_PARROT_AMBIENT,
                    SoundCategory.NEUTRAL, 0.7f, 1.6f);
        }
    }

    @Override
    public ActionResult interactMob(PlayerEntity player, Hand hand) {
        if (this.getWorld().isClient) return ActionResult.SUCCESS;
        openTalkChannel(player);
        return ActionResult.CONSUME;
    }

    /** Right-click conversation channel: whisper-mode Q&A against the shared brain. */
    public void openTalkChannel(net.minecraft.server.network.ServerPlayerEntity player) {
        ChatHistory.add("*system*", player.getName().getString()
                + " sat down next to a sunbird and wants to talk privately.");
        BehaviorBrain.askDirectly(player,
                "(You are sitting together. " + player.getName().getString()
                        + " just sat down beside you and said hi. Greet them warmly and ask what's on their mind.)");
        this.getWorld().playSound(null, this.getBlockPos(), SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP,
                SoundCategory.NEUTRAL, 0.6f, 1.4f);
    }

    @Override
    protected void fall(float distance, double damageMultiplier) {
        // rubber ball: bounce, no damage
        this.setVelocity(this.getVelocity().multiply(1.0, -0.4, 1.0));
    }

    @Override
    public boolean canPickUpLoot() { return false; }
}
