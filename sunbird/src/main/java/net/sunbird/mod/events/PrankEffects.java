package net.sunbird.mod.events;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.passive.ChickenEntity;
import net.minecraft.item.Item;
import net.minecraft.particle.ParticleTypes;
import java.util.Random;

/**
 * The comedy toolkit: petty, harmless, Hilarious pranks the bird pulls when bored
 * (mischief meter high). Everything is reversible and grief-safe - we only touch
 * blocks in a tiny pocket around the victim and never destroy player builds.
 */
public final class PrankEffects {
    private static final Random R = new Random();

    private PrankEffects() {}

    /** Bonk: play the cow-bell "hit" sound right on top of someone. */
    public static void bonk(ServerPlayerEntity target) {
        target.getWorld().playSound(target, target.getBlockPos(),
                SoundEvents.ENTITY_COW_HURT, SoundCategory.PLAYERS, 1.0f, 2.0f);
        target.sendMessage(Text.literal("*bonk*").formatted(Formatting.WHITE), false);
    }

    /** Rain a few snowballs/bubbles of particles on the victim's head. */
    public static void rainOnHead(ServerPlayerEntity target) {
        World world = target.getWorld();
        BlockPos head = target.getBlockPos().up(2);
        for (int i = 0; i < 25; i++) {
            world.addParticle(ParticleTypes.SPLASH,
                    head.getX() + 0.5 + R.nextFloat() - 0.5f,
                    head.getY() + R.nextFloat(),
                    head.getZ() + 0.5 + R.nextFloat() - 0.5f,
                    0, -0.2, 0);
        }
        world.playSound(target, head, SoundEvents.ENTITY_GENERIC_SPLASH,
                SoundCategory.PLAYERS, 0.6f, 1.4f);
    }

    /** Turn one random nearby mob into a chicken-for-a-minute... visually: spawn a chick with hearts. */
    public static void suspiciousChicken(MinecraftServer server, ServerPlayerEntity target) {
        World world = target.getWorld();
        BlockPos at = target.getBlockPos().add(R.nextInt(7) - 3, 1, R.nextInt(7) - 3);
        ChickenEntity chick = EntityType.CHICKEN.create(world);
        if (chick != null) {
            chick.refreshPositionAndAngles(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
            chick.setVelocity(0, 0.4, 0);
            world.spawnEntity(chick);
            world.playSound(null, at.getX()+0.5, at.getY(), at.getZ()+0.5, SoundEvents.ENTITY_CHICKEN_AMBIENT,
                    SoundCategory.NEUTRAL, 1.0f, 0.6f);
        }
    }

    /** Give everyone within 40 blocks one saturation/absorption-free "weird food" message & note sound. */
    public static void imaginaryPicnic(MinecraftServer server) {
        server.execute(() -> {
            for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                p.getWorld().playSound(p, p.getBlockPos(), SoundEvents.BLOCK_NOTE_BLOCK_PLING,
                        SoundCategory.PLAYERS, 0.8f, 1.2f);
                p.sendMessage(Text.literal(net.sunbird.mod.i18n.LangService.tr(p, "sunbird.spook.smell"))
                        .formatted(Formatting.GOLD, Formatting.ITALIC), false);
            }
        });
    }

    /** Flip the pitch of the world around them briefly by spamming low-volume cat sounds. */
    public static void cursedCatSerenade(ServerPlayerEntity target) {
        for (int i = 0; i < 3; i++) {
            BlockPos catAt = target.getBlockPos().add(R.nextInt(5) - 2, 1, R.nextInt(5) - 2);
            target.getWorld().playSound(target, catAt,
                    SoundEvents.ENTITY_CAT_AMBIENT, SoundCategory.HOSTILE, 0.8f, 0.7f + R.nextFloat());
        }
    }

    /** "Accidentally" drop an item at their feet from nowhere. */
    public static void mysteryGift(ServerPlayerEntity target, net.minecraft.item.Item item) {
        ItemEntity drop = new ItemEntity(target.getWorld(),
                        target.getX(), target.getY() + 1.2, target.getZ(),
                        new net.minecraft.item.ItemStack(item));
        drop.setPickupDelay(60);
        target.getWorld().spawnEntity(drop);
        target.sendMessage(Text.literal(net.sunbird.mod.i18n.LangService.tr(target, "sunbird.spook.sky_thanks")).formatted(Formatting.AQUA), false);
    }

    public static boolean chance(double p) { return R.nextDouble() < p; }
}
