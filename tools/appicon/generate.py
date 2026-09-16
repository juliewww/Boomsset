#!/usr/bin/env python3
"""Generate all sizes of the app icon.

**This is the single source of truth for the icon.** The PNGs under res/ and
Assets.xcassets are build artifacts -- do not hand-edit them, any edits are wiped out
the next time this script runs. To change the design, edit the constants below.

    python3 tools/appicon/generate.py
    python3 tools/appicon/validate.py     # must be run after any change

Requires Pillow (usually already present in macOS's bundled python3). The build itself
does not depend on it -- the PNGs are committed to the repo, so CI and other people's
machines don't need Pillow installed.

The two platforms have completely different geometry requirements, which is also why
this has to be a script rather than manually cropped images:

* **iOS** needs a full-bleed 1024x1024 image, **must not have an alpha channel**, and
  must not have rounded corners of its own -- the system applies a squircle mask. An
  alpha channel gets the icon rejected by the App Store.
* **Android adaptive icons** use a 108dp layer, but only the center 72dp is guaranteed
  visible, and only a 66dp-diameter circle is guaranteed not to be clipped. The
  launcher's mask shape is up to the OEM (circle/square/squircle/teardrop), so the
  artwork must be inset within the safe zone.

── Design: "flying pig carrying money" ──────────────────────────────────────────

The previous version was "breaking out of the ring" (a segmented ring + three bars
punching through it), abstract and tied directly to "asset allocation", but the
feedback was **too busy, too many colors**. This version switches to a concrete
mascot:

* **A flying pig** -- pig = piggy bank / good fortune, wings = assets growing ("it can
  fly")
* **Three coins at its side** -- the coins themselves, while also borrowing the
  piggy-bank association
* **Two legs (front and back) / round ears with a small point / eye highlight** --
  cuteness details, all sized by comparing on an actual device

WARNING: **the geometry is now adaptive; the bounding radius is no longer computed by
hand.** The previous version had a hand-computed `CONTENT_R` constant that was **off by
27%** (the farthest point was the top-right corner of the rightmost bar, not the bar's
top), so the adaptive foreground overflowed the safe circle and got clipped on
launchers with a circular mask -- and **this was invisible when composited locally.**
This version instead: draws the artwork on a transparent canvas first, **scans
pixel-by-pixel for the true bounding radius**, then scales and centers it to the
target radius -- there's no constant left that can be miscalculated.
"""
from PIL import Image, ImageDraw
import json
import math
import os

# ── Brand ──────────────────────────────────────────────────────────────
# Brand hue angle. WARNING: must match BrandRose (#C94385) in
# shared/.../ui/theme/Theme.kt. An icon disconnected from the UI is worse than an ugly
# icon.
BRAND_HUE = 354                 # medium rose

# Background color: near-white pale lavender-pink. WARNING: this is **the icon's own
# background**, not the same hue as the app's page background (warm H70) -- the UI's
# neutral surfaces are required to stay warm-toned, while the icon follows the brand
# color; this is intentional.
# It is also the color value for the Android adaptive background layer (ic_launcher_bg
# in colors.xml), because the foreground carves the gaps out to transparent, and this
# is what shows through. Changing it here requires changing it there too (validate.py
# checks this).
# WARNING: this value is the result of **moving #F9F0FD to the brand hue**. When
# picking the background color, #F9F0FD was chosen (the whiter, less pink variant),
# but that value was solved for **the old purple brand color**, at hue 315.7° --
# once switched to medium rose (H 354) it became disconnected from the brand. Moving
# the same lightness and chroma to H 354 gives #FFEFF4; the two are only **ΔE 1.3
# apart** (indistinguishable to the eye), so this keeps both the look that was chosen
# and hue consistency with the brand.
FIELD = (255, 239, 244)         # #FFEFF4

