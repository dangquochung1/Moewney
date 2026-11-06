"""Build the "Meowney Pixel" fonts: Pixeloid Sans + the 32 Vietnamese glyphs it lacks.

Pixeloid (GGBotNet, SIL OFL 1.1) has every Vietnamese letter except the ones that
stack a tone mark on a circumflex or breve: ấ ầ ẩ ẫ ắ ằ ẳ ẵ ế ề ể ễ ố ồ ổ ỗ (+ capitals).

* Regular: the old game's bitmap font (assets/fonts/pixeloid_9.fnt, rendered from
  Pixeloid at 1 px = 204 units with those glyphs hand-drawn) has all of them, so each
  missing glyph is traced from it pixel by pixel.
* Bold: no bitmap exists, so each glyph is the bold base (â ê ô ă ...) plus the bold
  tone mark (taken from á à ả ã minus the plain letter), stacked exactly where the
  regular bitmap stacks it.

The ascent grows from 9 px to 11 px so the stacked capitals (Ấ is 11 px tall) are not
clipped. The OFL reserves the name "Pixeloid", so the family is renamed.

Usage:  python -I tools/patch_font.py [--preview out.png]
"""
from __future__ import annotations

import argparse
import re
import sys
import unicodedata
from pathlib import Path

from fontTools.pens.pointInsidePen import PointInsidePen
from fontTools.pens.ttGlyphPen import TTGlyphPen
from fontTools.ttLib import TTFont
from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "assets" / "packs" / "Pixeloid_Font_1_0" / "TrueType (.ttf)"
FNT = ROOT / "assets" / "fonts" / "pixeloid_9.fnt"
OUT_DIR = ROOT / "app" / "src" / "main" / "res" / "font"

U = 204          # font units per pixel (unitsPerEm 1836 = 9 px)
BASE = 11        # bitmap rows from line top to baseline
ASCENT_PX = 11   # room for Ấ
DESCENT_PX = 2

VIETNAMESE = (
    "aàáảãạăằắẳẵặâầấẩẫậeèéẻẽẹêềếểễệiìíỉĩịoòóỏõọôồốổỗộơờớởỡợuùúủũụưừứửữựyỳýỷỹỵđ"
)
VIETNAMESE += VIETNAMESE.upper()
TONES = {"̀", "́", "̉", "̃"}  # grave, acute, hook, tilde


# ---------------------------------------------------------------- pixels <-> glyphs
def raster(font: TTFont, glyph_name: str) -> set[tuple[int, int]]:
    """Pixels (x, y) a glyph covers; y = 0 is the row just above the baseline."""
    glyph_set = font.getGlyphSet()
    glyph = glyph_set[glyph_name]
    pixels = set()
    for y in range(-4, 13):
        for x in range(-2, 12):
            pen = PointInsidePen(glyph_set, ((x + 0.5) * U, (y + 0.5) * U))
            glyph.draw(pen)
            if pen.getResult():
                pixels.add((x, y))
    return pixels


def to_glyph(pixels: set[tuple[int, int]]):
    """One clockwise rectangle per horizontal run of pixels."""
    pen = TTGlyphPen(None)
    for y in sorted({p[1] for p in pixels}):
        xs = sorted(p[0] for p in pixels if p[1] == y)
        start = prev = xs[0]
        for x in xs[1:] + [None]:
            if x is not None and x == prev + 1:
                prev = x
                continue
            x0, x1, y0, y1 = start * U, (prev + 1) * U, y * U, (y + 1) * U
            pen.moveTo((x0, y0))
            pen.lineTo((x0, y1))
            pen.lineTo((x1, y1))
            pen.lineTo((x1, y0))
            pen.closePath()
            if x is not None:
                start = prev = x
    return pen.glyph()


def show(pixels: set[tuple[int, int]]) -> str:
    if not pixels:
        return "(empty)"
    xs = [p[0] for p in pixels]
    ys = [p[1] for p in pixels]
    return "\n".join(
        "".join("#" if (x, y) in pixels else "." for x in range(min(xs), max(xs) + 1))
        for y in range(max(ys), min(ys) - 1, -1)
    )


