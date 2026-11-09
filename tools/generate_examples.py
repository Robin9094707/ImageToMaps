#!/usr/bin/env python3
"""
Generates the example content bundled with SimplexImageToMap:
  * 20 custom blocks (16x16 textures in vanilla style + block definitions with English/German names, recipes)
  * 3 furniture models (Java block-model JSON + textures) – chair, table, lamp
  * 2 custom sounds (.ogg, needs ffmpeg with libvorbis)
  * demo images: a poster PNG and an animated GIF

Output: plugin/src/main/examples/  (zipped into the plugin jar by Gradle and installed on first start)
Run:    python3 tools/generate_examples.py
"""
import json
import math
import os
import random
import shutil
import subprocess
import tempfile
import wave

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "plugin", "src", "main", "examples")
TEX = os.path.join(ROOT, "textures")
BLOCKS = os.path.join(ROOT, "blocks")
MODELS = os.path.join(ROOT, "models")
SOUNDS = os.path.join(ROOT, "sounds")
IMAGES = os.path.join(ROOT, "images")
S = 16


def rgb(h):
    h = h.lstrip("#")
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


def clamp(v):
    return max(0, min(255, int(round(v))))


def shade(c, f):
    return tuple(clamp(x * f) for x in c[:3])


def mix(a, b, t):
    return tuple(clamp(a[i] * (1 - t) + b[i] * t) for i in range(3))


def noise(seed, scale=1.0, octaves=3):
    """Small tileable value noise in [0,1]."""
    rnd = np.random.default_rng(seed)
    out = np.zeros((S, S))
    amp, total = 1.0, 0.0
    for o in range(octaves):
        cells = max(2, int(4 * scale * (2 ** o)))
        grid = rnd.random((cells, cells))
        ys, xs = np.mgrid[0:S, 0:S]
        gx, gy = xs / S * cells, ys / S * cells
        x0, y0 = np.floor(gx).astype(int), np.floor(gy).astype(int)
        fx, fy = gx - x0, gy - y0
        fx, fy = fx * fx * (3 - 2 * fx), fy * fy * (3 - 2 * fy)
        x1, y1 = (x0 + 1) % cells, (y0 + 1) % cells
        x0, y0 = x0 % cells, y0 % cells
        v = (grid[y0, x0] * (1 - fx) + grid[y0, x1] * fx) * (1 - fy) + (grid[y1, x0] * (1 - fx) + grid[y1, x1] * fx) * fy
        out += v * amp
        total += amp
        amp *= 0.5
    return out / total


def img_from(fn):
    im = Image.new("RGBA", (S, S))
    px = im.load()
    for y in range(S):
        for x in range(S):
            c = fn(x, y)
            px[x, y] = c if len(c) == 4 else (*c, 255)
    return im


def save(im, name):
    os.makedirs(TEX, exist_ok=True)
    im.save(os.path.join(TEX, name + ".png"))
    return name + ".png"


# ---------------------------------------------------------------------------------------------------------
# textures
# ---------------------------------------------------------------------------------------------------------

def planks(name, base, seed, grain=0.18):
    n = noise(seed, 1.5)
    rnd = random.Random(seed)
    offsets = [rnd.randint(0, 15) for _ in range(4)]

    def f(x, y):
        board = y // 4
        local = y % 4
        c = shade(base, 0.92 + n[y, (x + offsets[board]) % S] * grain * 2 - grain)
        streak = math.sin((x + offsets[board] * 3) * 0.9 + board) * 0.04
        c = shade(c, 1 + streak)
        if local == 3:
            c = shade(c, 0.62)
        elif local == 0:
            c = shade(c, 1.08)
        joint = (offsets[board] * 5 + 7) % S
        if x == joint and local != 3:
            c = shade(c, 0.7)
        return c

    return save(img_from(f), name)


def log_side(name, bark, seed):
    n = noise(seed, 2.0)
    rnd = random.Random(seed)
    cracks = [rnd.randint(0, 15) for _ in range(5)]

    def f(x, y):
        c = shade(bark, 0.85 + n[y, x] * 0.35)
        if x in cracks:
            c = shade(c, 0.6 + 0.2 * n[(y + 5) % S, x])
        if (x + y * 3) % 11 == 0:
            c = shade(c, 1.15)
        return c

    return save(img_from(f), name)


