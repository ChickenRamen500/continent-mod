package com.continentgen.map;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Singleton lazy-loading wrapper for MapData.
 * Loads the 7 PNG maps from mods/continent-mod-maps/ directory.
 */
public class MapLoader {
    private static MapLoader instance;
    private MapData mapData;
    private boolean initialized = false;

    private MapLoader() {}

    public static MapLoader getInstance() {
        if (instance == null) {
            instance = new MapLoader();
        }
        return instance;
    }

    /**
     * Ensures maps are loaded. Returns the MapData instance.
     * If maps cannot be loaded, returns MapData with loaded=false (generates default world).
     */
    public synchronized MapData getMapData() {
        if (!initialized) {
            initialized = true;
            mapData = new MapData();
            // Карты лежат в папке mods/continent-mod-maps/
            Path runDir = Paths.get("").toAbsolutePath();
            Path mapsDir = runDir.resolve("mods").resolve("continent-mod-maps");
            java.util.logging.Logger.getLogger("ContinentGen")
                .info("Looking for map PNGs in: " + mapsDir);
            mapData.load(mapsDir);
        }
        return mapData;
    }

    /**
     * Force reload maps (e.g., after hot-reload).
     */
    public synchronized void reload() {
        initialized = false;
        mapData = null;
        getMapData();
    }
}
