package com.continentgen.world;

import com.continentgen.map.MapData;
import com.continentgen.map.MapLoader;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.ChunkRegion;
import net.minecraft.world.HeightLimitView;
import net.minecraft.world.Heightmap;
import net.minecraft.world.biome.source.BiomeAccess;
import net.minecraft.world.biome.source.BiomeSource;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.ProtoChunk;
import net.minecraft.world.gen.GenerationStep;
import net.minecraft.world.gen.StructureAccessor;
import net.minecraft.world.gen.chunk.Blender;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import net.minecraft.world.gen.chunk.VerticalBlockSample;
import net.minecraft.world.gen.noise.NoiseConfig;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.logging.Logger;

/**
 * Кастомный ChunkGenerator для генерации мира из PNG карт.
 *
 * v7 (01.08.2026) — ИСПРАВЛЕНИЕ terrain carving:
 *   Исправлены два критических бага v6:
 *   1) «Гора из воды» — вода озёр была на terrainHeight (Y=195 на горе).
 *      ФИКС: вода озёр на ПЛОСКОМ фиксированном уровне (SEA_LEVEL + 3).
 *      Террейн LERP-ится к дну озера. На горе → кратерная форма озера.
 *   2) «Двойной берег» — отдельная зона берега [0.2..0.5) поверх
 *      билинейного градиента PNG маски = два перехода = двойной берег.
 *      ФИКС: зона берега УДАЛЕНА. riverNess/lakeNess используется
 *      непрерывно на всём диапазоне [0..1].
 *
 *   Принцип работы:
 *   - Озеро: terrain LERP(terrainHeight, LAKE_FLOOR, lakeNess).
 *     Вода на ПЛОСКОМ уровне LAKE_WATER_LEVEL (не terrainHeight!).
 *   - Река: русло = terrainHeight - RIVER_CARVE_DEPTH * riverNess.
 *     Вода заполняет русло до оригинального уровня terrainHeight.
 *   - Океан: без изменений (дно = terrainHeight, вода = seaLevel).
 *
 * v6 (31.07.2026) — terrain carving (БАГОВАН):
 *   Вода следовала за terrainHeight → горы из воды.
 *
 * v5 (28.07.2026):
 *   - Билинейная интерполяция маски рек/озёр вместо кругов.
 *   - Океан: плавное смешивание через oceanNess.
 */
public class ContinentChunkGenerator extends ChunkGenerator {
    private static final Logger LOGGER = Logger.getLogger("ContinentGen");

    public static final Codec<ContinentChunkGenerator> CODEC = RecordCodecBuilder.create(instance ->
        instance.group(
            BiomeSource.CODEC.fieldOf("biome_source").forGetter(ChunkGenerator::getBiomeSource),
            Codec.LONG.optionalFieldOf("seed", 0L).forGetter(gen -> gen.seed)
        ).apply(instance, ContinentChunkGenerator::new)
    );

    private static final int SURFACE_BLOCK_COUNT = 4;

    // ─── Константы карвинга ─────────────────────────────────────
    /** Максимальная глубина русла реки (блоки ниже terrainHeight). */
    private static final int RIVER_CARVE_DEPTH = 8;
    /** Уровень поверхности воды для ВСЕХ озёр (фиксированный, плоский). */
    private static final int LAKE_WATER_LEVEL = MapData.SEA_LEVEL + 3; // = 66
    /** Глубина дна озера от уровня воды (дно = LAKE_WATER_LEVEL - LAKE_DEPTH). */
    private static final int LAKE_DEPTH = 8;

    private final long seed;
    private final int worldSize;
    private final MapData mapData;

    public ContinentChunkGenerator(BiomeSource biomeSource, long seed) {
        super(biomeSource);
        this.seed = seed;
        this.mapData = MapLoader.getInstance().getMapData();
        int ws = MapData.DEFAULT_WORLD_SIZE;
        if (biomeSource instanceof ContinentBiomeSource) {
            ws = ((ContinentBiomeSource) biomeSource).getWorldSize();
        }
        this.worldSize = ws;
        LOGGER.info("[ContinentChunkGen] seed=" + seed + " worldSize=" + worldSize
            + " mapsLoaded=" + mapData.isLoaded());
    }

    public int getWorldSize() { return worldSize; }