# ── Color palette ──────────────────────────────────────────────────────────────
# The pig's body and hooves share the same hue (H≈354), part of the same family as
# the brand color.
# WARNING: **the hooves use the exact primary color value from `Theme.kt`**
# (#C94385) -- it's the same color as the FAB and the nav selected state in the UI,
# the most direct evidence that "this icon belongs to this app".
BODY   = (246, 160, 195)        # #F6A0C3
SNOUT  = (214, 103, 153)        # #D66799  snout and inner ear
HOOF   = (201,  67, 133)        # #C94385  = Theme.kt's primary
WINGC  = (255, 224, 235)        # #FFE0EB  wing
EYE    = ( 63,  15,  39)        # #3F0F27

# Coins: bright gold + a darker gold outline from the same color family.
# WARNING: **the outline cannot be dropped, and cannot be white.** Gold's contrast
# against the pig's body is only **1.11:1** (their lightness is nearly identical);
# without an outline the coins would blend straight into the body, and there would
# be no boundary between the three coins either.
# A white outline was tried, but it read like a sticker (feedback: "no white
# edges"), so it was changed to **dark gold** instead -- it belongs to the coin's
# own color family, reading like the coin's edge thickness.
# The square hole is **filled with the pig's body color**, letting the body show
# through the hole, adding one more shape cue.
GOLD   = (238, 188,  74)        # #EEBC4A
GEDGE  = (176, 126,  16)        # #B07E10

# ── Geometry (all relative to canvas width) ────────────────────────────────────
TILT      = -14                 # body tilt-up angle, reads as "flying"
BODY_RX   = 0.245
BODY_RY   = 0.186
LEG_W     = 0.036
HOOF_R    = 0.020
EAR_R     = 0.048
EYE_R     = 0.027
SNOUT_R   = 0.068

# Coins: 24px diameter @250 preview -> radius ratio 0.048.
# WARNING: the size was tuned by comparing on an actual device: any bigger and it
# overlaps the face and wings; any smaller (22px) and at 48px the square hole
# smears away, leaving just a gold blob. **This size sits near the lower bound of
# "still readable as a coin".**
COIN_R    = 0.048
COIN_POS  = (0.12, 0.02)        # relative to body center (x positive = backward, y positive = down)
COIN_LAY  = [(-0.78, 0.39), (0.78, 0.39), (0, -0.39)]   # relative positions of the three coins (unit = radius)
COIN_HOLE = 0.34                # square-hole half-width / coin radius
COIN_EDGE = 0.025               # outline width / coin radius. WARNING: at small sizes this
                                # inevitably degrades into anti-aliased gray; at 48px on the
                                # legacy icon the three coins read as a cluster of gold dots
                                # -- known and accepted

WING_L    = 0.235
WING_W    = 0.062
WING_ANG  = -74

SS = 4                          # supersampling factor -- PIL's draw has no anti-aliasing
WORK = 1024                     # working canvas the artwork is drawn on (then scaled to each target size)

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


# ── Drawing primitives ──────────────────────────────────────────────────────────────
def _bez(p0, p1, p2, n=48):
    return [((1 - t) ** 2 * p0[0] + 2 * (1 - t) * t * p1[0] + t * t * p2[0],
             (1 - t) ** 2 * p0[1] + 2 * (1 - t) * t * p1[1] + t * t * p2[1])
            for t in (i / n for i in range(n + 1))]


def _frame(ox, oy, ang):
    a = math.radians(ang)
    ca, sa = math.cos(a), math.sin(a)
    return lambda x, y: (ox + x * ca - y * sa, oy + x * sa + y * ca)


def _plume(d, ox, oy, L, W, ang, col, bulge=1.0):
    """A single feather: narrow at the base, full in the middle, rounded at the tip."""
    T = _frame(ox, oy, ang)
    up = _bez(T(0, 0), T(L * 0.42, -W * bulge), T(L, -W * 0.16))
    tip = [T(L, -W * 0.16), T(L + W * 0.22, 0), T(L, W * 0.16)]
    dn = _bez(T(L, W * 0.16), T(L * 0.46, W * 0.72 * bulge), T(0, 0))
    d.polygon(up + tip + dn, fill=col)


