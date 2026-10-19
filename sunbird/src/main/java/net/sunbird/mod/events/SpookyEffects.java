package net.sunbird.mod.events;

import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.List;
import java.util.Random;

/**
 * The horror toolkit: everything spooky the bird can physically do to a server,
 * built ONLY from vanilla 1.20.4 APIs so it works on any Fabric server without
 * resource packs. Each effect is cheap (a few packets) - no lag, all dread.
 */
public final class SpookyEffects {
    private static final Random R = new Random();

    private SpookyEffects() {}

    /** Extinguish torches/lanterns in a radius around a player -> "the lights went out". */
    public static void snuffLights(ServerPlayerEntity target, int radius) {
        World world = target.getWorld();
        BlockPos center = target.getBlockPos();
        int removed = 0;
        for (int x = -radius; x <= radius && removed < 12; x++) {
            for (int y = -4; y <= 4 && removed < 12; y++) {
                for (int z = -radius; z <= radius && removed < 12; z++) {
                    BlockPos pos = center.add(x, y, z);
                    var state = world.getBlockState(pos);
                    String name = state.getBlock().getTranslationKey();
                    if (name.contains("torch") || name.contains("lantern") || name.contains("candle")
                            || name.contains("campfire") || name.contains("lava")) {
                        // NEVER modify the block - just a break-packet particle burst for atmosphere.
                        // Grief-safe: players keep their lights, they only *flicker* in the mind.
                        int hash = Registries.BLOCK.getId(state.getBlock()).hashCode();
                        world.syncWorldEvent(2001, pos, hash);
                        removed++;
                    }
                }
            }
        }
    }

    /** Fake thunder roll WITHOUT a lightning entity, damage or fire - pure theatre. */
    public static void fakeThunder(MinecraftServer server, ServerPlayerEntity target) {
        World world = target.getWorld();
        BlockPos at = target.getBlockPos().add(R.nextInt(24) - 12, 8 + R.nextInt(8), R.nextInt(24) - 12);
        playAt(world, at, SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER,
                SoundCategory.WEATHER, 0.6f, 0.9f + R.nextFloat() * 0.2f);
    }

    /** Play an unsettling sound AT THE PLAYER'S EARS (positional, others don't hear it). */
    public static void whisperInEar(ServerPlayerEntity target, float pitch) {
        List<SoundEvent> creepy = List.of(
                SoundEvents.ENTITY_WARDEN_HEARTBEAT,
                SoundEvents.BLOCK_BEACON_AMBIENT,
                SoundEvents.ENTITY_ENDERMAN_STARE,
                SoundEvents.BLOCK_REDSTONE_TORCH_BURNOUT,
                SoundEvents.ENTITY_PHANTOM_SWOOP,
                SoundEvents.ITEM_CHORUS_FRUIT_TELEPORT);
        playAt(target.getWorld(), target.getBlockPos(),
                creepy.get(R.nextInt(creepy.size())), SoundCategory.PLAYERS, 0.7f, pitch);
    }

    /** Everyone within range hears one heartbeat. Classic. */
    public static void globalHeartbeat(MinecraftServer server) {
        server.execute(() -> {
            for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                playAt(p.getWorld(), p.getBlockPos(), SoundEvents.ENTITY_WARDEN_HEARTBEAT,
                        SoundCategory.PLAYERS, 1.0f, 0.8f);
            }
        });
    }

    /** Ghost particles around the player - the glow of the world seems to flicker. */
    public static void flickerGlow(ServerPlayerEntity target) {
        World world = target.getWorld();
        for (int i = 0; i < 30; i++) {
            BlockPos pos = target.getBlockPos().add(R.nextInt(10) - 5, R.nextInt(6) - 2, R.nextInt(10) - 5);
            world.addParticle(ParticleTypes.END_ROD,
                    pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                    0, 0, 0);
        }
    }

    /** Send an unsettling pair of chat messages: obfuscated big text + a quiet line under it. */
    public static void ominousTitle(ServerPlayerEntity target, String big, String small) {
        target.sendMessage(Text.literal(big).formatted(Formatting.DARK_PURPLE, Formatting.BOLD, Formatting.OBFUSCATED), false);
        target.sendMessage(Text.literal(small).formatted(Formatting.DARK_GRAY, Formatting.ITALIC), false);
    }

    /** Teleport-like blink of the bird itself (called from entity code). */
    public static boolean chance(double p) { return R.nextDouble() < p; }

    private static void playAt(World world, BlockPos pos, SoundEvent sound, SoundCategory cat, float vol, float pitch) {
        world.playSound(null, pos.getX(), pos.getY(), pos.getZ(), sound, cat, vol, pitch, 0L);
    }
}