# ---------------------------------------------------------------- bitmap font
def read_bitmap() -> dict[int, tuple[set[tuple[int, int]], int]]:
    """codepoint -> (pixels in font coordinates, advance px) from pixeloid_9.fnt."""
    text = FNT.read_text(encoding="utf-8")
    page = re.search(r'^page id=0 file="([^"]+)"', text, re.M).group(1)
    atlas = Image.open(FNT.parent / page).convert("RGBA")
    px = atlas.load()
    alpha_used = any(px[i, j][3] < 255 for i in range(atlas.width) for j in range(atlas.height))
    glyphs = {}
    pattern = (r"^char id=(\d+)\s+x=(\d+)\s+y=(\d+)\s+width=(\d+)\s+height=(\d+)"
               r"\s+xoffset=(-?\d+)\s+yoffset=(-?\d+)\s+xadvance=(\d+)")
    for m in re.finditer(pattern, text, re.M):
        cid, x, y, w, h, xo, yo, xa = map(int, m.groups())
        pixels = set()
        for i in range(w):
            for j in range(h):
                r, g, b, a = px[x + i, y + j]
                on = a >= 128 if alpha_used else max(r, g, b) >= 128
                if on:
                    pixels.add((xo + i, BASE - 1 - (yo + j)))
        glyphs[cid] = (pixels, xa)
    return glyphs


# ---------------------------------------------------------------- helpers
def glyph_name_for(font: TTFont, ch: str) -> str | None:
    return font.getBestCmap().get(ord(ch))


def split(ch: str) -> tuple[str, str, str]:
    """'ấ' -> ('a', 'â', U+0301): plain letter, letter with circumflex/breve, tone."""
    nfd = unicodedata.normalize("NFD", ch)
    tone = next(c for c in nfd if c in TONES)
    rest = "".join(c for c in nfd if c != tone)
    return nfd[0], unicodedata.normalize("NFC", rest), tone


def tone_mark(font: TTFont, letter: str, tone: str) -> set[tuple[int, int]]:
    """Pixels of a tone mark as drawn on a plain letter, e.g. 'á' minus 'a'."""
    marked = unicodedata.normalize("NFC", letter + tone)
    return raster(font, glyph_name_for(font, marked)) - raster(font, glyph_name_for(font, letter))


def find_shift(mark: set, placed: set) -> tuple[int, int] | None:
    ax = min(p[0] for p in mark); ay = min(p[1] for p in mark)
    bx = min(p[0] for p in placed); by = min(p[1] for p in placed)
    dx, dy = bx - ax, by - ay
    return (dx, dy) if {(x + dx, y + dy) for x, y in mark} == placed else None


def add_glyph(font: TTFont, name: str, codepoint: int, pixels: set, advance: int) -> None:
    glyph = to_glyph(pixels)
    order = font.getGlyphOrder()
    if name not in order:
        font.setGlyphOrder(order + [name])
    font["glyf"][name] = glyph
    glyph.recalcBounds(font["glyf"])
    font["hmtx"][name] = (advance, glyph.xMin)
    for table in font["cmap"].tables:
        if table.isUnicode():
            table.cmap[codepoint] = name


def rename(font: TTFont, style: str) -> None:
    family = "Meowney Pixel"
    ps = f"MeowneyPixel-{style}"
    values = {1: family, 2: style, 3: f"{ps};patched", 4: f"{family} {style}".replace(" Regular", ""),
              6: ps, 16: family, 17: style}
    name = font["name"]
    for rec in list(name.names):
        if rec.nameID in values or rec.nameID in (16, 17, 21, 22):
            name.removeNames(nameID=rec.nameID)
    for nid, val in values.items():
        name.setName(val, nid, 3, 1, 0x409)
        name.setName(val, nid, 1, 0, 0)
    note = ("Meowney Pixel is a modified version of Pixeloid by GGBotNet "
            "(https://ggbot.net/fonts/), adding stacked Vietnamese diacritics.")
    name.setName(note, 10, 3, 1, 0x409)


