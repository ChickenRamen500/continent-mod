"""
map_locator.py — Показывает позицию игрока на КАРТЕ БИОМОВ (создаётся автоматически).

Использование:
    python map_locator.py

При запуске скрипт:
    1. Загружает 7 PNG карт (height, humidity, temperature, sea-ocean,
       map_rivers, map_lakes) из текущей директории (или указанной --maps-dir).
       Имена файлов СООТВЕТСТВУЮТ Java-коду мода:
         height.png, temperature.png, humidity.png, erosion.png,
         map_sea-ocean.png, map_rivers.png, map_lakes.png
    v4 (July 2026): океан определяется МАСКОЙ map_sea-ocean.png (не по высоте),
    биомы подбираются по параметрам T/H/C/E/W (nearest-point matching).
    2. Генерирует карту биомов biome_map.png (алгоритм Qwen).
    3. Сохраняет оригинал для последующего затирания старого маркера.
    4. Открывает интерактивный режим: вводите "X Z", скрипт:
       - Переводит координаты в пиксели
       - Выводит в консоль ПОДРОБНУЮ информацию:
           * высота (gray + Y)
           * влажность (значение + сухо/влажно)
           * находимся ли мы в воде (океан/глубокий океан/река/озеро)
           * климат (зона + температура + RGB + расстояние)
           * предполагаемый биом
           * эрозия
           * координаты пикселя и чанка
       - Рисует маркер на карте biome_map.png
       - Старый маркер затирается данными из оригинала

Параметры:
    --maps-dir DIR    Папка с PNG картами (по умолчанию авто-поиск)
    --output PATH     Файл для вывода карты с маркером (по умолчанию map_located.png)
    --regenerate      Перегенерировать карту биомов при каждом запуске
    --biome-map PATH  Файл для сохранения карты биомов (по умолчанию biome_map.png)
    --no-marker        Не рисовать маркер, только вывести информацию

Требования:
    pip install Pillow numpy
"""

import sys
import os
import argparse
import numpy as np
from PIL import Image, ImageDraw, ImageFont

# ========================================================
# КОНСТАНТЫ МИРА (совпадают с Java MapData.java)
# ========================================================
WORLD_SIZE = 150_000        # Default world size (1px = 50 blocks at 3000px map)
HALF_WORLD = 75_000
SEA_LEVEL = 63              # v3: matches MapData.SEA_LEVEL (was 62)
MIN_Y = -64
MAX_Y = 320

# ─── Диапазон высот рельефа (v3, July 2026 — совпадает с MapData.java) ───
# height.png — карта высот ВСЕГО мира:
#   gray   0 (чёрный)  = самая глубокая точка океана (Марианская впадина)
#   gray 255 (белый)   = самая высокая точка гор (Гималаи)
# Кусочно-линейная формула:
#   gray 0..72   → Y  -30..63    (океан, 93 блока диапазона)
#   gray 72..255 → Y   63..200   (суша, 137 блоков диапазона)
# Порог 72 эмпирически выведен из height.png: 90% совпадения с sea-ocean.png.
MIN_OCEAN = -30             # самая глубокая точка океана
MAX_TERRAIN = 200           # самая высокая точка суши
SEA_LEVEL_GRAY = 72         # gray, при котором Y пересекает уровень моря
DEEP_OCEAN_Y = 25           # Y ниже этого = глубокий океан

# Устаревшие пороги (только для F3-отладки / совместимости со старым sea-ocean.png)
OCEAN_THRESHOLD = 255       # <255 = океан (sea-ocean.png)
DEEP_OCEAN_THRESHOLD = 80   # <=80 = глубокий океан (sea-ocean.png)
WATER_RED_THRESHOLD = 128   # red<128 AND alpha>0 = вода (реки/озёра)
OCEAN_MAX_DEPTH = 60

# ========================================================
# ЦВЕТА БИОМОВ (как в скрипте Qwen)
# ========================================================
BIOME_COLORS = {
    'deep_ocean':     (0, 0, 80),
    'ocean':          (0, 0, 180),
    'river':          (0, 150, 255),
    'lake':           (50, 200, 200),
    'snowy_plains':   (230, 230, 230),
    'snowy_slopes':   (190, 200, 210),
    'taiga':          (10, 80, 80),
    'snowy_taiga':    (170, 190, 180),
    'plains':         (140, 180, 90),
    'forest':         (60, 130, 50),
    'savanna':        (180, 170, 70),
    'jungle':         (70, 120, 10),
    'badlands':       (200, 70, 30),
    'mangrove_swamp': (50, 110, 60),
    'desert':         (240, 150, 20),
    'bamboo_jungle':  (80, 150, 30),
    'frozen_ocean':   (160, 200, 255),  # дополнительный
    'swamp':          (100, 120, 60),   # дополнительный
}

