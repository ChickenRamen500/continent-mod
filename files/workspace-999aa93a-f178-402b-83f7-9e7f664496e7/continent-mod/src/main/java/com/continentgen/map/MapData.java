package com.continentgen.map;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Holds all 7 map arrays loaded from PNG files.
 * Provides methods to query map data at world coordinates with
 * toroidal (Pac-Man) wrapping at world boundaries.
 *
 * World boundaries: X: [-150000, 150000], Z: [-150000, 150000]
 * Total world size: 300000 x 300000 blocks
 * 1 pixel = 100 blocks (for a 3000x3000 image)
 */
public class MapData {
    private static final Logger LOGGER = Logger.getLogger("ContinentGen");

    // World constants
    public static final int WORLD_SIZE = 300_000;
    public static final int HALF_WORLD = 150_000;
    public static final int SEA_LEVEL = 62;

    // Minimum and maximum Y values for terrain
    public static final int MIN_Y = -64;
    public static final int MAX_Y = 320;

    // 7 map arrays (row-major, [z][x])
    private byte[][] heightMap;       // height.png - grayscale
    private byte[][] temperatureMap;   // temperature.png - RGB (stored as packed int)
    private byte[][] humidityMap;      // humidity.png - grayscale
    private byte[][] erosionMap;       // erosion.png - grayscale
    private byte[][] seaOceanMap;      // sea-ocean.png - grayscale
    private byte[][] riversMap;        // map_rivers.png - monochrome (0=river)
    private byte[][] lakesMap;         // map_lakes.png - monochrome (0=lake)

    private int imageWidth;
    private int imageHeight;
    private boolean loaded = false;

    /**
     * Attempts to load all 7 PNG maps from the given directory.
     * Sets loaded=true only if ALL files are found and loaded.
     */
    public void load(Path mapDir) {
        String[] fileNames = {
            "height.png",
            "temperature.png",
            "humidity.png",
            "erosion.png",
            "sea-ocean.png",
            "map_rivers.png",
            "map_lakes.png"
        };

        try {
            // Load height.png first to get dimensions
            BufferedImage heightImg = loadImage(mapDir, "height.png");
            if (heightImg == null) {
                LOGGER.warning("Failed to load height.png — map data unavailable");
                return;
            }
            imageWidth = heightImg.getWidth();
            imageHeight = heightImg.getHeight();
            LOGGER.info("Map dimensions: " + imageWidth + "x" + imageHeight);

            // Initialize arrays
            heightMap = new byte[imageHeight][imageWidth];
            temperatureMap = new byte[imageHeight][imageWidth * 3]; // RGB
            humidityMap = new byte[imageHeight][imageWidth];
            erosionMap = new byte[imageHeight][imageWidth];
            seaOceanMap = new byte[imageHeight][imageWidth];
            riversMap = new byte[imageHeight][imageWidth];
            lakesMap = new byte[imageHeight][imageWidth];

            // Parse height.png
            parseGrayscale(heightImg, heightMap);

            // Load and parse remaining maps
            BufferedImage tempImg = loadImage(mapDir, "temperature.png");
            if (tempImg != null) parseTemperature(tempImg);

            BufferedImage humImg = loadImage(mapDir, "humidity.png");
            if (humImg != null) parseGrayscale(humImg, humidityMap);

            BufferedImage eroImg = loadImage(mapDir, "erosion.png");
            if (eroImg != null) parseGrayscale(eroImg, erosionMap);

            BufferedImage seaImg = loadImage(mapDir, "sea-ocean.png");
            if (seaImg != null) parseGrayscale(seaImg, seaOceanMap);

            BufferedImage rivImg = loadImage(mapDir, "map_rivers.png");
            if (rivImg != null) parseGrayscale(rivImg, riversMap);

            BufferedImage lakeImg = loadImage(mapDir, "map_lakes.png");
            if (lakeImg != null) parseGrayscale(lakeImg, lakesMap);

            loaded = true;
            LOGGER.info("All 7 maps loaded successfully!");

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to load map data", e);
            loaded = false;
        }
    }

    private BufferedImage loadImage(Path dir, String fileName) {
        File file = dir.resolve(fileName).toFile();
        if (!file.exists()) {
            LOGGER.warning("Map file not found: " + file.getAbsolutePath());
            return null;
        }
        try {
            return ImageIO.read(file);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to read: " + fileName, e);
            return null;
        }
    }

    /**
     * Parse a grayscale image into a byte[][] array.
     */
    private void parseGrayscale(BufferedImage img, byte[][] target) {
        int w = img.getWidth();
        int h = img.getHeight();
        for (int z = 0; z < h; z++) {
            for (int x = 0; x < w; x++) {
                int argb = img.getRGB(x, z);
                int gray = (argb >> 16) & 0xFF; // Use red channel
                target[z][x] = (byte) gray;
            }
        }
    }

