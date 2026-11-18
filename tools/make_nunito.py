"""Static Nunito weights for the app's text (the pixel font is only for decoration now).

Nunito (SIL OFL 1.1, https://github.com/googlefonts/nunito) ships as one variable font;
Android resources want one file per weight, so each weight is cut out with fontTools.

Output: app/src/main/res/font/nunito_{regular,semibold,bold,extrabold}.ttf and the licence
in app/src/main/assets/licenses/nunito_OFL.txt.

Usage:  python -I tools/make_nunito.py
"""
from __future__ import annotations

import shutil
import sys
from pathlib import Path

from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "assets" / "packs" / "Nunito"
OUT = ROOT / "app" / "src" / "main" / "res" / "font"
WEIGHTS = {"regular": 400, "semibold": 600, "bold": 700, "extrabold": 800}

if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    for name, wght in WEIGHTS.items():
        vf = TTFont(SRC / "Nunito-VF.ttf")
        static = instantiateVariableFont(vf, {"wght": wght}, updateFontNames=True)
        out = OUT / f"nunito_{name}.ttf"
        static.save(out)
        # every Vietnamese letter must be there
        cmap = static.getBestCmap()
        missing = [c for c in "ăâđêôơưấầẩẫậắằẳẵặếềểễệốồổỗộớờởỡợứừửữựỳỷỹỵ" if ord(c) not in cmap]
        print(f"{out.name}: {out.stat().st_size // 1024} KB, missing {missing or 'none'}")
    shutil.copyfile(SRC / "OFL.txt", ROOT / "app" / "src" / "main" / "assets" / "licenses" / "nunito_OFL.txt")
