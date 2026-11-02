#!/usr/bin/env python3
# Turns CarPictureDump's PNGs into the WebP files the app ships.
#
#   pack_pictures.py <dump dir> <out dir>
import os
import sys

from PIL import Image

CELL = 160
COLUMNS = 9
ROWS = 8


def main():
    source, out = sys.argv[1], sys.argv[2]
    os.makedirs(out, exist_ok=True)
    for key in ("blue", "red"):
        poster = Image.open(os.path.join(source, f"poster_{key}.png"))
        poster.resize((960, 600), Image.LANCZOS).save(os.path.join(out, f"poster_{key}.webp"), quality=86, method=6, alpha_quality=80)
        sheet = Image.open(os.path.join(source, f"map_{key}.png"))
        sheet.resize((CELL * COLUMNS, CELL * ROWS), Image.LANCZOS).save(os.path.join(out, f"map_{key}.webp"), quality=84, method=6, alpha_quality=80)


if __name__ == "__main__":
    main()
