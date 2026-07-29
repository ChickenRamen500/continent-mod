package com.continentgen.map;

/**
 * Holds biome parameter definitions for MC 1.20.1 multi-noise biome selection.
 * Each biome has target values for Temperature, Humidity, Continentalness,
 * Erosion, Weirdness, and Depth.
 *
 * Parameter ranges (vanilla MC 1.20.1):
 *   T: -1.0 to +1.0 (ice to desert heat)
 *   H: -1.0 to +1.0 (dry to wet)
 *   C: -1.2 to +1.0 (deep ocean to continent center)
 *      Ocean: -1.2 to -0.15, Coast: -0.15 to +0.1, Inland: +0.1 to +1.0
 *   E: -1.0 to +1.0 (sharp peaks/ridges to flat plains)
 *   W: -1.0 to +1.0 (biome variation/rarity)
 *   D: -1.0 to +1.0 (depth parameter, based on Y)
 */
public class BiomeParameters {

    public static class BiomeEntry {
        public final String biomeId;       // e.g. "minecraft:ocean"
        public final double t, h, c, e, w;
        public final double minD, maxD;    // depth range (0=surface, negative=deep underground)
        public final int weight;           // priority (lower = checked first)

        public BiomeEntry(String biomeId, double t, double h, double c, double e, double w,
                          double minD, double maxD, int weight) {
            this.biomeId = biomeId;
            this.t = t; this.h = h; this.c = c; this.e = e; this.w = w;
            this.minD = minD; this.maxD = maxD; this.weight = weight;
        }

        /** Squared Euclidean distance to given parameter point. */
        public double distanceSquared(double pt, double ph, double pc, double pe, double pw) {
            double dt = pt - t, dh = ph - h, dc = pc - c, de = pe - e, dw = pw - w;
            return dt*dt + dh*dh + dc*dc + de*de + dw*dw;
        }
    }

    // ─── Complete biome parameter table ───────────────────────────────
    // Sources: MC 1.20.1 wiki, Gemini biome generation analysis
    // C values: -1.2=deep ocean, -0.5=mid ocean, -0.2=shallow ocean/coast,
    //           0.0=near coast, 0.3=inland, 0.6=mid continent, 1.0=deep inland
    // E values: -1.0=peaks/ridges, 0.0=normal, +1.0=flat plains

