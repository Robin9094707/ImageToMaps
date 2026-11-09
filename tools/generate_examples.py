#!/usr/bin/env python3
"""
Generates the example images bundled with SimplexImageToMap: a poster PNG and an animated GIF.

Output: plugin/src/main/examples/images/  (zipped into the plugin jar by Gradle and installed on first start)
Run:    python3 tools/generate_examples.py   (needs Pillow)
"""
import colorsys
import math
import os
import random
import shutil

from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "plugin", "src", "main", "examples")
IMAGES = os.path.join(ROOT, "images")


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
            col = tuple(int(c * 255) for c in colorsys.hsv_to_rgb(hue, 0.75, 1))
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
    generate_images()
    with open(os.path.join(ROOT, "README.txt"), "w", encoding="utf-8") as fh:
        fh.write("Example images installed by SimplexImageToMap on the first start. Delete freely.\n")
    print("Generated example images into " + os.path.abspath(IMAGES))


if __name__ == "__main__":
    main()