def _wing(d, ox, oy, L, W, ang, c, fld, shade):
    """Angel wing: four outer primary feathers + three shorter covert feathers near
    the root (slightly darker) -- it takes two layers to get the "angel wing" layered
    look.

    WARNING: the forward sweep angle is deliberately narrowed (outermost is +20°
    instead of +32°), together with moving the wing root back, **otherwise the front
    feather would cover the ear** -- at 48px the ear would disappear entirely.
    """
    for da, l in [(-34, 0.80), (-14, 0.98), (6, 1.0), (20, 0.80)]:
        _plume(d, ox, oy, L * l + L * 0.05, W * 1.30, ang + da, fld)
        _plume(d, ox, oy, L * l, W, ang + da, c)
    for da, l in [(-22, 0.44), (-4, 0.50), (12, 0.40)]:
        _plume(d, ox, oy, L * l + L * 0.05, W * 1.34, ang + da, fld)
        _plume(d, ox, oy, L * l, W * 0.98, ang + da, shade)


def _ear(d, cx, cy, r, col, icol):
    """A circle + a small point facing up (union of a circle and a triangle).

    WARNING: a purely triangular pointed ear was rejected ("ears shouldn't be so
    pointy"); but a circle with no point at all reads as a cat's ear.
    """
    a = math.radians(-90)
    tip = 0.55
    ax, ay = cx + r * (1 + tip) * math.cos(a), cy + r * (1 + tip) * math.sin(a)
    b1 = (cx + r * math.cos(a - math.radians(62)), cy + r * math.sin(a - math.radians(62)))
    b2 = (cx + r * math.cos(a + math.radians(62)), cy + r * math.sin(a + math.radians(62)))
    d.polygon([(ax, ay), b1, b2], fill=col)
    d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=col)
    ri = r * 0.50
    ix, iy = cx + r * 0.10 * math.cos(a), cy + r * 0.10 * math.sin(a)
    d.ellipse([ix - ri, iy - ri, ix + ri, iy + ri], fill=icol)


def _coins(d, T, r, edge_w):
    """Three stacked coins (round outside, square inside). Drawn back-to-front, so
    each coin behind has a corner covered by the one in front.

    WARNING: **a gold ingot (yuanbao) doesn't work in this position.** Drawing an
    ingot at the pig's side was tried, but it's a horizontal boat shape, and against
    the round body it smears into a blob at small sizes (completely unrecognizable
    at 48px).
    The coin's outer circle echoes the body's circle, and the square hole still
    holds up at small sizes -- a choice forced by the small-size constraint.
    """
    w = max(1, round(edge_w))
    for dx, dy in COIN_LAY:
        cx, cy = T(dx * r, dy * r)
        d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=GOLD, outline=GEDGE, width=w)
        h = r * COIN_HOLE
        d.polygon([(cx - h, cy - h), (cx + h, cy - h), (cx + h, cy + h), (cx - h, cy + h)],
                  fill=BODY, outline=GEDGE, width=w)