    public static final BiomeEntry[] BIOMES = {
        // ── WATER BIOMES (checked first, weight 0-10) ──
        new BiomeEntry("deep_ocean",      -0.3,  0.0, -1.2,  0.0,  0.0, -0.5, 0.5, 0),
        new BiomeEntry("ocean",            0.0,  0.0, -0.5,  0.0,  0.0, -0.5, 0.5, 1),
        new BiomeEntry("cold_ocean",       -0.5,  0.0, -0.5,  0.0,  0.0, -0.5, 0.5, 2),
        new BiomeEntry("frozen_ocean",     -0.8,  0.0, -0.5,  0.0,  0.0, -0.5, 0.5, 3),
        new BiomeEntry("warm_ocean",        0.5,  0.0, -0.5,  0.0,  0.0, -0.5, 0.5, 4),
        new BiomeEntry("lukewarm_ocean",    0.3,  0.0, -0.5,  0.0,  0.0, -0.5, 0.5, 5),
        new BiomeEntry("river",             0.0,  0.3,  0.0,  0.0,  0.0, -0.5, 0.5, 6),
        new BiomeEntry("frozen_river",      -0.7,  0.3,  0.0,  0.0,  0.0, -0.5, 0.5, 7),

        // ── BEACH / COAST (weight 10-19) ──
        new BiomeEntry("beach",             0.2,  0.0, -0.1,  0.0,  0.0, -0.5, 0.5, 10),
        new BiomeEntry("snowy_beach",      -0.5,  0.0, -0.1,  0.0,  0.0, -0.5, 0.5, 11),
        new BiomeEntry("stony_shore",       0.0,  0.0, -0.1,  0.0,  0.0, -0.5, 0.5, 12),

        // ── MOUNTAINS / PEAKS (weight 20-29) ──
        new BiomeEntry("jagged_peaks",     -0.7,  0.9,  0.6, -1.0,  0.0, -0.5, 0.5, 20),
        new BiomeEntry("frozen_peaks",     -0.7,  0.9,  0.6, -1.0,  0.0, -0.5, 0.5, 21),
        new BiomeEntry("stony_peaks",       1.0,  0.3,  0.6, -1.0,  0.0, -0.5, 0.5, 22),
        new BiomeEntry("snowy_slopes",     -0.3,  0.8,  0.3, -0.5,  0.0, -0.5, 0.5, 23),
        new BiomeEntry("meadow",            0.5,  0.8,  0.3,  0.0,  0.0, -0.5, 0.5, 24),

        // ── ARCTIC / COLD (weight 30-39) ──
        new BiomeEntry("snowy_plains",     -0.5,  0.0,  0.3,  0.5,  0.0, -0.5, 0.5, 30),
        new BiomeEntry("ice_spikes",       -0.5,  0.0,  0.3,  0.5,  1.0, -0.5, 0.5, 31),
        new BiomeEntry("snowy_taiga",      -0.3,  0.4,  0.3,  0.3,  0.0, -0.5, 0.5, 32),
        new BiomeEntry("taiga",            -0.2,  0.3,  0.3,  0.0,  0.0, -0.5, 0.5, 33),
        new BiomeEntry("old_growth_spruce_taiga", -0.3, 0.8,  0.3,  0.3, 0.0, -0.5, 0.5, 34),
        new BiomeEntry("old_growth_pine_taiga",   -0.3,0.8,  0.3,  0.3, 0.0, -0.5, 0.5, 35),
        new BiomeEntry("grove",            -0.2,  0.8,  0.3, -0.3,  0.0, -0.5, 0.5, 36),

        // ── TEMPERATE (weight 40-49) ──
        new BiomeEntry("plains",            0.3,  0.0,  0.3,  0.5,  0.0, -0.5, 0.5, 40),
        new BiomeEntry("sunflower_plains",  0.3,  0.0,  0.3,  0.5,  1.0, -0.5, 0.5, 41),
        new BiomeEntry("forest",           0.4,  0.6,  0.3,  0.0,  0.0, -0.5, 0.5, 42),
        new BiomeEntry("flower_forest",    0.4,  0.6,  0.3,  0.0,  1.0, -0.5, 0.5, 43),
        new BiomeEntry("birch_forest",      0.6,  0.6,  0.3,  0.0,  0.0, -0.5, 0.5, 44),
        new BiomeEntry("dark_forest",       0.7,  0.8,  0.3,  0.0,  0.0, -0.5, 0.5, 45),
        new BiomeEntry("swamp",             0.8,  0.9, -0.1,  0.0,  0.0, -0.5, 0.5, 46),
        new BiomeEntry("mushroom_fields",   0.9,  1.0, -1.0,  0.0,  1.0, -0.5, 0.5, 47),

        // ── WARM / SAVANNA (weight 50-59) ──
        new BiomeEntry("savanna",          0.8, -0.3,  0.3,  0.3,  0.0, -0.5, 0.5, 50),
        new BiomeEntry("savanna_plateau",  0.8, -0.3,  0.6,  0.5,  0.0, -0.5, 0.5, 51),
        new BiomeEntry("windswept_savanna", 1.0, -0.3,  0.3, -0.5,  0.0, -0.5, 0.5, 52),

        // ── HOT / DRY (weight 60-69) ──
        new BiomeEntry("desert",            1.0, -0.5,  0.3,  0.8,  0.0, -0.5, 0.5, 60),
        new BiomeEntry("badlands",          1.0, -0.5,  0.6, -0.5,  0.0, -0.5, 0.5, 61),
        new BiomeEntry("wooded_badlands",    1.0, -0.3,  0.6, -0.5,  0.0, -0.5, 0.5, 62),

        // ── JUNGLE (weight 70-79) ──
        new BiomeEntry("jungle",            0.9,  0.9,  0.3,  0.0,  0.0, -0.5, 0.5, 70),
        new BiomeEntry("sparse_jungle",      0.9,  0.9,  0.3,  0.0,  1.0, -0.5, 0.5, 71),
        new BiomeEntry("bamboo_jungle",     0.9,  0.9,  0.3,  0.0,  0.5, -0.5, 0.5, 72),
        new BiomeEntry("mangrove_swamp",    0.8,  0.9, -0.1,  0.0,  0.5, -0.5, 0.5, 73),

        // ── WINDSWEPT HILLS (weight 80-89) ──
        new BiomeEntry("windswept_hills",   0.2,  0.0,  0.3, -0.5,  0.0, -0.5, 0.5, 80),
        new BiomeEntry("windswept_gravelly_hills", 0.2, 0.0, 0.3, -0.5, 0.0, -0.5, 0.5, 81),
        new BiomeEntry("windswept_forest",  0.2,  0.3,  0.3, -0.5,  0.0, -0.5, 0.5, 82),

        // ── CHERRY GROVE (weight 90-99) ──
        new BiomeEntry("cherry_grove",      0.5,  0.8,  0.3,  0.0,  1.0, -0.5, 0.5, 90),
    };

    /**
     * Find the best matching biome for given parameters using nearest-point matching.
     * Filters by depth range first, then picks smallest Euclidean distance.
     * Returns the biome's resource location string (e.g., "minecraft:ocean").
     */
    public static String findBestBiome(double t, double h, double c, double e, double w, double d) {
        BiomeEntry best = null;
        double bestDist = Double.MAX_VALUE;

        for (BiomeEntry entry : BIOMES) {
            // Filter by depth range
            if (d < entry.minD || d > entry.maxD) continue;

            double dist = entry.distanceSquared(t, h, c, e, w);
            if (dist < bestDist) {
                bestDist = dist;
                best = entry;
            }
        }

        return best != null ? best.biomeId : "minecraft:plains";
    }
}
