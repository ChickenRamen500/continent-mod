package com.continentgen.client;

import com.continentgen.map.MapData;
import com.continentgen.world.ContinentChunkGenerator;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.world.CreateWorldScreen;
import net.minecraft.client.gui.screen.world.LevelScreenProvider;
import net.minecraft.client.world.GeneratorOptionsHolder;
import net.minecraft.world.gen.chunk.ChunkGenerator;

/**
 * LevelScreenProvider implementation that opens the ContinentCustomizeScreen
 * when the user clicks "Customize" on the Continent world preset.
 *
 * Vanilla's CreateWorldScreen.WorldTab shows the "Customize" button next to
 * the world type dropdown whenever a LevelScreenProvider is registered in
 * LevelScreenProvider.WORLD_PRESET_TO_SCREEN_PROVIDER for the currently
 * selected world preset's registry key.
 *
 * We register an instance of this class in ContinentGenModClient.
 */
public class ContinentLevelScreenProvider implements LevelScreenProvider {

    @Override
    public Screen createEditScreen(CreateWorldScreen parent,
                                   GeneratorOptionsHolder generatorOptionsHolder) {
        // Read the current world_size from the existing ChunkGenerator
        int currentSize = MapData.DEFAULT_WORLD_SIZE;
        try {
            ChunkGenerator gen = generatorOptionsHolder
                .selectedDimensions().getChunkGenerator();
            if (gen instanceof ContinentChunkGenerator) {
                currentSize = ((ContinentChunkGenerator) gen).getWorldSize();
            }
        } catch (Throwable t) {
            // ignore — use default
        }

        return new ContinentCustomizeScreen(parent, generatorOptionsHolder, currentSize);
    }
}
