package com.continentgen.map;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Хранит 7 массивов PNG карт для генерации мира.
 *
 * v7 (01.08.2026):
 *   - Высота: билинейная интерполяция (гладкая поверхность без ступеней).
 *   - Океан: маска через getOceanNess() [0..1] — плавный берег.
 *   - Реки/Озёра: getRiverNess()/getLakeNess() [0..1] — билинейная
 *     интерполяция бинарной маски. НЕ использует круги.
 *   - Вода рек/озёр: terrain carving (реализуется в ContinentChunkGenerator).
 *     Озёра: ПЛОСКИЙ уровень воды (SEA_LEVEL + 3). Реки: русло в рельефе.
 *     БЕЗ отдельных зон берега (решает баг «двойной берег»).
 */
public class MapData {
    private static final Logger LOGGER = Logger.getLogger("ContinentGen");

    // ─── Константы мира ─────────────────────────────────────────────
    public static final int DEFAULT_WORLD_SIZE = 150_000;
    public static final int SEA_LEVEL = 63;
    public static final int MIN_Y = -64;
    public static final int MAX_Y = 320;
    public static final int MAX_TERRAIN = 200;

    // ─── Пороги (должны совпадать с map_locator.py) ──────────────
    public static final int OCEAN_THRESHOLD = 255;      // < 255 = океан
    public static final int DEEP_OCEAN_THRESHOLD = 80;  // <= 80 = глубокий океан
    public static final int WATER_RED_THRESHOLD = 128;  // red < 128 AND alpha > 0 = вода

    // ─── Пороги "riverNess" / "lakeNess" ──────────────────────────
    // Примечание v7: пороги bank_threshold больше НЕ используются
    // в ContinentChunkGenerator (зоны берега удалены).
    // Оставлены для совместимости с BiomeSource и внешними вызовами.
    public static final double RIVER_CHANNEL_THRESHOLD = 0.5;
    public static final double RIVER_BANK_THRESHOLD = 0.2;
    public static final double LAKE_CHANNEL_THRESHOLD = 0.5;
    public static final double LAKE_BANK_THRESHOLD = 0.2;

    // 7 массивов карт (row-major, [z][x])
    private byte[][] heightMap;
    private byte[][] temperatureMap;   // [z][x*3] = R,G,B
    private byte[][] humidityMap;
    private byte[][] erosionMap;
    private byte[][] seaOceanMap;
    private boolean[][] riversMask;
    private boolean[][] lakesMask;

    private int imageWidth;
    private int imageHeight;
    private boolean loaded = false;

    // ═══════════════════════════════════════════════════════════════
    // ЗАГРУЗКА
    // ═══════════════════════════════════════════════════════════════