# Климатические зоны (RGB) — совпадают с ClimateZone.java
CLIMATE_ZONES = {
    'ARCTIC':         np.array([0x7F, 0x8B, 0x99]),
    'SUBARCTIC':      np.array([0x88, 0xB4, 0xD7]),
    'TEMPERATE':      np.array([0x6C, 0xC8, 0x89]),
    'TROPICAL':       np.array([0xF7, 0xFB, 0x83]),
    'SUBEQUATORIAL':  np.array([0xE7, 0xBD, 0x83]),
    'EQUATORIAL':     np.array([0xCE, 0x82, 0x84]),
}

# Температура для каждого климата — совпадает с ClimateZone.java
CLIMATE_TEMPS = {
    'ARCTIC': 0.05, 'SUBARCTIC': 0.25, 'TEMPERATE': 0.50,
    'TROPICAL': 0.75, 'SUBEQUATORIAL': 0.90, 'EQUATORIAL': 1.00,
}

# ========================================================
# НАСТРОЙКИ МАРКЕРА
# ========================================================
MARKER_SIZE = 14
CROSSHAIR_SIZE = 22
MARKER_COLOR_OUTER = (255, 0, 255)   # Magenta
MARKER_COLOR_INNER = (255, 255, 255)  # Белый
MARKER_TEXT = "P"

# Имена файлов — СОВПАДАЮТ с Java MapData.java / MapLoader.java
DEFAULT_MAP_FILES = {
    'height':     'height.png',
    'humidity':   'humidity.png',
    'temperature':'temperature.png',
    'erosion':    'erosion.png',
    'sea_ocean':  'map_sea-ocean.png',  # v4: LA маска (alpha>0 = океан)
    'rivers':     'map_rivers.png',   # <-- было rivers.png, исправлено!
    'lakes':      'map_lakes.png',    # <-- было lakes.png, исправлено!
}


def find_maps_dir(maps_dir=None):
    """Найти папку с PNG картами."""
    candidates = []
    if maps_dir:
        candidates.append(maps_dir)
    candidates.append('.')
    candidates.append(os.path.dirname(os.path.abspath(__file__)) or '.')

    # Стандартные пути Minecraft
    if sys.platform == "win32":
        appdata = os.environ.get("APPDATA", "")
        if appdata:
            candidates.append(os.path.join(appdata, ".tlauncher", "minecraft"))
            candidates.append(os.path.join(appdata, ".minecraft"))
        localappdata = os.environ.get("LOCALAPPDATA", "")
        if localappdata:
            candidates.append(os.path.join(localappdata, ".minecraft"))
    else:
        home = os.path.expanduser("~")
        candidates.append(home)

    for d in candidates:
        if os.path.isdir(d) and os.path.exists(os.path.join(d, DEFAULT_MAP_FILES['height'])):
            return d
    return None


def load_maps(maps_dir):
    """Загрузить все 7 PNG карт. Возвращает словарь numpy массивов."""
    maps = {}
    print(f"[*] Загрузка карт из: {maps_dir}")
    print(f"    Ожидаемые файлы: {list(DEFAULT_MAP_FILES.values())}")

    # Сначала покажем все файлы в директории
    try:
        all_files = sorted(os.listdir(maps_dir))
        png_files = [f for f in all_files if f.lower().endswith('.png')]
        print(f"    PNG файлы в директории ({len(png_files)}):")
        for f in png_files:
            size = os.path.getsize(os.path.join(maps_dir, f))
            print(f"      {f} ({size} bytes)")
    except Exception as e:
        print(f"    [!] Не удалось перечислить файлы: {e}")

    for key, fname in DEFAULT_MAP_FILES.items():
        path = os.path.join(maps_dir, fname)
        if not os.path.exists(path):
            print(f"    [!] НЕ НАЙДЕН: {fname}")
            return None
        try:
            if key == 'temperature':
                img = Image.open(path).convert('RGB')
                maps[key] = np.array(img)
            elif key in ('rivers', 'lakes'):
                # rivers/lakes — RGBA, используем alpha channel
                img = Image.open(path).convert('RGBA')
                maps[key] = np.array(img)
            elif key == 'sea_ocean':
                # v4: sea_ocean — LA или RGBA маска (alpha>0 = океан)
                img = Image.open(path).convert('RGBA')
                maps[key] = np.array(img)
            else:
                img = Image.open(path).convert('L')
                maps[key] = np.array(img)
            print(f"    OK {fname}: shape={maps[key].shape} dtype={maps[key].dtype}")
        except Exception as e:
            print(f"    [!] Ошибка загрузки {fname}: {e}")
            return None

    return maps


def detect_climate(rgb):
    """Найти ближайшую климатическую зону к RGB цвету."""
    best = None
    best_dist = float('inf')
    for name, color in CLIMATE_ZONES.items():
        d = np.sqrt(np.sum((np.array(rgb).astype(int) - color) ** 2))
        if d < best_dist:
            best_dist = d
            best = name
    return best, best_dist


