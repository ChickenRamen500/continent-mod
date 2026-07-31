package com.continentgen.world;

import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

/**
 * Toroidal (Pac-Man) world boundary handler.
 *
 * Yarn 1.20.1 corrections:
 * - ServerPlayerEntity: net.minecraft.server.network (NOT server.world!)
 * - EndWorldTick method name: onEndTick (NOT onEndWorldTick!)
 * - ServerPlayerEntity.getY() returns double (NOT float!)
 */
public class WorldBorderHandler implements ServerTickEvents.EndWorldTick {

    private static final int HALF_WORLD = 150_000;
    private static final int WORLD_SIZE = 300_000;
    private static final int WARNING_DISTANCE = 50;

    @Override
    public void onEndTick(ServerWorld world) {
        for (ServerPlayerEntity player : world.getPlayers()) {
            double x = player.getX();
            double z = player.getZ();
            double y = player.getY();

            // Check X boundary
            if (x >= HALF_WORLD) {
                player.requestTeleport(x - WORLD_SIZE + 1, y, z);
                applyTeleportDarkness(player);
                continue;
            } else if (x <= -HALF_WORLD) {
                player.requestTeleport(x + WORLD_SIZE - 1, y, z);
                applyTeleportDarkness(player);
                continue;
            }

            // Check Z boundary
            if (z >= HALF_WORLD) {
                player.requestTeleport(x, y, z - WORLD_SIZE + 1);
                applyTeleportDarkness(player);
                continue;
            } else if (z <= -HALF_WORLD) {
                player.requestTeleport(x, y, z + WORLD_SIZE - 1);
                applyTeleportDarkness(player);
                continue;
            }

            // Warning zone within 50 blocks of boundary
            if (x >= HALF_WORLD - WARNING_DISTANCE || x <= -HALF_WORLD + WARNING_DISTANCE ||
                z >= HALF_WORLD - WARNING_DISTANCE || z <= -HALF_WORLD + WARNING_DISTANCE) {
                player.addStatusEffect(new StatusEffectInstance(
                    StatusEffects.DARKNESS, 40, 0, false, false
                ));
            }
        }
    }

    private void applyTeleportDarkness(ServerPlayerEntity player) {
        player.addStatusEffect(new StatusEffectInstance(
            StatusEffects.DARKNESS, 40, 1, false, false
        ));
    }
}
