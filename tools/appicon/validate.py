#!/usr/bin/env python3
"""校验 app icon 的配色和几何。改了 generate.py 的常量就跑这个。

    python3 tools/appicon/validate.py

⚠️ **这一版删掉了「环的五段 = ChartColors 的五个大类色」那组检查。**
图标从「破环而出」（分段环 + 三根柱）换成了「驮着钱的飞猪」，环和柱都不存在了，
那几条判据没有可检查的对象。删掉是对的 —— 留着一条永远 FAIL 或永远空转的检查，
比没有检查更糟，它会让人以为还有东西在把关。

**配色**
  1. 图标底色 FIELD 和 Theme.kt 的品牌色**同一个色相角**
  2. 自适应背景层的色值 = FIELD（前景把缝隙留成透明，露出的就是它）
  3. 钱币对猪身要有分离度 —— 金和粉的**亮度几乎一样**（1.11:1），
     全靠那圈深金描边分开。这条锁住"描边没被顺手删掉"

**几何**（直接读生成物，不信常量）
  4. 自适应前景的**全部不透明像素**落在直径 66/108 的保证可见圆内。
     ⚠️ 这条**不能靠核对常量**：上一版有个手算的 `CONTENT_R`，**算错了 27%**
     （最远的点不在想当然的位置），前景因此超出安全圆、在圆形遮罩的启动器上被削掉，
     而这本地合成看不出来。现在 generate.py 改成扫像素自适应了，这条是它的独立复核。
  5. iOS 那张 1024 **不能带 alpha 通道**（带 alpha 会被 App Store 拒）。

退出码 0 全过，1 有 FAIL。
"""
import math
import os
import re
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import generate as G  # noqa: E402

# 色相角允许的误差（度）。**不能取太紧**：8-bit 量化会把色相角推 ±1.5°。
HUE_TOL = 3.0
COIN_EDGE_MIN = 1.6     # 描边色对钱币色的对比度下限（它是唯一的分隔手段）


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

# 1. 图标底色和 Theme.kt 的品牌色同色相
theme = os.path.join(ROOT, "shared/src/commonMain/kotlin/com/boomsset/ui/theme/Theme.kt")
# 匹配 `val Brand<任意名>` —— 这个常量名已经改过四次（Amber→Olive→Gold→Cream→Rose），
# 写死名字只会让验证器在下次改色时假报警。
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

# 蹄色应当就是 Theme.kt 的 primary 本身 —— 图标和 UI 共用一个色值
if brand:
    check(hex_to_rgb(brand.group(1)) == G.HOOF, "蹄色 = Theme.kt 的 primary",
          f"图标 #{'%02X%02X%02X' % G.HOOF} · Theme #{brand.group(1).upper()}")

# 2. 自适应背景层的色值必须等于 FIELD
colors = os.path.join(ROOT, "androidApp/src/main/res/values/colors.xml")
bg = ET.parse(colors).getroot().find("./color[@name='ic_launcher_bg']")
check(bg is not None and hex_to_rgb(bg.text) == G.FIELD,
      "背景层色值 = FIELD",
      f"colors.xml {bg.text if bg is not None else '缺失'} · FIELD #{'%02X%02X%02X' % G.FIELD}")

# 3. 钱币靠描边和猪身分开
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
    # **逐像素**找真正最远的不透明点。用包围盒的角算会高估 ——
    # 那个角上往往根本没有像素，会把没问题的设计判成超界。
    far = 0.0
    for y in range(n):
        dy2 = (y - cy) ** 2
        for x in range(n):
            if px[x, y] > 8:        # 8：忽略抗锯齿边缘的近透明像素
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
