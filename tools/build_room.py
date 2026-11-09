"""Lay out the cat's corner: the little scene seen through the round window on the home screen.

The scene is 64 x 64 px (4 x 4 tiles of the old game's home tilesets): stone wall with a
window on top, wooden floor below. The app shows a 54 px circle of it, scaled by a whole
number (x17 on a 1080 px phone), so the cat is about half the circle tall.

Writes app/src/main/assets/room/corner.json:
  tilesets: [{name, image, columns}]
  layers:   [{name, tiles: [..]}]   tile = 0 (empty) or tileset * 10000 + index + 1
  anims:    {"tileset:index": [[index, ms], ...]}   animated tiles (the window), from home.tmx
  circle:   [cx, cy, r]      the visible circle, in scene pixels
  spots:    {name: [x, y]}   feet / bottom-centre positions in scene pixels:
            home            where the cat idles
            bowl, bowl_cat  food bowl, and where the cat stands to eat (facing left)
            yarn, yarn_cat  yarn ball, and where the cat stands to play (facing right)
            bed, bed_cat    cat bed, and the sleeping cat inside it

Usage:  python -I tools/build_room.py [--preview corner.png]
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
W, H, T = 4, 4, 16
# The visible circle inside the 64 x 64 scene: centre and radius in scene pixels. A 54 px
# circle makes the 26 px cat about half its height.
CIRCLE = (32, 35, 27)

TILESETS = [  # name, file in OUT, columns
    ("walls_floor", "walls_floor.png", 11),
    ("interior", "interior.png", 14),
    ("doors_windows", "doors_windows.png", 17),
]
WALL, INT, DOOR = 0, 1, 2
LAYERS = ["floor", "walls", "decor"]

SPOTS = {
    "home": (32, 57),
    "bowl": (22, 57), "bowl_cat": (36, 57),
    "yarn": (42, 57), "yarn_cat": (28, 57),
    "bed": (32, 61), "bed_cat": (32, 57),
}


class Scene:
    def __init__(self):
        self.layers = {name: [0] * (W * H) for name in LAYERS}

    def set(self, layer, ts, idx, row, col):
        if 0 <= row < H and 0 <= col < W:
            self.layers[layer][row * W + col] = ts * 10000 + idx + 1

    def block(self, layer, ts, src_row, src_col, rows, cols, row, col):
        """Copy a whole rows x cols block from a tileset grid (multi-tile objects stay whole)."""
        columns = TILESETS[ts][2]
        for r in range(rows):
            for c in range(cols):
                self.set(layer, ts, (src_row + r) * columns + src_col + c, row + r, col + c)


def build() -> Scene:
    rnd = random.Random(3)
    s = Scene()
    # Wall: two rows of stone (the lower one with the skirting), middle pieces only -
    # the circle hides the ends.
    for c in range(W):
        s.set("walls", WALL, [34, 68, 34, 101][c], 0, c)
        s.set("walls", WALL, [45, 79, 45, 112][c], 1, c)
    for r in range(2, H):
        for c in range(W):
            s.set("floor", WALL, rnd.choice([103] * 6 + [104, 105, 116]), r, c)
    # Window (2 x 2, animated in home.tmx) centred above the cat.
    s.block("decor", DOOR, 0, 15, 2, 2, 0, 1)
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


def write(scene: Scene) -> dict:
    data = {
        "width": W, "height": H, "tile": T,
        "tilesets": [{"name": n, "image": f, "columns": c} for n, f, c in TILESETS],
        "layers": [{"name": n, "tiles": scene.layers[n]} for n in LAYERS],
        "anims": window_anims(),
        "spots": {k: [x, y] for k, (x, y) in SPOTS.items()},
        "circle": list(CIRCLE),
    }
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "corner.json").write_text(json.dumps(data, separators=(",", ":")), encoding="utf-8")
    old = OUT / "room.json"
    if old.exists():
        old.unlink()
    return data


def render(data: dict, path: Path, scale: int = 8) -> None:
    """Preview: the scene, a cat at home, the bowl and yarn, masked to the circle."""
    sheets = [Image.open(OUT / ts["image"]).convert("RGBA") for ts in data["tilesets"]]
    img = Image.new("RGBA", (W * T, H * T), (42, 32, 39, 255))
    for layer in data["layers"]:
        for i, v in enumerate(layer["tiles"]):
            if not v:
                continue
            ts, idx = divmod(v - 1, 10000)
            cols = data["tilesets"][ts]["columns"]
            tile = sheets[ts].crop(((idx % cols) * T, (idx // cols) * T, (idx % cols + 1) * T, (idx // cols + 1) * T))
            img.alpha_composite(tile, ((i % W) * T, (i // W) * T))
    sprites = json.loads((OUT.parent / "sprites" / "sprites.json").read_text(encoding="utf-8"))
    pages = [Image.open(OUT.parent / "sprites" / p).convert("RGBA") for p in sprites["pages"]]

    def sprite(key, x, y, frame=0):  # x, y = bottom-centre
        s = sprites["sprites"][key]
        im = pages[s["p"]].crop((s["x"] + frame * s["w"], s["y"], s["x"] + (frame + 1) * s["w"], s["y"] + s["h"]))
        img.alpha_composite(im, (int(x - s["w"] / 2), int(y - s["h"])))

    spots = data["spots"]
    sprite("prop/pet_food_red", *spots["bowl"])
    sprite("prop/yarnball", *spots["yarn"])
    sprite("cat/5/sit", *spots["home"])
    # circle mask, then crop to the circle
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


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser()
    ap.add_argument("--preview", type=Path)
    args = ap.parse_args()
    data = write(build())
    print(f"corner.json: {W}x{H} tiles, {len(data['anims'])} animated tiles, spots {sorted(data['spots'])}")
    if args.preview:
        render(data, args.preview)
