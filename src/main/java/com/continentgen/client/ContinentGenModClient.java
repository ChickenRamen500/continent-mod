package com.continentgen.client;

import com.continentgen.ContinentGenMod;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.screen.world.LevelScreenProvider;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.world.gen.WorldPreset;

import java.lang.reflect.Field;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Client-side entry point for the Continent Generator mod.
 *
 * ─────────────────────────────────────────────────────────────────────────
 * WHAT IT DOES:
 *   Registers our ContinentLevelScreenProvider so the "Customize" button
 *   appears next to the "World Type" dropdown in Create New World → World
 *   tab when the "Continents" world type is selected — exactly like
 *   vanilla's "Superflat" and "Single Biome" types.
 *
 *   Clicking the button opens ContinentCustomizeScreen, where the user
 *   types ANY world size in blocks (default 300000). The PNG map is
 *   always 3000×3000 px, so blocksPerPixel = worldSize / 3000.
 *
 * ─────────────────────────────────────────────────────────────────────────
 * REGISTRATION STRATEGY (multi-layer, redundant):
 *
 *   Layer 1  — LevelScreenProviderMixin  (PRIMARY)
 *      A Mixin injects at the TAIL of LevelScreenProvider.<clinit>, the
 *      very first moment the immutable Map.of(...) is assigned, and
 *      replaces it with a mutable HashMap containing our entry. This
 *      runs BEFORE the JIT can inline the static final field value,
 *      which was the reason the previous Unsafe-only approach silently
 *      failed (the WorldTab still saw the OLD immutable map).
 *
 *   Layer 2  — onInitializeClient()      (DEFENSIVE BACKUP)
 *      If for some reason the Mixin did not apply (e.g. mixin config
 *      missing from fabric.mod.json, or the <clinit> injection was
 *      skipped), we try again here with three strategies in order:
 *         a) VarHandle via privateLookupIn  (modern, JDK 9+)
 *         b) sun.misc.Unsafe.putObject      (legacy, but powerful)
 *         c) Field.set after clearing FINAL (best-effort)
 *
 *   Both layers are idempotent: replacing the field with a HashMap
 *   that already contains our entry is harmless.
 * ─────────────────────────────────────────────────────────────────────────
 */
@Environment(EnvType.CLIENT)
public class ContinentGenModClient implements ClientModInitializer {

    /** The single world preset registry key we register. */
    public static final RegistryKey<WorldPreset> CONTINENT_PRESET_KEY =
        RegistryKey.of(RegistryKeys.WORLD_PRESET,
            Identifier.of(ContinentGenMod.MOD_ID, "continent"));

    @Override
    public void onInitializeClient() {
        ContinentGenMod.LOGGER.info(
            "[ContinentGen-Client] onInitializeClient — verifying Customize "
            + "button registration (the Mixin should have already done it).");
        registerCustomizeScreenProvider();
    }

