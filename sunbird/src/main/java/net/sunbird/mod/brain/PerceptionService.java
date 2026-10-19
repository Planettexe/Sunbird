package net.sunbird.mod.brain;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.sunbird.mod.SunbirdMod;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Periodically samples the world (time, weather, dimension, player activity) on a
 * background thread using snapshots - never touches mutable world state off-thread.
 * The summary becomes part of the bird's prompt so it can talk about what it "sees".
 */
public final class PerceptionService {
    private static ScheduledExecutorService executor;
    private static final AtomicReference<String> LAST_SNAPSHOT = new AtomicReference<>("calm and quiet");

    private PerceptionService() {}

    public static void start() {
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sunbird-perception");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleWithFixedDelay(PerceptionService::sample, 20, 60, TimeUnit.SECONDS);
    }

    public static void stop() {
        if (executor != null) executor.shutdownNow();
    }

    private static void sample() {
        MinecraftServer server = SunbirdMod.SERVER;
        if (server == null) return;
        // Capture immutable-ish facts on the main thread, compose off-thread.
        server.execute(() -> {
            try {
                StringBuilder sb = new StringBuilder();
                World overworld = server.getOverworld();
                long time = overworld.getTimeOfDay() % 24000L;
                String dayPart = time < 12000 ? "day" : time < 13500 ? "dusk" : time < 22500 ? "night" : "dawn";
                sb.append("It is ").append(dayPart)
                        .append(", weather: ").append(overworld.isRaining() ? "raining" : "clear")
                        .append(". Players online: ").append(server.getCurrentPlayerCount());
                int mining = 0;
                for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                    BlockPos below = p.getBlockPos().down();
                    if (p.getWorld().getBlockState(below).isAir() && p.getVelocity().y > -0.01) mining++;
                }
                if (mining > 0) sb.append(", ").append(mining).append(" player(s) seem to be digging tunnels");
                LAST_SNAPSHOT.set(sb.toString());
                BehaviorBrain.enqueueSystemNote("Observation: " + sb);
            } catch (Exception e) {
                SunbirdMod.LOGGER.debug("[Sunbird] perception failed", e);
            }
        });
    }

    public static String describeWorld(MinecraftServer server) {
        return LAST_SNAPSHOT.get();
    }
}
