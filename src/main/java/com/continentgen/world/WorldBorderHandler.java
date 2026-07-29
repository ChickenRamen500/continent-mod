package com.continentgen.world;

import com.continentgen.map.MapData;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

/**
 * Toroidal (Pac-Man) world boundary handler.
 *
 * ─────────────────────────────────────────────────────────────────────────
 * WORLD SIZE — READ FROM THE ACTIVE WORLD'S CHUNK GENERATOR
 * ─────────────────────────────────────────────────────────────────────────
 * The world size is read from the world's ChunkGenerator (cast to
 * ContinentChunkGenerator if applicable) on EVERY tick. This means the
 * teleport boundary automatically scales with the chosen size:
 *   300k world → teleport at ±150000
 *   30k  world → teleport at ±15000
 *   3k   world → teleport at ±1500
 *   ...any size entered in the Customize screen...
 *
 * The warning distance (darkness) stays at 50 blocks regardless of world
 * size, per the user's request.
 * ─────────────────────────────────────────────────────────────────────────
 */
public class WorldBorderHandler implements ServerTickEvents.EndWorldTick {

    private static final int WARNING_DISTANCE = 50;
    /** Fallback if the world's ChunkGenerator is not our type. */
    private static final int FALLBACK_WORLD_SIZE = MapData.DEFAULT_WORLD_SIZE;

    @Override
    public void onEndTick(ServerWorld world) {
        // Read the active world size from the world's ChunkGenerator
        int worldSize = FALLBACK_WORLD_SIZE;
        try {
            ChunkGenerator gen = world.getChunkManager().getChunkGenerator();
            if (gen instanceof ContinentChunkGenerator) {
                worldSize = ((ContinentChunkGenerator) gen).getWorldSize();
            }
        } catch (Throwable t) {
            // ignore — use fallback
        }
        int halfWorld = worldSize / 2;

        for (ServerPlayerEntity player : world.getPlayers()) {
            double x = player.getX();
            double z = player.getZ();
            double y = player.getY();

            // Check X boundary (toroidal wrap)
            if (x >= halfWorld) {
                player.requestTeleport(x - worldSize + 1, y, z);
                applyTeleportDarkness(player);
                continue;
            } else if (x <= -halfWorld) {
                player.requestTeleport(x + worldSize - 1, y, z);
                applyTeleportDarkness(player);
                continue;
            }

            // Check Z boundary (toroidal wrap)
            if (z >= halfWorld) {
                player.requestTeleport(x, y, z - worldSize + 1);
                applyTeleportDarkness(player);
                continue;
            } else if (z <= -halfWorld) {
                player.requestTeleport(x, y, z + worldSize - 1);
                applyTeleportDarkness(player);
                continue;
            }

            // Warning zone: darkness within 50 blocks of boundary
            if (x >= halfWorld - WARNING_DISTANCE || x <= -halfWorld + WARNING_DISTANCE ||
                z >= halfWorld - WARNING_DISTANCE || z <= -halfWorld + WARNING_DISTANCE) {
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