def generate_biome_map(maps):
    """Сгенерировать карту биомов (RGB numpy array).

    v4 (July 2026):
      - Океан определяется МАСКОЙ map_sea-ocean.png (alpha>0 = океан).
      - Глубокий океан: ocean mask И height gray < 42 (Y < 25).
      - Биомы суши: nearest-point matching по параметрам T/H/C/E/W
        (совпадает с Java BiomeParameters.java).
    """
    print("[*] Генерация карты биомов (v4 — mask-driven ocean + multi-noise biomes)...")

    height_img = maps['height']
    humidity_img = maps['humidity']
    erosion_img = maps['erosion']
    temp_img = maps['temperature']
    lakes_data = maps['lakes']
    rivers_data = maps['rivers']
    sea_ocean_data = maps['sea_ocean']

    H, W = height_img.shape
    biome_map = np.zeros((H, W, 3), dtype=np.uint8)

    # ─── Маски воды (v4: ocean from mask) ───
    # Ocean mask: alpha > 0 AND luminance < 128 (LA format)
    ocean_mask = (sea_ocean_data[:, :, 3] > 0) & (sea_ocean_data[:, :, 0] < WATER_RED_THRESHOLD)
    # Deep ocean: ocean AND height < deep_ocean_gray (Y < 25)
    deep_ocean_gray = int(round((DEEP_OCEAN_Y - MIN_OCEAN) * SEA_LEVEL_GRAY / (SEA_LEVEL - MIN_OCEAN)))
    deep_ocean_mask = ocean_mask & (height_img < deep_ocean_gray)
    shallow_ocean_mask = ocean_mask & ~deep_ocean_mask

    # rivers/lakes: alpha > 0 AND red < 128
    lakes_mask = (lakes_data[:, :, 3] > 0) & (lakes_data[:, :, 0] < WATER_RED_THRESHOLD)
    rivers_mask = (rivers_data[:, :, 3] > 0) & (rivers_data[:, :, 0] < WATER_RED_THRESHOLD)

    water_mask = ocean_mask | lakes_mask | rivers_mask
    land_mask = ~water_mask

    # Заполняем воду
    biome_map[deep_ocean_mask] = BIOME_COLORS['deep_ocean']
    biome_map[shallow_ocean_mask] = BIOME_COLORS['ocean']
    biome_map[lakes_mask] = BIOME_COLORS['lake']
    biome_map[rivers_mask] = BIOME_COLORS['river']

    # ─── Параметры суши для biome matching ───
    # Temperature normalized: ClimateZone.temp (0.05..1.0) → (-1.0, 1.0)
    def get_climate(temp_rgb):
        best = None
        best_dist = float('inf')
        for name, color in CLIMATE_ZONES.items():
            d = np.sqrt(np.sum((np.array(temp_rgb).astype(int) - color) ** 2))
            if d < best_dist:
                best_dist = d
                best = name
        return best

    temp_norm_grid = np.zeros((H, W), dtype=np.float32)
    climate_grid = np.empty((H, W), dtype='U20')
    for z in range(H):
        for x in range(W):
            climate = get_climate(temp_img[z, x])
            climate_grid[z, x] = climate
            temp_norm_grid[z, x] = (CLIMATE_TEMPS[climate] - 0.5) * 2.0

    # Humidity normalized: 0..255 → (-1.0, 1.0)
    hum_norm_grid = (humidity_img.astype(np.float32) / 255.0) * 2.0 - 1.0

    # Erosion normalized: 0..255 → (-1.0, 1.0)
    ero_norm_grid = (erosion_img.astype(np.float32) / 255.0) * 2.0 - 1.0

    # Continentalness: from ocean mask proximity
    # Ocean pixel → C ≈ -1.0, Land far from coast → C ≈ 0.6
    from scipy.ndimage import gaussian_filter
    ocean_float = ocean_mask.astype(np.float32)
    ocean_smooth = gaussian_filter(ocean_float, sigma=2)
    c_grid = 0.6 - ocean_smooth * 1.8
    c_grid = np.clip(c_grid, -1.2, 1.0)

    # ─── Biome parameter table (matches BiomeParameters.java) ───
    BIOME_PARAMS = [
        # (name, T, H, C, E, W)
        ('plains',            0.3,  0.0,  0.3,  0.5,  0.0),
        ('sunflower_plains',  0.3,  0.0,  0.3,  0.5,  1.0),
        ('forest',           0.4,  0.6,  0.3,  0.0,  0.0),
        ('flower_forest',    0.4,  0.6,  0.3,  0.0,  1.0),
        ('birch_forest',      0.6,  0.6,  0.3,  0.0,  0.0),
        ('dark_forest',       0.7,  0.8,  0.3,  0.0,  0.0),
        ('taiga',           -0.2,  0.3,  0.3,  0.0,  0.0),
        ('snowy_taiga',      -0.3,  0.4,  0.3,  0.3,  0.0),
        ('old_growth_spruce_taiga', -0.3, 0.8, 0.3, 0.3, 0.0),
        ('old_growth_pine_taiga',   -0.3, 0.8, 0.3, 0.3, 0.0),
        ('snowy_plains',     -0.5,  0.0,  0.3,  0.5,  0.0),
        ('ice_spikes',       -0.5,  0.0,  0.3,  0.5,  1.0),
        ('grove',           -0.2,  0.8,  0.3, -0.3,  0.0),
        ('meadow',            0.5,  0.8,  0.3,  0.0,  0.0),
        ('jagged_peaks',     -0.7,  0.9,  0.6, -1.0,  0.0),
        ('frozen_peaks',     -0.7,  0.9,  0.6, -1.0,  0.0),
        ('stony_peaks',       1.0,  0.3,  0.6, -1.0,  0.0),
        ('snowy_slopes',     -0.3,  0.8,  0.3, -0.5,  0.0),
        ('savanna',          0.8, -0.3,  0.3,  0.3,  0.0),
        ('savanna_plateau',  0.8, -0.3,  0.6,  0.5,  0.0),
        ('windswept_savanna', 1.0, -0.3,  0.3, -0.5,  0.0),
        ('desert',            1.0, -0.5,  0.3,  0.8,  0.0),
        ('badlands',          1.0, -0.5,  0.6, -0.5,  0.0),
        ('wooded_badlands',    1.0, -0.3,  0.6, -0.5,  0.0),
        ('jungle',            0.9,  0.9,  0.3,  0.0,  0.0),
        ('sparse_jungle',      0.9,  0.9,  0.3,  0.0,  1.0),
        ('bamboo_jungle',     0.9,  0.9,  0.3,  0.0,  0.5),
        ('mangrove_swamp',    0.8,  0.9, -0.1,  0.0,  0.5),
        ('windswept_hills',   0.2,  0.0,  0.3, -0.5,  0.0),
        ('windswept_forest',  0.2,  0.3,  0.3, -0.5,  0.0),
        ('swamp',             0.8,  0.9, -0.1,  0.0,  0.0),
        ('cherry_grove',      0.5,  0.8,  0.3,  0.0,  1.0),
    ]
    # Pre-convert to numpy arrays for vectorized distance computation
    bp_t  = np.array([p[1] for p in BIOME_PARAMS], dtype=np.float32)
    bp_h  = np.array([p[2] for p in BIOME_PARAMS], dtype=np.float32)
    bp_c  = np.array([p[3] for p in BIOME_PARAMS], dtype=np.float32)
    bp_e  = np.array([p[4] for p in BIOME_PARAMS], dtype=np.float32)
    bp_w  = np.array([p[5] for p in BIOME_PARAMS], dtype=np.float32)
    bp_names = [p[0] for p in BIOME_PARAMS]

    # Vectorized nearest-point matching for all land pixels
    print("    Calculating biome parameters (vectorized)...")
    land_y, land_x = np.where(land_mask)
    if len(land_y) > 0:
        t_vals = temp_norm_grid[land_y, land_x]  # (N,)
        h_vals = hum_norm_grid[land_y, land_x]
        c_vals = c_grid[land_y, land_x]
        e_vals = ero_norm_grid[land_y, land_x]
        # w = 0.0 for all
        # Distance: (N, num_biomes)
        dist = ((t_vals[:, None] - bp_t[None, :]) ** 2 +
               (h_vals[:, None] - bp_h[None, :]) ** 2 +
               (c_vals[:, None] - bp_c[None, :]) ** 2 +
               (e_vals[:, None] - bp_e[None, :]) ** 2 +
               bp_w[None, :] ** 2)
        best_idx = np.argmin(dist, axis=1)
        best_biomes = [bp_names[i] for i in best_idx]

        for i in range(len(land_y)):
            biome_name = best_biomes[i]
            if biome_name in BIOME_COLORS:
                biome_map[land_y[i], land_x[i]] = BIOME_COLORS[biome_name]
            else:
                biome_map[land_y[i], land_x[i]] = BIOME_COLORS['plains']

    # Статистика
    total = H * W
    print(f"    Карта биомов: {W}x{H}")
    print(f"    Статистика покрытия:")
    print(f"      deep_ocean:  {deep_ocean_mask.sum():8d} ({100.0*deep_ocean_mask.sum()/total:.1f}%)")
    print(f"      ocean:       {shallow_ocean_mask.sum():8d} ({100.0*shallow_ocean_mask.sum()/total:.1f}%)")
    print(f"      river:       {rivers_mask.sum():8d} ({100.0*rivers_mask.sum()/total:.1f}%)")
    print(f"      lake:        {lakes_mask.sum():8d} ({100.0*lakes_mask.sum()/total:.1f}%)")
    print(f"      land:        {land_mask.sum():8d} ({100.0*land_mask.sum()/total:.1f}%)")
    return biome_map


