"""Make the "cat hugging a coin" launcher icon from the packed sprites.

The adaptive-icon foreground is 108 dp; drawn on a 54 x 54 art grid every density gets a
whole-number scale (mdpi 2 ... xxxhdpi 8), so the pixels never blur. Launchers show
roughly the middle 72 dp (36 art px), so the art stays inside ~32 px.

  python -I tools/make_icon.py --sheet variants.png     # compare the variants
  python -I tools/make_icon.py --pick 1                 # write the chosen one into res/
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
SPRITES = ROOT / "app" / "src" / "main" / "assets" / "sprites"
RES = ROOT / "app" / "src" / "main" / "res"
GRID = 54
DENSITIES = {"mdpi": 2, "hdpi": 3, "xhdpi": 4, "xxhdpi": 6, "xxxhdpi": 8}
BACKGROUNDS = {"kem": (243, 217, 164), "go": (138, 85, 54), "dem": (42, 32, 39)}

_data = json.loads((SPRITES / "sprites.json").read_text(encoding="utf-8"))
_pages = [Image.open(SPRITES / p).convert("RGBA") for p in _data["pages"]]


def frame(key: str, i: int = 0) -> Image.Image:
    s = _data["sprites"][key]
    return _pages[s["p"]].crop((s["x"] + i * s["w"], s["y"], s["x"] + (i + 1) * s["w"], s["y"] + s["h"]))


def outline(im: Image.Image, color=(42, 32, 39, 255)) -> Image.Image:
    """1-pixel dark outline so the art reads on any background."""
    out = Image.new("RGBA", (im.width + 2, im.height + 2))
    a = im.getchannel("A").load()
    o = out.load()
    for y in range(im.height):
        for x in range(im.width):
            if a[x, y] > 0:
                for dx, dy in ((-1, 0), (1, 0), (0, -1), (0, 1)):
                    o[x + 1 + dx, y + 1 + dy] = color
    out.alpha_composite(im, (1, 1))
    return out


def foreground(cat: int) -> Image.Image:
    """Sitting cat with a big gold coin held against its chest."""
    canvas = Image.new("RGBA", (GRID, GRID))
    body = outline(frame(f"cat/{cat}/sit", 0))
    coin = outline(frame("coin/coin", 0))
    cx, floor = GRID // 2, GRID // 2 + 15
    canvas.alpha_composite(body, (cx - body.width // 2, floor - body.height))
    canvas.alpha_composite(coin, (cx - coin.width // 2 + 1, floor - coin.height + 1))
    # two sparkles on the coin side
    d = ImageDraw.Draw(canvas)
    for sx, sy in ((cx + 10, floor - 18), (cx - 12, floor - 6)):
        d.point([(sx, sy - 1), (sx - 1, sy), (sx, sy), (sx + 1, sy), (sx, sy + 1)], fill=(255, 244, 200, 255))
    return canvas


def masked(fg: Image.Image, bg: tuple, scale: int, circle: bool) -> Image.Image:
    size = GRID * scale
    img = Image.new("RGBA", (size, size), bg + (255,))
    img.alpha_composite(fg.resize((size, size), Image.NEAREST))
    # launchers crop to the middle 72/108 and apply a mask
    crop = size * 18 // 108
    img = img.crop((crop, crop, size - crop, size - crop))
    mask = Image.new("L", img.size, 0)
    md = ImageDraw.Draw(mask)
    if circle:
        md.ellipse([0, 0, img.width - 1, img.height - 1], fill=255)
    else:
        md.rounded_rectangle([0, 0, img.width - 1, img.height - 1], radius=img.width // 4, fill=255)
    out = Image.new("RGBA", img.size)
    out.paste(img, mask=mask)
    return out


def sheet(path: Path) -> None:
    variants = [(c, b) for c in (1, 2, 3, 4, 5, 6) for b in ("kem",)] + [(1, "go"), (1, "dem")]
    cell = 220
    img = Image.new("RGBA", (cell * 4, cell * ((len(variants) + 3) // 4)), (230, 230, 235, 255))
    d = ImageDraw.Draw(img)
    for i, (cat, bg) in enumerate(variants):
        x, y = (i % 4) * cell, (i // 4) * cell
        big = masked(foreground(cat), BACKGROUNDS[bg], 4, circle=False)
        small = masked(foreground(cat), BACKGROUNDS[bg], 2, circle=True)
        img.alpha_composite(big, (x + 8, y + 8))
        img.alpha_composite(small, (x + 8 + big.width + 6, y + 8))
        d.text((x + 8, y + cell - 22), f"{i + 1}: meo {cat}, nen {bg}", fill=(20, 20, 20, 255))
    img.save(path)
    print(f"sheet: {path}")


def pick(n: int) -> None:
    variants = [(c, "kem") for c in (1, 2, 3, 4, 5, 6)] + [(1, "go"), (1, "dem")]
    cat, bg = variants[n - 1]
    fg = foreground(cat)
    for density, scale in DENSITIES.items():
        folder = RES / f"drawable-{density}"
        folder.mkdir(parents=True, exist_ok=True)
        fg.resize((GRID * scale, GRID * scale), Image.NEAREST).save(folder / "ic_launcher_foreground.png")
    vector = RES / "drawable" / "ic_launcher_foreground.xml"
    if vector.exists():
        vector.unlink()
    colors = RES / "values" / "colors.xml"
    text = colors.read_text(encoding="utf-8")
    hexbg = "#FF%02X%02X%02X" % BACKGROUNDS[bg]
    import re
    text = re.sub(r'(<color name="meowney_icon_bg">)[^<]*(</color>)', rf"\g<1>{hexbg}\g<2>", text)
    colors.write_text(text, encoding="utf-8")
    print(f"icon: variant {n} (cat {cat}, background {bg}) written to res/drawable-*/")


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser()
    ap.add_argument("--sheet", type=Path)
    ap.add_argument("--pick", type=int)
    args = ap.parse_args()
    if args.sheet:
        sheet(args.sheet)
    if args.pick:
        pick(args.pick)