    /**
     * Ensure our provider is in the WORLD_PRESET_TO_SCREEN_PROVIDER map.
     * This is the DEFENSIVE backup; the primary registration is done by
     * LevelScreenProviderMixin at class-load time.
     */
    @SuppressWarnings({"unchecked", "deprecation"})
    private void registerCustomizeScreenProvider() {
        ContinentLevelScreenProvider provider = new ContinentLevelScreenProvider();
        Optional<RegistryKey<WorldPreset>> key = Optional.of(CONTINENT_PRESET_KEY);

        // ─── Step 0: check if the Mixin already added our entry ───────
        try {
            Object current = LevelScreenProvider.WORLD_PRESET_TO_SCREEN_PROVIDER;
            if (current instanceof Map) {
                Map<Object, Object> map = (Map<Object, Object>) current;
                if (map.containsKey(key)) {
                    ContinentGenMod.LOGGER.info(
                        "[ContinentGen-Client] ✅ Customize provider already registered "
                        + "(likely by Mixin). Map class: " + current.getClass().getName()
                        + ", size: " + map.size());
                    return;
                }
                // Map exists but doesn't have our entry — try direct .put()
                // (will succeed if the Mixin replaced it with a HashMap).
                try {
                    map.put(key, provider);
                    ContinentGenMod.LOGGER.info(
                        "[ContinentGen-Client] ✅ Added provider via direct .put() on "
                        + current.getClass().getSimpleName() + ".");
                    return;
                } catch (UnsupportedOperationException e) {
                    ContinentGenMod.LOGGER.warn(
                        "[ContinentGen-Client] Map is still immutable ("
                        + current.getClass().getName() + ") — trying reflection.");
                }
            }
        } catch (Throwable t) {
            ContinentGenMod.LOGGER.warn(
                "[ContinentGen-Client] Step 0 check failed: " + t);
        }

        // ─── Step 1: reflection — replace the field value ─────────────
        try {
            Field mapField = LevelScreenProvider.class
                .getDeclaredField("WORLD_PRESET_TO_SCREEN_PROVIDER");
            mapField.setAccessible(true);
            Object currentValue = mapField.get(null);

            Map<Object, Object> newMap = new HashMap<>();
            if (currentValue instanceof Map) {
                newMap.putAll((Map<Object, Object>) currentValue);
            }
            newMap.put(key, provider);

            // Try strategies in order.
            if (tryReplaceViaVarHandle(mapField, newMap)) {
                ContinentGenMod.LOGGER.info(
                    "[ContinentGen-Client] ✅ Field replaced via VarHandle.");
                return;
            }
            if (tryReplaceViaUnsafe(mapField, newMap)) {
                ContinentGenMod.LOGGER.info(
                    "[ContinentGen-Client] ✅ Field replaced via Unsafe.");
                return;
            }
            if (tryReplaceViaReflection(mapField, newMap)) {
                ContinentGenMod.LOGGER.info(
                    "[ContinentGen-Client] ✅ Field replaced via Field.set.");
                return;
            }
            ContinentGenMod.LOGGER.error(
                "[ContinentGen-Client] ❌ All replacement strategies failed.");
        } catch (Throwable t) {
            ContinentGenMod.LOGGER.error(
                "[ContinentGen-Client] Reflection failed: " + t, t);
        }
    }

    private static boolean tryReplaceViaVarHandle(Field field, Object newValue) {
        try {
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(
                LevelScreenProvider.class, MethodHandles.lookup());
            VarHandle vh = lookup.findStaticVarHandle(
                LevelScreenProvider.class,
                "WORLD_PRESET_TO_SCREEN_PROVIDER",
                Map.class);
            vh.set(newValue);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    private static boolean tryReplaceViaUnsafe(Field field, Object newValue) {
        try {
            sun.misc.Unsafe unsafe = getUnsafe();
            if (unsafe == null) return false;
            Object base = unsafe.staticFieldBase(field);
            long offset = unsafe.staticFieldOffset(field);
            unsafe.putObject(base, offset, newValue);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean tryReplaceViaReflection(Field field, Object newValue) {
        try {
            try {
                MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(
                    Field.class, MethodHandles.lookup());
                VarHandle modsVh = lookup.findVarHandle(Field.class, "modifiers", int.class);
                int oldMods = (int) modsVh.get(field);
                modsVh.set(field, oldMods & ~Modifier.FINAL);
            } catch (Throwable t) {
                Field modsField = Field.class.getDeclaredField("modifiers");
                modsField.setAccessible(true);
                int oldMods = modsField.getInt(field);
                modsField.setInt(field, oldMods & ~Modifier.FINAL);
            }
            field.set(null, newValue);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static sun.misc.Unsafe getUnsafe() {
        try {
            Field theUnsafe = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            return (sun.misc.Unsafe) theUnsafe.get(null);
        } catch (Throwable t) {
            return null;
        }
    }
}
