#!/usr/bin/env python3
"""Generate the DSH Pocket launcher icon (dark navy rounded square + terminal prompt).

Run:  python make_icon.py
Writes app/res/mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher.png
"""
import os
from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(HERE, "..", "res")
DENSITIES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}

BG = (14, 22, 38, 255)          # #0E1626
BG2 = (22, 34, 56, 255)
ACCENT = (76, 141, 255, 255)    # #4C8DFF
FB = (236, 242, 255, 255)      # text


def font(size):
    for name in ("arialbd.ttf", "segoeuib.ttf", "DejaVuSans-Bold.ttf", "arial.ttf"):
        p = os.path.join(os.environ.get("WINDIR", r"C:\Windows"), "Fonts", name)
        if os.path.exists(p):
            try:
                return ImageFont.truetype(p, size)
            except OSError:
                pass
    return ImageFont.load_default()


def rounded_mask(size, radius):
    m = Image.new("L", (size, size), 0)
    ImageDraw.Draw(m).rounded_rectangle((0, 0, size - 1, size - 1), radius=radius, fill=255)
    return m


def make(size):
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    radius = int(size * 0.22)
    d = ImageDraw.Draw(img)
    # subtle vertical gradient background
    grad = Image.new("RGBA", (size, size), BG)
    gd = ImageDraw.Draw(grad)
    for y in range(size):
        t = y / max(1, size - 1)
        c = tuple(int(BG[i] * (1 - t) + BG2[i] * t) for i in range(3)) + (255,)
        gd.line([(0, y), (size, y)], fill=c)
    img.paste(grad, (0, 0), rounded_mask(size, radius))
    # inner "terminal" panel
    pad = int(size * 0.17)
    d.rounded_rectangle((pad, pad, size - pad, size - pad), radius=int(size * 0.10),
                        outline=ACCENT, width=max(1, int(size * 0.025)))
    # ">_" prompt, drawn manually (font agnostic)
    stroke = max(2, int(size * 0.055))
    cy = int(size * 0.44)
    x0 = int(size * 0.30)
    d.line([(x0, cy - int(size * 0.13)), (x0 + int(size * 0.14), cy)], fill=ACCENT, width=stroke)
    d.line([(x0 + int(size * 0.14), cy), (x0, cy + int(size * 0.13))], fill=ACCENT, width=stroke)
    # underscore
    uy = int(size * 0.66)
    d.line([(int(size * 0.48), uy), (int(size * 0.70), uy)], fill=FB, width=stroke)
    return img


def main():
    for dens, size in DENSITIES.items():
        outdir = os.path.join(RES, "mipmap-" + dens)
        os.makedirs(outdir, exist_ok=True)
        out = os.path.join(outdir, "ic_launcher.png")
        make(size).save(out, "PNG")
        print("wrote", out, size)


if __name__ == "__main__":
    main()
