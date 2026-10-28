#!/usr/bin/env python3
"""Build the README screenshots from raw phone and simulator captures.

    /usr/bin/python3 tools/brand/make-showcase.py build/showcase    # writes docs/screenshots/

The input folder holds full-size, unedited captures named as in HERO and CARDS below. Keep it out of git: a raw
capture can show the account's name, other players' chat or a hover menu. Check every capture before a build.

The chat's input line shows the account's name. The folder's redact.json lists a box over it for each capture,
{"<capture>": [[x, y, w, h], ...]} in the capture's own px, and the build paints each box over from the picture
just below it, so the name is gone, not blurred. Cover the cursor after the name too. The build stops if redact.json is missing, or names a capture no output uses. Read every output for the
name before publishing: text recognition misses the portrait chat's small text.

    iOS:     xcrun simctl io <udid> screenshot ios-hub.png       (iPhone 17 Pro Max simulator)
    Android: adb exec-out screencap -p > android-117hd.png        (Pixel 7a)

A capture is used the way it stands, so a phone in a card is landscape or portrait as its capture is. The simulator
saves its framebuffer portrait whichever way the game is turned: turn a landscape one a quarter left first
(top of the game on the left, island on the left), e.g. Image.rotate(90, expand=True).

Each output is a capture in a drawn phone on the brand's stone backdrop (make-brand.py). The hero pairs a
landscape phone with a portrait one; a card is a headline over a landscape phone, or beside a portrait one, all
cards the same size so the README's table lines up.

Needs Pillow and NumPy (/usr/bin/python3 has both).
"""
import argparse
import importlib.util
import json
import os
import sys
import textwrap

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

# (capture, phone kind): the landscape phone, then the portrait one in front of it
HERO = [("android-hero-land.png", "pixel"), ("ios-hero-port.png", "iphone")]
# (output, capture, phone kind, headline, line under it). The README shows them two to a row, in this order.
CARDS = [
    ("quest-helper.jpg", "ios-quest-helper.png", "iphone", "Quest Helper",
     "Every quest step on screen, and the way there drawn for you."),
    ("shortest-path.jpg", "ios-shortest-path.png", "iphone", "Shortest Path",
     "Pick a place and walk the route it draws on the ground and the minimap."),
    ("plugin-hub.jpg", "ios-plugin-hub.png", "iphone", "The Plugin Hub",
     "Thousands of community plugins, installed with a tap."),
    ("117hd.jpg", "android-117hd.png", "pixel", "117 HD",
     "Sunlight, shadows and the seasons, from the HD renderer."),
    ("menu.jpg", "ios-menu.png", "iphone", "Hold for every option",
     "The right-click menu, with the options plugins add."),
    ("menu-entry-swapper.jpg", "ios-menu-entry-swapper.png", "iphone", "Menu Entry Swapper",
     "Choose what a tap does, in native settings for every plugin."),
]
CARD_W, CARD_H, PAD = 1600, 1060, 84


def boxes(folder):
    """redact.json: the boxes to paint over in each capture."""
    path = os.path.join(folder, "redact.json")
    if not os.path.isfile(path):
        sys.exit(f"missing {path}: list the boxes over the account's name, or {{}} if no capture shows it")
    with open(path) as f:
        redact = json.load(f)
    used = {name for name, _ in HERO} | {c[1] for c in CARDS}
    for name, rects in redact.items():
        if name not in used:
            sys.exit(f"redact.json names {name}, which no output uses")
        if not all(len(r) == 4 and all(isinstance(v, int) for v in r) and r[2] > 0 and r[3] > 0 for r in rects):
            sys.exit(f"redact.json: {name} needs [[x, y, w, h], ...] in whole px")
    return redact


def erase(shot, x, y, w, h):
    """Paint over the box with the row of px just below it, softened sideways. Not the row above: in portrait that is
    the chat's divider line."""
    if x < 4 or x + w + 4 > shot.width or y < 0 or y + h >= shot.height:
        sys.exit(f"redact box {[x, y, w, h]} isn't inside the {shot.width}x{shot.height} capture")
    a = np.asarray(shot).astype(float)
    below = a[y + h, x - 4:x + w + 4]
    soft = np.stack([np.convolve(below[:, c], np.ones(9) / 9, mode="valid") for c in range(3)], axis=1)
    a[y:y + h, x:x + w] = soft[None]
    return Image.fromarray(a.round().astype("uint8"))


def capture(folder, name, redact):
    path = os.path.join(folder, name)
    if not os.path.isfile(path):
        sys.exit(f"missing capture: {path}")
    shot = Image.open(path).convert("RGB")
    if name.startswith("android-"):
        # The Pixel's captures can carry a short red line along the top row; the row below is clean game picture.
        a = np.asarray(shot).copy()
        a[0:2] = a[2]
        shot = Image.fromarray(a)
    for box in redact.get(name, []):
        shot = erase(shot, *box)
    return shot


def alpha(cov):
    return Image.fromarray((np.clip(cov, 0, 1) * 255).round().astype("uint8"))


