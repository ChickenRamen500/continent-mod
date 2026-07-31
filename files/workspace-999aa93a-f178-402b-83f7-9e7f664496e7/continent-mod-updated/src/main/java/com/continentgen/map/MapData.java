package com.continentgen.map;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Holds all 7 map arrays loaded from PNG files.
 *
 * ─────────────────────────────────────────────────────────────────────────
 * DESIGN:
 *   MapData is a SINGLETON that owns the 7 PNG arrays (height, temperature,
 *   humidity, erosion, sea-ocean, rivers, lakes). These are loaded ONCE
 *   from disk and never change.
 *
 *   The "world size" is NOT stored on MapData. It is passed as an explicit
 *   parameter to every query method. Each ContinentBiomeSource /
 *   ContinentChunkGenerator holds its own worldSize field and passes it
 *   to MapData.getHeight(x, z, worldSize), isRiver(x, z, worldSize), etc.
 *
 *   Coordinate conversion (toroidal wrap):
 *     pixelX = ((worldX + worldSize/2) / worldSize) * imageWidth
 *     pixelZ = ((worldZ + worldSize/2) / worldSize) * imageHeight
 *
 * INTERPOLATION (FIXES "stairs" and "sharp cliffs at coast"):
 *   • getHeight            — bilinear interpolation across 4 pixels
 *                            (already in the previous version)
 *   • getSeaOceanDepthBilinear — NEW: bilinear interpolation of the
 *                                 sea-ocean depth value (0..255). Smooths
 *                                 out the abrupt pixel-to-pixel depth
 *                                 steps that produced "stair-stepped"
 *                                 ocean floors and cliffs at the coast.
 *   • getOceanNess         — NEW: returns 0..1 (0 = full land, 1 = full
 *                            ocean). Computed by bilinear-interpolating a
 *                            binary ocean mask (depth<OCEAN_THRESHOLD ⇒ 1,
 *                            else 0). Used by the chunk generator to
 *                            BLEND the land height and ocean floor at the
 *                            coast instead of snapping between them.
 *
 * RIVER / LAKE THINNING (FIXES "circles in the middle of pixels"):
 *   Previously each river pixel was rendered as a CIRCLE of radius 15
 *   blocks centred on the pixel's centre. At 300k world size (100 blocks
 *   per pixel) the circles were 30 blocks wide but pixel centres were
 *   100 blocks apart → rivers appeared as chains of isolated 30-block
 *   puddles with 70-block gaps.
 *
 *   FIX: river/lake half-width is now Math.max(RIVER_HALF_WIDTH_BLOCKS,
 *   blocksPerPixel/2) so adjacent pixel circles always overlap and form
 *   a CONTINUOUS line. At 300k → halfWidth=50 (river is 100 blocks wide,
 *   fully tiling); at 3k → halfWidth=15 (river is 30 blocks wide, finer
 *   than one pixel).
 * ─────────────────────────────────────────────────────────────────────────
 */
public class MapData {
    private static final Logger LOGGER = Logger.getLogger("ContinentGen");

    // ─── Default world constants ─────────────────────────────────────────
    public static final int DEFAULT_WORLD_SIZE = 300_000;
    public static final int SEA_LEVEL = 62;

    // Min/max Y for terrain
    public static final int MIN_Y = -64;
    public static final int MAX_Y = 320;
    /** Maximum terrain height (compressed range — prevents 290+ mountains). */
    public static final int MAX_TERRAIN = 200;

    // ─── River / lake thinning ───────────────────────────────────────────
    /**
     * MINIMUM half-width of rendered rivers, in blocks. Rivers will be at
     * least 30 blocks wide. They get wider at large world sizes so that
     * adjacent river pixels overlap into a continuous line.
     */
    public static final double RIVER_HALF_WIDTH_BLOCKS = 15.0;
    /** MINIMUM half-width of rendered lakes, in blocks. */
    public static final double LAKE_HALF_WIDTH_BLOCKS = 25.0;

    // Thresholds — MUST match map_locator.py exactly
    public static final int OCEAN_THRESHOLD = 255;      // <255 = ocean
    public static final int DEEP_OCEAN_THRESHOLD = 80;  // <=80 = deep ocean
    public static final int WATER_RED_THRESHOLD = 128;  // red<128 AND alpha>0 = water

    // 7 map arrays (row-major, [z][x])
    private byte[][] heightMap;
    private byte[][] temperatureMap;
    private byte[][] humidityMap;
    private byte[][] erosionMap;
    private byte[][] seaOceanMap;
    private boolean[][] riversMask;
    private boolean[][] lakesMask;