    @Override public int getMinimumY() { return -64; }
    @Override public int getSeaLevel() { return MapData.SEA_LEVEL; }
    @Override public int getWorldHeight() { return 384; }
    @Override public void populateEntities(ChunkRegion region) {}
    @Override protected Codec<? extends ChunkGenerator> getCodec() { return CODEC; }

    @Override
    public CompletableFuture<Chunk> populateNoise(Executor executor, Blender blender,
                                                     NoiseConfig noiseConfig,
                                                     StructureAccessor structures,
                                                     Chunk chunk) {
        ChunkPos chunkPos = chunk.getPos();
        int startX = chunkPos.getStartX();
        int startZ = chunkPos.getStartZ();

        if (!mapData.isLoaded()) {
            for (int lx = 0; lx < 16; lx++)
                for (int lz = 0; lz < 16; lz++)
                    for (int y = getMinimumY(); y <= getSeaLevel(); y++) {
                        BlockState s = (y == getMinimumY()) ? Blocks.BEDROCK.getDefaultState()
                            : (y == getSeaLevel()) ? Blocks.WATER.getDefaultState()
                            : Blocks.STONE.getDefaultState();
                        chunk.setBlockState(new BlockPos(lx, y, lz), s, false);
                    }
            populateBiomesSafe(chunk);
            return CompletableFuture.completedFuture(chunk);
        }

        for (int localX = 0; localX < 16; localX++)
            for (int localZ = 0; localZ < 16; localZ++)
                generateColumn(chunk, startX + localX, startZ + localZ, localX, localZ);

        populateBiomesSafe(chunk);
        return CompletableFuture.completedFuture(chunk);
    }

    private void populateBiomesSafe(Chunk chunk) {
        BiomeSource source = getBiomeSource();
        if (chunk instanceof ProtoChunk) {
            try { ((ProtoChunk) chunk).populateBiomes(source, null); return; }
            catch (Exception e) { LOGGER.severe("[populateBiomes] " + e); }
        }
        ChunkSection[] sections = chunk.getSectionArray();
        int bottomY = chunk.getBottomY();
        for (int i = 0; i < sections.length; i++) {
            ChunkSection section = sections[i];
            if (section == null) continue;
            try {
                section.populateBiomes(source, null, chunk.getPos().getStartX(),
                    bottomY + i * 16, chunk.getPos().getStartZ());
            } catch (Exception ex) { LOGGER.severe("[populateBiomes][" + i + "] " + ex); }
        }
    }

    @Override
    public void carve(ChunkRegion chunkRegion, long seed, NoiseConfig noiseConfig,
                      BiomeAccess biomeAccess, StructureAccessor structures,
                      Chunk chunk, GenerationStep.Carver carver) {
        // No-op: предотвращаем ванильные пещеры.
    }

    @Override
    public void buildSurface(ChunkRegion region, StructureAccessor structures,
                             NoiseConfig noiseConfig, Chunk chunk) {
        // No-op: поверхность уже сгенерирована в populateNoise().
    }

    @Override
    public int getHeight(int x, int z, Heightmap.Type heightmap, HeightLimitView heightLimit,
                        NoiseConfig noiseConfig) {
        if (!mapData.isLoaded()) return getSeaLevel();
        return mapData.getHeight(x, z, worldSize);
    }

    @Override
    public VerticalBlockSample getColumnSample(int x, int z, HeightLimitView world,
                                                NoiseConfig noiseConfig) {
        return new VerticalBlockSample(world.getBottomY(), new BlockState[0]);
    }

    @Override
    public void getDebugHudText(List<String> info, NoiseConfig noiseConfig, BlockPos pos) {
        info.add("Continent Generator (v7 — fixed terrain carving)");
        info.add(mapData.isLoaded() ? "Карты: Загружены" : "Карты: НЕ НАЙДЕНЫ");
        if (mapData.isLoaded()) {
            int x = pos.getX(), z = pos.getZ();
            info.add("Высота: " + mapData.getHeight(x, z, worldSize)
                + " | Уровень моря: " + MapData.SEA_LEVEL);
            info.add("Океан: " + mapData.isOcean(x, z, worldSize)
                + " Глуб.океан: " + mapData.isDeepOcean(x, z, worldSize));
            info.add("Река: " + String.format("%.2f", mapData.getRiverNess(x, z, worldSize))
                + " Озеро: " + String.format("%.2f", mapData.getLakeNess(x, z, worldSize)));
            info.add("Климат: " + mapData.getClimateZone(x, z, worldSize));
            info.add("Континентальность: "
                + String.format("%.2f", mapData.getContinentalness(x, z, worldSize)));
        }
    }

