package com.continentgen.map;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Singleton lazy-loading wrapper for MapData.
 * Loads the 7 PNG maps from the Minecraft game directory on first access.
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
            // Use the game run directory (where the Minecraft process was launched)
            Path runDir = Paths.get("").toAbsolutePath();
            java.util.logging.Logger.getLogger("ContinentGen")
                .info("Looking for map PNGs in: " + runDir);
            mapData.load(runDir);
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