def fix_metrics(font: TTFont) -> None:
    asc, desc = ASCENT_PX * U, DESCENT_PX * U
    font["hhea"].ascent, font["hhea"].descent, font["hhea"].lineGap = asc, -desc, 0
    os2 = font["OS/2"]
    os2.sTypoAscender, os2.sTypoDescender, os2.sTypoLineGap = asc, -desc, 0
    os2.usWinAscent, os2.usWinDescent = asc, desc
    for tag in ("DSIG", "SVG "):     # signature is void once edited; Android ignores SVG glyphs
        if tag in font:
            del font[tag]


# ---------------------------------------------------------------- build
def build(preview: Path | None) -> None:
    bitmap = read_bitmap()
    regular = TTFont(SRC / "PixeloidSans.ttf")
    bold = TTFont(SRC / "PixeloidSans-Bold.ttf")
    missing = [ch for ch in VIETNAMESE if glyph_name_for(regular, ch) is None]
    print(f"missing in Pixeloid: {len(missing)} -> {''.join(missing)}")

    for ch in missing:
        cp = ord(ch)
        name = f"uni{cp:04X}"
        pixels, adv_px = bitmap[cp]
        add_glyph(regular, name, cp, pixels, adv_px * U)

        # Where does the regular bitmap put the tone mark?
        letter, based, tone = split(ch)
        reg_base = raster(regular, glyph_name_for(regular, based))
        placed = pixels - reg_base
        shift = find_shift(tone_mark(regular, letter, tone), placed)
        bold_base = raster(bold, glyph_name_for(bold, based))
        bold_mark = tone_mark(bold, letter, tone)
        if shift is not None:
            dx, dy = shift
            bold_pixels = bold_base | {(x + dx, y + dy) for x, y in bold_mark}
        else:   # the bitmap redrew the mark: widen it the way Pixeloid Bold widens strokes
            bold_pixels = bold_base | placed | {(x + 1, y) for x, y in placed}
        bold_adv = bold["hmtx"][glyph_name_for(bold, based)][0]
        add_glyph(bold, name, cp, bold_pixels, bold_adv)
        print(f"  {ch}  shift={shift}")

    OUT_DIR.mkdir(parents=True, exist_ok=True)
    for font, style, file in ((regular, "Regular", "meowney_pixel_regular.ttf"),
                              (bold, "Bold", "meowney_pixel_bold.ttf")):
        fix_metrics(font)
        rename(font, style)
        font.save(OUT_DIR / file)
        check = TTFont(OUT_DIR / file).getBestCmap()
        still = [c for c in VIETNAMESE if ord(c) not in check]
        print(f"wrote {file}: missing Vietnamese = {len(still)}")

    if preview:
        render_preview(preview)


def render_preview(path: Path) -> None:
    from PIL import ImageDraw, ImageFont
    k = 4  # 1 font pixel = 4 image pixels (size = 9 px em * k)
    lines = [
        "Meowney - Sen ơi, hôm nay còn 152.000đ!",
        "ấ ầ ẩ ẫ ậ  ắ ằ ẳ ẵ ặ  ế ề ể ễ ệ  ố ồ ổ ỗ ộ",
        "Ấ Ầ Ẩ Ẫ Ậ  Ắ Ằ Ẳ Ẵ Ặ  Ế Ề Ể Ễ Ệ  Ố Ồ Ổ Ỗ Ộ",
        "Tiền Thiết yếu, Hưởng thụ, Tiết kiệm. Số dư.",
        "Trẫm đói, Sen ghi sổ đi rồi trẫm ăn.",
    ]
    fonts = [ImageFont.truetype(str(OUT_DIR / f), 9 * k)
             for f in ("meowney_pixel_regular.ttf", "meowney_pixel_bold.ttf")]
    line_h = (ASCENT_PX + DESCENT_PX + 1) * k
    img = Image.new("RGB", (1200, line_h * len(lines) * 2 + 16), (58, 38, 30))
    draw = ImageDraw.Draw(img)
    draw.fontmode = "1"  # no anti-aliasing: shows the pixel grid honestly
    y = 8
    for font in fonts:
        for line in lines:
            draw.text((8, y), line, font=font, fill=(250, 236, 210))
            y += line_h
    img.save(path)
    print(f"preview: {path}")


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser()
    ap.add_argument("--preview", type=Path)
    build(ap.parse_args().preview)
