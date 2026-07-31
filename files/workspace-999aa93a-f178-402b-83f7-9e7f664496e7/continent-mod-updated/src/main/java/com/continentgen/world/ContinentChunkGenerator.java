package com.continentgen.world;

import com.continentgen.map.MapData;
import com.continentgen.map.MapLoader;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.ChunkRegion;
import net.minecraft.world.HeightLimitView;
import net.minecraft.world.Heightmap;
import net.minecraft.world.biome.Biome;
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
 * Custom ChunkGenerator for terrain from 7 PNG maps.
 *
 * ─────────────────────────────────────────────────────────────────────────
 * WORLD SIZE — PER-INSTANCE (NOT SHARED):
 *   Each ContinentChunkGenerator holds its OWN worldSize field (read from
 *   the BiomeSource on construction). MapData does NOT store worldSize — it
 *   is passed as a parameter to every query method.
 *
 * Erosion noise scale adapts to world size:
 *   300k → 0.01, 30k → 0.1, 3k → 1.0
 *
 * COAST BLENDING (FIXES "sharp cliffs at land/water boundary"):
 *   Previously, generateColumn() classified each column as either OCEAN
 *   or LAND based on a hard nearest-neighbour threshold, then assigned
 *   surfaceY = oceanFloor OR landHeight with no transition. At a coastline
 *   this produced a sudden ~50-block vertical cliff (ocean floor ~30 vs
 *   land surface ~80).
 *
 *   FIX: now we compute BOTH the land height and the ocean floor using
 *   bilinear interpolation, then blend them with a smooth "oceanNess"
 *   factor (0 = land, 1 = ocean) computed by bilinear-interpolating the
 *   binary ocean mask. The blend:
 *       surfaceY = oceanFloor + (landHeight - oceanFloor) * (1 - oceanNess)
 *   produces a smooth beach/cliff transition over the span of one pixel.
 *
 *   A small "beach cap" is applied: if 0.05 ≤ oceanNess ≤ 0.4 (i.e.
 *   near-coast on the land side) the surface is forced to sea level ± 2
 *   to produce a natural beach.
 * ─────────────────────────────────────────────────────────────────────────
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

    private final long seed;
    /** Per-instance world size — read from the BiomeSource, never shared. */
    private final int worldSize;
    private final MapData mapData;

    private final SimplexNoise erosionNoiseX;
    private final SimplexNoise erosionNoiseZ;
    private final SimplexNoise erosionNoiseY;

    /** Erosion noise scale — adapts to world size. */
    private final double noiseScale;

    public ContinentChunkGenerator(BiomeSource biomeSource, long seed) {
        super(biomeSource);
        this.seed = seed;
        this.mapData = MapLoader.getInstance().getMapData();

        // Determine world size from BiomeSource
        int ws = MapData.DEFAULT_WORLD_SIZE;
        if (biomeSource instanceof ContinentBiomeSource) {
            ws = ((ContinentBiomeSource) biomeSource).getWorldSize();
        }
        this.worldSize = ws;
        this.noiseScale = 3000.0 / worldSize;  // 300k→0.01, 30k→0.1, 3k→1.0

        LOGGER.info("[ContinentChunkGen] Constructor: seed=" + seed
            + " worldSize=" + worldSize + " noiseScale=" + noiseScale
            + " mapsLoaded=" + mapData.isLoaded());

        Random random = Random.create(seed);
        this.erosionNoiseX = new SimplexNoise(random);
        this.erosionNoiseZ = new SimplexNoise(random);
        this.erosionNoiseY = new SimplexNoise(random);
    }

    /** Public accessor — used by WorldBorderHandler and the Customize screen. */
    public int getWorldSize() {
        return worldSize;
    }

    // ======================== Abstract Methods ========================

    @Override
    public int getMinimumY() { return -64; }

    @Override
    public int getSeaLevel() { return 63; }

    @Override
    public int getWorldHeight() { return 384; }

    @Override
    public void populateEntities(ChunkRegion region) {
    }

    @Override
    protected Codec<? extends ChunkGenerator> getCodec() {
        return CODEC;
    }

    @Override
    public CompletableFuture<Chunk> populateNoise(Executor executor, Blender blender,
                                                     NoiseConfig noiseConfig,
                                                     StructureAccessor structures,
                                                     Chunk chunk) {
        ChunkPos chunkPos = chunk.getPos();
        int startX = chunkPos.getStartX();
        int startZ = chunkPos.getStartZ();

        if (!mapData.isLoaded()) {
            for (int lx = 0; lx < 16; lx++) {
                for (int lz = 0; lz < 16; lz++) {
                    for (int y = getMinimumY(); y <= getSeaLevel(); y++) {
                        BlockState s = (y == getMinimumY()) ? Blocks.BEDROCK.getDefaultState()
                            : (y == getSeaLevel()) ? Blocks.WATER.getDefaultState()
                            : Blocks.STONE.getDefaultState();
                        chunk.setBlockState(new BlockPos(lx, y, lz), s, false);
                    }
                }
            }
            populateBiomesSafe(chunk);
            return CompletableFuture.completedFuture(chunk);
        }

        // === Phase 1: Generate terrain columns ===
        for (int localX = 0; localX < 16; localX++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                int worldX = startX + localX;
                int worldZ = startZ + localZ;
                generateColumn(chunk, worldX, worldZ, localX, localZ);
            }
        }

        populateBiomesSafe(chunk);
        return CompletableFuture.completedFuture(chunk);
    }

    private void populateBiomesSafe(Chunk chunk) {
        BiomeSource source = getBiomeSource();
        ChunkSection[] sections = chunk.getSectionArray();
        int bottomY = chunk.getBottomY();

        if (chunk instanceof ProtoChunk) {
            try {
                ProtoChunk protoChunk = (ProtoChunk) chunk;
                protoChunk.populateBiomes(source, null);
                return;
            } catch (Exception e) {
                LOGGER.severe("[populateBiomes] ProtoChunk.populateBiomes() threw: " + e);
            }
        }

        // Fallback: section-by-section
        for (int i = 0; i < sections.length; i++) {
            ChunkSection section = sections[i];
            if (section == null) continue;
            int sectionWorldY = bottomY + i * 16;
            try {
                section.populateBiomes(source, null, chunk.getPos().getStartX(),
                    sectionWorldY, chunk.getPos().getStartZ());
            } catch (Exception ex) {
                LOGGER.severe("[populateBiomes] section[" + i + "] y=" + sectionWorldY
                    + " failed: " + ex);
            }
        }
    }

    @Override
    public void carve(ChunkRegion chunkRegion, long seed, NoiseConfig noiseConfig,
                      BiomeAccess biomeAccess, StructureAccessor structures,
                      Chunk chunk, GenerationStep.Carver carver) {
    }

    @Override
    public void buildSurface(ChunkRegion region, StructureAccessor structures,
                             NoiseConfig noiseConfig, Chunk chunk) {
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
        info.add("Continent Generator");
        info.add(mapData.isLoaded() ? "Maps: Loaded" : "Maps: NOT FOUND");
        if (mapData.isLoaded()) {
            int x = pos.getX();
            int z = pos.getZ();
            double oceanNess = mapData.getOceanNess(x, z, worldSize);
            int depthBilin = mapData.getSeaOceanDepthBilinear(x, z, worldSize);
            info.add("WorldSize: " + worldSize
                + " (1px=" + (int) mapData.getBlocksPerPixel(worldSize) + " blocks)");
            info.add("Height: " + mapData.getHeight(x, z, worldSize));
            info.add("Climate: " + mapData.getClimateZone(x, z, worldSize));
            info.add("OceanNess: " + String.format("%.2f", oceanNess)
                + " (depthNN=" + mapData.getSeaOceanDepth(x, z, worldSize)
                + ", depthBilin=" + depthBilin + ")");
            info.add("Ocean: " + mapData.isOcean(x, z, worldSize));
            info.add("River: " + mapData.isRiver(x, z, worldSize));
            info.add("Lake: " + mapData.isLake(x, z, worldSize));
            info.add("Erosion: " + mapData.getErosion(x, z, worldSize));
            info.add("Humidity: " + mapData.getHumidity(x, z, worldSize));
            info.add("Temp: " + String.format("%.2f", mapData.getTemperature(x, z, worldSize)));
        }
    }

    // ======================== Column Generation ========================

    /**
     * Generate a single terrain column at (worldX, worldZ).
     *
     * Coast blending:
     *   - Compute oceanNess ∈ [0, 1] (bilinear interpolation of ocean mask)
     *   - Compute landHeight (bilinear interpolation of height.png)
     *   - Compute oceanFloor (SEA_LEVEL minus bilinear depth factor)
     *   - Blend: surfaceY = oceanFloor + (landHeight - oceanFloor) * (1 - oceanNess)
     *   - Beach cap: near-coast land is clamped to sea level ± 2
     *
     * Rivers and lakes still snap to SEA_LEVEL (their masks are already
     * thin lines, so blending isn't useful — and they need to be visible
     * as water features).
     */
    private int generateColumn(Chunk chunk, int worldX, int worldZ, int localX, int localZ) {
        boolean isWater = false;
        int surfaceY;

        // River / lake — thin water features that snap to sea level.
        if (mapData.isRiver(worldX, worldZ, worldSize)) {
            isWater = true;
            surfaceY = MapData.SEA_LEVEL;
        } else if (mapData.isLake(worldX, worldZ, worldSize)) {
            isWater = true;
            surfaceY = MapData.SEA_LEVEL;
        } else {
            // ─── Blend land height and ocean floor using oceanNess ───
            double oceanNess = mapData.getOceanNess(worldX, worldZ, worldSize);
            int depthBilin = mapData.getSeaOceanDepthBilinear(worldX, worldZ, worldSize);

            // Ocean floor: SEA_LEVEL minus depth-based offset (0..50 blocks).
            int oceanFloor = MapData.SEA_LEVEL
                - (int) ((255 - depthBilin) / 255.0 * 50.0);
            oceanFloor = Math.max(MapData.MIN_Y + 1, oceanFloor);

            // Land height: bilinear interpolated base height + erosion noise.
            int baseY = mapData.getHeight(worldX, worldZ, worldSize);
            float erosionFactor = mapData.getErosion(worldX, worldZ, worldSize) / 255.0f;
            int noiseAmplitude = (int) (30 * (1.0f - erosionFactor));
            double noise = sampleErosionNoise(worldX, worldZ, baseY);
            int landHeight = baseY + (int) (noise * noiseAmplitude);
            landHeight = Math.max(MapData.MIN_Y + 1, Math.min(MapData.MAX_TERRAIN - 1, landHeight));

            // Blend: pure ocean → oceanFloor, pure land → landHeight.
            // The blend weight (1 - oceanNess) makes the transition smooth.
            double landWeight = 1.0 - oceanNess;
            int blendedY = (int) (oceanFloor * oceanNess + landHeight * landWeight);

            // Beach cap: near-coast on the land side, force surface to
            // sea level ± 2. This produces natural beaches instead of
            // underwater cliffs where the land height is just below
            // sea level.
            if (oceanNess > 0.05 && oceanNess < 0.45) {
                // Transition zone — clamp to [SEA_LEVEL - 2, SEA_LEVEL + 2].
                blendedY = Math.max(MapData.SEA_LEVEL - 2,
                                    Math.min(MapData.SEA_LEVEL + 2, blendedY));
            }

            surfaceY = blendedY;
            // Treat as water if it's mostly ocean OR the surface ended up
            // below sea level (flooded low land).
            isWater = (oceanNess > 0.5) || (surfaceY < MapData.SEA_LEVEL);
        }

        int minBuildY = getMinimumY();
        int seaLevel = getSeaLevel();

        for (int y = minBuildY; y <= Math.max(surfaceY, seaLevel); y++) {
            BlockPos pos = new BlockPos(localX, y, localZ);
            BlockState blockState;

            if (y > surfaceY && y <= seaLevel) {
                blockState = Blocks.WATER.getDefaultState();
            } else if (y == surfaceY) {
                if (isWater) {
                    blockState = getUnderwaterSurfaceBlock(worldX, worldZ, surfaceY);
                } else {
                    blockState = getSurfaceBlock(worldX, worldZ, surfaceY);
                }
            } else if (y > surfaceY - SURFACE_BLOCK_COUNT && y > minBuildY) {
                if (isWater) {
                    blockState = Blocks.GRAVEL.getDefaultState();
                } else {
                    blockState = Blocks.DIRT.getDefaultState();
                }
            } else {
                blockState = Blocks.STONE.getDefaultState();
            }

            chunk.setBlockState(pos, blockState, false);
        }

        chunk.setBlockState(new BlockPos(localX, minBuildY, localZ), Blocks.BEDROCK.getDefaultState(), false);

        return surfaceY;
    }

    private BlockState getSurfaceBlock(int worldX, int worldZ, int surfaceY) {
        float temp = mapData.getTemperature(worldX, worldZ, worldSize);
        if (surfaceY <= getSeaLevel() + 1) return Blocks.SAND.getDefaultState();
        if (temp < 0.15f) return Blocks.SNOW_BLOCK.getDefaultState();
        if (temp > 0.85f) return Blocks.SAND.getDefaultState();
        return Blocks.GRASS_BLOCK.getDefaultState();
    }

    private BlockState getUnderwaterSurfaceBlock(int worldX, int worldZ, int surfaceY) {
        float temp = mapData.getTemperature(worldX, worldZ, worldSize);
        if (temp < 0.15f) return Blocks.DIRT.getDefaultState();
        return Blocks.SAND.getDefaultState();
    }

    /**
     * Sample erosion noise. Scale adapts to world size.
     */
    private double sampleErosionNoise(int x, int z, int y) {
        double s = noiseScale;
        return (erosionNoiseX.sample(x * s, y * s, z * s) +
                erosionNoiseZ.sample(x * s + 100, y * s, z * s + 100) +
                erosionNoiseY.sample(x * s, y * s + 200, z * s + 200)) / 3.0;
    }

    // ======================== Simplex Noise ========================

    private static class SimplexNoise {
        private static final int GRAD3[][] = {
            {1,1,0},{-1,1,0},{1,-1,0},{-1,-1,0},
            {1,0,1},{-1,0,1},{1,0,-1},{-1,0,-1},
            {0,1,1},{0,-1,1},{0,1,-1},{0,-1,-1}
        };
        private final int[] perm;

        SimplexNoise(Random random) {
            perm = new int[512];
            int[] p = new int[256];
            for (int i = 0; i < 256; i++) p[i] = i;
            for (int i = 255; i > 0; i--) {
                int j = random.nextInt(i + 1);
                int tmp = p[i]; p[i] = p[j]; p[j] = tmp;
            }
            for (int i = 0; i < 512; i++) perm[i] = p[i & 255];
        }

        double sample(double x, double y, double z) {
            int ix = (int) Math.floor(x), iy = (int) Math.floor(y), iz = (int) Math.floor(z);
            double fx = x - ix, fy = y - iy, fz = z - iz;
            double F3 = 1.0 / 3.0;
            double s = (ix + iy + iz) * F3;
            int i = ix + (int) Math.floor(s), j = iy + (int) Math.floor(s), k = iz + (int) Math.floor(s);
            double G3 = 1.0 / 6.0, t = (i + j + k) * G3;
            double x0 = x - (i - t), y0 = y - (j - t), z0 = z - (k - t);
            int i1,j1,k1,i2,j2,k2;
            if (x0 >= y0) { if (y0 >= z0) {i1=1;j1=0;k1=0;i2=1;j2=1;k2=0;} else if (x0>=z0) {i1=1;j1=0;k1=0;i2=1;j2=0;k2=1;} else {i1=0;j1=0;k1=1;i2=1;j2=0;k2=1;} }
            else { if (y0<z0) {i1=0;j1=0;k1=1;i2=0;j2=1;k2=1;} else if (x0<z0) {i1=0;j1=1;k1=0;i2=0;j2=1;k2=1;} else {i1=0;j1=1;k1=0;i2=1;j2=1;k2=0;} }
            double x1=x0-i1+G3,y1=y0-j1+G3,z1=z0-k1+G3,x2=x0-i2+2*G3,y2=y0-j2+2*G3,z2=z0-k2+2*G3,x3=x0-1+3*G3,y3=y0-1+3*G3,z3=z0-1+3*G3;
            int ii=i&255,jj=j&255,kk=k&255;
            double n0=0,n1=0,n2=0,n3=0;
            double t0=0.6-x0*x0-y0*y0-z0*z0; if(t0>0){t0*=t0;n0=t0*t0*dot(GRAD3[perm[ii+perm[jj+perm[kk]]]%12],x0,y0,z0);}
            double t1=0.6-x1*x1-y1*y1-z1*z1; if(t1>0){t1*=t1;n1=t1*t1*dot(GRAD3[perm[ii+i1+perm[jj+j1+perm[kk+k1]]]%12],x1,y1,z1);}
            double t2=0.6-x2*x2-y2*y2-z2*z2; if(t2>0){t2*=t2;n2=t2*t2*dot(GRAD3[perm[ii+i2+perm[jj+j2+perm[kk+k2]]]%12],x2,y2,z2);}
            double t3=0.6-x3*x3-y3*y3-z3*z3; if(t3>0){t3*=t3;n3=t3*t3*dot(GRAD3[perm[ii+1+perm[jj+1+perm[kk+1]]]%12],x3,y3,z3);}
            return 32.0*(n0+n1+n2+n3);
        }
        private double dot(int[] g, double x, double y, double z) { return g[0]*x+g[1]*y+g[2]*z; }
    }
}
