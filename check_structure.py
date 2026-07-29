#!/usr/bin/env python3
"""
check_structure.py — Validates the continent-mod project folder structure.

Run from the project root (where src/ exists).
Usage:  python check_structure.py [--fix]

Checks:
  - Required Java source files exist
  - Required resource files exist
  - No FORBIDDEN files present (e.g., mixin/LevelScreenProviderMixin.java)
  - Maps folder (optional, warns if missing)
  - check_structure.py itself exists in root
  - map_locator.py exists in root

Exit codes:
  0 = all OK
  1 = missing required files
  2 = forbidden files present
"""

import os
import sys

# ─── Expected structure ────────────────────────────────────────────

REQUIRED_JAVA = {
    "src/main/java/com/continentgen/ContinentGenMod.java",
    "src/main/java/com/continentgen/client/ContinentCustomizeScreen.java",
    "src/main/java/com/continentgen/client/ContinentLevelScreenProvider.java",
    "src/main/java/com/continentgen/client/ContinentGenModClient.java",
    "src/main/java/com/continentgen/map/ClimateZone.java",
    "src/main/java/com/continentgen/map/MapLoader.java",
    "src/main/java/com/continentgen/map/MapData.java",
    "src/main/java/com/continentgen/map/BiomeParameters.java",
    "src/main/java/com/continentgen/world/ContinentChunkGenerator.java",
    "src/main/java/com/continentgen/world/ContinentBiomeSource.java",
    "src/main/java/com/continentgen/world/BuiltinRegistriesAccess.java",
    "src/main/java/com/continentgen/world/WorldBorderHandler.java",
}

REQUIRED_RESOURCES = {
    "src/main/resources/continentgen.mixins.json",
    "src/main/resources/fabric.mod.json",
    "src/main/resources/data/continentgen/worldgen/world_preset/continent.json",
    "src/main/resources/data/minecraft/tags/worldgen/world_preset/normal.json",
    "src/main/resources/data/minecraft/worldgen/world_preset/continent.json",
    "src/main/resources/assets/continentgen/lang/en_us.json",
    "src/main/resources/assets/continentgen/lang/ru_ru.json",
}

REQUIRED_ROOT = {
    "build.gradle",
    "settings.gradle",
    "gradle.properties",
}

OPTIONAL_ROOT = {
    "check_structure.py",
    "map_locator.py",
}

# ─── FORBIDDEN files (must NOT exist) ────────────────────────────

# Pattern: any file in src/main/java/com/continentgen/mixin/
FORBIDDEN_PATTERNS = [
    "src/main/java/com/continentgen/mixin/",
]

# ─── Checks ───────────────────────────────────────────────────────

errors = 0
warnings = 0

def check(label, path, required=True):
    global errors, warnings
    if os.path.exists(path):
        print(f"  ✅ {label}: {path}")
    else:
        if required:
            print(f"  ❌ MISSING (required): {path}")
            errors += 1
        else:
            print(f"  ⚠️  MISSING (optional): {path}")
            warnings += 1

def check_forbidden(label, path):
    global errors
    if os.path.exists(path):
        print(f"  ❌ FORBIDDEN file present: {path}")
        errors += 1
    else:
        print(f"  ✅ {label}: not present (OK)")

# ─── Main ──────────────────────────────────────────────────────────

print("=" * 60)
print("continent-mod — Structure Validation")
print("=" * 60)

print("\n📁 Required Java source files:")
for f in sorted(REQUIRED_JAVA):
    check(f, f)

print("\n📁 Required resource files:")
for f in sorted(REQUIRED_RESOURCES):
    check(f, f)

print("\n📁 Required root files:")
for f in sorted(REQUIRED_ROOT):
    check(f, f)

print("\n📁 Optional root files:")
for f in sorted(OPTIONAL_ROOT):
    check(f, f, required=False)

print("\n📁 Optional maps/ folder:")
if os.path.isdir("maps"):
    map_count = len([f for f in os.listdir("maps") if f.endswith(".png")])
    print(f"  ✅ maps/ folder exists ({map_count} PNG files)")
else:
    print("  ⚠️  maps/ folder not found (place 7 PNG maps here when testing)")
    warnings += 1

print("\n🚫 Forbidden files check:")
for pattern in FORBIDDEN_PATTERNS:
    if pattern.startswith("src/main/java/com/continentgen/mixin/"):
        # Check the whole mixin directory — should not exist
        mixin_dir = "src/main/java/com/continentgen/mixin"
        check_forbidden("mixin/ directory", mixin_dir)

# ─── mixin.json client list check ──────────────────────────────────
mixin_json = "src/main/resources/continentgen.mixins.json"
if os.path.exists(mixin_json):
    import json
    with open(mixin_json, "r") as mf:
        data = json.load(mf)
    client_list = data.get("client", [])
    if client_list:
        print(f"\n  ❌ continentgen.mixins.json has non-empty client list: {client_list}")
        errors += 1
    else:
        print(f"\n  ✅ continentgen.mixins.json client list is empty (OK)")

# ─── MapData API check ───────────────────────────────────────────
mapdata = "src/main/java/com/continentgen/map/MapData.java"
REQUIRED_MAPDATA_METHODS = [
    "getContinentalness",
    "getTemperatureNorm",
    "getHumidityNorm",
    "getErosionNorm",
    "getWeirdness",
    "getDepthNorm",
    "getOceanNess",
    "isOcean",
    "isRiver",
    "isLake",
    "getHeight",
]
if os.path.exists(mapdata):
    with open(mapdata, "r") as f:
        content = f.read()
    for method in REQUIRED_MAPDATA_METHODS:
        if f"{method}(" in content:
            print(f"  ✅ MapData.{method}() exists")
        else:
            print(f"  ❌ MapData.{method}() MISSING")
            errors += 1

# ─── Summary ──────────────────────────────────────────────────────
print("\n" + "=" * 60)
if errors == 0 and warnings == 0:
    print("✅ All checks passed!")
    sys.exit(0)
elif errors == 0:
    print(f"⚠️  {warnings} warning(s), no errors.")
    sys.exit(0)
else:
    print(f"❌ {errors} error(s), {warnings} warning(s).")
    if errors == 2:
        print("   Exit code 2 = forbidden files present")
    else:
        print("   Exit code 1 = missing required files")
    sys.exit(min(errors, 2))
