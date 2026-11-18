"""Lay out the cat's room scenes.

corner.json  the little scene seen through the round window on the home screen: 64 x 64 px
             (4 x 4 tiles of the old game's home tilesets), stone wall with a window on top,
             wooden floor below. The app shows a 54 px circle of it, scaled by a whole number
             (x17 on a 1080 px phone), so the cat is about half the circle tall. The 2x2
             home-screen widget shows the whole square.
widget.json  the same room stretched sideways for the 4x2 home-screen widget: 144 x 48 px
             (9 x 3 tiles): two rows of wall, one row of floor, the bed at the left and the
             scratching post at the right.

Both are written to app/src/main/assets/room/ in this shape:
  tilesets: [{name, image, columns}]
  layers:   [{name, tiles: [..]}]   tile = 0 (empty) or tileset * 10000 + index + 1
  anims:    {"tileset:index": [[index, ms], ...]}   animated tiles (the window), from home.tmx
  circle:   [cx, cy, r]      the visible circle, in scene pixels (corner only)
  spots:    {name: [x, y]}   feet / bottom-centre positions in scene pixels:
            home            where the cat idles
            bowl, bowl_cat  food bowl, and where the cat stands to eat (facing left)
            yarn, yarn_cat  yarn ball, and where the cat stands to play (facing right)
            bed, bed_cat    cat bed, and the sleeping cat inside it
            decor, post     (widget only) a floor decoration and the scratching post

Usage:  python -I tools/build_room.py [--preview corner.png] [--widget-previews]

--widget-previews also writes the two pictures the launcher shows in its widget picker
(app/src/main/res/drawable-nodpi/widget_preview_*.png).
"""
from __future__ import annotations

import argparse
import json
import math
import random
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
ART = ROOT / "assets"
OUT = ROOT / "app" / "src" / "main" / "assets" / "room"
RES = ROOT / "app" / "src" / "main" / "res"
T = 16
# The visible circle inside the 64 x 64 corner: centre and radius in scene pixels. A 54 px
# circle makes the 26 px cat about half its height.
CIRCLE = (32, 35, 27)

TILESETS = [  # name, file in OUT, columns
    ("walls_floor", "walls_floor.png", 11),
    ("interior", "interior.png", 14),
    ("doors_windows", "doors_windows.png", 17),
]
WALL, INT, DOOR = 0, 1, 2
LAYERS = ["floor", "walls", "decor"]

CORNER_SPOTS = {
    "home": (32, 57),
    "bowl": (22, 57), "bowl_cat": (36, 57),
    "yarn": (42, 57), "yarn_cat": (28, 57),
    "bed": (32, 61), "bed_cat": (32, 57),
}
WIDGET_SPOTS = {
    "home": (74, 45),
    "bowl": (52, 45), "bowl_cat": (62, 45),
    "yarn": (94, 45), "yarn_cat": (82, 45),
    "bed": (24, 46), "bed_cat": (24, 41),
    "decor": (108, 45), "post": (126, 45),
}


class Scene:
    def __init__(self, w: int, h: int):
        self.w, self.h = w, h
        self.layers = {name: [0] * (w * h) for name in LAYERS}

    def set(self, layer, ts, idx, row, col):
        if 0 <= row < self.h and 0 <= col < self.w:
            self.layers[layer][row * self.w + col] = ts * 10000 + idx + 1

    def block(self, layer, ts, src_row, src_col, rows, cols, row, col):
        """Copy a whole rows x cols block from a tileset grid (multi-tile objects stay whole)."""
        columns = TILESETS[ts][2]
        for r in range(rows):
            for c in range(cols):
                self.set(layer, ts, (src_row + r) * columns + src_col + c, row + r, col + c)


def build(w: int, h: int, window_col: int) -> Scene:
    rnd = random.Random(3)
    s = Scene(w, h)
    # Wall: two rows of stone (the lower one with the skirting), middle pieces only -
    # the frame hides the ends.
    for c in range(w):
        s.set("walls", WALL, [34, 68, 34, 101][c % 4], 0, c)
        s.set("walls", WALL, [45, 79, 45, 112][c % 4], 1, c)
    for r in range(2, h):
        for c in range(w):
            s.set("floor", WALL, rnd.choice([103] * 6 + [104, 105, 116]), r, c)
    # Window (2 x 2, animated in home.tmx) above the cat.
    s.block("decor", DOOR, 0, 15, 2, 2, 0, window_col)
    return s


def window_anims() -> dict:
    root = ET.parse(ART / "maps" / "home.tmx").getroot()
    anims = {}
    for ts in root.findall("tileset"):
        if ts.get("name") != "doors_windows_animation":
            continue
        for tile in ts.findall("tile"):
            frames = [[int(f.get("tileid")), int(f.get("duration"))] for f in tile.iter("frame")]
            if frames:
                anims[f"{DOOR}:{tile.get('id')}"] = frames
    return anims