    private int imageWidth;
    private int imageHeight;
    private boolean loaded = false;

    // ═════════════════════════════════════════════════════════════════════
    // LOADING
    // ═════════════════════════════════════════════════════════════════════

    public void load(Path mapDir) {
        LOGGER.info("========================================");
        LOGGER.info("[MapData] Loading maps from: " + mapDir.toAbsolutePath());

        try {
            BufferedImage heightImg = loadImage(mapDir, "height.png");
            if (heightImg == null) {
                LOGGER.severe("[MapData] CRITICAL: height.png not found — maps unavailable");
                return;
            }
            imageWidth = heightImg.getWidth();
            imageHeight = heightImg.getHeight();
            LOGGER.info("[MapData] Image dimensions: " + imageWidth + "x" + imageHeight);

            heightMap = new byte[imageHeight][imageWidth];
            temperatureMap = new byte[imageHeight][imageWidth * 3];
            humidityMap = new byte[imageHeight][imageWidth];
            erosionMap = new byte[imageHeight][imageWidth];
            seaOceanMap = new byte[imageHeight][imageWidth];
            riversMask = new boolean[imageHeight][imageWidth];
            lakesMask = new boolean[imageHeight][imageWidth];

            parseGrayscale(heightImg, heightMap, "height.png");

            BufferedImage tempImg = loadImage(mapDir, "temperature.png");
            if (tempImg != null) {
                parseTemperature(tempImg);
            } else {
                fillDefault(temperatureMap, (byte) 0x6C);
            }

            BufferedImage humImg = loadImage(mapDir, "humidity.png");
            if (humImg != null) parseGrayscale(humImg, humidityMap, "humidity.png");
            else fillDefault(humidityMap, (byte) 128);

            BufferedImage eroImg = loadImage(mapDir, "erosion.png");
            if (eroImg != null) parseGrayscale(eroImg, erosionMap, "erosion.png");
            else fillDefault(erosionMap, (byte) 128);

            BufferedImage seaImg = loadImage(mapDir, "sea-ocean.png");
            if (seaImg != null) parseGrayscale(seaImg, seaOceanMap, "sea-ocean.png");
            else fillDefault(seaOceanMap, (byte) 255);

            BufferedImage rivImg = loadImage(mapDir, "map_rivers.png");
            if (rivImg != null) parseWaterMap(rivImg, riversMask, "map_rivers.png");

            BufferedImage lakeImg = loadImage(mapDir, "map_lakes.png");
            if (lakeImg != null) parseWaterMap(lakeImg, lakesMask, "map_lakes.png");

            loaded = true;
            LOGGER.info("[MapData] All maps loaded! " + imageWidth + "x" + imageHeight
                + " (worldSize is now per-instance — passed as parameter)");

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "[MapData] Failed to load map data", e);
            loaded = false;
        }
    }

    private void fillDefault(byte[][] target, byte val) {
        for (int z = 0; z < imageHeight; z++) {
            java.util.Arrays.fill(target[z], val);
        }
    }

    private BufferedImage loadImage(Path dir, String fileName) {
        File file = dir.resolve(fileName).toFile();
        if (!file.exists()) {
            LOGGER.warning("[MapData] File not found: " + file.getAbsolutePath());
            return null;
        }
        try {
            return ImageIO.read(file);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "[MapData] Failed to read: " + fileName, e);
            return null;
        }
    }

    private void parseGrayscale(BufferedImage img, byte[][] target, String name) {
        int w = img.getWidth();
        int h = img.getHeight();
        for (int z = 0; z < h; z++) {
            for (int x = 0; x < w; x++) {
                int argb = img.getRGB(x, z);
                int gray = (argb >> 16) & 0xFF;
                target[z][x] = (byte) gray;
            }
        }
    }

    private void parseWaterMap(BufferedImage img, boolean[][] target, String name) {
        int w = img.getWidth();
        int h = img.getHeight();
        for (int z = 0; z < h; z++) {
            for (int x = 0; x < w; x++) {
                int argb = img.getRGB(x, z);
                int alpha = (argb >>> 24) & 0xFF;
                int red = (argb >> 16) & 0xFF;
                target[z][x] = (alpha > 0) && (red < WATER_RED_THRESHOLD);
            }
        }
    }

    private void parseTemperature(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        for (int z = 0; z < h; z++) {
            for (int x = 0; x < w; x++) {
                int argb = img.getRGB(x, z);
                int idx = x * 3;
                temperatureMap[z][idx]     = (byte) ((argb >> 16) & 0xFF);
                temperatureMap[z][idx + 1] = (byte) ((argb >> 8)  & 0xFF);
                temperatureMap[z][idx + 2] = (byte) (argb & 0xFF);
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // COORDINATE CONVERSION (with toroidal wrapping)
    // worldSize is now a PARAMETER — no shared state.
    // ═════════════════════════════════════════════════════════════════════

    private double toPixelXDouble(int worldX, int worldSize) {
        return ((worldX + worldSize / 2.0) / worldSize) * imageWidth;
    }

    private double toPixelZDouble(int worldZ, int worldSize) {
        return ((worldZ + worldSize / 2.0) / worldSize) * imageHeight;
    }

    private int toPixelX(int worldX, int worldSize) {
        double px = toPixelXDouble(worldX, worldSize);
        int ix = (int) Math.floor(px);
        return ((ix % imageWidth) + imageWidth) % imageWidth;
    }

    private int toPixelZ(int worldZ, int worldSize) {
        double pz = toPixelZDouble(worldZ, worldSize);
        int iz = (int) Math.floor(pz);
        return ((iz % imageHeight) + imageHeight) % imageHeight;
    }

    // ═════════════════════════════════════════════════════════════════════
    // QUERY METHODS — every method takes worldSize as a parameter
    // ═════════════════════════════════════════════════════════════════════

    public boolean isLoaded() { return loaded; }
    public int getImageWidth() { return imageWidth; }
    public int getImageHeight() { return imageHeight; }

    public double getBlocksPerPixel(int worldSize) {
        if (imageWidth <= 0) return 100.0;
        return worldSize / (double) imageWidth;
    }

    /**
     * Effective half-width of a river at this world size.
     *
     * FIX: use Math.max (NOT Math.min). At large world sizes (100 blocks
     * per pixel) the half-width must be at LEAST half a pixel (50 blocks)
     * so that circles drawn around adjacent river pixel CENTRES overlap
     * into a continuous line. With Math.min(15, 50) we got 15-block
     * circles separated by 70-block gaps.
     */
    public double getEffectiveRiverHalfWidth(int worldSize) {
        return Math.max(RIVER_HALF_WIDTH_BLOCKS, getBlocksPerPixel(worldSize) / 2.0);
    }

    public double getEffectiveLakeHalfWidth(int worldSize) {
        return Math.max(LAKE_HALF_WIDTH_BLOCKS, getBlocksPerPixel(worldSize) / 2.0);
    }

    /**
     * Get INTERPOLATED base height at world coordinates.
     * Uses bilinear interpolation between the 4 nearest pixels.
     */
    public int getHeight(int worldX, int worldZ, int worldSize) {
        if (!loaded) return SEA_LEVEL;

        double fx = toPixelXDouble(worldX, worldSize);
        double fz = toPixelZDouble(worldZ, worldSize);

        int x0 = (int) Math.floor(fx);
        int z0 = (int) Math.floor(fz);
        int x1 = x0 + 1;
        int z1 = z0 + 1;
        double tx = fx - x0;
        double tz = fz - z0;

        x0 = ((x0 % imageWidth) + imageWidth) % imageWidth;
        x1 = ((x1 % imageWidth) + imageWidth) % imageWidth;
        z0 = ((z0 % imageHeight) + imageHeight) % imageHeight;
        z1 = ((z1 % imageHeight) + imageHeight) % imageHeight;

        int h00 = heightMap[z0][x0] & 0xFF;
        int h01 = heightMap[z0][x1] & 0xFF;
        int h10 = heightMap[z1][x0] & 0xFF;
        int h11 = heightMap[z1][x1] & 0xFF;

        double h0 = h00 + (h01 - h00) * tx;
        double h1 = h10 + (h11 - h10) * tx;
        double h = h0 + (h1 - h0) * tz;

        return MIN_Y + (int) ((h / 255.0) * (MAX_TERRAIN - MIN_Y));
    }

    public ClimateZone getClimateZone(int worldX, int worldZ, int worldSize) {
        if (!loaded) return ClimateZone.TEMPERATE;
        int px = toPixelX(worldX, worldSize);
        int pz = toPixelZ(worldZ, worldSize);
        int idx = px * 3;
        int r = temperatureMap[pz][idx] & 0xFF;
        int g = temperatureMap[pz][idx + 1] & 0xFF;
        int b = temperatureMap[pz][idx + 2] & 0xFF;
        return ClimateZone.fromRGB(r, g, b);
    }

    public float getTemperature(int worldX, int worldZ, int worldSize) {
        return getClimateZone(worldX, worldZ, worldSize).getTemperature();
    }

    /**
     * Check if position is a RIVER.
     * Uses distance-based thinning: renders water only within
     * RIVER_HALF_WIDTH_BLOCKS of any river pixel centre (3x3 neighbourhood).
     *
     * FIX: half-width now uses Math.max so adjacent river pixels always
     * overlap into a continuous line — no more "circles in the middle
     * of pixels" gaps.
     */
    public boolean isRiver(int worldX, int worldZ, int worldSize) {
        if (!loaded) return false;

        double fx = toPixelXDouble(worldX, worldSize);
        double fz = toPixelZDouble(worldZ, worldSize);
        int px = (int) Math.floor(fx);
        int pz = (int) Math.floor(fz);

        double halfWidth = getEffectiveRiverHalfWidth(worldSize);
        double halfWidthSq = halfWidth * halfWidth;

        // Search a neighbourhood large enough to cover the half-width.
        // At 300k scale, halfWidth=50 blocks ≈ 0.5 pixel, so 1-pixel
        // neighbourhood suffices. At 3k scale, halfWidth=15 blocks ≈
        // 15 pixels, so we need a wider search.
        int pixelRadius = Math.max(1, (int) Math.ceil(halfWidth / getBlocksPerPixel(worldSize)));

        for (int dz = -pixelRadius; dz <= pixelRadius; dz++) {
            for (int dx = -pixelRadius; dx <= pixelRadius; dx++) {
                int nx = ((px + dx) % imageWidth + imageWidth) % imageWidth;
                int nz = ((pz + dz) % imageHeight + imageHeight) % imageHeight;
                if (!riversMask[nz][nx]) continue;

                double centerX = (nx + 0.5) / imageWidth * worldSize - worldSize / 2.0;
                double centerZ = (nz + 0.5) / imageHeight * worldSize - worldSize / 2.0;

                double ddx = worldX - centerX;
                double ddz = worldZ - centerZ;
                if (ddx * ddx + ddz * ddz <= halfWidthSq) {
                    return true;
                }
            }
        }
        return false;
    }

    public boolean isLake(int worldX, int worldZ, int worldSize) {
        if (!loaded) return false;

        double fx = toPixelXDouble(worldX, worldSize);
        double fz = toPixelZDouble(worldZ, worldSize);
        int px = (int) Math.floor(fx);
        int pz = (int) Math.floor(fz);

        double halfWidth = getEffectiveLakeHalfWidth(worldSize);
        double halfWidthSq = halfWidth * halfWidth;

        int pixelRadius = Math.max(1, (int) Math.ceil(halfWidth / getBlocksPerPixel(worldSize)));

        for (int dz = -pixelRadius; dz <= pixelRadius; dz++) {
            for (int dx = -pixelRadius; dx <= pixelRadius; dx++) {
                int nx = ((px + dx) % imageWidth + imageWidth) % imageWidth;
                int nz = ((pz + dz) % imageHeight + imageHeight) % imageHeight;
                if (!lakesMask[nz][nx]) continue;

                double centerX = (nx + 0.5) / imageWidth * worldSize - worldSize / 2.0;
                double centerZ = (nz + 0.5) / imageHeight * worldSize - worldSize / 2.0;

                double ddx = worldX - centerX;
                double ddz = worldZ - centerZ;
                if (ddx * ddx + ddz * ddz <= halfWidthSq) {
                    return true;
                }
            }
        }
        return false;
    }

    // ─── Ocean depth queries ─────────────────────────────────────────────

    /** Nearest-neighbour depth (legacy). Kept for the F3 debug HUD. */
    public int getSeaOceanDepth(int worldX, int worldZ, int worldSize) {
        if (!loaded) return 255;
        int px = toPixelX(worldX, worldSize);
        int pz = toPixelZ(worldZ, worldSize);
        return seaOceanMap[pz][px] & 0xFF;
    }

    /**
     * Bilinear-interpolated sea-ocean depth (0..255).
     *
     * The raw sea-ocean.png is a grayscale image where 255 = land and
     * <255 = ocean (lower = deeper). Nearest-neighbour sampling produces
     * abrupt steps between adjacent pixels at the coast, which become
     * visible as "stair-stepped" ocean floors and cliffs. Bilinear
     * interpolation smooths those transitions.
     */
    public int getSeaOceanDepthBilinear(int worldX, int worldZ, int worldSize) {
        if (!loaded) return 255;

        double fx = toPixelXDouble(worldX, worldSize);
        double fz = toPixelZDouble(worldZ, worldSize);

        int x0 = (int) Math.floor(fx);
        int z0 = (int) Math.floor(fz);
        int x1 = x0 + 1;
        int z1 = z0 + 1;
        double tx = fx - x0;
        double tz = fz - z0;

        x0 = ((x0 % imageWidth) + imageWidth) % imageWidth;
        x1 = ((x1 % imageWidth) + imageWidth) % imageWidth;
        z0 = ((z0 % imageHeight) + imageHeight) % imageHeight;
        z1 = ((z1 % imageHeight) + imageHeight) % imageHeight;

        int d00 = seaOceanMap[z0][x0] & 0xFF;
        int d01 = seaOceanMap[z0][x1] & 0xFF;
        int d10 = seaOceanMap[z1][x0] & 0xFF;
        int d11 = seaOceanMap[z1][x1] & 0xFF;

        double d0 = d00 + (d01 - d00) * tx;
        double d1 = d10 + (d11 - d10) * tx;
        double d = d0 + (d1 - d0) * tz;
        return (int) Math.round(d);
    }

    /**
     * Returns a smooth "ocean-ness" factor in [0, 1].
     *   0.0 = fully land (sea-ocean depth = 255, i.e. solid land)
     *   1.0 = fully ocean (sea-ocean depth ≤ some "deep ocean" threshold)
     *
     * Computed by bilinear-interpolating a binary ocean mask:
     *   mask = (depth < OCEAN_THRESHOLD) ? 1 : 0
     *
     * At a coastline, the value smoothly transitions from 0 to 1 over
     * the span of one pixel, instead of snapping. The chunk generator
     * uses this to BLEND the land height and ocean floor (no more
     * vertical cliffs at the coast).
     */
    public double getOceanNess(int worldX, int worldZ, int worldSize) {
        if (!loaded) return 0.0;

        double fx = toPixelXDouble(worldX, worldSize);
        double fz = toPixelZDouble(worldZ, worldSize);

        int x0 = (int) Math.floor(fx);
        int z0 = (int) Math.floor(fz);
        int x1 = x0 + 1;
        int z1 = z0 + 1;
        double tx = fx - x0;
        double tz = fz - z0;

        x0 = ((x0 % imageWidth) + imageWidth) % imageWidth;
        x1 = ((x1 % imageWidth) + imageWidth) % imageWidth;
        z0 = ((z0 % imageHeight) + imageHeight) % imageHeight;
        z1 = ((z1 % imageHeight) + imageHeight) % imageHeight;

        // Binary ocean mask (1 = ocean, 0 = land).
        double m00 = (seaOceanMap[z0][x0] & 0xFF) < OCEAN_THRESHOLD ? 1.0 : 0.0;
        double m01 = (seaOceanMap[z0][x1] & 0xFF) < OCEAN_THRESHOLD ? 1.0 : 0.0;
        double m10 = (seaOceanMap[z1][x0] & 0xFF) < OCEAN_THRESHOLD ? 1.0 : 0.0;
        double m11 = (seaOceanMap[z1][x1] & 0xFF) < OCEAN_THRESHOLD ? 1.0 : 0.0;

        double m0 = m00 + (m01 - m00) * tx;
        double m1 = m10 + (m11 - m10) * tx;
        return m0 + (m1 - m0) * tz;
    }

    public boolean isOcean(int worldX, int worldZ, int worldSize) {
        return getSeaOceanDepth(worldX, worldZ, worldSize) < OCEAN_THRESHOLD;
    }

    public boolean isDeepOcean(int worldX, int worldZ, int worldSize) {
        return getSeaOceanDepth(worldX, worldZ, worldSize) <= DEEP_OCEAN_THRESHOLD;
    }

    public int getHumidity(int worldX, int worldZ, int worldSize) {
        if (!loaded) return 128;
        int px = toPixelX(worldX, worldSize);
        int pz = toPixelZ(worldZ, worldSize);
        return humidityMap[pz][px] & 0xFF;
    }

    public int getErosion(int worldX, int worldZ, int worldSize) {
        if (!loaded) return 128;
        int px = toPixelX(worldX, worldSize);
        int pz = toPixelZ(worldZ, worldSize);
        return erosionMap[pz][px] & 0xFF;
    }
}