def _pig(n):
    """Draw the pig on an n x n **transparent** canvas (artwork only, no background fill)."""
    img = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    B, S, W = BODY + (255,), SNOUT + (255,), WINGC + (255,)
    Hf, E = HOOF + (255,), EYE + (255,)

    tilt = math.radians(TILT)
    cx, cy = n * 0.50, n * 0.52
    ca, sa = math.cos(tilt), math.sin(tilt)

    def T(x, y):
        return (cx + x * ca - y * sa, cy + x * sa + y * ca)

    RX, RY = n * BODY_RX, n * BODY_RY
    lw, hr = n * LEG_W, n * HOOF_R

    def leg(ax, bx, ay0, by1):
        p0, p1 = T(RX * ax, RY * ay0), T(RX * bx, RY * by1)
        d.line([*p0, *p1], fill=B, width=int(lw))
        for p in (p0, p1):
            d.ellipse([p[0] - lw / 2, p[1] - lw / 2, p[0] + lw / 2, p[1] + lw / 2], fill=B)
        d.ellipse([p1[0] - hr, p1[1] - hr, p1[0] + hr, p1[1] + hr], fill=Hf)

    # Tail (a spiral that tapers as it winds, built from small dots)
    tx, ty = T(-RX * 1.02, -RY * 0.30)
    for i in range(70):
        t = i / 69
        a = math.radians(-40 + t * 430)
        rr = n * 0.050 * (1.0 - 0.45 * t)
        x, y = tx + rr * math.cos(a), ty + rr * math.sin(a)
        d.ellipse([x - n * 0.0145, y - n * 0.0145, x + n * 0.0145, y + n * 0.0145], fill=B)

    # Only two legs (one front, one back). Drawn once first so the body covers the
    # upper half; drawn again after the body so the lower half shows through.
    for _ in range(1):
        leg(-0.30, -0.34, 0.90, 1.10)
        leg(0.28, 0.25, 0.88, 1.08)

    # Body (a rotated ellipse)
    lay = Image.new("RGBA", (int(RX * 2) + 6, int(RY * 2) + 6), (0, 0, 0, 0))
    ImageDraw.Draw(lay).ellipse([3, 3, RX * 2 + 3, RY * 2 + 3], fill=B)
    lay = lay.rotate(-TILT, expand=True, resample=Image.BICUBIC)
    img.paste(lay, (int(cx - lay.size[0] / 2), int(cy - lay.size[1] / 2)), lay)
    d = ImageDraw.Draw(img)

    leg(-0.30, -0.34, 0.90, 1.10)
    leg(0.28, 0.25, 0.88, 1.08)

    # Snout + two nostrils
    sx, sy = T(RX * 0.92, -RY * 0.08)
    sr = n * SNOUT_R
    d.ellipse([sx - sr * 0.75, sy - sr * 0.80, sx + sr * 0.95, sy + sr * 0.80], fill=S)
    for k in (-1, 1):
        nr = n * 0.013
        d.ellipse([sx + n * 0.010 - nr, sy + k * n * 0.023 - nr,
                   sx + n * 0.010 + nr, sy + k * n * 0.023 + nr], fill=(168, 76, 122, 255))

    # Ear
    ex, ey = T(RX * 0.46, -RY * 0.86)
    _ear(d, ex, ey, n * EAR_R, B, S)

    # Eye + highlight. WARNING: the highlight disappears at 48px; this is expected
    # graceful degradation of detail, not a bug.
    ox, oy = T(RX * 0.52, -RY * 0.30)
    er = n * EYE_R
    d.ellipse([ox - er, oy - er, ox + er, oy + er], fill=E)
    hr2 = er * 0.36
    d.ellipse([ox - er * 0.34 - hr2, oy - er * 0.36 - hr2,
               ox - er * 0.34 + hr2, oy - er * 0.36 + hr2], fill=(255, 255, 255, 255))

    # Wing. WARNING: must be drawn before the coins: the coins sit at the side and
    # the wing sits on the back, so they don't overlap, but the wing root sits
    # further back and drawing it after the coins would cover the body's outline.
    wx, wy = T(-RX * 0.30, -RY * 0.62)
    _wing(d, wx, wy, n * WING_L, n * WING_W, WING_ANG, W, FIELD + (255,),
          (255, 208, 224, 255))

    # Three coins (at the side)
    gx, gy = T(-RX * COIN_POS[0], RY * COIN_POS[1])
    _coins(d, _frame(gx, gy, TILT), n * COIN_R, n * COIN_R * COIN_EDGE)

    return img


