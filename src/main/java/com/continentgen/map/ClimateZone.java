package com.continentgen.map;

/**
 * Enum representing the 6 climate zones defined by temperature.png color palette.
 * Each zone has a strict hex color for matching and an associated temperature value.
 */
public enum ClimateZone {
    ARCTIC(0x7F8B99, 0.05f),
    SUBARCTIC(0x88B4D7, 0.25f),
    TEMPERATE(0x6CC889, 0.50f),
    TROPICAL(0xF7FB83, 0.75f),
    SUBEQUATORIAL(0xE7BD83, 0.90f),
    EQUATORIAL(0xCE8284, 1.00f);

    private final int color;
    private final float temperature;

    // Pre-extracted RGB components for fast comparison
    private final int r;
    private final int g;
    private final int b;

    ClimateZone(int color, float temperature) {
        this.color = color;
        this.temperature = temperature;
        this.r = (color >> 16) & 0xFF;
        this.g = (color >> 8) & 0xFF;
        this.b = color & 0xFF;
    }

    public int getColor() {
        return color;
    }

    public float getTemperature() {
        return temperature;
    }

    public int getR() { return r; }
    public int getG() { return g; }
    public int getB() { return b; }

    /**
     * Finds the closest climate zone to the given RGB color
     * using Euclidean distance in RGB space.
     *
     * @param r red component (0-255)
     * @param g green component (0-255)
     * @param b blue component (0-255)
     * @return the closest ClimateZone
     */
    public static ClimateZone fromRGB(int r, int g, int b) {
        ClimateZone closest = ARCTIC;
        double minDist = Double.MAX_VALUE;

        for (ClimateZone zone : values()) {
            double dr = zone.r - r;
            double dg = zone.g - g;
            double db = zone.b - b;
            double dist = dr * dr + dg * dg + db * db;
            if (dist < minDist) {
                minDist = dist;
                closest = zone;
            }
        }
        return closest;
    }
}