    public void load(Path mapDir) {
        LOGGER.info("========================================");
        LOGGER.info("[MapData] Загрузка карт из: " + mapDir.toAbsolutePath());
        try {
            BufferedImage heightImg = loadImage(mapDir, "height.png");
            if (heightImg == null) {
                LOGGER.severe("[MapData] КРИТИЧЕСКИ: height.png не найден!");
                return;
            }
            imageWidth = heightImg.getWidth();
            imageHeight = heightImg.getHeight();
            LOGGER.info("[MapData] Размер изображений: " + imageWidth + "x" + imageHeight);

            heightMap = new byte[imageHeight][imageWidth];
            temperatureMap = new byte[imageHeight][imageWidth * 3];
            humidityMap = new byte[imageHeight][imageWidth];
            erosionMap = new byte[imageHeight][imageWidth];
            seaOceanMap = new byte[imageHeight][imageWidth];
            riversMask = new boolean[imageHeight][imageWidth];
            lakesMask = new boolean[imageHeight][imageWidth];

            parseGrayscale(heightImg, heightMap, "height.png");

            BufferedImage tempImg = loadImage(mapDir, "temperature.png");
            if (tempImg != null) parseTemperature(tempImg);
            else fillDefault(temperatureMap, (byte) 0x6C);

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
            LOGGER.info("[MapData] Все карты загружены! " + imageWidth + "x" + imageHeight);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "[MapData] Ошибка загрузки карт", e);
            loaded = false;
        }
    }

    private void fillDefault(byte[][] target, byte val) {
        for (int z = 0; z < imageHeight; z++) java.util.Arrays.fill(target[z], val);
    }

    private BufferedImage loadImage(Path dir, String fileName) {
        File file = dir.resolve(fileName).toFile();
        if (!file.exists()) {
            LOGGER.warning("[MapData] Файл не найден: " + file.getAbsolutePath());
            return null;
        }
        try {
            return ImageIO.read(file);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "[MapData] Не удалось прочитать: " + fileName, e);
            return null;
        }
    }

    private void parseGrayscale(BufferedImage img, byte[][] target, String name) {
        int w = img.getWidth(), h = img.getHeight();
        for (int z = 0; z < h; z++)
            for (int x = 0; x < w; x++) {
                int argb = img.getRGB(x, z);
                target[z][x] = (byte) ((argb >> 16) & 0xFF);
            }
    }

    private void parseWaterMap(BufferedImage img, boolean[][] target, String name) {
        int w = img.getWidth(), h = img.getHeight();
        for (int z = 0; z < h; z++)
            for (int x = 0; x < w; x++) {
                int argb = img.getRGB(x, z);
                int alpha = (argb >>> 24) & 0xFF;
                int red = (argb >> 16) & 0xFF;
                target[z][x] = (alpha > 0) && (red < WATER_RED_THRESHOLD);
            }
    }

    private void parseTemperature(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        for (int z = 0; z < h; z++)
            for (int x = 0; x < w; x++) {
                int argb = img.getRGB(x, z);
                int idx = x * 3;
                temperatureMap[z][idx]     = (byte) ((argb >> 16) & 0xFF);
                temperatureMap[z][idx + 1] = (byte) ((argb >> 8) & 0xFF);
                temperatureMap[z][idx + 2] = (byte) (argb & 0xFF);
            }
    }

    // ═══════════════════════════════════════════════════════════════
    // ПРЕОБРАЗОВАНИЕ КООРДИНАТ (тороидальное)
    // ═══════════════════════════════════════════════════════════════

    private double toPixelXDouble(int worldX, int worldSize) {
        return ((worldX + worldSize / 2.0) / worldSize) * imageWidth;
    }
    private double toPixelZDouble(int worldZ, int worldSize) {
        return ((worldZ + worldSize / 2.0) / worldSize) * imageHeight;
    }
    private int toPixelX(int worldX, int worldSize) {
        int ix = (int) Math.floor(toPixelXDouble(worldX, worldSize));
        return ((ix % imageWidth) + imageWidth) % imageWidth;
    }
    private int toPixelZ(int worldZ, int worldSize) {
        int iz = (int) Math.floor(toPixelZDouble(worldZ, worldSize));
        return ((iz % imageHeight) + imageHeight) % imageHeight;
    }
    private int wrapX(int x) { return ((x % imageWidth) + imageWidth) % imageWidth; }
    private int wrapZ(int z) { return ((z % imageHeight) + imageHeight) % imageHeight; }

    /** Билинейная интерполяция 4 соседних пикселей. */
    private double bilinearSample(boolean[][] mask, double fx, double fz) {
        int x0 = (int) Math.floor(fx), z0 = (int) Math.floor(fz);
        int x1 = x0 + 1, z1 = z0 + 1;
        double tx = fx - x0, tz = fz - z0;
        x0 = wrapX(x0); x1 = wrapX(x1); z0 = wrapZ(z0); z1 = wrapZ(z1);
        double m00 = mask[z0][x0] ? 1.0 : 0.0;
        double m01 = mask[z0][x1] ? 1.0 : 0.0;
        double m10 = mask[z1][x0] ? 1.0 : 0.0;
        double m11 = mask[z1][x1] ? 1.0 : 0.0;
        double m0 = m00 + (m01 - m00) * tx;
        double m1 = m10 + (m11 - m10) * tx;
        return m0 + (m1 - m0) * tz;
    }

    // ═══════════════════════════════════════════════════════════════
    // МЕТОДЫ ЗАПРОСА
    // ═══════════════════════════════════════════════════════════════

    public boolean isLoaded() { return loaded; }
    public int getImageWidth() { return imageWidth; }
    public int getImageHeight() { return imageHeight; }
    public double getBlocksPerPixel(int worldSize) {
        return imageWidth > 0 ? worldSize / (double) imageWidth : 50.0;
    }

    // ─── Высота (билинейная) ────────────────────────────────────────

    public int getHeight(int worldX, int worldZ, int worldSize) {
        if (!loaded) return SEA_LEVEL;
        double fx = toPixelXDouble(worldX, worldSize);
        double fz = toPixelZDouble(worldZ, worldSize);
        int x0 = (int) Math.floor(fx), z0 = (int) Math.floor(fz);
        int x1 = x0 + 1, z1 = z0 + 1;
        double tx = fx - x0, tz = fz - z0;
        x0 = wrapX(x0); x1 = wrapX(x1); z0 = wrapZ(z0); z1 = wrapZ(z1);
        int h00 = heightMap[z0][x0] & 0xFF, h01 = heightMap[z0][x1] & 0xFF;
        int h10 = heightMap[z1][x0] & 0xFF, h11 = heightMap[z1][x1] & 0xFF;
        double h0 = h00 + (h01 - h00) * tx, h1 = h10 + (h11 - h10) * tx;
        double h = h0 + (h1 - h0) * tz;
        return MIN_Y + (int) ((h / 255.0) * (MAX_TERRAIN - MIN_Y));
    }

    // ─── Реки (билинейная интерполяция маски, БЕЗ кругов) ────────

    /**
     * Плавное значение принадлежности к реке [0..1].
     *  1.0 = центр русла, 0.0 = суша далеко от реки.
     * Билинейная интерполяция бинарной маски → плавные берега.
     */
    public double getRiverNess(int worldX, int worldZ, int worldSize) {
        if (!loaded) return 0.0;
        return bilinearSample(riversMask,
            toPixelXDouble(worldX, worldSize),
            toPixelZDouble(worldZ, worldSize));
    }

    /** Простой бинарный тест: в русле реки? */
    public boolean isRiver(int worldX, int worldZ, int worldSize) {
        return getRiverNess(worldX, worldZ, worldSize) >= RIVER_CHANNEL_THRESHOLD;
    }

    // ─── Озёра (билинейная интерполяция маски, БЕЗ кругов) ─────

    public double getLakeNess(int worldX, int worldZ, int worldSize) {
        if (!loaded) return 0.0;
        return bilinearSample(lakesMask,
            toPixelXDouble(worldX, worldSize),
            toPixelZDouble(worldZ, worldSize));
    }

    public boolean isLake(int worldX, int worldZ, int worldSize) {
        return getLakeNess(worldX, worldZ, worldSize) >= LAKE_CHANNEL_THRESHOLD;
    }

    // ─── Океан (билинейная маска) ─────────────────────────────────

    public double getOceanNess(int worldX, int worldZ, int worldSize) {
        if (!loaded) return 0.0;
        double fx = toPixelXDouble(worldX, worldSize);
        double fz = toPixelZDouble(worldZ, worldSize);
        int x0 = (int) Math.floor(fx), z0 = (int) Math.floor(fz);
        int x1 = x0 + 1, z1 = z0 + 1;
        double tx = fx - x0, tz = fz - z0;
        x0 = wrapX(x0); x1 = wrapX(x1); z0 = wrapZ(z0); z1 = wrapZ(z1);
        double m00 = (seaOceanMap[z0][x0] & 0xFF) < OCEAN_THRESHOLD ? 1.0 : 0.0;
        double m01 = (seaOceanMap[z0][x1] & 0xFF) < OCEAN_THRESHOLD ? 1.0 : 0.0;
        double m10 = (seaOceanMap[z1][x0] & 0xFF) < OCEAN_THRESHOLD ? 1.0 : 0.0;
        double m11 = (seaOceanMap[z1][x1] & 0xFF) < OCEAN_THRESHOLD ? 1.0 : 0.0;
        double m0 = m00 + (m01 - m00) * tx, m1 = m10 + (m11 - m10) * tx;
        return m0 + (m1 - m0) * tz;
    }

    public boolean isOcean(int worldX, int worldZ, int worldSize) {
        if (!loaded) return false;
        return getSeaOceanDepth(worldX, worldZ, worldSize) < OCEAN_THRESHOLD;
    }

    public boolean isDeepOcean(int worldX, int worldZ, int worldSize) {
        return getSeaOceanDepth(worldX, worldZ, worldSize) <= DEEP_OCEAN_THRESHOLD;
    }

    public int getSeaOceanDepth(int worldX, int worldZ, int worldSize) {
        if (!loaded) return 255;
        return seaOceanMap[toPixelZ(worldZ, worldSize)][toPixelX(worldX, worldSize)] & 0xFF;
    }

    // ─── Климат ─────────────────────────────────────────────────────

    public ClimateZone getClimateZone(int worldX, int worldZ, int worldSize) {
        if (!loaded) return ClimateZone.TEMPERATE;
        int px = toPixelX(worldX, worldSize), pz = toPixelZ(worldZ, worldSize);
        int idx = px * 3;
        return ClimateZone.fromRGB(
            temperatureMap[pz][idx] & 0xFF,
            temperatureMap[pz][idx + 1] & 0xFF,
            temperatureMap[pz][idx + 2] & 0xFF);
    }

    public float getTemperature(int worldX, int worldZ, int worldSize) {
        return getClimateZone(worldX, worldZ, worldSize).getTemperature();
    }

    public int getHumidity(int worldX, int worldZ, int worldSize) {
        if (!loaded) return 128;
        return humidityMap[toPixelZ(worldZ, worldSize)][toPixelX(worldX, worldSize)] & 0xFF;
    }

    public int getErosion(int worldX, int worldZ, int worldSize) {
        if (!loaded) return 128;
        return erosionMap[toPixelZ(worldZ, worldSize)][toPixelX(worldX, worldSize)] & 0xFF;
    }

    // ─── Нормализованные параметры для биома ─────────────────────

    public double getTemperatureNorm(int worldX, int worldZ, int worldSize) {
        return (getTemperature(worldX, worldZ, worldSize) - 0.5) * 2.0;
    }
    public double getHumidityNorm(int worldX, int worldZ, int worldSize) {
        return (getHumidity(worldX, worldZ, worldSize) / 255.0) * 2.0 - 1.0;
    }
    public double getContinentalness(int worldX, int worldZ, int worldSize) {
        double oceanNess = getOceanNess(worldX, worldZ, worldSize);
        double c = 0.6 - oceanNess * 1.8;
        return Math.max(-1.2, Math.min(1.0, c));
    }
    public double getErosionNorm(int worldX, int worldZ, int worldSize) {
        return (getErosion(worldX, worldZ, worldSize) / 255.0) * 2.0 - 1.0;
    }
    public double getWeirdness() { return 0.0; }
    public static double getDepthNorm(int y) {
        return ((y - MIN_Y) / (double) (MAX_Y - MIN_Y)) * 2.0 - 1.0;
    }
}
