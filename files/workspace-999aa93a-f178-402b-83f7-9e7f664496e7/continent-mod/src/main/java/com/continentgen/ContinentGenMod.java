package com.continentgen;

import com.continentgen.world.WorldBorderHandler;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;
import com.continentgen.world.ContinentChunkGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main mod entry point.
 * Registers chunk generator codec and world border handler.
 */
public class ContinentGenMod implements ModInitializer {
    public static final String MOD_ID = "continentgen";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("[ContinentGen] Initializing Custom Continent Generator v1.0.0");

        // Register chunk generator codec.
        // Registries.CHUNK_GENERATOR returns raw Registry in Yarn 1.20.1,
        // so we suppress the unchecked warning.
        try {
            @SuppressWarnings("unchecked")
            Registry<net.minecraft.world.gen.chunk.ChunkGenerator> cgRegistry =
                (Registry<net.minecraft.world.gen.chunk.ChunkGenerator>)
                (Registry<?>) Registries.CHUNK_GENERATOR;

            Registry.register(
                cgRegistry,
                Identifier.of(MOD_ID, "continent"),
                ContinentChunkGenerator.CODEC
            );
            LOGGER.info("[ContinentGen] ChunkGenerator codec registered as 'continentgen:continent'");
        } catch (Exception e) {
            LOGGER.error("[ContinentGen] Failed to register ChunkGenerator codec", e);
        }

        // Register world border handler for Pac-Man teleportation
        ServerTickEvents.END_WORLD_TICK.register(new WorldBorderHandler());
        LOGGER.info("[ContinentGen] WorldBorderHandler registered!");
        LOGGER.info("[ContinentGen] Mod initialized. Use 'Custom continents' world preset.");
    }
}