def world_to_pixel(worldX, worldZ, imgW, imgH):
    """Перевести мировые координаты в пиксельные."""
    px = int((worldX + HALF_WORLD) / WORLD_SIZE * imgW)
    pz = int((worldZ + HALF_WORLD) / WORLD_SIZE * imgH)
    px = max(0, min(imgW - 1, px))
    pz = max(0, min(imgH - 1, pz))
    return px, pz


def gray_to_height(gray):
    """Преобразовать gray (0..255) в Y координату.

    Кусочно-линейная формула (v3, совпадает с MapData.grayToHeight):
      gray 0..72   → Y -30..63   (океан)
      gray 72..255 → Y 63..200   (суша)
    """
    if gray <= SEA_LEVEL_GRAY:
        t = gray / SEA_LEVEL_GRAY
        return MIN_OCEAN + int(round(t * (SEA_LEVEL - MIN_OCEAN)))
    else:
        t = (gray - SEA_LEVEL_GRAY) / (255 - SEA_LEVEL_GRAY)
        return SEA_LEVEL + int(round(t * (MAX_TERRAIN - SEA_LEVEL)))


def get_pixel_info(maps, biome_map, px, pz):
    """Получить подробную информацию о пикселе."""
    info = {}

    # Height (v3): gray 0 (Марианская) → Y -30, gray 72 → Y 63 (море), gray 255 → Y 200
    gray = int(maps['height'][pz, px])
    info['height_gray'] = gray
    info['height_y'] = gray_to_height(gray)
    # Legacy height-based ocean (for comparison only)
    info['is_ocean_by_height'] = info['height_y'] < SEA_LEVEL
    info['is_deep_ocean_by_height'] = info['height_y'] < DEEP_OCEAN_Y

    # Humidity
    info['humidity'] = int(maps['humidity'][pz, px])

    # Temperature RGB → climate zone
    rgb = maps['temperature'][pz, px]
    info['temp_rgb'] = (int(rgb[0]), int(rgb[1]), int(rgb[2]))
    climate, dist = detect_climate(rgb)
    info['climate'] = climate
    info['climate_temp'] = CLIMATE_TEMPS[climate]
    info['climate_dist'] = round(dist, 2)

    # Sea/ocean — v4: МАСКА (alpha>0 = океан)
    sea_pixel = maps['sea_ocean'][pz, px]
    info['sea_rgba'] = (int(sea_pixel[0]), int(sea_pixel[1]),
                         int(sea_pixel[2]), int(sea_pixel[3]))
    info['is_ocean'] = (sea_pixel[3] > 0) and (sea_pixel[0] < WATER_RED_THRESHOLD)
    # Deep ocean: ocean AND height Y < DEEP_OCEAN_Y
    deep_ocean_gray = int(round((DEEP_OCEAN_Y - MIN_OCEAN) * SEA_LEVEL_GRAY / (SEA_LEVEL - MIN_OCEAN)))
    info['is_deep_ocean'] = info['is_ocean'] and (gray <= deep_ocean_gray)

    # Lakes — alpha-aware (СОВПАДАЕТ с Java)
    lake_pixel = maps['lakes'][pz, px]
    info['lake_rgba'] = (int(lake_pixel[0]), int(lake_pixel[1]),
                         int(lake_pixel[2]), int(lake_pixel[3]))
    info['is_lake'] = (lake_pixel[3] > 0) and (lake_pixel[0] < WATER_RED_THRESHOLD)

    # Rivers — alpha-aware (СОВПАДАЕТ с Java)
    river_pixel = maps['rivers'][pz, px]
    info['river_rgba'] = (int(river_pixel[0]), int(river_pixel[1]),
                          int(river_pixel[2]), int(river_pixel[3]))
    info['is_river'] = (river_pixel[3] > 0) and (river_pixel[0] < WATER_RED_THRESHOLD)

    # Erosion
    info['erosion'] = int(maps['erosion'][pz, px])

    # Biome (determine from biome_map color)
    biome_color = tuple(int(c) for c in biome_map[pz, px])
    info['biome_color'] = biome_color
    info['biome'] = None
    for name, color in BIOME_COLORS.items():
        if biome_color == color:
            info['biome'] = name
            break

    # Определяем, нужно ли менять температуру из-за высоты (>80)
    alt_temp = info['climate_temp']
    if info['height_y'] > 80:
        alt_temp -= (info['height_y'] - 80) * 0.001
        alt_temp = max(0.0, alt_temp)
    info['altitude_temp'] = round(alt_temp, 4)

    return info