def _fit(content, canvas, radius_frac):
    """Scale and center the artwork so its **actual** bounding radius equals exactly
    radius_frac x canvas width.

    WARNING: **the radius is scanned pixel-by-pixel, not computed.** The previous
    version used a hand-computed constant that was off by 27% (the farthest point
    wasn't where it was assumed to be), so the adaptive foreground overflowed the
    safe circle without this being visible locally.
    The bounding box's corner can't be used either -- there's often no pixel there
    at all, which would underestimate the scale and needlessly shrink the artwork.
    """
    a = content.getchannel("A")
    bbox = a.getbbox()
    if bbox is None:
        raise SystemExit("图形是空的")
    cx, cy = (bbox[0] + bbox[2]) / 2, (bbox[1] + bbox[3]) / 2
    px = a.load()
    far = 0.0
    for y in range(bbox[1], bbox[3]):
        dy2 = (y - cy) ** 2
        for x in range(bbox[0], bbox[2]):
            if px[x, y] > 8:                      # 8: ignore near-transparent anti-aliased edge pixels
                r2 = (x - cx) ** 2 + dy2
                if r2 > far:
                    far = r2
    far = math.sqrt(far)

    target = canvas * SS * radius_frac
    scale = target / far
    w, h = content.size
    small = content.resize((max(1, round(w * scale)), max(1, round(h * scale))), Image.LANCZOS)
    # Position of the content's center after scaling
    ncx, ncy = cx * scale, cy * scale
    out = Image.new("RGBA", (canvas * SS, canvas * SS), (0, 0, 0, 0))
    out.paste(small, (round(canvas * SS / 2 - ncx), round(canvas * SS / 2 - ncy)), small)
    return out


def full_bleed(size):
    """Full-bleed version: used for iOS, and for Android's legacy icon. No alpha."""
    content = _fit(_pig(WORK * SS), size, 0.40)
    out = Image.new("RGBA", content.size, FIELD + (255,))
    out.alpha_composite(content)
    return out.resize((size, size), Image.LANCZOS).convert("RGB")


def adaptive_foreground(size):
    """Android adaptive foreground: transparent background + inset to the
    guaranteed-visible 66/108-diameter circle."""
    # WARNING: multiplying by 0.985 leaves a small margin: when the target radius is
    # set to exactly equal the safe radius, rounding from scaling and resampling
    # pushes the farthest pixel out by about 0.3% (validate.py measured 100.3% in
    # practice, which fails).
    content = _fit(_pig(WORK * SS), size, (66 / 108) / 2 * 0.985)
    return content.resize((size, size), Image.LANCZOS)


def main():
    written = []

    # ── iOS: single 1024 image, no alpha ──
    ios_dir = os.path.join(ROOT, "iosApp/iosApp/Assets.xcassets/AppIcon.appiconset")
    os.makedirs(ios_dir, exist_ok=True)
    p = os.path.join(ios_dir, "icon-1024.png")
    full_bleed(1024).save(p)
    written.append(p)
    with open(os.path.join(ios_dir, "Contents.json"), "w") as f:
        json.dump({
            "images": [{"filename": "icon-1024.png", "idiom": "universal",
                        "platform": "ios", "size": "1024x1024"}],
            "info": {"author": "xcode", "version": 1},
        }, f, indent=2)
        f.write("\n")
    written.append(os.path.join(ios_dir, "Contents.json"))

    xcassets = os.path.join(ROOT, "iosApp/iosApp/Assets.xcassets")
    with open(os.path.join(xcassets, "Contents.json"), "w") as f:
        json.dump({"info": {"author": "xcode", "version": 1}}, f, indent=2)
        f.write("\n")

    # ── Android: adaptive foreground + legacy full-bleed ──
    res = os.path.join(ROOT, "androidApp/src/main/res")
    for bucket, factor in [("mdpi", 1), ("hdpi", 1.5), ("xhdpi", 2),
                           ("xxhdpi", 3), ("xxxhdpi", 4)]:
        dd = os.path.join(res, f"mipmap-{bucket}")
        os.makedirs(dd, exist_ok=True)

        p = os.path.join(dd, "ic_launcher_foreground.png")
        adaptive_foreground(round(108 * factor)).save(p)
        written.append(p)

        legacy = full_bleed(round(48 * factor))
        for name in ("ic_launcher.png", "ic_launcher_round.png"):
            p = os.path.join(dd, name)
            legacy.save(p)
            written.append(p)

    print(f"生成 {len(written)} 个文件")
    for p in written:
        print("  " + os.path.relpath(p, ROOT))


if __name__ == "__main__":
    main()
