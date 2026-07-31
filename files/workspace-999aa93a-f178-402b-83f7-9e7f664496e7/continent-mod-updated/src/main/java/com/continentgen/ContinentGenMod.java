package com.continentgen;

import com.continentgen.world.ContinentBiomeSource;
import com.continentgen.world.ContinentChunkGenerator;
import com.continentgen.world.WorldBorderHandler;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main mod entry point.
 * Registers BOTH codecs (BiomeSource + ChunkGenerator) and the world border handler.
 *
 * Both codecs are registered under the same id "continentgen:continent" — the
 * game distinguishes them by which registry they are in (BIOME_SOURCE vs
 * CHUNK_GENERATOR).
 */
public class ContinentGenMod implements ModInitializer {
    public static final String MOD_ID = "continentgen";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("[ContinentGen] Initializing Custom Continent Generator v1.0.0");

        // Register BiomeSource codec in Registries.BIOME_SOURCE (raw type)
        @SuppressWarnings({"unchecked", "rawtypes"})
        Registry bsRaw = Registries.BIOME_SOURCE;
        Registry.register(bsRaw, Identifier.of(MOD_ID, "continent"), ContinentBiomeSource.CODEC);
        LOGGER.info("[ContinentGen] BiomeSource codec registered as 'continentgen:continent'");

        // Register ChunkGenerator codec in Registries.CHUNK_GENERATOR (raw type)
        @SuppressWarnings({"unchecked", "rawtypes"})
        Registry cgRaw = Registries.CHUNK_GENERATOR;
        Registry.register(cgRaw, Identifier.of(MOD_ID, "continent"), ContinentChunkGenerator.CODEC);
        LOGGER.info("[ContinentGen] ChunkGenerator codec registered as 'continentgen:continent'");

        // Register world border handler for Pac-Man teleportation
        ServerTickEvents.END_WORLD_TICK.register(new WorldBorderHandler());
        LOGGER.info("[ContinentGen] WorldBorderHandler registered!");
        LOGGER.info("[ContinentGen] Mod initialized. Use 'Continents' world preset.");
    }
}