def print_info(info, worldX, worldZ, px, pz, imgW, imgH):
    """Вывести подробную информацию о точке."""
    chunkX = worldX >> 4
    chunkZ = worldZ >> 4
    biomeX = worldX >> 2
    biomeZ = worldZ >> 2

    print()
    print("=" * 64)
    print(f"  КООРДИНАТЫ ИГРОКА: ({worldX:+8d}, {worldZ:+8d})")
    print("=" * 64)
    print(f"  Пиксель на карте: ({px:5d}, {pz:5d}) / ({imgW}x{imgH})")
    print(f"  Чанк:              ({chunkX:+7d}, {chunkZ:+7d})")
    print(f"  Биом-координаты:   ({biomeX:+7d}, {biomeZ:+7d})")
    print(f"  Масштаб: 1 пиксель = {WORLD_SIZE // imgW} блоков")
    print("-" * 64)

    # ВЫСОТА
    print(f"  ВЫСОТА (height.png — v4 кусочно-линейная формула):")
    print(f"    gray = {info['height_gray']:3d} / 255")
    print(f"    Y    = {info['height_y']:+4d}  (диапазон {MIN_OCEAN}..{MAX_TERRAIN})")
    if info.get('is_ocean', False):
        depth = SEA_LEVEL - info['height_y']
        deep_flag = " [DEEP OCEAN]" if info.get('is_deep_ocean', False) else ""
        print(f"    => ОКЕАН (mask): глубина {depth} блоков ({SEA_LEVEL}){deep_flag}")
    elif info['height_y'] < SEA_LEVEL:
        print(f"    => НИЗКАЯ СУША: {info['height_y'] - SEA_LEVEL} блоков ниже уровня моря (без маски океана)")
    else:
        print(f"    => СУША: {info['height_y'] - SEA_LEVEL} блоков выше уровня моря ({SEA_LEVEL})")
    print("-" * 64)

    # КЛИМАТ
    print(f"  КЛИМАТ (temperature.png):")
    print(f"    RGB         = {info['temp_rgb']}")
    print(f"    Зона        = {info['climate']}")
    print(f"    Температура = {info['climate_temp']:.2f}")
    print(f"    Выс.темп    = {info['altitude_temp']:.4f} (с поправкой на высоту)")
    print(f"    Евкл. dist  = {info['climate_dist']:.2f}")
    print("-" * 64)

    # ВЛАЖНОСТЬ
    hum = info['humidity']
    hum_label = "ВЛАЖНО" if hum > 128 else "СУХО"
    print(f"  ВЛАЖНОСТЬ (humidity.png):")
    print(f"    value = {hum:3d} / 255  →  {hum_label}")
    if hum < 85:
        print(f"    Индекс влажности: 0 (очень сухо)")
    elif hum < 170:
        print(f"    Индекс влажности: 1 (умеренно)")
    else:
        print(f"    Индекс влажности: 2 (очень влажно)")
    print("-" * 64)

    # ЭРОЗИЯ
    ero = info['erosion']
    print(f"  ЭРОЗИЯ (erosion.png):")
    print(f"    value = {ero:3d} / 255")
    if ero > 200:
        print(f"    => Сильно эродировано (ровный ландшафт)")
    elif ero < 60:
        print(f"    => Слабо эродировано (горный рельеф)")
    else:
        print(f"    => Умеренная эрозия")
    print("-" * 64)

    # ВОДА
    print(f"  ВОДА (v4: океан определяется МАСКОЙ map_sea-ocean.png):")
    water_flags = []
    if info.get('is_deep_ocean', False):
        water_flags.append("DEEP_OCEAN (mask)")
    elif info.get('is_ocean', False):
        water_flags.append("OCEAN (mask)")
    if info.get('is_lake', False):
        water_flags.append("LAKE")
    if info.get('is_river', False):
        water_flags.append("RIVER")

    if water_flags:
        print(f"    *** В ВОДЕ: {' + '.join(water_flags)} ***")
    else:
        print(f"    СУША (воды нет)")

    if 'sea_rgba' in info:
        print(f"    sea_ocean RGBA = {info['sea_rgba']}"
              f"  → {'OCEAN' if info.get('is_ocean', False) else 'land'}")
    if 'lake_rgba' in info:
        print(f"    lakes RGBA  = {info['lake_rgba']}"
              f"  → {'WATER' if info.get('is_lake', False) else 'no water'}")
    if 'river_rgba' in info:
        print(f"    rivers RGBA = {info['river_rgba']}"
              f"  → {'WATER' if info.get('is_river', False) else 'no water'}")

    # Normalized parameters (for reference)
    if 'climate_temp' in info:
        t_norm = (info['climate_temp'] - 0.5) * 2.0
        print(f"    [параметры] T={t_norm:+.2f}")
    if 'humidity' in info:
        h_norm = (info['humidity'] / 255.0) * 2.0 - 1.0
        print(f"    [параметры] H={h_norm:+.2f}")
    if 'erosion' in info:
        e_norm = (info['erosion'] / 255.0) * 2.0 - 1.0
        print(f"    [параметры] E={e_norm:+.2f}")
    print("-" * 64)

    # БИОМ
    print(f"  ПРЕДПОЛАГАЕМЫЙ БИОМ:")
    print(f"    Название = {info['biome'] or 'UNKNOWN'}")
    print(f"    RGB      = {info['biome_color']}")
    print("=" * 64)
    print()


