#!/usr/bin/env python3
"""Build the Moss launcher icon set from per-size renders of eye-icon.svg.

Two steps; the first needs node + sharp (see render_eye_icon.cjs):

    node icon-src/render_eye_icon.cjs icon-src/eye-icon.svg /tmp/eye-renders
    python3 icon-src/make_eye_icon.py /tmp/eye-renders

Output contract — same shape as the bear set it replaces, five densities,
lossless WebP:

  * ic_launcher_foreground / ic_launcher_background / ic_launcher_monochrome
    on the 108dp adaptive canvas (108/162/216/324/432 px)
  * ic_launcher / ic_launcher_round on the 48dp legacy canvas (48/72/96/144/192)

Layer choices, fixed by the artwork rather than by the old character
pipeline (andee/tools/make_launcher_icon.py is for cut-out characters and
does not apply to a full-bleed picture):

  * The SVG already carries its own near-black background and a rounded
    square clip, so the foreground is the render composited over the
    background colour: seamless on any launcher, and the adaptive mask
    still crops it to the device shape.
  * The background is that same edge colour, #020101 — the outermost stop
    of the SVG's own background gradient — so nothing the mask reveals
    introduces a new colour.
  * The monochrome layer is the render's own alpha (the rounded-square
    silhouette) in white: themed icons tint by alpha, so the shape is
    what matters, not the artwork inside it.
  * The round layer is the legacy icon under a circular mask.

mipmap-anydpi/ic_launcher.xml and ic_launcher_round.xml are unchanged:
they already point background + foreground + monochrome at these names.
"""
import sys
from pathlib import Path

import numpy as np
from PIL import Image

DENSITIES = [("mdpi", 1.0), ("hdpi", 1.5), ("xhdpi", 2.0), ("xxhdpi", 3.0), ("xxxhdpi", 4.0)]
ADAPTIVE_DP = 108.0
LEGACY_DP = 48.0
BG = (2, 1, 1)  # #020101, the SVG background gradient's outer stop


def webp(im: Image.Image, path: Path):
    im.save(path, "WEBP", lossless=True, method=6)


def main() -> int:
    renders = Path(sys.argv[1]) if len(sys.argv) > 1 else None
    if renders is None or not renders.is_dir():
        print("usage: python3 make_eye_icon.py <renders-dir>  (see render_eye_icon.cjs)")
        return 2
    res = Path(__file__).resolve().parent.parent / "app" / "src" / "main" / "res"
    total = 0
    for name, dpi in DENSITIES:
        out = res / f"mipmap-{name}"
        out.mkdir(parents=True, exist_ok=True)
        cvs = int(round(ADAPTIVE_DP * dpi))
        lgs = int(round(LEGACY_DP * dpi))

        adaptive = Image.open(renders / f"eye-{cvs}.png").convert("RGBA")
        flat_adaptive = Image.new("RGB", (cvs, cvs), BG)
        flat_adaptive.paste(adaptive, (0, 0), adaptive)
        webp(flat_adaptive, out / "ic_launcher_foreground.webp")
        webp(Image.new("RGB", (cvs, cvs), BG), out / "ic_launcher_background.webp")

        alpha = np.asarray(adaptive)[:, :, 3]
        mono = np.dstack([np.full_like(alpha, 255)] * 3 + [alpha]).astype(np.uint8)
        webp(Image.fromarray(mono, "RGBA"), out / "ic_launcher_monochrome.webp")

        legacy = Image.open(renders / f"eye-{lgs}.png").convert("RGBA")
        flat = Image.new("RGB", (lgs, lgs), BG)
        flat.paste(legacy, (0, 0), legacy)
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
        print(f"  {name:7s} adaptive {cvs:3d} legacy {lgs:3d}")
    print(f"wrote {5 * len(DENSITIES)} files, {total / 1024:.0f}KB total")
    return 0


if __name__ == "__main__":
    sys.exit(main())
