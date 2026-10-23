#!/usr/bin/env python3
"""Build the README screenshots from raw phone and simulator captures.

    /usr/bin/python3 tools/brand/make-showcase.py build/showcase    # writes docs/screenshots/

The input folder holds full-size, unedited captures named as in HERO and CARDS below. Keep it out of git: a raw
capture can show the account's name, other players' chat or a hover menu. Check every capture before a build;
this script frames what it's given and hides nothing.

    Android: adb exec-out screencap -p > android-117hd.png              (Pixel 7a, 2400x1080 landscape)
    iOS:     a screenshot taken on the iPhone, for 117 HD               (the simulator takes minutes to warm it up)
             xcrun simctl io <udid> screenshot ios-plugins.png          (iPhone 17 Pro Max simulator)

The simulator saves its captures portrait; they are turned to landscape here, island on the left as on the Pixel.

Each output is a capture in a drawn phone on the brand's stone backdrop (make-brand.py). The game draws 540 rows
and each phone scales that up to fill its screen, so a phone's screen here is about the game's own size: shown
smaller than the capture, it is also sharper.

Needs Pillow and NumPy (/usr/bin/python3 has both).
"""
import argparse
import importlib.util
import os
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

_spec = importlib.util.spec_from_file_location(
    "make_brand", os.path.join(os.path.dirname(os.path.abspath(__file__)), "make-brand.py"))
brand = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(brand)

OUT = os.path.join(brand.ROOT, "docs/screenshots")
WHITE = (255, 255, 255)
SAND = (226, 214, 196)  # the social preview's second line
EMBER = (255, 104, 24)  # the logo's flame, for the glow under the phones
HEAVY, MEDIUM = 8, 5  # faces in brand.FONT

# Phone shells. bezel and corner are fractions of the screen's short side; the camera sits at the top of the phone,
# which is on the left when it's held landscape.
PHONES = {
    "pixel": dict(bezel=0.042, corner=0.115, body=(22, 23, 26), rim=(74, 77, 84)),  # punch-hole camera
    "iphone": dict(bezel=0.030, corner=0.150, body=(12, 12, 14), rim=(150, 145, 138)),  # Dynamic Island
}

# (output, capture, phone kind)
HERO = [("ios-117hd.png", "iphone"), ("android-117hd.png", "pixel")]  # back to front
# (output, capture, phone kind, headline, line under it)
CARDS = [
    ("overlays.jpg", "android-overlays.png", "pixel", "Your overlays, on the go",
     "Ground items, hit splats, timers and the FPS counter, as on desktop."),
    ("plugins.jpg", "ios-plugins.png", "iphone", "Real RuneLite plugins",
     "Turn plugins on and off and change their settings in native screens."),
    ("renderers.jpg", "android-renderers.png", "pixel", "CPU, GPU or 117 HD",
     "Pick the renderer that suits your phone."),
    ("panels.jpg", "ios-panel.png", "iphone", "Side panels a tap away",
     "World Switcher, XP Tracker, Notes and more open beside the game."),
]


def capture(folder, name):
    path = os.path.join(folder, name)
    if not os.path.isfile(path):
        sys.exit(f"missing capture: {path}")
    shot = Image.open(path).convert("RGB")
    if shot.height > shot.width:  # a simulator capture: portrait framebuffer, top of the phone up
        shot = shot.rotate(90, expand=True)
    if name.startswith("android-"):
        # The Pixel's captures can carry a short red line along the top row; the row below is clean game picture.
        a = np.asarray(shot).copy()
        a[0:2] = a[2]
        shot = Image.fromarray(a)
    return shot


def alpha(cov):
    return Image.fromarray((np.clip(cov, 0, 1) * 255).round().astype("uint8"))


