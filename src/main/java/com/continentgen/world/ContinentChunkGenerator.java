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
 * v6 (31.07.2026) — TERRAIN CARVING:
 *   - Реки и озёра: terrain carving вместо фиксированного SEA_LEVEL.
 *     Вода следует за рельефом: riverBed = terrainHeight - CARVE_DEPTH * ness,
 *     waterSurface = terrainHeight (не seaLevel!).
 *     Решает проблему «перепад 128 блоков на горных берегах».
 *   - Берег: плавный склон от terrainHeight к carvedHeight,
 *     вода заполняет склон естественным образом.
 *   - Океан: без изменений (дно = terrainHeight, вода = seaLevel).
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
    /** Максимальная глубина русла реки (блоки ниже terrainHeight). */
    private static final int RIVER_CARVE_DEPTH = 8;
    /** Максимальная глубина дна озера (блоки ниже terrainHeight). */
    private static final int LAKE_CARVE_DEPTH = 5;

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
        info.add("Continent Generator (v6 — terrain carving)");
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
     * Логика (v6 — terrain carving):
     *  1. terrainHeight = getHeight() — билинейная, гладкая.
     *  2. Определить тип:
     *     - Океан: пол = terrainHeight, вода = SEA_LEVEL (без изменений).
     *     - Река (riverNess > 0.5): TERRAIN CARVING.
     *       riverBed = terrainHeight - RIVER_CARVE_DEPTH * centerFactor.
     *       waterSurface = terrainHeight (вода следует за рельефом, НЕ seaLevel!).
     *       Вода заполняет русло от riverBed до terrainHeight.
     *     - Озеро (lakeNess > 0.5): аналогично реке, но мельче.
     *     - Берег реки/озёра (0.2 < ness < 0.5): плавный склон,
     *       terrainHeight → carvedHeight. Вода заполняет склон.
     *     - Суша: поверхность = terrainHeight.
     *
     *  Пример: гора на Y=150, река в центре (riverNess=1.0):
     *    riverBed = 150 - 8 = 142
     *    waterSurface = 150
     *    Вода заполняет Y=143..150 (8 блоков глубины).
     *    Берег: плавный склон от 150 до 142.
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
            // landHeight на суще, oceanFloor в океане
            int oceanFloor = Math.max(minBuildY + 1, terrainHeight);
            double landWeight = 1.0 - oceanNess;
            surfaceY = (int) (oceanFloor * oceanNess + terrainHeight * landWeight);
            waterSurfaceY = seaLevel;
            hasWater = true;
        } else if (riverNess >= 0.5) {
            // ─── РУСЛО РЕКИ: TERRAIN CARVING ────────────────────
            // Русло = понижение рельефа. Вода следует за высотой.
            // НЕ используется фиксированный SEA_LEVEL!
            double centerFactor = (riverNess - 0.5) / 0.5; // 0 на краю, 1 в центре
            int carveDepth = (int)(RIVER_CARVE_DEPTH * centerFactor);
            int carvedHeight = Math.max(minBuildY + 1, terrainHeight - carveDepth);
            surfaceY = carvedHeight;                              // дно русла
            waterSurfaceY = Math.max(terrainHeight, seaLevel);     // вода = уровень рельефа
            hasWater = true;
        } else if (lakeNess >= 0.5) {
            // ─── РУСЛО ОЗЕРА: TERRAIN CARVING ──────────────────
            double centerFactor = (lakeNess - 0.5) / 0.5;
            int carveDepth = (int)(LAKE_CARVE_DEPTH * centerFactor);
            int carvedHeight = Math.max(minBuildY + 1, terrainHeight - carveDepth);
            surfaceY = carvedHeight;
            waterSurfaceY = Math.max(terrainHeight, seaLevel);
            hasWater = true;
        } else if (riverNess >= 0.2 || lakeNess >= 0.2) {
            // ─── БЕРЕГ РЕКИ/ОЗЕРА: плавный склон ──────────────
            // Склон от terrainHeight к carvedHeight.
            // Вода заполняет склон (естественный переход).
            double bankNess = Math.max(riverNess, lakeNess);
            boolean isLake = lakeNess > riverNess;
            int maxCarve = isLake ? LAKE_CARVE_DEPTH : RIVER_CARVE_DEPTH;
            // bankNess в диапазоне [0.2, 0.5) → blendFactor [0.0, 1.0)
            double blendFactor = (bankNess - 0.2) / 0.3;
            int carvedHeight = Math.max(minBuildY + 1, terrainHeight - maxCarve);
            surfaceY = (int)(terrainHeight * (1.0 - blendFactor) + carvedHeight * blendFactor);
            waterSurfaceY = Math.max(terrainHeight, seaLevel);
            hasWater = true;
        } else {
            // ─── СУША: обычный рельеф ─────────────────────────────
            surfaceY = terrainHeight;
            waterSurfaceY = surfaceY;
            if (surfaceY < seaLevel) {
                waterSurfaceY = seaLevel;
                hasWater = true;
            }
        }

        // Заполнение колонки
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
