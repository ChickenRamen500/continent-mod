package com.continentgen.world;

import com.continentgen.map.MapData;
import com.continentgen.map.MapLoader;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.ChunkRegion;
import net.minecraft.world.HeightLimitView;
import net.minecraft.world.Heightmap;
import net.minecraft.world.biome.source.BiomeAccess;
import net.minecraft.world.biome.source.BiomeSource;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.GenerationStep;
import net.minecraft.world.gen.StructureAccessor;
import net.minecraft.world.gen.chunk.Blender;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import net.minecraft.world.gen.chunk.VerticalBlockSample;
import net.minecraft.world.gen.noise.NoiseConfig;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Custom ChunkGenerator for terrain from 7 PNG maps.
 * All signatures verified against Yarn 1.20.1+build.10 javadoc.
 */
public class ContinentChunkGenerator extends ChunkGenerator {

    public static final Codec<ContinentChunkGenerator> CODEC = RecordCodecBuilder.create(instance ->
        instance.group(
            BiomeSource.CODEC.fieldOf("biome_source").forGetter(ChunkGenerator::getBiomeSource),
            Codec.LONG.fieldOf("seed").forGetter(gen -> gen.seed)
        ).apply(instance, ContinentChunkGenerator::new)
    );

    private static final int SURFACE_BLOCK_COUNT = 4;

    private final long seed;
    private final MapData mapData;

    private final SimplexNoise erosionNoiseX;
    private final SimplexNoise erosionNoiseZ;
    private final SimplexNoise erosionNoiseY;

    public ContinentChunkGenerator(BiomeSource biomeSource, long seed) {
        super(biomeSource);
        this.seed = seed;
        this.mapData = MapLoader.getInstance().getMapData();

        Random random = Random.create(seed);
        this.erosionNoiseX = new SimplexNoise(random);
        this.erosionNoiseZ = new SimplexNoise(random);
        this.erosionNoiseY = new SimplexNoise(random);
    }

    // ======================== Abstract Methods ========================

    @Override
    public int getMinimumY() { return -64; }

    @Override
    public int getSeaLevel() { return 63; }

    @Override
    public int getWorldHeight() { return 320; }

    @Override
    public void populateEntities(ChunkRegion region) {
        // No custom entity population
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
        if (!mapData.isLoaded()) {
            return CompletableFuture.completedFuture(chunk);
        }

        ChunkPos chunkPos = chunk.getPos();
        int startX = chunkPos.getStartX();
        int startZ = chunkPos.getStartZ();

        for (int localX = 0; localX < 16; localX++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                generateColumn(chunk, startX + localX, startZ + localZ, localX, localZ);
            }
        }

        return CompletableFuture.completedFuture(chunk);
    }

    @Override
    public void carve(ChunkRegion chunkRegion, long seed, NoiseConfig noiseConfig,
                      BiomeAccess biomeAccess, StructureAccessor structures,
                      Chunk chunk, GenerationStep.Carver carver) {
        // Skip cave carving to preserve map-driven terrain
    }

    @Override
    public void buildSurface(ChunkRegion region, StructureAccessor structures,
                             NoiseConfig noiseConfig, Chunk chunk) {
        // Surface already built in populateNoise
    }

    @Override
    public int getHeight(int x, int z, Heightmap.Type heightmap, HeightLimitView heightLimit,
                        NoiseConfig noiseConfig) {
        if (!mapData.isLoaded()) {
            return getSeaLevel();
        }
        return mapData.getHeight(x, z);
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
    }

    // ======================== Column Generation ========================

    private void generateColumn(Chunk chunk, int worldX, int worldZ, int localX, int localZ) {
        boolean isWater = false;
        int surfaceY;

        if (mapData.isRiver(worldX, worldZ) || mapData.isLake(worldX, worldZ)) {
            isWater = true;
            surfaceY = MapData.SEA_LEVEL;
        } else if (mapData.isOcean(worldX, worldZ)) {
            isWater = true;
            int depthValue = mapData.getSeaOceanDepth(worldX, worldZ);
            surfaceY = MapData.SEA_LEVEL - (int) ((200 - depthValue) / 200.0 * 50.0);
            surfaceY = Math.max(MapData.MIN_Y + 1, surfaceY);
        } else {
            isWater = false;
            int baseY = mapData.getHeight(worldX, worldZ);
            float erosionFactor = mapData.getErosion(worldX, worldZ) / 255.0f;
            int noiseAmplitude = (int) (30 * (1.0f - erosionFactor));
            double noise = sampleErosionNoise(worldX, worldZ, baseY);
            surfaceY = baseY + (int) (noise * noiseAmplitude);
            surfaceY = Math.max(MapData.MIN_Y + 1, Math.min(MapData.MAX_Y - 1, surfaceY));
        }

        int minBuildY = getMinimumY();
        int seaLevel = getSeaLevel();

        for (int y = minBuildY; y <= Math.max(surfaceY, seaLevel); y++) {
            BlockPos pos = new BlockPos(localX, y, localZ);
            BlockState blockState;

            if (y > surfaceY && y <= seaLevel) {
                blockState = Blocks.WATER.getDefaultState();
            } else if (y == surfaceY && !isWater) {
                blockState = getSurfaceBlock(worldX, worldZ, surfaceY);
            } else if (y > surfaceY - SURFACE_BLOCK_COUNT && y > surfaceY && isWater) {
                blockState = Blocks.GRAVEL.getDefaultState();
            } else if (y > surfaceY - SURFACE_BLOCK_COUNT && y > minBuildY) {
                blockState = Blocks.DIRT.getDefaultState();
            } else {
                blockState = Blocks.STONE.getDefaultState();
            }

            chunk.setBlockState(pos, blockState, false);
        }

        chunk.setBlockState(new BlockPos(localX, minBuildY, localZ), Blocks.BEDROCK.getDefaultState(), false);
    }

    private BlockState getSurfaceBlock(int worldX, int worldZ, int surfaceY) {
        float temp = mapData.getTemperature(worldX, worldZ);
        if (surfaceY <= getSeaLevel() + 1) return Blocks.SAND.getDefaultState();
        if (temp < 0.15f) return Blocks.SNOW_BLOCK.getDefaultState();
        if (temp > 0.85f) return Blocks.SAND.getDefaultState();
        return Blocks.GRASS_BLOCK.getDefaultState();
    }

    private double sampleErosionNoise(int x, int z, int y) {
        double scale = 0.01;
        return (erosionNoiseX.sample(x * scale, y * scale, z * scale) +
                erosionNoiseZ.sample(x * scale + 100, y * scale, z * scale + 100) +
                erosionNoiseY.sample(x * scale, y * scale + 200, z * scale + 200)) / 3.0;
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
