#!/usr/bin/env python3
"""Regenerate every RuneMobile logo asset from the master icon.

    tools/brand/make-brand.py                    # rebuild the derived assets from docs/brand/runemobile-icon.png
    tools/brand/make-brand.py --source art.png   # first rebuild that master from a designer export

The master is the full-bleed 1024 px square: no rounded corners, because iOS and Android cut their own. A designer
export with rounded corners on black (the original RuneMobile Logo.PNG) goes through --source, which fills those
corners with stone texture lifted from the artwork itself.

Writes, relative to the repo root:
    docs/brand/runemobile-icon.png        master; also the AltStore feed's iconURL
    docs/brand/runemobile-logo.png        rounded, transparent corners (README)
    docs/brand/social-preview.jpg         1280x640 GitHub social preview (uploaded by hand in repo Settings)
    ios/app/RuneMobile/Assets.xcassets/   AppIcon (1024, opaque) + Logo (launch screen, cover, About)
    android/app/src/main/res/             adaptive icon layers, themed-icon + notification vectors, TV banner,
                                          in-app logo

Needs Pillow, NumPy, SciPy and potrace (brew install potrace).
"""
import argparse
import json
import os
import re
import subprocess
import sys
import tempfile

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
MASTER = os.path.join(ROOT, "docs/brand/runemobile-icon.png")
IOS_ASSETS = os.path.join(ROOT, "ios/app/RuneMobile/Assets.xcassets")
RES = os.path.join(ROOT, "android/app/src/main/res")
FONT = "/System/Library/Fonts/Avenir Next.ttc"  # face 8 = Heavy, 5 = Medium

SIZE = 1024
# A patch of bare stone in the master (the band above the R, between the sword grip and the flames), in fractions
# of the side. It fills the designer export's corners and tiles the backgrounds the logo is set into.
PATCH = (0.223, 0.0, 0.207)  # x, y, side
# iOS draws app icons with a ~22.4% corner radius; the rounded exports here match it.
CORNER = 0.2237
# Android adaptive icon: the logo's side in dp on the 108 dp layer. The M's outer flame tips reach 1.14x the
# half-side from the centre, so 62 dp keeps them inside the 72 dp circle mask (Pixel's default shape).
ADAPTIVE_LOGO_DP = 62
# Android themed icon: the glyph's width in dp on the 108 dp layer (inside the 66 dp safe circle).
MONO_GLYPH_DP = 46


def out(path):
    path = os.path.join(ROOT, path)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    return path


def sd_roundrect(w, h, r, inset=0.0):
    """Signed distance (px) from each pixel centre to a w x h rounded rect, negative inside."""
    yy, xx = np.mgrid[0:h, 0:w].astype(float)
    qx = np.abs(xx + 0.5 - w / 2) - (w / 2 - r)
    qy = np.abs(yy + 0.5 - h / 2) - (h / 2 - r)
    d = np.hypot(np.maximum(qx, 0), np.maximum(qy, 0)) + np.minimum(np.maximum(qx, qy), 0) - r
    return d + inset


def coverage(d, feather=1.0):
    return np.clip(0.5 - d / feather, 0, 1)


def build_master(source):
    """Square up a designer export: replace its black rounded corners (and the bright rim along them) with stone."""
    img = Image.open(source).convert("RGB")
    if img.width != img.height:
        sys.exit(f"make-brand: {source} is {img.width}x{img.height}; the logo must be square")
    a = np.asarray(img).astype(float)
    n = img.width
    lum = 0.299 * a[..., 0] + 0.587 * a[..., 1] + 0.114 * a[..., 2]
    px, py, ps = (round(v * n) for v in PATCH)
    warm = (a[..., 0] > a[..., 2] + 40) & (lum > 50)  # flame, spark or copper; bare stone is blue-black
    if warm[py:py + ps, px:px + ps].any():
        sys.exit("make-brand: the texture patch overlaps the artwork; move PATCH")
    patch = a[py:py + ps, px:px + ps]
    fill = a.copy()
    for fy in (False, True):
        for fx in (False, True):
            p = patch[::-1] if fy else patch
            p = p[:, ::-1] if fx else p
            y0, x0 = (n - ps if fy else 0), (n - ps if fx else 0)
            fill[y0:y0 + ps, x0:x0 + ps] = p
    # The export's corner radius is ~0.2 of the side; keep everything 1% inside it, blending over 1.3%.
    keep = coverage(sd_roundrect(n, n, 0.2 * n, inset=0.01 * n), feather=0.013 * n)[..., None]
    a = a * keep + fill * (1 - keep)
    img = Image.fromarray(a.round().clip(0, 255).astype("uint8")).resize((SIZE, SIZE), Image.LANCZOS)
    img.save(MASTER, optimize=True)
    print(f"make-brand: master <- {source}")


