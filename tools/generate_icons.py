#!/usr/bin/env python3
"""Generate launcher icons from tools/icon-source.b64 during CI.

Replaces the stock Kotatsu launcher icons (WebP) with the custom gold
calligraphy artwork on white, at every density the app ships:
legacy square, legacy round (circular mask) and the adaptive-icon
foreground (108dp canvas). Run from the repository root before Gradle.
"""
import base64
import io
import os
import sys

from PIL import Image, ImageDraw

RES = os.path.join("app", "src", "main", "res")
DENSITIES = {
    "mipmap-mdpi": (48, 108),
    "mipmap-hdpi": (72, 162),
    "mipmap-xhdpi": (96, 216),
    "mipmap-xxhdpi": (144, 324),
    "mipmap-xxxhdpi": (192, 432),
}


def main() -> int:
    with open(os.path.join("tools", "icon-source.b64"), "r", encoding="ascii") as fh:
        raw = base64.b64decode(fh.read())
    src = Image.open(io.BytesIO(raw)).convert("RGB")
    if src.size[0] != src.size[1]:
        side = min(src.size)
        left = (src.size[0] - side) // 2
        top = (src.size[1] - side) // 2
        src = src.crop((left, top, left + side, top + side))

    for folder, (legacy_px, fg_px) in DENSITIES.items():
        out_dir = os.path.join(RES, folder)
        os.makedirs(out_dir, exist_ok=True)
        for name in ("ic_launcher", "ic_launcher_round", "ic_launcher_foreground"):
            webp = os.path.join(out_dir, name + ".webp")
            if os.path.exists(webp):
                os.remove(webp)

        legacy = src.resize((legacy_px, legacy_px), Image.LANCZOS)
        legacy.save(os.path.join(out_dir, "ic_launcher.png"))

        round_img = Image.new("RGBA", (legacy_px, legacy_px), (0, 0, 0, 0))
        mask = Image.new("L", (legacy_px, legacy_px), 0)
        ImageDraw.Draw(mask).ellipse((0, 0, legacy_px, legacy_px), fill=255)
        round_img.paste(legacy.convert("RGBA"), (0, 0), mask)
        round_img.save(os.path.join(out_dir, "ic_launcher_round.png"))

        foreground = src.resize((fg_px, fg_px), Image.LANCZOS)
        foreground.save(os.path.join(out_dir, "ic_launcher_foreground.png"))
        print(f"{folder}: legacy {legacy_px}px, foreground {fg_px}px")
    return 0


if __name__ == "__main__":
    sys.exit(main())