def phone(shot, kind, width=None, height=None):
    """`shot` in a drawn `kind` phone `width` px wide (or `height` px tall), as RGBA."""
    spec = PHONES[kind]
    ratio = shot.height / shot.width
    portrait = ratio > 1
    short = 1 if portrait else ratio  # the screen's short side, per px of its width
    if width is None:
        width = round(height / (ratio + 2 * spec["bezel"] * short) * (1 + 2 * spec["bezel"] * short))
    sw = round(width / (1 + 2 * spec["bezel"] * short))
    sh = round(sw * ratio)
    b = (width - sw) // 2
    w, h = sw + 2 * b, sh + 2 * b
    radius = spec["corner"] * min(w, h)

    d = brand.sd_roundrect(w, h, radius)
    out = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    out.paste(Image.new("RGB", (w, h), spec["body"]), (0, 0), alpha(brand.coverage(d)))
    out.paste(Image.new("RGB", (w, h), spec["rim"]), (0, 0), alpha(brand.coverage(d) - brand.coverage(d + 3)))
    screen = shot.resize((sw, sh), Image.LANCZOS)
    out.paste(screen, (b, b), alpha(brand.coverage(brand.sd_roundrect(sw, sh, max(radius - b, 0)))))

    # Work in the phone's own frame: u runs along the long side from the camera end, v across the short side.
    yy, xx = np.mgrid[0:h, 0:w].astype(float) + 0.5
    long_px, short_px = (sh, sw) if portrait else (sw, sh)
    u, v = (yy - b, xx - w / 2) if portrait else (xx - b, yy - h / 2)
    if kind == "pixel":
        cam = np.hypot(u - 0.03 * long_px, v) - 0.021 * short_px
        out.paste(Image.new("RGB", (w, h), (4, 4, 6)), (0, 0), alpha(brand.coverage(cam)))
    else:
        # Dynamic Island: 36.7 x 125.3 pt on a 956 x 440 pt screen, 14 pt in from the edge, as the simulator draws it.
        thick, length, inset = 36.7 / 956 * long_px, 125.3 / 440 * short_px, 14 / 956 * long_px
        # A capsule: a segment along the short side, rounded by half the thickness.
        qu = np.abs(u - inset - thick / 2)
        qv = np.abs(v) - (length / 2 - thick / 2)
        island = np.hypot(np.maximum(qu, 0), np.maximum(qv, 0)) + np.minimum(np.maximum(qu, qv), 0) - thick / 2
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


def wrapped(text, face, size, width):
    """`text` broken into lines no wider than `width` at `size`."""
    font = ImageFont.truetype(brand.FONT, size, index=face)
    for chars in range(len(text), 0, -1):
        lines = textwrap.wrap(text, chars)
        if all(font.getlength(line) <= width for line in lines):
            return lines
    return [text]


def hero(master, folder, redact):
    w, h = 2400, 1250
    canvas = brand.backdrop(master, w, h, 0.5)
    glow(canvas, (240, 220, 2260, 1130), 0.32)
    port = phone(capture(folder, HERO[1][0], redact), HERO[1][1], height=1110)
    port_x = w - port.width - 110
    # The landscape phone tucks under the portrait one by less than its bezel: a HUD drawn to the screen's edge,
    # like the Pixel's tab buttons, stays in view.
    land = phone(capture(folder, HERO[0][0], redact), HERO[0][1], width=port_x + 20 - 110)
    brand.shadow(canvas, land, (110, (h - land.height) // 2 + 30), 36)
    brand.shadow(canvas, port, (port_x, (h - port.height) // 2), 36)
    return canvas


def card(master, folder, redact, shot, kind, headline, line):
    shot = capture(folder, shot, redact)
    canvas = brand.backdrop(master, CARD_W, CARD_H, 0.5)
    draw = ImageDraw.Draw(canvas)
    if shot.width > shot.height:
        # Landscape: the headline and its line centred over the phone.
        dev = phone(shot, kind, width=1400)
        w = CARD_W
        y = centred(draw, headline, HEAVY, brand.fit_size(headline, HEAVY, 84, w - 2 * PAD), WHITE, PAD, w)
        y = centred(draw, line, MEDIUM, brand.fit_size(line, MEDIUM, 42, w - 2 * PAD), SAND, y + 22, w)
        top = y + (CARD_H - y - dev.height) // 2
        glow(canvas, (160, top + 80, w - 160, top + dev.height + 40), 0.28)
        brand.shadow(canvas, dev, ((w - dev.width) // 2, top), 30)
        return canvas
    # Portrait: the phone on the right, the headline and its line beside it.
    dev = phone(shot, kind, height=CARD_H - 2 * 70)
    x = CARD_W - dev.width - 150
    text_w = x - 2 * PAD
    head = wrapped(headline, HEAVY, 96, text_w)
    body = wrapped(line, MEDIUM, 46, text_w)
    head_font = ImageFont.truetype(brand.FONT, 96, index=HEAVY)
    body_font = ImageFont.truetype(brand.FONT, 46, index=MEDIUM)
    head_step, body_step, gap = 112, 62, 34
    block = len(head) * head_step + gap + len(body) * body_step
    y = (CARD_H - block) // 2
    for text in head:
        draw.text((PAD + 20, y), text, font=head_font, fill=WHITE)
        y += head_step
    y += gap
    for text in body:
        draw.text((PAD + 20, y), text, font=body_font, fill=SAND)
        y += body_step
    glow(canvas, (x - 120, 120, x + dev.width + 120, CARD_H - 80), 0.30)
    brand.shadow(canvas, dev, (x, (CARD_H - dev.height) // 2), 30)
    return canvas


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("captures", help="folder of raw captures (keep it out of git)")
    args = ap.parse_args()
    master = Image.open(brand.MASTER).convert("RGB")
    redact = boxes(args.captures)
    os.makedirs(OUT, exist_ok=True)
    outputs = [("hero.jpg", hero(master, args.captures, redact))]
    outputs += [(name, card(master, args.captures, redact, *rest)) for name, *rest in CARDS]
    for name, img in outputs:
        path = os.path.join(OUT, name)
        img.save(path, quality=90, subsampling=0, optimize=True, progressive=True)  # 4:4:4 keeps the UI text crisp
        print(f"{os.path.relpath(path, brand.ROOT)}  {img.width}x{img.height}  {os.path.getsize(path) // 1024} KB")


if __name__ == "__main__":
    main()
