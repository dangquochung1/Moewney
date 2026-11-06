"""Copy the hand-picked sprites from the 1.1 GB art store into the app.

Output (app/src/main/assets/):
  sprites/atlas_N.png + sprites/sprites.json   strips and single images, shelf-packed
  room/*.png                                   whole tilesets (the room indexes them by tile)
  icons/raven16.png                            16 px icon sheet (indexed by cell)

sprites.json entry: {"p": page, "x": .., "y": .., "w": cellW, "h": cellH, "n": frames,
                     "fps": .., "nine": [l, t, r, b] (optional)}
Frame i of a strip sits at (x + i*w, y).

Usage:  python -I tools/pack_assets.py [--sheet preview.png]
"""
from __future__ import annotations

import argparse
import json
import math
import re
import shutil
import sys
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
ART = ROOT / "assets"
OUT = ROOT / "app" / "src" / "main" / "assets"
PAGE = 1024
PAD = 1

# ------------------------------------------------------------------ what goes in
# key prefix -> (glob relative to assets/, is_strip)
SOURCES = [
    ("cat", "gfx/ui/cats/*.png", True),
    ("ui/wood", "gfx/ui/theme/theme_wood/*.png", False),
    ("ui/dialog", "gfx/ui/dialog/*.png", False),
    ("ui/emote", "gfx/ui/emote/*.png", False),
    ("ui/flask", "gfx/ui/receptacle/receptacle_sphere/*.png", False),
    ("ui/bar", "gfx/ui/receptacle/receptacle_rectangle/*.png", False),
    ("ui/sunny", "gfx/ui/sunny/icons/*.png", False),
    ("ui/sunny", "gfx/ui/sunny/box_*.png", False),
    ("ui/sunny", "gfx/ui/sunny/label.png", False),
    ("crop", "gfx/ui/sunny/crops/*.png", False),
    ("treasure", "gfx/items/treasure/*.png", True),
    ("coin", "gfx/props/depths/coin/coin.png", True),
]

# Nine-patch insets (source pixels) for stretchable UI pieces. The wood panels have a
# 1 px inner shadow on the top/left of their window, so those sides are one wider.
NINE = {
    "ui/wood/nine_path_panel": (6, 6, 5, 5),
    "ui/wood/nine_path_panel_2": (6, 6, 5, 5),
    "ui/wood/nine_path_panel_3": (6, 6, 5, 5),
    "ui/wood/nine_path_panel_disabled": (6, 6, 5, 5),
    "ui/wood/nine_path_panel_interior": (4, 4, 4, 4),
    "ui/wood/nine_path_bg": (4, 4, 4, 4),
    "ui/wood/nine_path_bg_2": (4, 4, 4, 4),
    "ui/wood/nine_path_focus": (3, 3, 3, 3),
    "ui/wood/inventory_cell": (3, 3, 3, 3),
    "ui/wood/slider_progress": (4, 4, 4, 4),
    "ui/wood/slider_progress_hover": (4, 4, 4, 4),
    "ui/wood/button_normal": (3, 3, 3, 3),
    "ui/wood/button_hover": (3, 3, 3, 3),
    "ui/wood/button_pressed": (3, 3, 3, 3),
    "ui/wood/button_disabled": (3, 3, 3, 3),
    "ui/wood/tab_selected": (4, 4, 4, 2),
    "ui/wood/tab_unselected": (4, 4, 4, 2),
    "ui/wood/tab_hover": (4, 4, 4, 2),
    "ui/wood/tab_disabled": (4, 4, 4, 2),
    "ui/wood/tab_box": (5, 5, 5, 5),
    "ui/wood/tab_box_selected": (5, 5, 5, 5),
    "ui/wood/tab_box_over": (5, 5, 5, 5),
}

# Frames per second by animation name (cat strips).
FPS = [
    (r"walk", 10), (r"scratch", 10), (r"wash|paw", 8), (r"eat|meow|yawn|hiss|stand|hind", 6),
    (r"sit", 5), (r"lie|rest", 4), (r"sleep", 2), (r"", 6),
]

# Hand fixes when the frame detector guesses wrong: key -> frame count.
FRAME_OVERRIDES: dict[str, int] = {}


