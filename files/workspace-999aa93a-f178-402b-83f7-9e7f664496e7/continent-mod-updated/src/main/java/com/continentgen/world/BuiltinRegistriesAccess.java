package com.continentgen.world;

import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.Identifier;
import net.minecraft.registry.BuiltinRegistries;
import net.minecraft.world.biome.Biome;

/**
 * Helper for safe access to BuiltinRegistries in Yarn 1.20.1.
 *
 * BuiltinRegistries: net.minecraft.registry.BuiltinRegistries
 * getWrapperOrThrow returns raw RegistryWrapper.Impl — must cast to RegistryWrapper<Biome>
 * getOrThrow is a default method on RegistryEntryLookup (parent of RegistryWrapper)
 */
public final class BuiltinRegistriesAccess {
    private BuiltinRegistriesAccess() {}

    @SuppressWarnings("unchecked")
    private static RegistryWrapper<Biome> getBiomeWrapper() {
        return (RegistryWrapper<Biome>) BuiltinRegistries.createWrapperLookup()
            .getWrapperOrThrow(RegistryKeys.BIOME);
    }

    public static RegistryEntry<Biome> getBiomeEntry(RegistryKey<Biome> key) {
        try {
            return getBiomeWrapper().getOrThrow(key);
        } catch (Exception e) {
            return null;
        }
    }
}
