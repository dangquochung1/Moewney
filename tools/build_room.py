"""Lay out the cat room (portrait, 11 x 18 tiles of 16 px) from the old game's home tilesets.

Writes app/src/main/assets/room/room.json:
  tilesets: [{name, image, columns}]
  layers:   [{name, tiles: [..]}]   tile = 0 (empty) or tileset * 10000 + index + 1
  anims:    {"tileset:index": [[index, ms], ...]}   animated tiles (windows), from home.tmx
  hotspots: [{id, x, y, w, h}]   room pixels; tapping one opens a module
  spots:    {name: [x, y]}       where the cat can go (feet position)

Usage:  python -I tools/build_room.py [--preview room.png]
"""
from __future__ import annotations

import argparse
import json
import random
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
ART = ROOT / "assets"
OUT = ROOT / "app" / "src" / "main" / "assets" / "room"
W, H, T = 11, 18, 16

TILESETS = [  # name, file in OUT, columns
    ("walls_floor", "walls_floor.png", 11),
    ("interior", "interior.png", 14),
    ("doors_windows", "doors_windows.png", 17),
]
WALL, INT, DOOR = 0, 1, 2
LAYERS = ["floor", "walls", "rugs", "furniture", "decor"]


class Room:
    def __init__(self):
        self.layers = {name: [0] * (W * H) for name in LAYERS}

    def set(self, layer, ts, idx, row, col):
        if 0 <= row < H and 0 <= col < W:
            self.layers[layer][row * W + col] = ts * 10000 + idx + 1

    def block(self, layer, ts, src_row, src_col, rows, cols, row, col, skip=()):
        """Copy a rows x cols block of tiles from a tileset grid."""
        columns = TILESETS[ts][2]
        for r in range(rows):
            for c in range(cols):
                idx = (src_row + r) * columns + src_col + c
                if idx not in skip:
                    self.set(layer, ts, idx, row + r, col + c)


def build() -> Room:
    rnd = random.Random(7)
    room = Room()
    # ---- floor: wood planks with a few worn variants
    for r in range(1, H - 1):
        for c in range(1, W - 1):
            room.set("floor", WALL, rnd.choice([103] * 14 + [104, 105, 106, 116]), r, c)
    # ---- outer frame
    room.set("walls", WALL, 133, 0, 0); room.set("walls", WALL, 135, 0, W - 1)
    room.set("walls", WALL, 155, H - 1, 0); room.set("walls", WALL, 157, H - 1, W - 1)
    for c in range(1, W - 1):
        room.set("walls", WALL, 134, 0, c)
        room.set("walls", WALL, 156, H - 1, c)
    for r in range(1, H - 1):
        room.set("walls", WALL, 144, r, 0)
        room.set("walls", WALL, 146, r, W - 1)
    # ---- back wall: cream cap, two rows of stone, skirting
    stone_top = [34, 34, 68, 34, 101, 34, 34]
    stone_low = [45, 45, 79, 45, 112, 45, 45]
    for i, c in enumerate(range(1, W - 1)):
        last = c == W - 2
        room.set("walls", WALL, 14 if c == 1 else 15 if last else 23, 1, c)
        room.set("walls", WALL, 25 if c == 1 else 26 if last else stone_top[i % 7], 2, c)
        room.set("walls", WALL, 36 if c == 1 else 37 if last else stone_low[i % 7], 3, c)
    # ---- side borders and bottom skirting
    for r in range(4, H - 2):
        room.set("walls", WALL, 13, r, 1)
        room.set("walls", WALL, 11, r, W - 2)
    room.set("walls", WALL, 47, H - 2, 1)
    room.set("walls", WALL, 48, H - 2, W - 2)
    for c in range(2, W - 2):
        room.set("walls", WALL, 1, H - 2, c)
    # ---- window (animated in home.tmx: doors_windows 15,16 / 32,33)
    room.block("decor", DOOR, 0, 15, 2, 2, 2, 2)
    # ---- jar shelf: empty 3x4 wooden shelf, jars are drawn on it by the app
    room.block("furniture", INT, 6, 5, 4, 3, 1, 6)
    # ---- bed + nightstand under the window
    room.block("furniture", INT, 1, 4, 2, 2, 4, 1)
    room.block("furniture", INT, 1, 8, 2, 2, 4, 3, skip={22})
    # ---- round rug in the middle
    room.block("rugs", INT, 16, 1, 6, 7, 8, 2, skip={225, 231, 295, 301})
    # ---- armchair (left) and writing desk + chair (right, lower)
    room.block("furniture", INT, 10, 1, 3, 2, 11, 1)
    room.block("furniture", INT, 4, 8, 2, 2, 13, 7)       # small table
    room.block("furniture", INT, 13, 5, 2, 1, 13, 6)      # chair facing the table
    # ---- potted plants (habits) along the right wall
    for top, row in ((328, 5), (329, 7), (326, 9)):   # plants are 1 x 2 tiles
        room.set("decor", INT, top, row, 9)
        room.set("decor", INT, top + 14, row + 1, 9)
    # ---- small things on the desk / nightstand
    room.set("decor", INT, 337, 13, 8)
    return room