# ------------------------------------------------------------------ frame detection
def detect_frames(im: Image.Image) -> int:
    """Frame count of a horizontal strip with equal-width cells.

    Each cell width that divides the strip is scored by the share of cell boundaries
    that fall on a fully transparent column (every cell must hold pixels). Frames may
    touch, so the most frames with a score >= 0.65 wins; otherwise it is one image.
    """
    w, h = im.size
    alpha = im.getchannel("A").load()
    empty = [all(alpha[x, y] == 0 for y in range(h)) for x in range(w)]
    best = 1
    for n in range(2, w // 12 + 1):
        if w % n:
            continue
        cw = w // n
        if not 12 <= cw <= 64:
            continue
        if any(all(empty[i * cw:(i + 1) * cw]) for i in range(n)):
            continue
        hits = sum(1 for i in range(1, n) if empty[i * cw] or empty[i * cw - 1])
        if hits / (n - 1) >= 0.65:
            best = n
    return best


def fps_for(name: str) -> int:
    return next(f for pat, f in FPS if re.search(pat, name))


# ------------------------------------------------------------------ collect
def key_for(prefix: str, path: Path) -> str:
    stem = path.stem
    if prefix == "cat":
        m = re.match(r"cat(\d)_(.+)", stem)
        if m:
            return f"cat/{m.group(1)}/{m.group(2)}"
        m = re.match(r"hat_([a-z0-9]+)_(.+)", stem)
        return f"hat/{m.group(1)}/{m.group(2)}"
    return f"{prefix}/{stem}"


def collect():
    items = []
    for prefix, pattern, strip in SOURCES:
        for path in sorted(ART.glob(pattern)):
            im = Image.open(path).convert("RGBA")
            key = key_for(prefix, path)
            n = FRAME_OVERRIDES.get(key) or (detect_frames(im) if strip else 1)
            items.append({"key": key, "im": im, "n": n,
                          "fps": fps_for(path.stem) if strip else 0})
    # Cats and hats share strip layouts per animation; hats have clean gaps where cat
    # bodies touch, so every colour/hat takes the largest frame count seen.
    by_anim: dict[str, int] = {}
    for it in items:
        if it["key"].startswith(("cat/", "hat/")):
            anim = it["key"].split("/", 2)[2]
            by_anim[anim] = max(by_anim.get(anim, 1), it["n"])
    for it in items:
        if it["key"].startswith(("cat/", "hat/")) and it["key"] not in FRAME_OVERRIDES:
            it["n"] = by_anim[it["key"].split("/", 2)[2]]
    return items


# ------------------------------------------------------------------ pack
def pack(items):
    order = sorted(items, key=lambda it: (-it["im"].height, -it["im"].width))
    pages, x, y, shelf_h = [Image.new("RGBA", (PAGE, PAGE))], PAD, PAD, 0
    for it in order:
        w, h = it["im"].size
        if x + w + PAD > PAGE:
            x, y, shelf_h = PAD, y + shelf_h + PAD, 0
        if y + h + PAD > PAGE:
            pages.append(Image.new("RGBA", (PAGE, PAGE)))
            x, y, shelf_h = PAD, PAD, 0
        pages[-1].paste(it["im"], (x, y))
        it["p"], it["x"], it["y"] = len(pages) - 1, x, y
        x += w + PAD
        shelf_h = max(shelf_h, h)
    # crop the last page to what is used
    last = pages[-1]
    bbox = last.getbbox()
    if bbox:
        pages[-1] = last.crop((0, 0, PAGE, min(PAGE, 2 ** math.ceil(math.log2(bbox[3] + 1)))))
    return pages


def write(items, pages):
    sprites_dir = OUT / "sprites"
    if sprites_dir.exists():
        shutil.rmtree(sprites_dir)
    sprites_dir.mkdir(parents=True)
    names = []
    for i, page in enumerate(pages):
        name = f"atlas_{i}.png"
        page.save(sprites_dir / name, optimize=True)
        names.append(name)
    entries = {}
    for it in sorted(items, key=lambda it: it["key"]):
        w, h = it["im"].size
        e = {"p": it["p"], "x": it["x"], "y": it["y"], "w": w // it["n"], "h": h, "n": it["n"]}
        if it["fps"]:
            e["fps"] = it["fps"]
        if it["key"] in NINE:
            e["nine"] = list(NINE[it["key"]])
        entries[it["key"]] = e
    (sprites_dir / "sprites.json").write_text(
        json.dumps({"pages": names, "sprites": entries}, separators=(",", ":")), encoding="utf-8")

    room = OUT / "room"
    room.mkdir(parents=True, exist_ok=True)
    for src, dst in (("gfx/tiles/home/walls_floor.png", "walls_floor.png"),
                     ("gfx/tiles/home/interior.png", "interior.png"),
                     ("gfx/tiles/home/doors_windows_animation.png", "doors_windows.png")):
        shutil.copyfile(ART / src, room / dst)
    icons = OUT / "icons"
    icons.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(ART / "gfx/icons/raven_icons_16.png", icons / "raven16.png")
    return entries


def contact_sheet(items, path: Path, prefix="cat/1/"):
    sel = [it for it in items if it["key"].startswith(prefix)]
    s, row_h = 3, 34 * 3
    width = max(it["im"].width for it in sel) * s + 160
    img = Image.new("RGBA", (width, row_h * len(sel)), (60, 50, 60, 255))
    d = ImageDraw.Draw(img)
    for r, it in enumerate(sel):
        im = it["im"]
        cw = im.width // it["n"]
        img.alpha_composite(im.resize((im.width * s, im.height * s), Image.NEAREST), (160, r * row_h + 4))
        for i in range(1, it["n"]):
            d.line([(160 + i * cw * s, r * row_h), (160 + i * cw * s, r * row_h + im.height * s + 4)],
                   fill=(255, 220, 0, 255))
        d.text((4, r * row_h + 10), f"{it['key'][len(prefix):]}  n={it['n']}", fill=(255, 255, 255, 255))
    img.save(path)
    print(f"sheet: {path}")


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser()
    ap.add_argument("--sheet", type=Path)
    args = ap.parse_args()
    items = collect()
    pages = pack(items)
    entries = write(items, pages)
    total = sum((OUT / "sprites" / f"atlas_{i}.png").stat().st_size for i in range(len(pages)))
    print(f"{len(entries)} sprites in {len(pages)} page(s), {total / 1024:.0f} KB")
    if args.sheet:
        contact_sheet(items, args.sheet)
