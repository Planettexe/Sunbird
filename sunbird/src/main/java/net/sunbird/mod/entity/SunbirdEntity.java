package net.sunbird.mod.entity;

import net.minecraft.entity.EntityData;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.goal.FleeEntityGoal;
import net.minecraft.entity.ai.goal.LookAtEntityGoal;
import net.minecraft.entity.ai.goal.WanderAroundFarGoal;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
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
 * Mood (dread/haunted) and talk state are synced via DataTracker so ALL clients can
 * render the wrongness when the bird goes dark.
 */
public class SunbirdEntity extends PathAwareEntity {

    private static final TrackedData<Float> DREAD =
            DataTracker.registerData(SunbirdEntity.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<Boolean> HAUNTED =
            DataTracker.registerData(SunbirdEntity.class, TrackedDataHandlerRegistry.BOOLEAN);
    private static final TrackedData<Boolean> TALKING =
            DataTracker.registerData(SunbirdEntity.class, TrackedDataHandlerRegistry.BOOLEAN);

    /** Server-side countdown (in ticks) that keeps TALKING true while audio plays. */
    private int talkTicksLeft = 0;

    public SunbirdEntity(EntityType<? extends PathAwareEntity> type, World world) {
        super(type, world);
        this.setNoGravity(true); // it's a floating ball
    }

    public static DefaultAttributeContainer.Builder createAttributes() {
        return MobEntity.createMobAttributes()
                .add(EntityAttributes.GENERIC_MAX_HEALTH, 10.0)
                .add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.30)
                .add(EntityAttributes.GENERIC_FLYING_SPEED, 0.45);
    }

    public static SpawnGroup spawnGroup() {
        return SpawnGroup.CREATURE;
    }

    @Override
    protected void initDataTracker() {
        super.initDataTracker();
        this.dataTracker.startTracking(DREAD, 0f);
        this.dataTracker.startTracking(HAUNTED, false);
        this.dataTracker.startTracking(TALKING, false);
    }

    public float getDread() { return this.dataTracker.get(DREAD); }
    public boolean isHauntedVisual() { return this.dataTracker.get(HAUNTED); }
    public boolean isTalking() { return this.dataTracker.get(TALKING); }

    /** Called by the TTS bridge when audio starts playing for this bird's voice. */
    public void startTalking(int durationTicks) {
        this.talkTicksLeft = Math.max(this.talkTicksLeft, durationTicks);
        this.dataTracker.set(TALKING, true);
    }

    @Override
    public EntityData initialize(ServerWorldAccess world, LocalDifficulty difficulty,
                                 SpawnReason spawnReason, EntityData entityData,
                                 net.minecraft.nbt.NbtCompound nbt) {
        EntityData d = super.initialize(world, difficulty, spawnReason, entityData, nbt);
        // A brand-new bird inherits the shared soul-state: if the flock has seen things,
        // this one starts... uneasy. Very Verity: there is only ever ONE sunbird mind.
        var m = HauntDirector.mood();
        this.dataTracker.set(DREAD, Math.min(1f, m.dread));
        this.dataTracker.set(HAUNTED, m.haunting);
        return d;
    }

    @Override
    protected void initGoals() {
        // Gentle wander + watch; all "thinking" happens server-side in BehaviorBrain.
        // No follow goal: the bird drifts where it pleases - like a real weird god-ball.
        this.goalSelector.add(1, new LookAtEntityGoal(this, PlayerEntity.class, 8.0f));
        this.goalSelector.add(2, new WanderAroundFarGoal(this, 0.6));
        // If someone hurt it recently, it keeps its distance from players for a while.
        this.goalSelector.add(0, new FleeEntityGoal<PlayerEntity>(this, PlayerEntity.class,
                p -> HauntDirector.shouldFleeFrom(p), 6.0f, 1.1, 1.3, t -> true));
    }

    /** It's a ball - it bobs instead of walking awkwardly, and hums now and then. */
    @Override
    public void tick() {
        super.tick();
        if (!this.getWorld().isClient) {
            if (this.age % 20 == 0) {
                // sync shared mood into the visible body once per second
                var m = HauntDirector.mood();
                this.dataTracker.set(DREAD, Math.min(1f, m.dread));
                this.dataTracker.set(HAUNTED, m.haunting);
            }
            if (this.talkTicksLeft > 0) {
                this.talkTicksLeft--;
                if (this.talkTicksLeft == 0) this.dataTracker.set(TALKING, false);
            }
            if (this.age % 200 == 0 && this.getRandom().nextFloat() < 0.25f) {
                var m = HauntDirector.mood();
                // haunted birds hum at a WRONG pitch. you will notice before you know why.
                float pitch = m.haunting ? 0.45f : 1.6f;
                this.getWorld().playSound(null, this.getBlockPos(), SoundEvents.ENTITY_PARROT_AMBIENT,
                        SoundCategory.NEUTRAL, 0.7f, pitch);
            }
        }
    }

    @Override
    protected ActionResult interactMob(PlayerEntity player, Hand hand) {
        if (this.getWorld().isClient) return ActionResult.SUCCESS;
        if (player instanceof ServerPlayerEntity sp) openTalkChannel(sp);
        return ActionResult.CONSUME;
    }

    /** Right-click conversation channel: whisper-mode Q&A against the shared brain. */
    public void openTalkChannel(ServerPlayerEntity player) {
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
        if (!this.getWorld().isClient) {
            if (source.getAttacker() instanceof ServerPlayerEntity sp) {
                HauntDirector.onHurt(sp, amount);
            } else {
                HauntDirector.onHurt(null, amount);
            }
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
    public boolean handleFallDamage(float fallDistance, float damageMultiplier, DamageSource damageSource) {
        // rubber ball: never takes fall damage
        return false;
    }

    @Override
    protected void dropInventory() { /* the soul is not an item */ }

    @Override
    protected void dropEquipment(DamageSource source, int lootMultiplier, boolean vanishingEnchantmentUsed) {
        /* nothing to loot */
    }

    @Override
    public boolean canPickUpLoot() { return false; }
}