HOTSPOTS = [  # id, tile rect (col, row, cols, rows)
    ("jars", (6, 1, 3, 4)),
    ("calendar", (4, 2, 1, 2)),
    ("plants", (9, 5, 1, 6)),
    ("desk", (6, 13, 3, 2)),
    ("bed", (1, 4, 2, 2)),
    ("gift", (2, 15, 1, 1)),
    ("window", (2, 2, 2, 2)),
]
SPOTS = {  # feet positions in room pixels
    "rug": (5.5 * T, 11.2 * T), "bed": (2.0 * T, 5.6 * T), "shelf": (7.5 * T, 5.8 * T),
    "desk": (6.4 * T, 15.6 * T), "plants": (8.4 * T, 8.2 * T), "door": (5.0 * T, 15.8 * T),
    "armchair": (2.0 * T, 12.4 * T),
}


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


def write(room: Room) -> dict:
    data = {
        "width": W, "height": H, "tile": T,
        "tilesets": [{"name": n, "image": f, "columns": c} for n, f, c in TILESETS],
        "layers": [{"name": n, "tiles": room.layers[n]} for n in LAYERS],
        "anims": window_anims(),
        "hotspots": [{"id": i, "x": c * T, "y": r * T, "w": w * T, "h": h * T}
                     for i, (c, r, w, h) in HOTSPOTS],
        "spots": {k: [round(x), round(y)] for k, (x, y) in SPOTS.items()},
    }
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "room.json").write_text(json.dumps(data, separators=(",", ":")), encoding="utf-8")
    return data


def render(data: dict, path: Path, scale: int = 4) -> None:
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

    d = ImageDraw.Draw(img)
    # placeholder wall calendar + jars as the app will draw them
    d.rectangle([4 * T + 2, 2 * T + 2, 5 * T - 2, 3 * T + 6], fill=(246, 231, 200, 255), outline=(60, 40, 30, 255))
    d.rectangle([4 * T + 2, 2 * T + 2, 5 * T - 2, 2 * T + 5], fill=(200, 60, 50, 255))
    for i, lvl in enumerate([0.8, 0.4, 0.9, 0.2, 0.6, 1.0]):
        jx = 6 * T + 4 + (i % 3) * 15
        jy = 2 * T + 3 + (i // 3) * 16
        d.rectangle([jx, jy, jx + 7, jy + 10], outline=(230, 240, 255, 255))
        d.rectangle([jx + 1, jy + 1 + int(9 * (1 - lvl)), jx + 6, jy + 9], fill=(90, 200, 120, 255))
    sprite("treasure/littletreasurechest", 2.5 * T, 16 * T)
    sprite("cat/1/sit", *data["spots"]["rug"])
    sprite("cat/3/sleep1_r", *data["spots"]["bed"])
    for hid in data["hotspots"]:
        d.rectangle([hid["x"], hid["y"], hid["x"] + hid["w"] - 1, hid["y"] + hid["h"] - 1], outline=(80, 220, 255, 120))
    img.resize((img.width * scale, img.height * scale), Image.NEAREST).save(path)
    print(f"preview: {path}")


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser()
    ap.add_argument("--preview", type=Path)
    args = ap.parse_args()
    data = write(build())
    print(f"room.json: {W}x{H} tiles, {len(data['anims'])} animated tiles, {len(data['hotspots'])} hotspots")
    if args.preview:
        render(data, args.preview)
