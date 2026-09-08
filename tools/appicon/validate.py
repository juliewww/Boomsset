#!/usr/bin/env python3
"""校验 app icon 的配色和几何。改了 generate.py 的常量就跑这个。

    python3 tools/appicon/validate.py

分两部分：

**配色**（判据见 generate.py 的 RING 注释）
  1. 环的五段 = ChartColors.kt 里的五个大类色，**逐个核对色值**。
     图标上的环就是配置页上那五个色块，品牌一致性靠这条锁住。
     ⚠️ 早先几版是拿这五个**色相**重新铺一条亮度梯度，那时这里验的是
     "亮度单调递增"；改用原色之后那条**不再成立也不再检查**——
     最亮的三个几乎持平，兜底的是下面的色盲分离度
  2. 相邻段色盲分离度 ΔE ≥ 8（OKLab ×100，Machado 2009 protan/deutan 模拟）
  2b. 柱与任一环段的 ΔE ≥ 10 —— 中间虽有底色间隙，色值太接近仍会读成
      "柱子是环的一部分"。这条**曾经只写在注释里没有实现**，而它正是
      把「权益类」按到最暗位的原因，漏掉就会在下次改色时静默失效
  3. 每段对底 ≥ 1.8:1，否则那段会读成环上的缺口
  4. 图标底色和 Theme.kt 的品牌色**同一个色相角**；和 colors.xml 的背景层**同一个色值**

**几何**（直接读生成物，不信常量）
  5. 自适应前景的**全部不透明像素**落在直径 66/108 的保证可见圆内。
     这条不能靠核对 CONTENT_R —— 改任何一个几何常量都会让手算的值失真，
     而裁切的后果（穿出环外的那根柱被削掉）恰恰是本地合成看不出来的。

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

CVD_TARGET = 8.0        # OKLab ΔE×100，相邻段，min(protan, deutan)
BAR_RING_MIN = 10.0     # 柱与任一环段的 ΔE 下限（见 generate.py 的判据 2b）
FIELD_MIN = 1.80        # 每段对底的对比度下限
# 色相角允许的误差（度）。**不能取太紧**：两个色都是从 BRAND_HUE 解出来的，
# 但 8-bit 量化会把色相角推 ±1.5°（实测 Theme.kt 的 #918163 落在 83.0°、
# 图标底 #D7B984 落在 81.4°）。判据是**各自对 BRAND_HUE 的偏差**，
# 不是两者互相比 —— 后者会把两边各偏一点的正常情况判成 FAIL。
HUE_TOL = 2.0

# ── 色彩数学（和 shared 的 ChartColorsTest 注释里那套验证器同源）──────────
MACHADO = {
    'protan': [[0.152286, 1.052583, -0.204868],
               [0.114503, 0.786281, 0.099216],
               [-0.003882, -0.048116, 1.051998]],
    'deutan': [[0.367322, 0.860646, -0.227968],
               [0.280085, 0.672501, 0.047413],
               [-0.011820, 0.042940, 0.968881]],
}


def _s2l(c):
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4


def _l2s(c):
    return c * 12.92 if c <= 0.0031308 else 1.055 * (c ** (1 / 2.4)) - 0.055


def oklab(rgb):
    r, g, b = (_s2l(c / 255) for c in rgb)
    l = 0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b
    m = 0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b
    s = 0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b
    l_, m_, s_ = (math.copysign(abs(v) ** (1 / 3), v) for v in (l, m, s))
    return (0.2104542553 * l_ + 0.7936177850 * m_ - 0.0040720468 * s_,
            1.9779984951 * l_ - 2.4285922050 * m_ + 0.4505937099 * s_,
            0.0259040371 * l_ + 0.7827717662 * m_ - 0.8086757660 * s_)


def lightness(rgb):
    return oklab(rgb)[0]


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


def cvd(rgb, kind):
    lin = [_s2l(c / 255) for c in rgb]
    m = MACHADO[kind]
    return tuple(round(255 * _l2s(max(0.0, min(1.0, sum(m[i][j] * lin[j] for j in range(3))))))
                 for i in range(3))


def delta_e(x, y):
    return 100 * math.dist(oklab(x), oklab(y))


def cvd_delta_e(x, y):
    return min(delta_e(cvd(x, k), cvd(y, k)) for k in ('protan', 'deutan'))


def hex_to_rgb(h):
    h = h.strip().lstrip('#')
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


# ── 检查 ──────────────────────────────────────────────────────────────
fails, warns = [], []


def check(ok, label, detail):
    line = f"  [{'PASS' if ok else 'FAIL'}] {label:<26} {detail}"
    print(line)
    if not ok:
        fails.append(line)


ring = [c for _, c in G.RING]

print("配色")

# 环必须逐色等于 App 的大类色 —— 这是"图标环 = 配置页色块"这条品牌规则的锁
chart_src = os.path.join(ROOT, "shared/src/commonMain/kotlin/com/boomsset/ui/theme/ChartColors.kt")
body = open(chart_src).read()
light = body[body.index("private val LightChartColors"):]
chart = [hex_to_rgb(m) for m in re.findall(r"Color\(0xFF([0-9A-Fa-f]{6})\)", light)[:5]]
check(sorted(ring) == sorted(chart), "环 = App 的五个大类色",
      f"图标 {[('#%02X%02X%02X' % c) for c in ring]} · "
      f"ChartColors {[('#%02X%02X%02X' % c) for c in chart]}")
print(f"  [INFO] 环的亮度               {' → '.join(f'{lightness(c):.3f}' for c in ring)}"
      f"（最亮三个几乎持平，故不验单调性）")

cvds = [cvd_delta_e(a, b) for a, b in zip(ring, ring[1:])]
check(min(cvds) >= CVD_TARGET, "相邻段色盲分离度",
      f"最小 ΔE {min(cvds):.1f}（门槛 {CVD_TARGET}）")

ctr = [contrast(c, G.FIELD) for c in ring]
check(min(ctr) >= FIELD_MIN, "每段对底的对比度",
      f"{min(ctr):.2f}~{max(ctr):.2f}:1（门槛 {FIELD_MIN}）")

barc = [contrast(c, G.FIELD) for c in G.BARS]
print(f"  [INFO] 柱对底的对比度        {min(barc):.2f}~{max(barc):.2f}:1")

pairs = [(delta_e(b, r), bi, ri) for bi, b in enumerate(G.BARS) for ri, r in enumerate(ring)]
worst, bi, ri = min(pairs)
check(worst >= BAR_RING_MIN, "柱与环段的分离度",
      f"最小 ΔE {worst:.1f}（门槛 {BAR_RING_MIN}）· 最接近的一对：柱{bi + 1} ↔ 环{ri + 1}")

# 图标底色和 Theme.kt 的品牌色必须同一个色相角
theme = os.path.join(ROOT, "shared/src/commonMain/kotlin/com/boomsset/ui/theme/Theme.kt")
# 匹配 `val Brand<任意名>` —— 这个常量名已经改过三次（BrandAmber → BrandOlive →
# BrandGold → BrandCream），写死名字只会让验证器在下次改色时假报警。
brand = re.search(r"val Brand\w* = Color\(0xFF([0-9A-Fa-f]{6})\)", open(theme).read())
if not brand:
    check(False, "图标底与品牌色同色相", "Theme.kt 里找不到 `val Brand* = Color(0xFF……)`")
else:
    bh, fh = hue(hex_to_rgb(brand.group(1))), hue(G.FIELD)
    check(abs(bh - G.BRAND_HUE) <= HUE_TOL and abs(fh - G.BRAND_HUE) <= HUE_TOL,
          "图标底与品牌色同色相",
          f"Theme.kt #{brand.group(1).upper()} H={bh:.1f}° · 图标底 H={fh:.1f}° · "
          f"BRAND_HUE={G.BRAND_HUE}° · 各自偏差 {abs(bh - G.BRAND_HUE):.1f}° / "
          f"{abs(fh - G.BRAND_HUE):.1f}°（容差 {HUE_TOL}°）")

# 自适应背景层的色值必须等于 FIELD（前景把间隙掏成了透明，露出的就是它）
colors = os.path.join(ROOT, "androidApp/src/main/res/values/colors.xml")
bg = ET.parse(colors).getroot().find("./color[@name='ic_launcher_bg']")
check(bg is not None and hex_to_rgb(bg.text) == G.FIELD,
      "背景层色值 = FIELD",
      f"colors.xml {bg.text if bg is not None else '缺失'} · FIELD #{'%02X%02X%02X' % G.FIELD}")

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
          f"最远像素 {far:.1f}px / 安全半径 {safe:.1f}px（{far / safe * 100:.1f}%）· {os.path.basename(fg)} {n}px")
except FileNotFoundError:
    warns.append("自适应前景还没生成 —— 先跑 generate.py")
    print("  [WARN] 自适应前景还没生成 —— 先跑 generate.py")

if warns:
    print(f"\n{len(warns)} 条 WARN")
if fails:
    print(f"\n✗ {len(fails)} 项没过")
    sys.exit(1)
print("\n✓ 全部通过")