    // ═══════════════════ Генерация колонки ═════════════════════════

    /**
     * Генерация одной колонки.
     *
     * Логика (v7 — исправленный terrain carving):
     *
     *  1. terrainHeight = getHeight() — билинейная, гладкая.
     *  2. Определить тип:
     *     - Океан (oceanNess >= 0.5): дно = terrainHeight, вода = SEA_LEVEL.
     *     - Озеро (lakeNess > 0 и lakeNess >= riverNess):
     *       TERRAIN CARVING к фиксированному уровню воды.
     *       terrain LERP(terrainHeight, LAKE_FLOOR, lakeNess).
     *       Вода на ПЛОСКОМ уровне LAKE_WATER_LEVEL (SEA_LEVEL + 3 = 66).
     *       Решает баг «гора из воды» (v6).
     *     - Река (riverNess > 0 и riverNess > lakeNess):
     *       Русло = terrainHeight - RIVER_CARVE_DEPTH * riverNess.
     *       Вода заполняет русло до terrainHeight (следует за рельефом).
     *       Без отдельной зоны берега (решает баг «двойной берег»).
     *     - Суша: поверхность = terrainHeight.
     *
     *  Ключевое отличие от v6:
     *    v6: waterSurfaceY = Math.max(terrainHeight, seaLevel)
     *        → на горе Y=195: вода на Y=195 = «гора из воды»
     *    v7: озёра → waterSurfaceY = LAKE_WATER_LEVEL (фикс. Y=66)
     *        реки → waterSurfaceY = terrainHeight (русло вырезано ниже)
     *
     *  Ключевое отличие от v5:
     *    v5: surfaceY = seaLevel - depth (дно на Y=59)
     *        → на горе Y=195: перепад 136 блоков (отвесная стена)
     *    v7: surfaceY = LERP(terrainHeight, LAKE_FLOOR, lakeNess)
     *        → плавный переход, без обрыва
     *
     *  Примеры:
     *    Гора Y=195, озеро в центре (lakeNess=1.0):
     *      surfaceY = 195 + (58-195)*1.0 = 58
     *      waterSurfaceY = 66 (плоский!)
     *      Вода: Y=59..66 (8 блоков). Берег: плавный спуск 195→58.
     *
     *    Равнина Y=80, река в центре (riverNess=1.0):
     *      surfaceY = 80 - 8 = 72
     *      waterSurfaceY = 80
     *      Вода: Y=73..80 (8 блоков). Берег: flush с рельефом.
     */
    private void generateColumn(Chunk chunk, int worldX, int worldZ, int localX, int localZ) {
        int seaLevel = MapData.SEA_LEVEL;
        int minBuildY = getMinimumY();
        int terrainHeight = mapData.getHeight(worldX, worldZ, worldSize);

        double oceanNess = mapData.getOceanNess(worldX, worldZ, worldSize);
        double riverNess = mapData.getRiverNess(worldX, worldZ, worldSize);
        double lakeNess = mapData.getLakeNess(worldX, worldZ, worldSize);

        int surfaceY;
        int waterSurfaceY;
        boolean hasWater = false;

        if (oceanNess >= 0.5) {
            // ─── ОКЕАН: плавное смешивание ───────────────────────────
            int oceanFloor = Math.max(minBuildY + 1, terrainHeight);
            double landWeight = 1.0 - oceanNess;
            surfaceY = (int) (oceanFloor * oceanNess + terrainHeight * landWeight);
            waterSurfaceY = seaLevel;
            hasWater = true;

        } else {
            // ─── СУША / РЕКИ / ОЗЁРА ──────────────────────────────
            // Непрерывная обработка через waterNess, БЕЗ отдельных зон.
            // Решает баг «двойной берег» из v6.
            double waterNess = Math.max(riverNess, lakeNess);

            if (waterNess > 0.01) {
                boolean isLake = lakeNess >= riverNess;

                if (isLake) {
                    // ═══ ОЗЕРО: terrain carving к ПЛОСКОМУ уровню воды ═══
                    // ФИКС бага «гора из воды» (v6): вода НЕ на terrainHeight!
                    // Вода озера — всегда на фиксированном уровне LAKE_WATER_LEVEL.
                    // Террейн LERP-ится вниз к дну озера.
                    //
                    // На горе (Y=195): кратерная форма, плоская вода на Y=66.
                    // На равнине (Y=80): неглубокое озеро, плоская вода на Y=66.

                    // Целевой уровень дна: LAKE_WATER_LEVEL - LAKE_DEPTH
                    int lakeFloorTarget = LAKE_WATER_LEVEL - LAKE_DEPTH; // = 58

                    // LERP: terrainHeight → lakeFloorTarget по lakeNess [0..1]
                    // lakeNess=0: без изменений, lakeNess=1: полный карвинг
                    int carvedHeight = (int) (terrainHeight
                        + (lakeFloorTarget - terrainHeight) * lakeNess);
                    carvedHeight = Math.max(minBuildY + 1, carvedHeight);

                    surfaceY = carvedHeight;
                    waterSurfaceY = LAKE_WATER_LEVEL; // ПЛОСКИЙ уровень воды!
                    // Вода существует только когда дно ниже уровня воды
                    hasWater = (surfaceY < waterSurfaceY);

                } else {
                    // ═══ РЕКА: русло вырезается в рельефе ═══
                    // Русло = понижение террейна на RIVER_CARVE_DEPTH * riverNess.
                    // Вода заполняет русло до оригинального уровня terrainHeight.
                    //
                    // На горе (Y=195): русло Y=187..195, берега flush с рельефом.
                    // На равнине (Y=80): русло Y=72..80, нормальная река.

                    int carvedHeight = Math.max(minBuildY + 1,
                        terrainHeight - (int) (RIVER_CARVE_DEPTH * riverNess));

                    surfaceY = carvedHeight;
                    waterSurfaceY = terrainHeight; // Вода до уровня рельефа
                    // Вода есть когда русло глубже нуля
                    hasWater = (surfaceY < waterSurfaceY);
                }
            } else {
                // ─── СУША: обычный рельеф ─────────────────────────────
                surfaceY = terrainHeight;
                waterSurfaceY = surfaceY;
                if (surfaceY < seaLevel) {
                    waterSurfaceY = seaLevel;
                    hasWater = true;
                }
            }
        }

        // ═══ Заполнение колонки ═══
        int topY = Math.max(surfaceY, waterSurfaceY);
        // Есть ли вода НАД поверхностью? (для выбора блока поверхности)
        boolean waterAbove = waterSurfaceY > surfaceY;

        for (int y = minBuildY; y <= topY; y++) {
            BlockPos pos = new BlockPos(localX, y, localZ);
            BlockState blockState;

            if (y > surfaceY && y <= waterSurfaceY) {
                // Вода выше поверхности/дна
                blockState = Blocks.WATER.getDefaultState();
            } else if (y == surfaceY) {
                if (waterAbove) {
                    blockState = getUnderwaterSurfaceBlock(worldX, worldZ, surfaceY);
                } else {
                    blockState = getSurfaceBlock(worldX, worldZ, surfaceY);
                }
            } else if (y > surfaceY - SURFACE_BLOCK_COUNT && y > minBuildY) {
                blockState = waterAbove ? Blocks.GRAVEL.getDefaultState() : Blocks.DIRT.getDefaultState();
            } else {
                blockState = Blocks.STONE.getDefaultState();
            }

            chunk.setBlockState(pos, blockState, false);
        }

        // Бедрок
        chunk.setBlockState(new BlockPos(localX, minBuildY, localZ),
            Blocks.BEDROCK.getDefaultState(), false);
    }

    private BlockState getSurfaceBlock(int worldX, int worldZ, int surfaceY) {
        float temp = mapData.getTemperature(worldX, worldZ, worldSize);
        if (surfaceY <= getSeaLevel() + 1) return Blocks.SAND.getDefaultState();
        if (temp < 0.15f) return Blocks.SNOW_BLOCK.getDefaultState();
        if (temp > 0.85f && surfaceY < 100) return Blocks.SAND.getDefaultState();
        return Blocks.GRASS_BLOCK.getDefaultState();
    }

    private BlockState getUnderwaterSurfaceBlock(int worldX, int worldZ, int surfaceY) {
        float temp = mapData.getTemperature(worldX, worldZ, worldSize);
        if (temp < 0.15f) return Blocks.DIRT.getDefaultState();
        return Blocks.SAND.getDefaultState();
    }
}