def write(scene: Scene, name: str, spots: dict, anims: dict, circle=None) -> dict:
    data = {
        "width": scene.w, "height": scene.h, "tile": T,
        "tilesets": [{"name": n, "image": f, "columns": c} for n, f, c in TILESETS],
        "layers": [{"name": n, "tiles": scene.layers[n]} for n in LAYERS],
        "anims": anims,
        "spots": {k: [x, y] for k, (x, y) in spots.items()},
    }
    if circle:
        data["circle"] = list(circle)
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / name).write_text(json.dumps(data, separators=(",", ":")), encoding="utf-8")
    old = OUT / "room.json"
    if old.exists():
        old.unlink()
    return data


def paint(data: dict, props: list[tuple[str, str]], cat: str = "cat/5/sit") -> Image.Image:
    """The scene at 1x with a cat at home and the given (sprite, spot) props."""
    w, h = data["width"], data["height"]
    sheets = [Image.open(OUT / ts["image"]).convert("RGBA") for ts in data["tilesets"]]
    img = Image.new("RGBA", (w * T, h * T), (42, 32, 39, 255))
    for layer in data["layers"]:
        for i, v in enumerate(layer["tiles"]):
            if not v:
                continue
            ts, idx = divmod(v - 1, 10000)
            cols = data["tilesets"][ts]["columns"]
            tile = sheets[ts].crop(((idx % cols) * T, (idx // cols) * T, (idx % cols + 1) * T, (idx // cols + 1) * T))
            img.alpha_composite(tile, ((i % w) * T, (i // w) * T))
    sprites = json.loads((OUT.parent / "sprites" / "sprites.json").read_text(encoding="utf-8"))
    pages = [Image.open(OUT.parent / "sprites" / p).convert("RGBA") for p in sprites["pages"]]

    def sprite(key, x, y, frame=0):  # x, y = bottom-centre
        s = sprites["sprites"][key]
        im = pages[s["p"]].crop((s["x"] + frame * s["w"], s["y"], s["x"] + (frame + 1) * s["w"], s["y"] + s["h"]))
        img.alpha_composite(im, (int(x - s["w"] / 2), int(y - s["h"])))

    spots = data["spots"]
    for key, spot in props:
        sprite(key, *spots[spot])
    sprite(cat, *spots["home"])
    return img


def render(data: dict, path: Path, scale: int = 8) -> None:
    """Preview: the corner, a cat at home, the bowl and yarn, masked to the circle."""
    img = paint(data, [("prop/pet_food_red", "bowl"), ("prop/yarnball", "yarn")])
    cx, cy, r = data["circle"]
    px = img.load()
    for y in range(img.height):
        for x in range(img.width):
            if math.hypot(x + 0.5 - cx, y + 0.5 - cy) > r:
                px[x, y] = (42, 32, 39, 255)
    img = img.crop((cx - r, cy - r, cx + r, cy + r))
    big = img.resize((img.width * scale, img.height * scale), Image.NEAREST)
    ImageDraw.Draw(big).ellipse([0, 0, big.width - 1, big.height - 1], outline=(138, 85, 54, 255), width=scale * 2)
    big.save(path)
    print(f"preview: {path}")


def widget_preview(img: Image.Image, path: Path, scale: int, radius: int) -> None:
    """What the launcher's widget picker shows: the scene on the cream card with round corners."""
    big = img.resize((img.width * scale, img.height * scale), Image.NEAREST)
    mask = Image.new("L", big.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, big.width - 1, big.height - 1], radius=radius, fill=255)
    pad = radius // 2
    card = Image.new("RGBA", (big.width + 2 * pad, big.height + 2 * pad), (0, 0, 0, 0))
    ImageDraw.Draw(card).rounded_rectangle([0, 0, card.width - 1, card.height - 1], radius=radius + pad, fill=(247, 243, 234, 255))
    card.paste(big, (pad, pad), mask)
    path.parent.mkdir(parents=True, exist_ok=True)
    card.save(path)
    print(f"widget preview: {path}")


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser()
    ap.add_argument("--preview", type=Path)
    ap.add_argument("--widget-previews", action="store_true")
    args = ap.parse_args()
    anims = window_anims()
    corner = write(build(4, 4, window_col=1), "corner.json", CORNER_SPOTS, anims, CIRCLE)
    widget = write(build(9, 3, window_col=4), "widget.json", WIDGET_SPOTS, anims)
    print(f"corner.json: 4x4 tiles, widget.json: 9x3 tiles, {len(anims)} animated tiles")
    if args.preview:
        render(corner, args.preview)
    if args.widget_previews:
        nodpi = RES / "drawable-nodpi"
        wide = paint(widget, [("prop/catbed", "bed"), ("prop/pet_food_red", "bowl"), ("prop/yarnball", "yarn"), ("decor/pot", "decor"), ("prop/scratchingpost", "post")])
        widget_preview(wide, nodpi / "widget_preview_wide.png", scale=4, radius=40)
        small = paint(corner, [("prop/pet_food_red", "bowl"), ("prop/yarnball", "yarn")])
        widget_preview(small, nodpi / "widget_preview_small.png", scale=4, radius=40)
