#!/usr/bin/env python3
"""Build the Moss launcher icon set from the 550W wordmark master.

The master is a black square with the "550W" lockup and its two subtitle
lines in the middle (moss/icon-src/550w-logo.jpg). It is a raster picture,
not a cut-out, so there is no character pipeline to reuse: the crop is the
bounding box of everything brighter than near-black, and the box is fitted
by width so the lockup fills the tile the way the picture fills its frame:
a diagonal fit shrinks a wide, short lockup until the tile reads as the
source picture shrunk down instead of an icon. The width fit keeps the
glyphs inside every squircle and rounded-square mask; only a strict
inscribed-circle mask can reach the box corners, and there only the black
guard and the extreme corner of a glyph edge.

The subtitle lines stay in: they carry the MOSS name, and at icon sizes
they read as texture under the 550W. The background is solid black, the
master's own background colour, so parallax reveals nothing new. The
monochrome layer is white where the artwork is bright (luminance ramp
40..110), transparent where it is black.

Usage:
  python3 icon-src/make_550w_icon.py
"""
import sys
from pathlib import Path

import numpy as np
from PIL import Image

DENSITIES = [("mdpi", 1.0), ("hdpi", 1.5), ("xhdpi", 2.0), ("xxhdpi", 3.0), ("xxxhdpi", 4.0)]
ADAPTIVE_DP = 108.0
LEGACY_DP = 48.0
# Width fit: the lockup spans this fraction of the canvas. Fitted by width,
# not by the box diagonal — the box is wide and short, so a diagonal fit
# shrinks it until the tile reads as the source picture shrunk down instead
# of an icon. At 0.94 the box corners sit ~5% outside the inscribed circle,
# which only the strictest circle masks can reach, and there only the
# black guard and the extreme corner of a glyph edge; squircle and
# rounded-square masks (what the phone actually shows) keep everything.
WIDTH_FIT = 0.94
BG = (0, 0, 0)


def webp(im: Image.Image, path: Path):
    im.save(path, "WEBP", lossless=True, method=6)


def main() -> int:
    here = Path(__file__).resolve().parent
    src = Image.open(here / "550w-logo.jpg").convert("RGB")
    lum = np.asarray(src.convert("L")).astype(np.float64)
    ys, xs = np.where(lum > 30)
    # 3px of guard on every side: the glyphs' antialiased top rows sit under
    # any content threshold, and a flat-cut edge would show at icon sizes.
    pad = 3
    box = (max(0, int(xs.min()) - pad), max(0, int(ys.min()) - pad),
           min(src.width, int(xs.max()) + 1 + pad),
           min(src.height, int(ys.max()) + 1 + pad))
    crop = src.crop(box)
    cw, ch = crop.size
    print(f"master {src.size}  lockup {box} {cw}x{ch}")

    res = here.parent / "app" / "src" / "main" / "res"
    total = 0
    for name, dpi in DENSITIES:
        out = res / f"mipmap-{name}"
        out.mkdir(parents=True, exist_ok=True)
        cvs = int(round(ADAPTIVE_DP * dpi))
        lgs = int(round(LEGACY_DP * dpi))

        # ============ adaptive layers ============
        # Width fit, centred. See WIDTH_FIT.
        scale = WIDTH_FIT * cvs / cw
        w, h = max(8, int(round(cw * scale))), max(8, int(round(ch * scale)))
        ox, oy = (cvs - w) // 2, (cvs - h) // 2
        fg = Image.new("RGB", (cvs, cvs), BG)
        art = crop.resize((w, h), Image.LANCZOS)
        fg.paste(art, (ox, oy))
        webp(fg, out / "ic_launcher_foreground.webp")
        webp(Image.new("RGB", (cvs, cvs), BG), out / "ic_launcher_background.webp")

        grey = np.asarray(art.convert("L")).astype(np.float64)
        alpha = np.clip((grey - 40.0) / 70.0, 0.0, 1.0) * 255.0
        full = np.zeros((cvs, cvs), dtype=np.uint8)
        full[oy:oy + h, ox:ox + w] = alpha.astype(np.uint8)
        mono = np.dstack([np.full_like(full, 255)] * 3 + [full]).astype(np.uint8)
        webp(Image.fromarray(mono, "RGBA"), out / "ic_launcher_monochrome.webp")

        # ============ legacy raster (built separately, not shrunk down) ============
        lscale = WIDTH_FIT * lgs / cw
        lw, lh = max(8, int(round(cw * lscale))), max(8, int(round(ch * lscale)))
        lox, loy = (lgs - lw) // 2, (lgs - lh) // 2
        flat = Image.new("RGB", (lgs, lgs), BG)
        flat.paste(crop.resize((lw, lh), Image.LANCZOS), (lox, loy))
        webp(flat, out / "ic_launcher.webp")

        yy, xx = np.mgrid[0:lgs, 0:lgs]
        cc = (lgs - 1) / 2.0
        circ = Image.fromarray(
            ((np.hypot(xx - cc, yy - cc) <= cc + 0.5) * 255).astype(np.uint8), "L")
        rnd = Image.new("RGBA", (lgs, lgs), (0, 0, 0, 0))
        rnd.paste(flat, (0, 0), circ)
        webp(rnd, out / "ic_launcher_round.webp")

        total += sum((out / f).stat().st_size for f in (
            "ic_launcher.webp", "ic_launcher_round.webp", "ic_launcher_foreground.webp",
            "ic_launcher_background.webp", "ic_launcher_monochrome.webp"))
        print(f"  {name:7s} adaptive {cvs:3d} lockup {w}x{h}  legacy {lgs:3d} lockup {lw}x{lh}")
    print(f"wrote {5 * len(DENSITIES)} files, {total / 1024:.0f}KB total")
    return 0


if __name__ == "__main__":
    sys.exit(main())
