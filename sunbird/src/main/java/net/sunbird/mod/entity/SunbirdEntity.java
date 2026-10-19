package net.sunbird.mod.entity;

import net.minecraft.entity.EntityData;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.goal.FollowEntityGoal;
import net.minecraft.entity.ai.goal.LookAtEntityGoal;
import net.minecraft.entity.ai.goal.PanicGoal;
import net.minecraft.entity.ai.goal.WanderAroundFarGoal;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.world.LocalDifficulty;
import net.minecraft.world.ServerWorldAccess;
import net.minecraft.world.World;
import net.sunbird.mod.ai.ChatHistory;
import net.sunbird.mod.brain.BehaviorBrain;
import net.sunbird.mod.mood.HauntDirector;

/**
 * The Sunbird: a perfectly round, glowing yellow ball of a bird with a simple face.
 * Floats around, follows nearby players, chirps, and is the physical anchor for the
 * server-hosted AI brain. The intelligence lives in BehaviorBrain on the server -
 * this entity is just its body, so even vanilla clients benefit from it.
 *
 * Mood (dread/haunted) is synced via DataTracker so ALL clients - including other
 * mods' shaders - can render the wrongness when the bird goes dark.
 */
public class SunbirdEntity extends PathAwareEntity {

    private static final TrackedData<Float> DREAD =
            DataTracker.registerData(SunbirdEntity.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<Boolean> HAUNTED =
            DataTracker.registerData(SunbirdEntity.class, TrackedDataHandlerRegistry.BOOLEAN);

    public SunbirdEntity(EntityType<? extends PathAwareEntity> type, World world) {
        super(type, world);
        this.experienceValue = 0;
        this.setNoGravity(true); // it's a floating ball
    }

    public static EntityType.Builder<? extends MobEntity> buildEntityType() {
        return EntityType.Builder.<SunbirdEntity>create(SunbirdEntity::new, SpawnGroup.CREATURE)
                .dimensions(0.6f, 0.6f)   // a sphere-ish hitbox for a sphere-body
                .maxDistance(48)
                .fireImmune();
    }

    public static DefaultAttributeContainer.Builder createAttributes() {
        return PathAwareEntity.createMobAttributes()
                .add(EntityAttributes.MAX_HEALTH, 10.0)
                .add(EntityAttributes.MOVEMENT_SPEED, 0.30)
                .add(EntityAttributes.FLYING_SPEED, 0.45);
    }

    @Override
    protected void initDataTracker() {
        super.initDataTracker();
        this.dataTracker.startTracking(DREAD, 0f);
        this.dataTracker.startTracking(HAUNTED, false);
    }

    public float getDread() { return this.dataTracker.get(DREAD); }
    public boolean isHauntedVisual() { return this.dataTracker.get(HAUNTED); }

    @Override
    public EntityData initialize(ServerWorldAccess world, LocalDifficulty difficulty,
                                 net.minecraft.entity.SpawnReason reason,
                                 EntityData spawnData, NbtCompound dataTag) {
        EntityData d = super.initialize(world, difficulty, reason, spawnData, dataTag);
        // A brand-new bird inherits the shared soul-state: if the flock has seen things,
        // this one starts... uneasy. Very Verity: there is only ever ONE sunbird mind.
        var m = HauntDirector.mood();
        this.dataTracker.set(DREAD, Math.min(1f, m.dread));
        this.dataTracker.set(HAUNTED, m.haunting);
        return d;
    }

    @Override
    protected void initGoals() {
        // Gentle wander + follow; all "thinking" happens server-side in BehaviorBrain
        this.goalSelector.add(0, new PanicGoal(this, 1.2));
        this.goalSelector.add(1, new LookAtEntityGoal(this, PlayerEntity.class, 8.0f));
        this.goalSelector.add(2, new FollowEntityGoal<>(this, PlayerEntity.class, 8.0f, 1.15, 16.0f));
        this.goalSelector.add(3, new WanderAroundFarGoal(this, 0.9));
    }

    /** It's a ball - it bobs instead of walking awkwardly, and hums now and then. */
    @Override
    public void tick() {
        super.tick();
        if (!this.getWorld().isClient && this.age % 20 == 0) {
            // sync shared mood into the visible body once per second
            var m = HauntDirector.mood();
            this.dataTracker.set(DREAD, Math.min(1f, m.dread));
            this.dataTracker.set(HAUNTED, m.haunting);
        }
        if (!this.getWorld().isClient && this.age % 200 == 0 && this.getRandom().nextFloat() < 0.25f) {
            var m = HauntDirector.mood();
            // haunted birds hum at a WRONG pitch. you will notice before you know why.
            float pitch = m.haunting ? 0.45f : 1.6f;
            this.getWorld().playSound(null, this.getBlockPos(), SoundEvents.ENTITY_PARROT_AMBIENT,
                    SoundCategory.NEUTRAL, 0.7f, pitch);
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
        HauntDirector.onPet(player); // petting the bird is kindness; it notices
        ChatHistory.add("*system*", player.getName().getString()
                + " sat down next to a sunbird and wants to talk privately.");
        BehaviorBrain.askDirectly(player,
                "(You are sitting together. " + player.getName().getString()
                        + " just sat down beside you and said hi. Greet them warmly and ask what's on their mind.)");
        this.getWorld().playSound(null, this.getBlockPos(), SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP,
                SoundCategory.NEUTRAL, 0.6f, 1.4f);
    }

    /** Being attacked feeds the grudge ledger. The bird does not forget faces. */
    @Override
    public boolean damage(DamageSource source, float amount) {
        // handled below
        if (!this.getWorld().isClient && source.getAttacker() instanceof PlayerEntity p) {
            HauntDirector.onHurt(p instanceof net.minecraft.server.network.ServerPlayerEntity sp ? sp : null, amount);
        } else if (!this.getWorld().isClient) {
            HauntDirector.onHurt(null, amount);
        }
        return super.damage(source, amount);
    }

    @Override
    public void onDeath(DamageSource source) {
        super.onDeath(source);
        if (!this.getWorld().isClient) {
            String killer = null;
            LivingEntity atk = source.getAttacker() instanceof LivingEntity l ? l : null;
            if (atk instanceof PlayerEntity p) killer = p.getName().getString();
            BehaviorBrain.onBirdDeath(killer);
        }
    }

    @Override
    protected void fall(float distance, double damageMultiplier) {
        // rubber ball: bounce, no damage
        this.setVelocity(this.getVelocity().multiply(1.0, -0.4, 1.0));
    }

    @Override
    public boolean canPickUpLoot() { return false; }
}
