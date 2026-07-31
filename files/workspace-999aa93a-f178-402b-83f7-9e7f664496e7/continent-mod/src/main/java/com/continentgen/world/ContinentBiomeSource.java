package com.continentgen.world;

import com.continentgen.map.ClimateZone;
import com.continentgen.map.MapData;
import com.continentgen.map.MapLoader;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.Identifier;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.source.BiomeSource;
import net.minecraft.world.biome.source.util.MultiNoiseUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Custom BiomeSource that selects biomes based on the 7 PNG maps.
 *
 * Yarn 1.20.1 specifics:
 * - BiomeSource has NO-ARG constructor (no Stream param)
 * - Must implement biomeStream() — abstract method
 * - Must implement getCodec()
 * - getBiome() is also abstract
 * - Use @SuppressWarnings for raw RegistryWrapper.Impl cast
 */
public class ContinentBiomeSource extends BiomeSource {

    public static final Codec<ContinentBiomeSource> CODEC = RecordCodecBuilder.create(instance ->
        instance.group(
            Codec.LONG.fieldOf("seed").forGetter(source -> source.seed)
        ).apply(instance, ContinentBiomeSource::new)
    );

    private static final RegistryKey<Biome>[][] BIOME_MATRIX = buildBiomeMatrix();
    private static final RegistryKey<Biome>[] UNIQUE_BIOME_KEYS = collectUniqueBiomes();

    private final long seed;
    private final MapData mapData;
    private final List<RegistryEntry<Biome>> biomeEntries;

    // Pre-resolved biome lookup map
    private final java.util.Map<RegistryKey<Biome>, RegistryEntry<Biome>> biomeLookupMap;

    public ContinentBiomeSource(long seed) {
        // BiomeSource has no-arg constructor in Yarn 1.20.1
        this.seed = seed;
        this.mapData = MapLoader.getInstance().getMapData();
        this.biomeLookupMap = new java.util.HashMap<>();
        this.biomeEntries = new ArrayList<>();

        // Resolve all biome entries at construction
        for (RegistryKey<Biome> key : UNIQUE_BIOME_KEYS) {
            RegistryEntry<Biome> entry = BuiltinRegistriesAccess.getBiomeEntry(key);
            if (entry != null) {
                biomeLookupMap.put(key, entry);
                biomeEntries.add(entry);
            }
        }

        // Add fallback plains
        RegistryKey<Biome> plainsKey = RegistryKey.of(RegistryKeys.BIOME, Identifier.of("minecraft", "plains"));
        RegistryEntry<Biome> plains = BuiltinRegistriesAccess.getBiomeEntry(plainsKey);
        if (plains != null && !biomeEntries.contains(plains)) {
            biomeEntries.add(plains);
        }
    }

    @Override
    protected Codec<? extends BiomeSource> getCodec() {
        return CODEC;
    }

    /**
     * Abstract method in Yarn 1.20.1 BiomeSource.
     * Returns a stream of all biomes this source can provide.
     */
    @Override
    protected Stream<RegistryEntry<Biome>> biomeStream() {
        return biomeEntries.stream();
    }

    @Override
    public RegistryEntry<Biome> getBiome(int x, int y, int z, MultiNoiseUtil.MultiNoiseSampler noise) {
        if (mapData.isLoaded()) {
            ClimateZone zone = mapData.getClimateZone(x, z);
            int humidity = mapData.getHumidity(x, z);
            int height = mapData.getHeight(x, z);

            float altTemp = zone.getTemperature();
            if (height > 80) {
                altTemp -= (height - 80) * 0.001f;
                altTemp = Math.max(0.0f, altTemp);
            }

            int tempIdx = temperatureToIndex(altTemp);
            int humIdx = humidityToIndex(humidity);

            RegistryKey<Biome> biomeKey = BIOME_MATRIX[tempIdx][humIdx];
            if (biomeKey != null) {
                RegistryEntry<Biome> entry = biomeLookupMap.get(biomeKey);
                if (entry != null) {
                    return entry;
                }
            }
        }

        // Fallback: plains
        RegistryKey<Biome> plainsKey = RegistryKey.of(RegistryKeys.BIOME, Identifier.of("minecraft", "plains"));
        return biomeLookupMap.getOrDefault(plainsKey, biomeEntries.get(0));
    }

    // ======================== Helpers ========================

    private static int temperatureToIndex(float temp) {
        if (temp < 0.15f) return 0;
        if (temp < 0.35f) return 1;
        if (temp < 0.60f) return 2;
        if (temp < 0.80f) return 3;
        if (temp < 0.95f) return 4;
        return 5;
    }

    private static int humidityToIndex(int humidity) {
        if (humidity < 85) return 0;
        if (humidity < 170) return 1;
        return 2;
    }

    private static RegistryKey<Biome>[][] buildBiomeMatrix() {
        @SuppressWarnings("unchecked")
        RegistryKey<Biome>[][] matrix = new RegistryKey[6][3];

        matrix[0][0] = bikey("snowy_plains");
        matrix[0][1] = bikey("snowy_taiga");
        matrix[0][2] = bikey("ice_spikes");

        matrix[1][0] = bikey("snowy_plains");
        matrix[1][1] = bikey("snowy_taiga");
        matrix[1][2] = bikey("taiga");

        matrix[2][0] = bikey("plains");
        matrix[2][1] = bikey("forest");
        matrix[2][2] = bikey("birch_forest");

        matrix[3][0] = bikey("savanna");
        matrix[3][1] = bikey("forest");
        matrix[3][2] = bikey("jungle");

        matrix[4][0] = bikey("savanna");
        matrix[4][1] = bikey("jungle");
        matrix[4][2] = bikey("sparse_jungle");

        matrix[5][0] = bikey("desert");
        matrix[5][1] = bikey("savanna");
        matrix[5][2] = bikey("jungle");

        return matrix;
    }

    @SuppressWarnings("unchecked")
    private static RegistryKey<Biome>[] collectUniqueBiomes() {
        java.util.Set<RegistryKey<Biome>> set = new java.util.HashSet<>();
        for (RegistryKey<Biome>[] row : buildBiomeMatrix()) {
            for (RegistryKey<Biome> key : row) {
                if (key != null) set.add(key);
            }
        }
        return set.toArray(new RegistryKey[0]);
    }

    private static RegistryKey<Biome> bikey(String path) {
        return RegistryKey.of(RegistryKeys.BIOME, Identifier.of("minecraft", path));
    }
}
