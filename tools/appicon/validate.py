#!/usr/bin/env python3
"""Validate the app icon's color scheme and geometry. Run this after changing any
constants in generate.py.

    python3 tools/appicon/validate.py

WARNING: **this version removes the check group "the ring's five segments = ChartColors'
five asset-class colors".** The icon changed from "breaking out of the ring" (a
segmented ring + three bars) to "flying pig carrying money" -- the ring and bars no
longer exist, so those checks have nothing left to check against. Removing them was
the right call -- keeping a check that either always FAILs or always no-ops is worse
than having no check at all, since it gives the false impression that something is
still being gated.

**Color scheme**
  1. The icon background color FIELD shares **the same hue angle** as Theme.kt's brand
     color
  2. The adaptive background layer's color value = FIELD (the foreground leaves the gaps
     transparent, so this is what shows through)
  3. The coins need separation from the pig's body -- gold and pink have **nearly
     identical lightness** (1.11:1), so the dark-gold outline is the only thing keeping
     them apart. This check locks in "the outline wasn't accidentally deleted"

**Geometry** (reads the generated artifact directly, doesn't trust the constants)
  4. **All opaque pixels** of the adaptive foreground fall within the guaranteed-visible
     circle of diameter 66/108.
     WARNING: this check **cannot rely on checking a constant**: the previous version
     had a hand-computed `CONTENT_R` that was **off by 27%** (the farthest point wasn't
     where it was assumed to be), so the foreground overflowed the safe circle and got
     clipped on launchers with a circular mask, invisible when composited locally.
     generate.py now scans pixels adaptively instead; this check is its independent
     cross-check.
  5. The iOS 1024 image **must not have an alpha channel** (having one gets it rejected
     by the App Store).

Exit code 0 = all pass, 1 = at least one FAIL.
"""
import math
import os
import re
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import generate as G  # noqa: E402

# Allowed tolerance for the hue angle (degrees). **Can't be too tight**: 8-bit
# quantization can push the hue angle by ±1.5°.
HUE_TOL = 3.0
COIN_EDGE_MIN = 1.6     # minimum contrast of the outline color against the coin color (the only means of separation)


def _s2l(c):
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4


def oklab(rgb):
    r, g, b = (_s2l(c / 255) for c in rgb)
    l = 0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b
    m = 0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b
    s = 0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b
    l_, m_, s_ = (math.copysign(abs(v) ** (1 / 3), v) for v in (l, m, s))
    return (0.2104542553 * l_ + 0.7936177850 * m_ - 0.0040720468 * s_,
            1.9779984951 * l_ - 2.4285922050 * m_ + 0.4505937099 * s_,
            0.0259040371 * l_ + 0.7827717662 * m_ - 0.8086757660 * s_)


def hue(rgb):
    _, a, b = oklab(rgb)
    return math.degrees(math.atan2(b, a)) % 360


def luminance(rgb):
    r, g, b = (_s2l(c / 255) for c in rgb)
    return 0.2126 * r + 0.7152 * g + 0.0722 * b


def contrast(x, y):
    a, b = luminance(x), luminance(y)
    lo, hi = min(a, b), max(a, b)
    return (hi + 0.05) / (lo + 0.05)


def hex_to_rgb(h):
    h = h.strip().lstrip('#')
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


fails = []


def check(ok, label, detail):
    print(f"  [{'PASS' if ok else 'FAIL'}] {label:<26} {detail}")
    if not ok:
        fails.append(label)


print("配色")

# 1. The icon background color and Theme.kt's brand color share the same hue
theme = os.path.join(ROOT, "shared/src/commonMain/kotlin/com/boomsset/ui/theme/Theme.kt")
# Matches `val Brand<any name>` -- this constant's name has already changed four
# times (Amber -> Olive -> Gold -> Cream -> Rose); hardcoding the name would just
# make the validator false-alarm the next time the color changes.
brand = re.search(r"val Brand\w* = Color\(0xFF([0-9A-Fa-f]{6})\)", open(theme).read())
if not brand:
    check(False, "图标底与品牌色同色相", "Theme.kt 里找不到 `val Brand* = Color(0xFF……)`")