def rounded(master, side):
    """The master at `side` px with iOS-style rounded, transparent corners."""
    img = master.resize((side, side), Image.LANCZOS).convert("RGBA")
    ss = 4  # supersample the corner mask
    m = coverage(sd_roundrect(side * ss, side * ss, CORNER * side * ss))
    alpha = Image.fromarray((m * 255).astype("uint8")).resize((side, side), Image.LANCZOS)
    img.putalpha(alpha)
    return img


def stone(master, w, h, scale):
    """A w x h canvas of the master's bare stone, mirror-tiled, at `scale` times the master's own grain.

    The patch is a little brighter than the stone along the master's edge, so it is toned to that edge's mean colour:
    a logo feathered onto the canvas then shows no frame."""
    a = np.asarray(master).astype(float)
    px, py, ps = (round(v * SIZE) for v in PATCH)
    patch = a[py:py + ps, px:px + ps]
    lum = 0.299 * a[..., 0] + 0.587 * a[..., 1] + 0.114 * a[..., 2]
    edge = (sd_roundrect(SIZE, SIZE, 0) > -0.06 * SIZE) & (lum < 100)
    patch = np.clip(patch * (a[edge].mean(0) / patch.reshape(-1, 3).mean(0)), 0, 255)
    t = max(1, round(ps * scale))
    tile = np.asarray(Image.fromarray(patch.round().astype("uint8")).resize((t, t), Image.LANCZOS))
    quad = np.concatenate([np.concatenate([tile, tile[:, ::-1]], 1),
                           np.concatenate([tile[::-1], tile[::-1, ::-1]], 1)], 0)
    reps = (h // quad.shape[0] + 1, w // quad.shape[1] + 1, 1)
    return Image.fromarray(np.tile(quad, reps)[:h, :w])


def backdrop(master, w, h, scale):
    """A wide stone canvas under a dark vignette, so the mirror-tiling's repeat doesn't read at banner sizes."""
    yy, xx = np.mgrid[0:h, 0:w].astype(float)
    r = np.hypot((xx - w / 2) / (w / 2), (yy - h / 2) / (h / 2)) / np.sqrt(2)
    keep = (0.55 - 0.35 * r)[..., None]  # 55% of the stone at the centre, 20% at the corners
    a = np.asarray(stone(master, w, h, scale)).astype(float) * keep + np.array([4, 8, 12]) * (1 - keep)
    return Image.fromarray(a.round().clip(0, 255).astype("uint8"))


def feathered(master, side, feather):
    """The master at `side` px whose square edge fades out over `feather` px, to sit on a stone() canvas."""
    img = master.resize((side, side), Image.LANCZOS).convert("RGBA")
    m = coverage(sd_roundrect(side, side, feather * 1.5, inset=feather / 2), feather=feather)
    img.putalpha(Image.fromarray((m * 255).astype("uint8")))
    return img


def silhouette(master):
    """The sword-R and flame-M as one clean mask (the stone's highlights, the sparks and the old rim dropped)."""
    from scipy import ndimage as ndi  # only this step needs SciPy; make-showcase.py imports the rest without it
    a = np.asarray(master).astype(float)
    lum = 0.299 * a[..., 0] + 0.587 * a[..., 1] + 0.114 * a[..., 2]
    m = (lum > 100) & (sd_roundrect(SIZE, SIZE, 0.2 * SIZE) < -0.03 * SIZE)
    m = ndi.binary_closing(m, structure=np.ones((5, 5)), iterations=2)
    lab, n = ndi.label(m)
    sizes = ndi.sum(m, lab, range(1, n + 1))
    keep = np.zeros(n + 1, bool)
    keep[1:] = sizes > 0.02 * SIZE * SIZE  # the letters are each >3% of the square; stone highlights are <1%
    m = ndi.binary_fill_holes(keep[lab])
    m = ndi.gaussian_filter(m.astype(float), SIZE / 300) > 0.5
    ys, xs = np.nonzero(m)
    return m[ys.min():ys.max() + 1, xs.min():xs.max() + 1]


def trace(mask, side=256):
    """potrace the mask (fit inside side x side px). Returns (w, h, svg transform numbers, path data)."""
    h0, w0 = mask.shape
    k = side / max(h0, w0)
    w, h = round(w0 * k), round(h0 * k)
    small = Image.fromarray((mask * 255).astype("uint8")).resize((w, h), Image.LANCZOS)
    bits = np.asarray(small) > 127
    with tempfile.TemporaryDirectory() as tmp:
        pbm = os.path.join(tmp, "glyph.pbm")
        Image.fromarray(np.where(bits, 0, 255).astype("uint8")).convert("1").save(pbm)  # potrace traces black
        svg = subprocess.run(["potrace", "-s", "--flat", "-t", "16", "-a", "1.1", "-O", "0.8", "-o", "-", pbm],
                             check=True, capture_output=True, text=True).stdout
    tr = re.search(r'transform="translate\(([-\d.]+),([-\d.]+)\) scale\(([-\d.]+),([-\d.]+)\)"', svg)
    paths = re.findall(r'<path d="([^"]+)"', svg)
    if not tr or not paths:
        sys.exit("make-brand: unexpected potrace output")
    d = " ".join(" ".join(p.split()) for p in paths)
    return w, h, tuple(float(v) for v in tr.groups()), d


def vector(path, comment, dp, view, glyph, box, color):
    """An Android vector drawable: `glyph` (from trace) scaled to fit `box` (x, y, w, h) on a view x view viewport."""
    w, h, (tx, ty, sx, sy), d = glyph
    bx, by, bw, bh = box
    k = min(bw / w, bh / h)
    ox, oy = bx + (bw - w * k) / 2, by + (bh - h * k) / 2
    f = lambda v: f"{v:.4f}".rstrip("0").rstrip(".")
    xml = f"""<?xml version="1.0" encoding="utf-8"?>
<!-- {comment}
     Generated by tools/brand/make-brand.py from docs/brand/runemobile-icon.png; edit the master, not this. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="{dp}dp"
    android:height="{dp}dp"
    android:viewportWidth="{view}"
    android:viewportHeight="{view}">
    <group
        android:translateX="{f(ox)}"
        android:translateY="{f(oy)}"
        android:scaleX="{f(k)}"
        android:scaleY="{f(k)}">
        <group
            android:translateX="{f(tx)}"
            android:translateY="{f(ty)}"
            android:scaleX="{f(sx)}"
            android:scaleY="{f(sy)}">
            <path
                android:fillColor="{color}"
                android:pathData="{d}" />
        </group>
    </group>
</vector>
"""
    open(out(path), "w").write(xml)


def text_block(draw, x, y, lines):
    """Draw (text, face, size, fill) lines top-down from (x, y); returns the bottom y."""
    for text, face, size, fill, gap in lines:
        font = ImageFont.truetype(FONT, size, index=face)
        top = font.getbbox(text)[1]
        draw.text((x, y - top), text, font=font, fill=fill)
        y += font.getbbox(text)[3] - top + gap
    return y


def fit_size(text, face, size, width):
    while size > 8 and ImageFont.truetype(FONT, size, index=face).getlength(text) > width:
        size -= 1
    return size


def shadow(canvas, logo, xy, radius):
    """Paste `logo` at xy over a soft drop shadow."""
    a = Image.new("L", canvas.size, 0)
    a.paste(logo.getchannel("A"), (xy[0], xy[1] + radius // 3))
    a = a.filter(ImageFilter.GaussianBlur(radius)).point(lambda v: v * 0.7)
    canvas.paste(Image.new("RGB", canvas.size, (0, 0, 0)), (0, 0), a)
    canvas.paste(logo, xy, logo)


def docs(master):
    rounded(master, 320).save(out("docs/brand/runemobile-logo.png"), optimize=True)  # README shows it at 160

    w, h = 1280, 640
    canvas = backdrop(master, w, h, 400 / SIZE)
    logo = rounded(master, 400)
    shadow(canvas, logo, (110, 120), 18)
    x, width = 580, w - 580 - 70
    draw = ImageDraw.Draw(canvas)
    lines = [("RuneMobile", 8, fit_size("RuneMobile", 8, 120, width), (255, 255, 255), 30),
             ("RuneLite on iOS & Android", 5, fit_size("RuneLite on iOS & Android", 5, 46, width), (226, 214, 196), 22),
             ("Unofficial fan project · sideload-only", 5, 30, (150, 160, 170), 0)]
    block = text_block(ImageDraw.Draw(Image.new("RGB", (1, 1))), 0, 0, lines)
    text_block(draw, x, (h - block) // 2, lines)
    canvas.save(out("docs/brand/social-preview.jpg"), quality=90, optimize=True)  # GitHub caps it at 1 MB


def ios(master):
    def contents(path, obj):
        json.dump(obj, open(out(path), "w"), indent=2)
        open(out(path), "a").write("\n")

    info = {"author": "xcode", "version": 1}
    contents("ios/app/RuneMobile/Assets.xcassets/Contents.json", {"info": info})
    master.save(out("ios/app/RuneMobile/Assets.xcassets/AppIcon.appiconset/AppIcon.png"), optimize=True)
    contents("ios/app/RuneMobile/Assets.xcassets/AppIcon.appiconset/Contents.json", {
        "images": [{"filename": "AppIcon.png", "idiom": "universal", "platform": "ios", "size": "1024x1024"}],
        "info": info})
    # 128 pt: the size UILaunchScreen shows it at; the cover and About draw it smaller.
    images = []
    for scale in (2, 3):
        name = f"Logo@{scale}x.png"
        rounded(master, 128 * scale).save(out(f"ios/app/RuneMobile/Assets.xcassets/Logo.imageset/{name}"),
                                          optimize=True)
        images.append({"filename": name, "idiom": "universal", "scale": f"{scale}x"})
    contents("ios/app/RuneMobile/Assets.xcassets/Logo.imageset/Contents.json", {"images": images, "info": info})
    # The launch screen's backdrop: the tap-to-play cover's colour (ViewController.m buildLaunchScreen).
    contents("ios/app/RuneMobile/Assets.xcassets/LaunchBackground.colorset/Contents.json", {
        "colors": [{"color": {"color-space": "srgb", "components": {
            "alpha": "1.000", "red": "0.040", "green": "0.040", "blue": "0.050"}}, "idiom": "universal"}],
        "info": info})


def android(master, glyph):
    densities = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}
    for name, k in densities.items():
        c = round(108 * k)
        side = round(ADAPTIVE_LOGO_DP * k)
        grain = side / SIZE  # the stone around the logo keeps the logo's own grain
        stone(master, c, c, grain).save(out(f"android/app/src/main/res/mipmap-{name}/ic_launcher_background.webp"),
                                        quality=90, method=6)
        fg = Image.new("RGBA", (c, c), (0, 0, 0, 0))
        logo = feathered(master, side, max(2, round(side * 0.05)))
        fg.paste(logo, ((c - side) // 2, (c - side) // 2), logo)
        fg.save(out(f"android/app/src/main/res/mipmap-{name}/ic_launcher_foreground.webp"), quality=92, method=6)

    g = MONO_GLYPH_DP
    vector("android/app/src/main/res/drawable/ic_launcher_monochrome.xml",
           "Themed-icon layer (Android 13+): the RuneMobile sword-R and flame-M as one silhouette; the launcher tints it.",
           108, 108, glyph, ((108 - g) / 2, (108 - g) / 2, g, g), "#FFFFFFFF")
    vector("android/app/src/main/res/drawable/ic_stat_rune.xml",
           "Notification small-icon: the RuneMobile silhouette in white (the status bar tints it).",
           24, 24, glyph, (1, 1, 22, 22), "#FFFFFFFF")

    # In-app logo (splash card, About): rounded like the iOS icon. nodpi: one bitmap, drawn at an explicit dp size.
    rounded(master, 384).save(out("android/app/src/main/res/drawable-nodpi/runemobile_logo.webp"),
                              quality=92, method=6)

    # Android TV banner, 320x180 dp at xhdpi: the logo and the name on stone.
    w, h = 640, 360
    side = 240
    canvas = backdrop(master, w, h, side / SIZE)
    logo = rounded(master, side)
    name_size = fit_size("RuneMobile", 8, 64, w - side - 3 * 30)
    font = ImageFont.truetype(FONT, name_size, index=8)
    total = side + 28 + font.getlength("RuneMobile")
    x = round((w - total) / 2)
    shadow(canvas, logo, (x, (h - side) // 2), 10)
    draw = ImageDraw.Draw(canvas)
    bbox = font.getbbox("RuneMobile")
    draw.text((x + side + 28, (h - (bbox[3] - bbox[1])) // 2 - bbox[1]), "RuneMobile", font=font, fill=(255, 255, 255))
    canvas.save(out("android/app/src/main/res/drawable-nodpi/tv_banner.webp"), quality=90, method=6)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--source", help="designer export to rebuild the master from (square PNG)")
    args = ap.parse_args()
    if args.source:
        build_master(args.source)
    if not os.path.exists(MASTER):
        sys.exit(f"make-brand: no master at {MASTER}; pass --source")
    master = Image.open(MASTER).convert("RGB")
    if master.size != (SIZE, SIZE):
        sys.exit(f"make-brand: the master must be {SIZE}x{SIZE}, got {master.size}")
    glyph = trace(silhouette(master))
    docs(master)
    ios(master)
    android(master, glyph)
    print("make-brand: done")


if __name__ == "__main__":
    main()