    /**
     * Parse temperature.png (RGB) into the temperature array.
     * Stores as flat RGB bytes: temperatureMap[z][x*3], [x*3+1], [x*3+2]
     */
    private void parseTemperature(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        for (int z = 0; z < h; z++) {
            for (int x = 0; x < w; x++) {
                int argb = img.getRGB(x, z);
                int idx = x * 3;
                temperatureMap[z][idx]     = (byte) ((argb >> 16) & 0xFF); // R
                temperatureMap[z][idx + 1] = (byte) ((argb >> 8)  & 0xFF); // G
                temperatureMap[z][idx + 2] = (byte) (argb & 0xFF);          // B
            }
        }
    }

    // ======================== Coordinate Helpers ========================

    /**
     * Convert world coordinate to image pixel coordinate with toroidal wrapping.
     * Clamps result to [0, maxDim-1].
     */
    private int toPixelX(int worldX) {
        double px = ((worldX + HALF_WORLD) / (double) WORLD_SIZE) * imageWidth;
        int ix = (int) px;
        if (ix < 0) ix = 0;
        if (ix >= imageWidth) ix = imageWidth - 1;
        return ix;
    }

    private int toPixelZ(int worldZ) {
        double pz = ((worldZ + HALF_WORLD) / (double) WORLD_SIZE) * imageHeight;
        int iz = (int) pz;
        if (iz < 0) iz = 0;
        if (iz >= imageHeight) iz = imageHeight - 1;
        return iz;
    }

    // ======================== Query Methods ========================

    public boolean isLoaded() {
        return loaded;
    }

    /**
     * Get base height at world coordinates.
     * Returns Y value mapped from grayscale 0..255 to MIN_Y..MAX_Y.
     */
    public int getHeight(int worldX, int worldZ) {
        if (!loaded) return SEA_LEVEL;
        int px = toPixelX(worldX);
        int pz = toPixelZ(worldZ);
        int gray = heightMap[pz][px] & 0xFF;
        // Map 0..255 → MIN_Y..MAX_Y
        return MIN_Y + (int) ((gray / 255.0) * (MAX_Y - MIN_Y));
    }

    /**
     * Get climate zone at world coordinates from temperature.png.
     */
    public ClimateZone getClimateZone(int worldX, int worldZ) {
        if (!loaded) return ClimateZone.TEMPERATE;
        int px = toPixelX(worldX);
        int pz = toPixelZ(worldZ);
        int idx = px * 3;
        int r = temperatureMap[pz][idx] & 0xFF;
        int g = temperatureMap[pz][idx + 1] & 0xFF;
        int b = temperatureMap[pz][idx + 2] & 0xFF;
        return ClimateZone.fromRGB(r, g, b);
    }

    /**
     * Get raw temperature value at world coordinates.
     */
    public float getTemperature(int worldX, int worldZ) {
        return getClimateZone(worldX, worldZ).getTemperature();
    }

    /**
     * Check if position is a river (pixel value == 0 on map_rivers.png).
     */
    public boolean isRiver(int worldX, int worldZ) {
        if (!loaded) return false;
        int px = toPixelX(worldX);
        int pz = toPixelZ(worldZ);
        return (riversMap[pz][px] & 0xFF) == 0;
    }

    /**
     * Check if position is a lake (pixel value == 0 on map_lakes.png).
     */
    public boolean isLake(int worldX, int worldZ) {
        if (!loaded) return false;
        int px = toPixelX(worldX);
        int pz = toPixelZ(worldZ);
        return (lakesMap[pz][px] & 0xFF) == 0;
    }

    /**
     * Get sea/ocean depth value (0..255, darker=deeper).
     */
    public int getSeaOceanDepth(int worldX, int worldZ) {
        if (!loaded) return 0;
        int px = toPixelX(worldX);
        int pz = toPixelZ(worldZ);
        return seaOceanMap[pz][px] & 0xFF;
    }

    /**
     * Check if position is ocean (sea-ocean.png indicates water).
     * Uses threshold: brightness < 200 means water.
     */
    public boolean isOcean(int worldX, int worldZ) {
        return getSeaOceanDepth(worldX, worldZ) < 200;
    }

    /**
     * Get humidity value (0..255) at world coordinates.
     */
    public int getHumidity(int worldX, int worldZ) {
        if (!loaded) return 128;
        int px = toPixelX(worldX);
        int pz = toPixelZ(worldZ);
        return humidityMap[pz][px] & 0xFF;
    }

    /**
     * Get erosion value (0..255) at world coordinates.
     * Higher value = more eroded (flatter), lower = more rugged (mountainous noise).
     */
    public int getErosion(int worldX, int worldZ) {
        if (!loaded) return 128;
        int px = toPixelX(worldX);
        int pz = toPixelZ(worldZ);
        return erosionMap[pz][px] & 0xFF;
    }

    public int getImageWidth() { return imageWidth; }
    public int getImageHeight() { return imageHeight; }
}