def phone(shot, kind, width):
    """`shot` in a drawn `kind` phone `width` px wide, as RGBA."""
    spec = PHONES[kind]
    ratio = shot.height / shot.width
    sw = round(width / (1 + 2 * spec["bezel"] * ratio))
    sh = round(sw * ratio)
    b = (width - sw) // 2
    w, h = sw + 2 * b, sh + 2 * b
    radius = spec["corner"] * h

    d = brand.sd_roundrect(w, h, radius)
    out = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    out.paste(Image.new("RGB", (w, h), spec["body"]), (0, 0), alpha(brand.coverage(d)))
    out.paste(Image.new("RGB", (w, h), spec["rim"]), (0, 0), alpha(brand.coverage(d) - brand.coverage(d + 3)))
    screen = shot.resize((sw, sh), Image.LANCZOS)
    out.paste(screen, (b, b), alpha(brand.coverage(brand.sd_roundrect(sw, sh, max(radius - b, 0)))))

    yy, xx = np.mgrid[0:h, 0:w].astype(float)
    if kind == "pixel":
        r = 0.021 * sh
        cam = np.hypot(xx + 0.5 - (b + 0.03 * sw), yy + 0.5 - h / 2) - r
        out.paste(Image.new("RGB", (w, h), (4, 4, 6)), (0, 0), alpha(brand.coverage(cam)))
    else:
        # Dynamic Island: 37 x 126 pt on a 956 x 440 pt screen, 11 pt in from the edge.
        iw, ih = 0.0387 * sw, 0.286 * sh
        cx, cy = b + 0.0115 * sw + iw / 2, h / 2
        qx = np.abs(xx + 0.5 - cx)  # a capsule: the corner radius is half the width
        qy = np.abs(yy + 0.5 - cy) - (ih / 2 - iw / 2)
        island = np.hypot(np.maximum(qx, 0), np.maximum(qy, 0)) + np.minimum(np.maximum(qx, qy), 0) - iw / 2
        out.paste(Image.new("RGB", (w, h), (0, 0, 0)), (0, 0), alpha(brand.coverage(island)))
    return out


def glow(canvas, box, strength):
    """A soft ember glow over `box` (x0, y0, x1, y1), behind whatever is pasted next."""
    g = Image.new("L", canvas.size, 0)
    ImageDraw.Draw(g).ellipse(box, fill=round(255 * strength))
    g = g.filter(ImageFilter.GaussianBlur(min(box[2] - box[0], box[3] - box[1]) / 3))
    canvas.paste(Image.new("RGB", canvas.size, EMBER), (0, 0), g)


def centred(draw, text, face, size, fill, y, width):
    font = ImageFont.truetype(brand.FONT, size, index=face)
    left, top, right, bottom = font.getbbox(text)
    draw.text(((width - (right - left)) // 2 - left, y - top), text, font=font, fill=fill)
    return y + bottom - top


def hero(master, folder):
    w, h = 2400, 1250
    canvas = brand.backdrop(master, w, h, 0.5)
    glow(canvas, (300, 260, 2100, 1150), 0.32)
    back = phone(capture(folder, HERO[0][0]), HERO[0][1], 1420)
    front = phone(capture(folder, HERO[1][0]), HERO[1][1], 1420)
    brand.shadow(canvas, back, (w - back.width - 90, 80), 36)
    brand.shadow(canvas, front, (90, h - front.height - 90), 36)
    return canvas


def card(master, folder, shot, kind, headline, line):
    w, pad, phone_w = 1600, 84, 1400
    dev = phone(capture(folder, shot), kind, phone_w)
    h = pad + 84 + 22 + 42 + 64 + dev.height + 96
    canvas = brand.backdrop(master, w, h, 0.5)
    draw = ImageDraw.Draw(canvas)
    y = centred(draw, headline, HEAVY, brand.fit_size(headline, HEAVY, 84, w - 2 * pad), WHITE, pad, w)
    y = centred(draw, line, MEDIUM, brand.fit_size(line, MEDIUM, 42, w - 2 * pad), SAND, y + 22, w)
    top = y + 64
    glow(canvas, (160, top + 80, w - 160, top + dev.height + 40), 0.28)
    brand.shadow(canvas, dev, ((w - dev.width) // 2, top), 30)
    return canvas


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("captures", help="folder of raw captures (keep it out of git)")
    args = ap.parse_args()
    master = Image.open(brand.MASTER).convert("RGB")
    os.makedirs(OUT, exist_ok=True)
    outputs = [("hero.jpg", hero(master, args.captures))]
    outputs += [(name, card(master, args.captures, *rest)) for name, *rest in CARDS]
    for name, img in outputs:
        path = os.path.join(OUT, name)
        img.save(path, quality=90, subsampling=0, optimize=True, progressive=True)  # 4:4:4 keeps the UI text crisp
        print(f"{os.path.relpath(path, brand.ROOT)}  {img.width}x{img.height}  {os.path.getsize(path) // 1024} KB")


if __name__ == "__main__":
    main()