else:
    bh, fh = hue(hex_to_rgb(brand.group(1))), hue(G.FIELD)
    check(abs(bh - G.BRAND_HUE) <= HUE_TOL and abs(fh - G.BRAND_HUE) <= HUE_TOL,
          "图标底与品牌色同色相",
          f"Theme.kt #{brand.group(1).upper()} H={bh:.1f}° · 图标底 H={fh:.1f}° · "
          f"BRAND_HUE={G.BRAND_HUE}° · 偏差 {abs(bh - G.BRAND_HUE):.1f}° / "
          f"{abs(fh - G.BRAND_HUE):.1f}°（容差 {HUE_TOL}°）")

# The hoof color should be exactly Theme.kt's primary -- the icon and the UI share one color value
if brand:
    check(hex_to_rgb(brand.group(1)) == G.HOOF, "蹄色 = Theme.kt 的 primary",
          f"图标 #{'%02X%02X%02X' % G.HOOF} · Theme #{brand.group(1).upper()}")

# 2. The adaptive background layer's color value must equal FIELD
colors = os.path.join(ROOT, "androidApp/src/main/res/values/colors.xml")
bg = ET.parse(colors).getroot().find("./color[@name='ic_launcher_bg']")
check(bg is not None and hex_to_rgb(bg.text) == G.FIELD,
      "背景层色值 = FIELD",
      f"colors.xml {bg.text if bg is not None else '缺失'} · FIELD #{'%02X%02X%02X' % G.FIELD}")

# 3. The coins are separated from the pig's body by their outline
c_body = contrast(G.GOLD, G.BODY)
c_edge = contrast(G.GEDGE, G.GOLD)
print(f"  [INFO] 金对猪身               {c_body:.2f}:1 —— 几乎一样，所以描边是**唯一**的分隔手段")
check(c_edge >= COIN_EDGE_MIN, "钱币描边对金的对比度",
      f"{c_edge:.2f}:1（门槛 {COIN_EDGE_MIN}）· 删掉描边钱币会糊进身体")

print("\n几何（读生成物，不信常量）")
try:
    from PIL import Image
    fg = os.path.join(ROOT, "androidApp/src/main/res/mipmap-xxxhdpi/ic_launcher_foreground.png")
    im = Image.open(fg).convert("RGBA")
    n = im.width
    px = im.getchannel("A").load()
    cx = cy = n / 2
    # Find the truly farthest opaque point **pixel by pixel**. Computing from the
    # bounding box's corner would overestimate it -- there's often no pixel at all at
    # that corner, which would wrongly flag a fine design as out of bounds.
    far = 0.0
    for y in range(n):
        dy2 = (y - cy) ** 2
        for x in range(n):
            if px[x, y] > 8:        # 8: ignore near-transparent anti-aliased edge pixels
                r2 = (x - cx) ** 2 + dy2
                if r2 > far:
                    far = r2
    far = math.sqrt(far)
    safe = n * 66 / 108 / 2
    check(far <= safe, "落在 66/108 保证可见圆内",
          f"最远像素 {far:.1f}px / 安全半径 {safe:.1f}px（{far / safe * 100:.1f}%）· "
          f"{os.path.basename(fg)} {n}px")

    ios = os.path.join(ROOT, "iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/icon-1024.png")
    m = Image.open(ios)
    check(m.mode == "RGB" and m.size == (1024, 1024), "iOS 1024 无 alpha",
          f"mode={m.mode} size={m.size}（带 alpha 会被 App Store 拒）")
except FileNotFoundError as e:
    check(False, "生成物缺失", f"{e} —— 先跑 generate.py")

if fails:
    print(f"\n✗ {len(fails)} 项没过")
    sys.exit(1)
print("\n✓ 全部通过")
