#!/usr/bin/env python3
# Puts a render beside the photograph it copies, and over it, so differences show.
#
#   compare.py <out.png> <photo> <render> [<photo> <render> ...] [--width 900]
import sys

from PIL import Image, ImageChops


def fit(img, w, h):
    img = img.convert("RGB")
    scale = min(w / img.width, h / img.height)
    img = img.resize((round(img.width * scale), round(img.height * scale)), Image.LANCZOS)
    canvas = Image.new("RGB", (w, h), (30, 30, 30))
    canvas.paste(img, ((w - img.width) // 2, (h - img.height) // 2))
    return canvas


def main():
    args = sys.argv[1:]
    width = 900
    if "--width" in args:
        i = args.index("--width")
        width = int(args[i + 1])
        del args[i : i + 2]
    out, rest = args[0], args[1:]
    pairs = list(zip(rest[0::2], rest[1::2]))
    height = round(width * 0.68)
    sheet = Image.new("RGB", (width * 3, height * len(pairs)), (0, 0, 0))
    for row, (photo, render) in enumerate(pairs):
        a = fit(Image.open(photo), width, height)
        b = fit(Image.open(render), width, height)
        sheet.paste(a, (0, row * height))
        sheet.paste(b, (width, row * height))
        sheet.paste(ImageChops.blend(a, b, 0.5), (width * 2, row * height))
    sheet.save(out)


if __name__ == "__main__":
    main()