def log_top(name, wood, bark, seed):
    n = noise(seed, 1.0)

    def f(x, y):
        dx, dy = x - 7.5, y - 7.5
        r = math.sqrt(dx * dx + dy * dy)
        if x in (0, 15) or y in (0, 15):
            return shade(bark, 0.8 + n[y, x] * 0.3)
        ring = (math.sin(r * 1.9 + n[y, x] * 2.0) + 1) / 2
        c = shade(wood, 0.82 + ring * 0.25)
        if r < 1.2:
            c = shade(wood, 0.7)
        return c

    return save(img_from(f), name)


def herringbone(name, a, b, seed):
    n = noise(seed, 2.0)

    def f(x, y):
        block = ((x // 4) + (y // 4)) % 2
        u = (x - y) % 8 if block else (x + y) % 8
        base = a if (u < 4) else b
        c = shade(base, 0.9 + n[y, x] * 0.2)
        if u in (3, 7):
            c = shade(c, 0.75)
        return c

    return save(img_from(f), name)


def marble(name, base, vein, seed, polished=False, vein_strength=1.0):
    n = noise(seed, 1.2, 4)
    n2 = noise(seed + 7, 2.5, 3)

    def f(x, y):
        t = math.sin((x * 0.55 + y * 0.35) + n[y, x] * 7.5)
        v = max(0.0, 1 - abs(t) * 3.2) * vein_strength
        c = mix(base, vein, min(1, v * 0.85))
        c = shade(c, 0.95 + n2[y, x] * 0.1)
        if polished:
            if x == 0 or y == 0:
                c = shade(c, 1.08)
            if x == 15 or y == 15:
                c = shade(c, 0.86)
            if (x + y) == 6 or (x + y) == 7:
                c = shade(c, 1.06)
        return c

    return save(img_from(f), name)


def bricks(name, base, mortar, seed, rows=4, var=0.12):
    n = noise(seed, 2.0)
    rnd = random.Random(seed)
    tones = [0.9 + rnd.random() * var * 2 - var for _ in range(16)]
    h = S // rows

    def f(x, y):
        row = y // h
        off = (h * 2 if row % 2 else 0) % 8
        col = ((x + off) % S) // 8
        if y % h == h - 1 or (x + off) % 8 == 7:
            return shade(mortar, 0.92 + n[y, x] * 0.15)
        c = shade(base, tones[row * 2 + col] + n[y, x] * 0.14 - 0.07)
        if y % h == 0:
            c = shade(c, 1.07)
        return c

    return save(img_from(f), name)


def tiles(name, a, b, grout, seed, size=8, checker=True):
    n = noise(seed, 2.0)

    def f(x, y):
        if x % size == size - 1 or y % size == size - 1:
            return shade(grout, 0.95 + n[y, x] * 0.1)
        base = a if (not checker or ((x // size + y // size) % 2 == 0)) else b
        c = shade(base, 0.92 + n[y, x] * 0.16)
        if x % size == 0 or y % size == 0:
            c = shade(c, 1.08)
        return c

    return save(img_from(f), name)


def volcanic(name, seed):
    n = noise(seed, 1.8, 4)
    dark = rgb("#2a2320")
    glow = rgb("#ff7a1a")
    hot = rgb("#ffd36b")

    def f(x, y):
        if y % 8 == 7 or (x + (4 if (y // 8) % 2 else 0)) % 8 == 7:
            t = n[y, x]
            return mix(glow, hot, t) if t > 0.45 else shade(glow, 0.8)
        return shade(dark, 0.85 + n[y, x] * 0.35)

    return save(img_from(f), name)


def jade(name, seed):
    n = noise(seed, 1.4, 4)
    a, b = rgb("#2f9e6f"), rgb("#9ff0c4")

    def f(x, y):
        t = (math.sin(n[y, x] * 9 + x * 0.3) + 1) / 2
        c = mix(a, b, t * 0.6)
        if x in (0, 15) or y in (0, 15):
            c = shade(c, 0.78)
        if x in (1,) or y in (1,):
            c = shade(c, 1.12)
        return c

    return save(img_from(f), name)


def framed_glass(name, frame, seed):
    def f(x, y):
        if x in (0, 15) or y in (0, 15):
            return (*shade(frame, 0.85), 255)
        if x in (1, 14) or y in (1, 14):
            return (*shade(frame, 1.1), 255)
        if (x, y) in ((4, 3), (3, 4), (5, 4), (4, 5)) or (x == y + 6 and 2 < x < 13):
            return (235, 245, 255, 255)  # highlights
        return (0, 0, 0, 0)

    return save(img_from(f), name)


def mosaic_glass(name, seed):
    rnd = random.Random(seed)
    colors = [rgb(c) for c in ("#e8505b", "#f9d56e", "#14b1ab", "#5f6caf", "#f3ecc2", "#6ab04c")]
    lead = rgb("#2b2b2b")
    cells = {}
    pts = [(rnd.uniform(0, 16), rnd.uniform(0, 16)) for _ in range(7)]

    def nearest(x, y):
        best, second, idx = 99, 99, 0
        for i, (px, py) in enumerate(pts):
            for ox in (-16, 0, 16):
                for oy in (-16, 0, 16):
                    d = math.hypot(x + 0.5 - px - ox, y + 0.5 - py - oy)
                    if d < best:
                        second, best, idx = best, d, i
                    elif d < second:
                        second = d
        return idx, second - best

    def f(x, y):
        idx, edge = nearest(x, y)
        if edge < 1.0 or x == 0 or y == 0:
            return (*lead, 255)
        c = colors[idx % len(colors)]
        # cutout rendering on Java: keep it opaque but leave a few clear "bubbles"
        if (x * 7 + y * 3 + idx) % 13 == 0:
            return (0, 0, 0, 0)
        return (*shade(c, 0.9 + ((x + y) % 3) * 0.05), 255)

    return save(img_from(f), name)


def frosted_glass(name, seed):
    n = noise(seed, 2.5)

    def f(x, y):
        if x in (0, 15) or y in (0, 15):
            return (214, 228, 236, 255)
        v = n[y, x]
        if (x + y) % 2 == 0 and v > 0.35:
            return (clamp(225 + v * 25), clamp(235 + v * 15), 245, 255)
        return (0, 0, 0, 0)

    return save(img_from(f), name)


def lattice(name, color, seed, spacing=5):
    def f(x, y):
        on = (x + y) % spacing == 0 or (x - y) % spacing == 0 or x in (0, 15) or y in (0, 15)
        if not on:
            return (0, 0, 0, 0)
        c = color
        if x in (0, 15) or y in (0, 15):
            c = shade(c, 0.8)
        elif (x + y) % 2:
            c = shade(c, 1.15)
        return (*c, 255)

    return save(img_from(f), name)


def terracotta(name, seed):
    return tiles(name, rgb("#c46a3f"), rgb("#d98a5a"), rgb("#7b4a33"), seed, size=8)


# ---------------------------------------------------------------------------------------------------------
# block definitions
# ---------------------------------------------------------------------------------------------------------

def block(id, en, de, desc_en, desc_de, textures, sound, tool="pickaxe", hardness=1.5, kind="solid",
          requires_tool=False, recipe=None, custom_sounds=None, category="building", model=None):
    os.makedirs(BLOCKS, exist_ok=True)
    lines = [
        f"# Example block – edit freely, then /sim reload (or edit it in the menu).",
        f"id: {id}",
        f"name:",
        f"  en: \"{en}\"",
        f"  de: \"{de}\"",
        f"description:",
        f"  en: \"{desc_en}\"",
        f"  de: \"{desc_de}\"",
        f"type: {kind}",
        f"textures:",
    ]
    for k, v in textures.items():
        lines.append(f"  {k}: {v}")
    if model:
        lines.append(f"model: {model}")
    lines += [
        f"hardness: {hardness}",
        f"tool: {tool}",
        f"requires-tool: {str(requires_tool).lower()}",
        f"sound: {sound}",
    ]
    if custom_sounds:
        lines.append("custom-sounds:")
        for k, v in custom_sounds.items():
            lines.append(f"  {k}: {v}")
    lines += ["drop: self", f"category: {category}"]
    if recipe:
        shape, ingredients, amount = recipe
        lines.append("recipe:")
        lines.append("  shape:")
        for row in shape:
            lines.append(f"    - \"{row}\"")
        lines.append("  ingredients:")
        for k, v in ingredients.items():
            lines.append(f"    {k}: {v}")
        lines.append(f"  amount: {amount}")
    with open(os.path.join(BLOCKS, id + ".yml"), "w", encoding="utf-8") as fh:
        fh.write("\n".join(lines) + "\n")


def generate_blocks():
    redwood = rgb("#9c4a32")
    ebony = rgb("#3b2a26")
    white = rgb("#d9d2c3")

    t = {}
    t["redwood_planks"] = planks("redwood_planks", redwood, 11)
    t["ebony_planks"] = planks("ebony_planks", ebony, 12, grain=0.25)
    t["whitewashed_planks"] = planks("whitewashed_planks", white, 13, grain=0.1)
    t["redwood_log"] = log_side("redwood_log", rgb("#5a3324"), 14)
    t["redwood_log_top"] = log_top("redwood_log_top", rgb("#b5603f"), rgb("#5a3324"), 15)
    t["ebony_log"] = log_side("ebony_log", rgb("#231915"), 16)
    t["ebony_log_top"] = log_top("ebony_log_top", rgb("#4a3630"), rgb("#231915"), 17)
    t["herringbone_parquet"] = herringbone("herringbone_parquet", rgb("#b8864f"), rgb("#9c6d3c"), 18)
    t["marble"] = marble("marble", rgb("#eeece6"), rgb("#9ea2a8"), 21)
    t["polished_marble"] = marble("polished_marble", rgb("#f4f2ec"), rgb("#b7bac0"), 22, polished=True, vein_strength=0.7)
    t["marble_bricks"] = bricks("marble_bricks", rgb("#ebe8e1"), rgb("#a9a7a2"), 23)
    t["black_marble"] = marble("black_marble", rgb("#1d1d22"), rgb("#d9b25a"), 24, polished=True)
    t["slate_tiles"] = tiles("slate_tiles", rgb("#4b5560"), rgb("#56616d"), rgb("#2c3238"), 25)
    t["sandstone_tiles"] = tiles("sandstone_tiles", rgb("#e3cf9a"), rgb("#d6bf86"), rgb("#a68e5b"), 26, size=4)
    t["volcanic_bricks"] = volcanic("volcanic_bricks", 27)
    t["jade_block"] = jade("jade_block", 28)
    t["terracotta_tiles"] = terracotta("terracotta_tiles", 29)
    t["granite_bricks"] = bricks("granite_bricks", rgb("#9a6a58"), rgb("#5e463d"), 30, rows=2)
    t["framed_glass"] = framed_glass("framed_glass", rgb("#6b4a33"), 31)
    t["mosaic_glass"] = mosaic_glass("mosaic_glass", 32)
    t["frosted_glass"] = frosted_glass("frosted_glass", 33)
    t["copper_lattice"] = lattice("copper_lattice", rgb("#c8774d"), 34)
    t["amber_lattice_glass"] = lattice("amber_lattice_glass", rgb("#f0a020"), 35, spacing=4)

    # --- wood ---
    block("redwood_planks", "Redwood Planks", "Rotholzbretter", "Warm red planks from giant redwoods.",
          "Warme rote Bretter aus Riesen-Rotholz.", {"all": t["redwood_planks"]}, "wood", "axe", 2.0,
          recipe=(["L"], {"L": "simplex:redwood_log"}, 4))
    block("redwood_log", "Redwood Log", "Rotholzstamm", "Rough bark, glowing red heartwood.",
          "Raue Rinde, leuchtend rotes Kernholz.", {"side": t["redwood_log"], "top": t["redwood_log_top"], "bottom": t["redwood_log_top"]},
          "wood", "axe", 2.0, recipe=(["MMM", "MDM", "MMM"], {"M": "minecraft:mangrove_log", "D": "minecraft:red_dye"}, 8))
    block("ebony_planks", "Ebony Planks", "Ebenholzbretter", "Almost black, elegant and heavy.",
          "Fast schwarz, elegant und schwer.", {"all": t["ebony_planks"]}, "wood", "axe", 2.5,
          recipe=(["L"], {"L": "simplex:ebony_log"}, 4))
    block("ebony_log", "Ebony Log", "Ebenholzstamm", "Dark, dense wood.", "Dunkles, dichtes Holz.",
          {"side": t["ebony_log"], "top": t["ebony_log_top"], "bottom": t["ebony_log_top"]}, "wood", "axe", 2.5,
          recipe=(["DDD", "DID", "DDD"], {"D": "minecraft:dark_oak_log", "I": "minecraft:black_dye"}, 8))
    block("whitewashed_planks", "Whitewashed Planks", "Weißgekalkte Bretter", "Bright coastal style planks.",
          "Helle Bretter im Küstenstil.", {"all": t["whitewashed_planks"]}, "cherry_wood", "axe", 2.0,
          recipe=(["PPP", "PBP", "PPP"], {"P": "minecraft:birch_planks", "B": "minecraft:bone_meal"}, 8))
    block("herringbone_parquet", "Herringbone Parquet", "Fischgrätparkett", "Classic herringbone floor.",
          "Klassischer Fischgrät-Boden.", {"all": t["herringbone_parquet"]}, "wood", "axe", 2.0,
          recipe=(["OS", "SO"], {"O": "minecraft:oak_planks", "S": "minecraft:spruce_planks"}, 4))

    # --- stone ---
    block("marble", "Marble", "Marmor", "Cool white stone with grey veins.", "Kühler weißer Stein mit grauen Adern.",
          {"all": t["marble"]}, "calcite", "pickaxe", 1.5, True, recipe=(["CC", "CC"], {"C": "minecraft:calcite"}, 4))
    block("polished_marble", "Polished Marble", "Polierter Marmor", "Mirror smooth marble.", "Spiegelglatter Marmor.",
          {"all": t["polished_marble"]}, "calcite", "pickaxe", 1.5, True, recipe=(["MM", "MM"], {"M": "simplex:marble"}, 4))
    block("marble_bricks", "Marble Bricks", "Marmorziegel", "Fine marble masonry.", "Feines Marmor-Mauerwerk.",
          {"all": t["marble_bricks"]}, "deepslate_bricks", "pickaxe", 1.8, True,
          recipe=(["PP", "PP"], {"P": "simplex:polished_marble"}, 4))
    block("black_marble", "Black Marble", "Schwarzer Marmor", "Black marble with golden veins.",
          "Schwarzer Marmor mit goldenen Adern.", {"all": t["black_marble"]}, "polished_deepslate", "pickaxe", 2.0, True,
          recipe=(["BBB", "BGB", "BBB"], {"B": "minecraft:blackstone", "G": "minecraft:gold_nugget"}, 8))
    block("slate_tiles", "Slate Tiles", "Schieferfliesen", "Blue grey slate tiles.", "Blaugraue Schieferfliesen.",
          {"all": t["slate_tiles"]}, "deepslate", "pickaxe", 2.0, True,
          recipe=(["DD", "DD"], {"D": "minecraft:polished_deepslate"}, 4))
    block("sandstone_tiles", "Sandstone Tiles", "Sandsteinfliesen", "Small desert tiles.", "Kleine Wüstenfliesen.",
          {"all": t["sandstone_tiles"]}, "stone", "pickaxe", 0.8, True,
          recipe=(["SS", "SS"], {"S": "minecraft:smooth_sandstone"}, 4))
    block("granite_bricks", "Granite Bricks", "Granitziegel", "Large rustic granite blocks.", "Große rustikale Granitblöcke.",
          {"all": t["granite_bricks"]}, "stone", "pickaxe", 1.5, True,
          recipe=(["GG", "GG"], {"G": "minecraft:polished_granite"}, 4))
    block("terracotta_tiles", "Terracotta Tiles", "Terrakottafliesen", "Mediterranean floor tiles.",
          "Mediterrane Bodenfliesen.", {"all": t["terracotta_tiles"]}, "mud_bricks", "pickaxe", 1.25, True,
          recipe=(["TT", "TT"], {"T": "minecraft:terracotta"}, 4))
    block("volcanic_bricks", "Volcanic Bricks", "Vulkanziegel", "Basalt bricks with glowing lava seams.",
          "Basaltziegel mit glühenden Lavafugen.", {"all": t["volcanic_bricks"]}, "nether_bricks", "pickaxe", 2.5, True,
          recipe=(["BBB", "BMB", "BBB"], {"B": "minecraft:polished_basalt", "M": "minecraft:magma_block"}, 8))
    block("jade_block", "Jade Block", "Jadeblock", "Polished green gemstone.", "Polierter grüner Edelstein.",
          {"all": t["jade_block"]}, "amethyst", "pickaxe", 3.0, True,
          recipe=(["EEE", "ECE", "EEE"], {"E": "minecraft:emerald", "C": "minecraft:calcite"}, 2))

    # --- glass / transparent ---
    block("framed_glass", "Framed Glass", "Gerahmtes Glas", "Clear glass in a dark wooden frame.",
          "Klares Glas in dunklem Holzrahmen.", {"all": t["framed_glass"]}, "glass", "none", 0.3, "transparent",
          recipe=(["SGS", "GGG", "SGS"], {"S": "minecraft:stick", "G": "minecraft:glass"}, 4))
    block("mosaic_glass", "Mosaic Glass", "Mosaikglas", "Colourful leaded church glass.", "Buntes Kirchenglas mit Bleifassung.",
          {"all": t["mosaic_glass"]}, "glass", "none", 0.3, "transparent",
          recipe=(["RYB", "GPG", "BYR"], {"R": "minecraft:red_stained_glass", "Y": "minecraft:yellow_stained_glass",
                                           "B": "minecraft:blue_stained_glass", "G": "minecraft:green_stained_glass",
                                           "P": "minecraft:glass"}, 8),
          custom_sounds={"place": "crystal_chime", "break": "crystal_chime"})
    block("frosted_glass", "Frosted Glass", "Milchglas", "Frosty privacy glass.", "Frostiges Sichtschutzglas.",
          {"all": t["frosted_glass"]}, "glass", "none", 0.3, "transparent",
          recipe=(["GGG", "GSG", "GGG"], {"G": "minecraft:glass", "S": "minecraft:snowball"}, 8))
    block("copper_lattice", "Copper Lattice", "Kupfergitter", "Decorative copper grid.", "Dekoratives Kupfergitter.",
          {"all": t["copper_lattice"]}, "copper", "pickaxe", 1.5, "transparent",
          recipe=(["C C", " C ", "C C"], {"C": "minecraft:copper_ingot"}, 4))
    block("amber_lattice_glass", "Amber Lattice Glass", "Bernstein-Gitterglas", "Golden lattice window.",
          "Goldenes Gitterfenster.", {"all": t["amber_lattice_glass"]}, "glass", "none", 0.3, "transparent",
          recipe=(["GHG", "HGH", "GHG"], {"G": "minecraft:glass", "H": "minecraft:honeycomb"}, 5))


# ---------------------------------------------------------------------------------------------------------
# furniture models (Java block-model format = what Blockbench exports as "Java Block/Item")
# ---------------------------------------------------------------------------------------------------------

def cube(frm, to, tex="#0", uv_all=None):
    faces = {}
    for d in ("north", "east", "south", "west", "up", "down"):
        f = {"texture": tex}
        if uv_all:
            f["uv"] = uv_all
        faces[d] = f
    return {"from": frm, "to": to, "faces": faces}


def wood_texture(name, base, seed):
    im = Image.open(os.path.join(TEX, planks("_tmp_" + name, base, seed)))
    os.remove(os.path.join(TEX, "_tmp_" + name + ".png"))
    return im


def write_model(id, en, de, desc_en, desc_de, elements, textures, extra):
    folder = os.path.join(MODELS, id)
    os.makedirs(folder, exist_ok=True)
    tex_map = {}
    for i, (tname, im) in enumerate(textures.items()):
        im.save(os.path.join(folder, tname + ".png"))
        tex_map[str(i)] = "simplex:" + tname
    tex_map["particle"] = tex_map["0"]
    model = {
        "credit": "Simplex ImageToMap example",
        "textures": tex_map,
        "elements": elements,
        "display": {
            "gui": {"rotation": [30, 225, 0], "scale": [0.55, 0.55, 0.55]},
            "ground": {"translation": [0, 3, 0], "scale": [0.3, 0.3, 0.3]},
            "fixed": {"scale": [0.5, 0.5, 0.5]},
            "thirdperson_righthand": {"rotation": [75, 45, 0], "translation": [0, 2.5, 0], "scale": [0.3, 0.3, 0.3]},
            "firstperson_righthand": {"rotation": [0, 45, 0], "scale": [0.35, 0.35, 0.35]},
            "head": {"translation": [0, -30.4, 0], "scale": [1.6, 1.6, 1.6]},
        },
    }
    with open(os.path.join(folder, "model.json"), "w", encoding="utf-8") as fh:
        json.dump(model, fh, indent=2)
    lines = [
        "# Example 3D model. model.json was written in Java block-model format (Blockbench: File > Export > Java Block/Item).",
        f"id: {id}",
        "name:",
        f"  en: \"{en}\"",
        f"  de: \"{de}\"",
        "description:",
        f"  en: \"{desc_en}\"",
        f"  de: \"{desc_de}\"",
        "model: model.json",
    ]
    for k, v in extra.items():
        lines.append(f"{k}: {v}")
    lines += ["bedrock:", "  yaw-offset: 180", "  offset: [0.0, 0.0, 0.0]"]
    with open(os.path.join(folder, "model.yml"), "w", encoding="utf-8") as fh:
        fh.write("\n".join(lines) + "\n")


def generate_models():
    oak = wood_texture("oak", rgb("#b08850"), 41)
    cushion = img_from(lambda x, y: shade(rgb("#b23a48"), 0.9 + ((x * 3 + y * 5) % 7) * 0.03 - (0.12 if x in (0, 15) or y in (0, 15) else 0)))

    # chair: 4 legs, seat (cushion), backrest
    chair = [
        cube([2, 0, 2], [4, 8, 4]), cube([12, 0, 2], [14, 8, 4]), cube([2, 0, 12], [4, 8, 14]), cube([12, 0, 12], [14, 8, 14]),
        cube([1.5, 8, 1.5], [14.5, 9.5, 14.5]),
        cube([2, 9.5, 2], [14, 10.5, 14], "#1"),
        cube([2, 9.5, 12.5], [4, 22, 14]), cube([12, 9.5, 12.5], [14, 22, 14]),
        cube([4, 14, 13], [12, 21, 13.8]),
    ]
    write_model("oak_chair", "Oak Chair", "Eichenstuhl", "Comfortable – right click to sit down.",
                "Bequem – Rechtsklick zum Hinsetzen.", chair, {"oak_chair_wood": oak, "oak_chair_cushion": cushion},
                {"scale": 1.0, "collision": "true", "seat": "true", "seat-height": 0.55, "rotation-step": 45, "full-bright": "false",
                 "recipe": "\n  shape: [\"P  \", \"PPP\", \"S S\"]\n  ingredients: {P: minecraft:oak_planks, S: minecraft:stick}\n  amount: 2"})

    table = [
        cube([1, 0, 1], [3, 14, 3]), cube([13, 0, 1], [15, 14, 3]), cube([1, 0, 13], [3, 14, 15]), cube([13, 0, 13], [15, 14, 15]),
        cube([0, 14, 0], [16, 16, 16]),
        cube([3, 11, 1.5], [13, 12.5, 2.5]), cube([3, 11, 13.5], [13, 12.5, 14.5]),
    ]
    write_model("oak_table", "Oak Table", "Eichentisch", "A sturdy dining table.", "Ein stabiler Esstisch.", table,
                {"oak_table_wood": oak}, {"scale": 1.0, "collision": "true", "seat": "false", "rotation-step": 90, "full-bright": "false",
                                           "recipe": "\n  shape: [\"PPP\", \"S S\", \"S S\"]\n  ingredients: {P: minecraft:oak_planks, S: minecraft:stick}\n  amount: 1"})

    iron = img_from(lambda x, y: shade(rgb("#3a3d42"), 0.85 + ((x + y) % 4) * 0.05))
    shade_tex = img_from(lambda x, y: shade(rgb("#ffd27a"), 0.92 + ((x * 7 + y * 3) % 5) * 0.025))
    lamp = [
        cube([5, 0, 5], [11, 1, 11]),
        cube([7.5, 1, 7.5], [8.5, 18, 8.5]),
        cube([4, 18, 4], [12, 26, 12], "#1"),
        cube([5, 26, 5], [11, 27, 11]),
    ]
    write_model("street_lamp", "Golden Lamp", "Goldene Lampe", "Warm light shade on an iron stand.",
                "Warmer Lampenschirm auf einem Eisenständer.", lamp, {"street_lamp_iron": iron, "street_lamp_shade": shade_tex},
                {"scale": 1.0, "collision": "true", "seat": "false", "rotation-step": 45, "full-bright": "true",
                 "recipe": "\n  shape: [\" G \", \" I \", \" I \"]\n  ingredients: {G: minecraft:glowstone, I: minecraft:iron_ingot}\n  amount: 1"})


# ---------------------------------------------------------------------------------------------------------
# sounds
# ---------------------------------------------------------------------------------------------------------

def generate_sounds():
    if not shutil.which("ffmpeg"):
        print("ffmpeg not found – skipping example sounds")
        return
    os.makedirs(SOUNDS, exist_ok=True)
    rate = 44100

    def write(name, samples):
        samples = np.clip(samples, -1, 1)
        with tempfile.TemporaryDirectory() as tmp:
            wav = os.path.join(tmp, name + ".wav")
            with wave.open(wav, "wb") as w:
                w.setnchannels(1)
                w.setsampwidth(2)
                w.setframerate(rate)
                w.writeframes((samples * 32767).astype("<i2").tobytes())
            subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", wav, "-c:a", "libvorbis", "-q:a", "5",
                            os.path.join(SOUNDS, name + ".ogg")], check=True)

    t = np.linspace(0, 1.1, int(rate * 1.1), endpoint=False)
    chime = np.zeros_like(t)
    for f, a, d in ((1318.5, 0.5, 2.2), (1975.5, 0.32, 3.0), (2637.0, 0.22, 4.0), (3951.0, 0.1, 6.0)):
        chime += a * np.sin(2 * np.pi * f * t) * np.exp(-t * d)
    chime *= np.minimum(1, t * 200)
    write("crystal_chime", chime * 0.8)

    t = np.linspace(0, 0.35, int(rate * 0.35), endpoint=False)
    rnd = np.random.default_rng(5)
    knock = (np.sin(2 * np.pi * 180 * t) * 0.7 + np.sin(2 * np.pi * 410 * t) * 0.3) * np.exp(-t * 22)
    knock += rnd.normal(0, 0.15, t.shape) * np.exp(-t * 60)
    write("wood_knock", knock * 0.9)


# ---------------------------------------------------------------------------------------------------------
# demo images
# ---------------------------------------------------------------------------------------------------------

def font(size):
    for path in ("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", "/usr/share/fonts/dejavu/DejaVuSans-Bold.ttf"):
        if os.path.exists(path):
            return ImageFont.truetype(path, size)
    return ImageFont.load_default()


def generate_images():
    os.makedirs(IMAGES, exist_ok=True)
    w, h = 384, 256
    im = Image.new("RGB", (w, h))
    px = im.load()
    for y in range(h):
        for x in range(w):
            t = x / w
            u = y / h
            r = int(20 + 60 * u + 40 * math.sin(t * 3))
            g = int(30 + 120 * t)
            b = int(120 + 110 * (1 - u))
            px[x, y] = (max(0, min(255, r)), max(0, min(255, g)), max(0, min(255, b)))
    d = ImageDraw.Draw(im)
    rnd = random.Random(3)
    for _ in range(60):
        x, y = rnd.randint(0, w), rnd.randint(0, h // 2)
        d.ellipse([x, y, x + 2, y + 2], fill=(255, 255, 255))
    for i, col in enumerate(((40, 60, 90), (30, 45, 70))):
        pts = [(0, h)]
        for x in range(0, w + 16, 16):
            pts.append((x, int(h * (0.62 + 0.08 * i) - 40 * abs(math.sin(x * 0.01 + i * 2)))))
        pts.append((w, h))
        d.polygon(pts, fill=col)
    d.text((w // 2, 70), "SIMPLEX", font=font(58), fill=(255, 255, 255), anchor="mm", stroke_width=3, stroke_fill=(60, 40, 140))
    d.text((w // 2, 122), "Image to Map", font=font(26), fill=(210, 230, 255), anchor="mm")
    im = im.filter(ImageFilter.SMOOTH)
    im.save(os.path.join(IMAGES, "simplex_poster.png"))

    frames = []
    size = 256
    for f in range(24):
        frame = Image.new("RGB", (size, size), (12, 10, 30))
        fd = ImageDraw.Draw(frame)
        a = f / 24 * 2 * math.pi
        for ring in range(8, 0, -1):
            r = ring * 15
            hue = (ring * 0.11 + f / 24) % 1
            col = tuple(int(c * 255) for c in __import__("colorsys").hsv_to_rgb(hue, 0.75, 1))
            ox = math.cos(a + ring * 0.5) * 12
            oy = math.sin(a + ring * 0.5) * 12
            fd.ellipse([size / 2 - r + ox, size / 2 - r + oy, size / 2 + r + ox, size / 2 + r + oy], outline=col, width=6)
        fd.text((size / 2, size / 2), "GIF", font=font(36), fill=(255, 255, 255), anchor="mm")
        frames.append(frame)
    frames[0].save(os.path.join(IMAGES, "demo_animation.gif"), save_all=True, append_images=frames[1:], duration=70, loop=0)


def main():
    if os.path.isdir(ROOT):
        shutil.rmtree(ROOT)
    os.makedirs(ROOT)
    generate_blocks()
    generate_models()
    generate_sounds()
    generate_images()
    with open(os.path.join(ROOT, "README.txt"), "w", encoding="utf-8") as fh:
        fh.write("Example content installed by SimplexImageToMap on the first start. Delete freely.\n")
    count = len([f for f in os.listdir(BLOCKS) if f.endswith(".yml")])
    print(f"Generated {count} blocks, {len(os.listdir(MODELS))} models into {os.path.abspath(ROOT)}")


if __name__ == "__main__":
    main()