def save_region(original_img, x, z, half_size):
    """Сохранить область для последующего восстановления."""
    key = (x, z)
    if key not in saved_regions:
        x1 = max(0, x - half_size)
        z1 = max(0, z - half_size)
        x2 = min(original_img.width, x + half_size + 1)
        z2 = min(original_img.height, z + half_size + 1)
        saved_regions[key] = original_img.crop((x1, z1, x2, z2))


def restore_region(canvas, original_img, x, z, half_size):
    """Восстановить область (затереть старый маркер)."""
    key = (x, z)
    if key in saved_regions:
        region = saved_regions[key]
        x1 = max(0, x - half_size)
        z1 = max(0, z - half_size)
        canvas.paste(region, (x1, z1))
        del saved_regions[key]


def draw_marker(draw, px, pz):
    """Нарисовать маркер (крест + круг + буква P)."""
    s = MARKER_SIZE
    cs = CROSSHAIR_SIZE

    # Crosshair
    draw.line([(px - cs, pz), (px + cs, pz)], fill=MARKER_COLOR_OUTER, width=2)
    draw.line([(px, pz - cs), (px, pz + cs)], fill=MARKER_COLOR_OUTER, width=2)

    # Внешний круг
    draw.ellipse([(px - s, pz - s), (px + s, pz + s)],
                 outline=MARKER_COLOR_OUTER, width=2)

    # Центральная точка
    draw.ellipse([(px - 3, pz - 3), (px + 3, pz + 3)],
                 fill=MARKER_COLOR_INNER, outline=MARKER_COLOR_OUTER, width=1)

    # Буква P (Player)
    try:
        font = ImageFont.truetype("arial.ttf", 16)
    except (OSError, IOError):
        try:
            font = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", 14)
        except (OSError, IOError):
            font = ImageFont.load_default()

    text = MARKER_TEXT
    bbox = draw.textbbox((px, pz), text, font=font)
    tw = bbox[2] - bbox[0]
    th = bbox[3] - bbox[1]
    draw.text((px - tw // 2, pz - s - th - 4), text, fill=MARKER_COLOR_OUTER, font=font)


# Глобальное состояние
saved_regions = {}


def main():
    parser = argparse.ArgumentParser(
        description="Локатор игрока на карте биомов (авто-генерация из 7 PNG)"
    )
    parser.add_argument("--maps-dir", default=None,
                        help="Папка с PNG картами (по умолчанию авто-поиск)")
    parser.add_argument("--output", default="map_located.png",
                        help="Файл вывода карты с маркером")
    parser.add_argument("--regenerate", action="store_true",
                        help="Перегенерировать карту биомов даже если есть biome_map.png")
    parser.add_argument("--biome-map", default="biome_map.png",
                        help="Файл для сохранения карты биомов")
    parser.add_argument("--no-marker", action="store_true",
                        help="Не рисовать маркер, только вывести информацию")
    args = parser.parse_args()

    print("=" * 64)
    print("  MAP LOCATOR v2.0 — генерация карты биомов из 7 PNG")
    print("  Имена файлов СОВПАДАЮТ с Java-модом:")
    for k, v in DEFAULT_MAP_FILES.items():
        print(f"    {k:12s} → {v}")
    print("=" * 64)

    # Найти папку с картами
    maps_dir = find_maps_dir(args.maps_dir)
    if not maps_dir:
        print(f"\n[!] Карты не найдены. Укажите папку через --maps-dir")
        print(f"    Ищет: {list(DEFAULT_MAP_FILES.values())}")
        print(f"    Стандартные пути:")
        if sys.platform == "win32":
            appdata = os.environ.get("APPDATA", "")
            print(f"      {appdata}\\.tlauncher\\minecraft")
            print(f"      {appdata}\\.minecraft")
        print(f"      . (текущая директория)")
        sys.exit(1)
    print(f"\n[*] Карты найдены в: {os.path.abspath(maps_dir)}")

    # Загрузить карты
    maps = load_maps(maps_dir)
    if maps is None:
        print("[!] Не удалось загрузить карты")
        sys.exit(1)

    # Сгенерировать или загрузить карту биомов
    biome_map_path = os.path.join(os.path.dirname(os.path.abspath(args.biome_map)) or '.',
                                  os.path.basename(args.biome_map))
    if args.regenerate or not os.path.exists(biome_map_path):
        biome_map_np = generate_biome_map(maps)
        Image.fromarray(biome_map_np).save(biome_map_path)
        print(f"[*] Карта биомов сохранена: {biome_map_path}")
        # Также сохраняем легенду
        legend = Image.new('RGB', (280, 500), (255, 255, 255))
        draw = ImageDraw.Draw(legend)
        try:
            font = ImageFont.truetype("arial.ttf", 12)
        except (OSError, IOError):
            try:
                font = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", 12)
            except (OSError, IOError):
                font = ImageFont.load_default()
        y_off = 10
        draw.text((10, y_off), "LEGENDA BIOME MAP", fill='black', font=font)
        y_off += 25
        for name, color in BIOME_COLORS.items():
            draw.rectangle([10, y_off, 30, y_off + 20], fill=color, outline='black')
            draw.text((40, y_off + 2), name.replace('_', ' ').title(),
                      fill='black', font=font)
            y_off += 25
        legend_path = os.path.join(os.path.dirname(biome_map_path) or '.',
                                   'biome_legend.png')
        legend.save(legend_path)
        print(f"[*] Легенда сохранена: {legend_path}")
    else:
        print(f"[*] Используется существующая карта биомов: {biome_map_path}")
        biome_map_np = np.array(Image.open(biome_map_path).convert('RGB'))

    imgW, imgH = biome_map_np.shape[1], biome_map_np.shape[0]
    print(f"\n[*] Размер карты: {imgW} x {imgH} пикселей")
    print(f"[*] Масштаб: 1 пиксель = {WORLD_SIZE // imgW} блоков")
    print(f"[*] Границы мира: X/Z от {-HALF_WORLD} до {HALF_WORLD}")
    print()
    print("Введите координаты (X Z) или 'q' для выхода:")
    print("  Пример: 50000 -30000")
    print("  Пример: -15000 -6000")
    print()

    # Открываем оригинал карты биомов (для последующего затирания маркеров)
    original_biome_img = Image.fromarray(biome_map_np.copy()).convert("RGBA")

    current_marker = None

    while True:
        try:
            user_input = input("> ").strip()
        except (EOFError, KeyboardInterrupt):
            print("\nВыход.")
            break

        if user_input.lower() in ("q", "quit", "exit", "выход"):
            print("Выход.")
            break

        if not user_input:
            continue

        # Парсим координаты
        parts = user_input.replace(",", " ").split()
        if len(parts) < 2:
            print("[!] Введите ДВА числа: X и Z. Пример: 50000 -30000")
            continue

        try:
            worldX = int(parts[0])
            worldZ = int(parts[1])
        except ValueError:
            print("[!] Неверный формат. Пример: 50000 -30000")
            continue

        # Проверяем границы
        out_of_bounds = False
        if abs(worldX) > HALF_WORLD or abs(worldZ) > HALF_WORLD:
            print(f"[!] Координаты ({worldX}, {worldZ}) вне границ мира (±{HALF_WORLD})")
            out_of_bounds = True
            worldX = max(-HALF_WORLD, min(HALF_WORLD, worldX))
            worldZ = max(-HALF_WORLD, min(HALF_WORLD, worldZ))

        # Переводим в пиксели
        px, pz = world_to_pixel(worldX, worldZ, imgW, imgH)

        # Получаем подробную информацию
        info = get_pixel_info(maps, biome_map_np, px, pz)
        print_info(info, worldX, worldZ, px, pz, imgW, imgH)

        if out_of_bounds:
            print("[!] Информация показана для ближайшей точки внутри границ мира.")

        if args.no_marker:
            continue

        # Работаем с копией оригинала
        canvas = original_biome_img.copy()

        # Если был старый маркер — восстанавливаем область под ним
        if current_marker is not None:
            old_px, old_pz = current_marker
            restore_region(canvas, original_biome_img, old_px, old_pz,
                           CROSSHAIR_SIZE + MARKER_SIZE + 20)

        # Сохраняем область под новым маркером
        save_region(original_biome_img, px, pz,
                    CROSSHAIR_SIZE + MARKER_SIZE + 20)

        # Рисуем новый маркер
        draw = ImageDraw.Draw(canvas)
        draw_marker(draw, px, pz)

        # Сохраняем результат
        output_path = args.output
        canvas.save(output_path, "PNG")
        print(f"    -> Маркер нарисован: {output_path}")
        print()

        current_marker = (px, pz)

    # Открываем изображение в системе (только Windows)
    if current_marker and not args.no_marker and sys.platform == "win32":
        try:
            os.startfile(args.output)
        except Exception:
            pass

    print("Готово.")


if __name__ == "__main__":
    main()
