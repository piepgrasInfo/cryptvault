#!/usr/bin/env python3
"""Give the legacy launcher PNGs the adaptive icon's own silhouette.

The app-launcher-icon-creator skill generates mipmap-<density>/ic_launcher.png
by flattening the two adaptive layers onto a full square. On this project that
file is never a launcher icon - minSdk is 26, so every launcher uses
mipmap-anydpi/ic_launcher.xml and applies its own mask - but it is still what
F-Droid and other store tooling pull out of the APK for a listing thumbnail,
which is why it is kept rather than deleted.

A full-bleed square is the wrong shape for that job, and lint says so
(IconLauncherShape: "Launcher icons should not fill every pixel of their square
region"). This masks each one with the squircle Android itself uses, so the
extracted icon has the shape a user would recognise and the lint baseline in
CLAUDE.md stays at 18 warnings per flavor.

Run it after every regeneration of the icon set:

    python3 scripts/shape_legacy_launcher_icons.py
"""

import math
import sys
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw

RES = Path(__file__).resolve().parent.parent / "app/src/main/res"
EXPONENT = 4.0     # squircle |x|^n + |y|^n = 1; 4 is the Android mask
SUPERSAMPLE = 8


def squircle(size: int) -> Image.Image:
    dim = size * SUPERSAMPLE
    radius = dim / 2 - 0.5
    points = []
    for step in range(1440):
        angle = step * math.pi / 720
        cos, sin = math.cos(angle), math.sin(angle)
        points.append((
            dim / 2 + radius * math.copysign(abs(cos) ** (2 / EXPONENT), cos),
            dim / 2 + radius * math.copysign(abs(sin) ** (2 / EXPONENT), sin),
        ))
    mask = Image.new("L", (dim, dim), 0)
    ImageDraw.Draw(mask).polygon(points, fill=255)
    return mask.resize((size, size), Image.LANCZOS)


def main() -> int:
    icons = sorted(RES.glob("mipmap-*/ic_launcher.png"))
    if not icons:
        print(f"no mipmap-*/ic_launcher.png under {RES}", file=sys.stderr)
        return 1
    for path in icons:
        img = Image.open(path).convert("RGBA")
        img.putalpha(ImageChops.multiply(img.getchannel("A"), squircle(img.width)))
        img.save(path, format="PNG", optimize=True)
        print(f"shaped {path.relative_to(RES.parents[3])} ({img.width}px)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
