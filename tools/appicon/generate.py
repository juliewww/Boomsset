#!/usr/bin/env python3
"""生成 app icon 的全部尺寸。

**这是图标的唯一事实来源。** res/ 和 Assets.xcassets 里的 PNG 都是产物，
不要手改 —— 改了下次跑这个脚本就没了。改设计请改下面的常量。

    python3 tools/appicon/generate.py

需要 Pillow（macOS 自带的 python3 通常已有）。构建本身不依赖它 ——
PNG 是提交进仓库的，所以 CI 和别人的机器不用装 Pillow。

两个平台的几何要求完全不同，这也是为什么必须用脚本而不是手切图：

* **iOS** 要满幅（full-bleed）1024×1024，**不能带 alpha 通道**，
  自己也不能画圆角 —— 系统会套 squircle 遮罩。带 alpha 会被 App Store 拒。
* **Android 自适应图标**的图层是 108dp，但只有中间 72dp 可见、
  且只有直径 66dp 的圆保证不被裁。启动器的遮罩形状由 OEM 决定
  （圆/方/squircle/水滴），所以图形必须缩进安全区。
  照 iOS 那张满幅图直接拿来当前景，环会被裁掉一圈。
"""
from PIL import Image, ImageDraw, ImageFont
import json
import os

# ── 设计常量 ──────────────────────────────────────────────────────────
# 品牌色**琥珀棕**。⚠️ 必须和 shared/.../ui/theme/Theme.kt 里的 BrandAmber 一致 ——
# 图标和界面脱节比图标丑更糟。改配色两边一起改。
#
# 底色刻意做暖（不是灰白）：上一版墨蓝配近乎灰白的底，实机上显得寡淡，
# 在一屏彩色图标里没有存在感。暖底 + 明度跨度更大的环能补这个。
BRAND      = (138, 90, 24)      # #8A5A18，= Theme.kt 的 BrandAmber，也是「旺」字色
BG_TOP     = (253, 248, 239)    # 暖米白，竖向渐变
BG_BOT     = (240, 227, 203)

# 四段配置环走**同一色相的明度梯度**，不是四个色相。
# 四个互不相干的色相（试过红/橙/金/紫）在这个尺寸上互相打架，很难看；
# 单色相梯度是配置类图表的通行做法，也更容易读出"这是一个整体被分成了几份"。
#
# 最浅那一段仍要有足够彩度：太浅会在浅色底上消失，40px 下整个环看着像断了一块。
SEGMENTS = [
    (0.34, (62, 38, 6)),        # 最深，近乎深褐
    (0.26, BRAND),
    (0.22, (184, 130, 58)),
    (0.18, (217, 174, 107)),    # 最浅——但不能再浅了
]
GAP_DEG = 0.9               # 段间留白，让"分段"读得出来

# 环的粗细由 INNER/OUTER 之比决定，**不是**由 OUTER 决定 ——
# 自适应前景的缩放公式里 OUTER 被除掉了，环最终一定是 66dp。
#
# 笔画刻意做粗（内外径比 0.72）：Pixel Launcher 会对自适应图标**再缩一次**
# （Launcher3 的图标归一化，不在 AdaptiveIconDrawable 规范里，本地合成看不出来），
# 实机上环比按 72dp 视口算出来的更小。顶着安全区放大环会在别的 OEM 遮罩下被削，
# 所以改为加粗笔画来补视觉重量。
# 内径和字号都**相对环外径**给，不是相对画布 ——
# 这样改 RING_OUTER（满幅版留白）不会牵动笔画粗细和字号。
# 之前写成相对画布，把留白从 0.80 调到 0.72 会顺带把笔画改细 20%，很难看出来。
RING_OUTER  = 0.72          # 环外径 / 画布宽。**只影响满幅版的留白** ——
                            # 自适应版的缩放公式里它被除掉了，环恒为 66dp
INNER_RATIO = 0.72          # 内径 / 外径。越小环越粗
GLYPH_RATIO = 0.48          # 「旺」字号 / 环外径 —— 必须塞进中心洞

# 黑体而不是宋体：宋体的细横在 40px 上直接糊掉，实测对比过。
# PingFang 受系统保护、PIL 读不了，Hiragino Sans GB W6 是可用的最接近选择。
FONT = ("/System/Library/Fonts/Hiragino Sans GB.ttc", 2)

SS = 4                      # 超采样倍数 —— PIL 的 draw 没有抗锯齿

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


def _vgrad(size, top, bot):
    col = Image.new("RGB", (1, size))
    d = ImageDraw.Draw(col)
    for y in range(size):
        t = y / (size - 1)
        d.point((0, y), tuple(round(top[i] + (bot[i] - top[i]) * t) for i in range(3)))
    return col.resize((size, size), Image.NEAREST)


def _draw(canvas, content_scale, transparent):
    """画一张 canvas×canvas 的图。

    content_scale 是图形相对画布的缩放 —— 满幅版 1.0，
    Android 自适应前景要缩进安全区所以小于 1。
    """
    n = canvas * SS
    if transparent:
        img = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    else:
        img = _vgrad(n, BG_TOP, BG_BOT).convert("RGBA")
    d = ImageDraw.Draw(img)

    outer = n * RING_OUTER * content_scale
    inner = outer * INNER_RATIO
    box = [(n - outer) / 2, (n - outer) / 2, (n + outer) / 2, (n + outer) / 2]

    angle = -90                      # 从 12 点开始，段间空隙落在正上方
    for frac, color in SEGMENTS:
        sweep = frac * 360
        d.pieslice(box, angle + GAP_DEG, angle + sweep - GAP_DEG, fill=color)
        angle += sweep

    # 掏空中心。透明前景要真的掏成透明（自适应背景层在下面），
    # 满幅版则把渐变底贴回去。
    hole = [(n - inner) / 2, (n - inner) / 2, (n + inner) / 2, (n + inner) / 2]
    mask = Image.new("L", (n, n), 0)
    ImageDraw.Draw(mask).ellipse(hole, fill=255)
    if transparent:
        img.paste((0, 0, 0, 0), (0, 0), mask)
    else:
        img.paste(_vgrad(n, BG_TOP, BG_BOT).convert("RGBA"), (0, 0), mask)

    d = ImageDraw.Draw(img)
    font = ImageFont.truetype(FONT[0], int(outer * GLYPH_RATIO), index=FONT[1])
    b = d.textbbox((0, 0), "旺", font=font)
    d.text(((n - (b[2] + b[0])) / 2, (n - (b[3] + b[1])) / 2), "旺", font=font, fill=BRAND)

    return img.resize((canvas, canvas), Image.LANCZOS)


def full_bleed(size):
    """满幅版：iOS 用，以及 Android 的 legacy 图标。"""
    return _draw(size, content_scale=1.0, transparent=False).convert("RGB")


def adaptive_foreground(size):
    """Android 自适应前景：透明底 + 缩进安全区。

    图形缩到直径 66/108 的保证可见圆内 —— 除以 RING_OUTER 是因为
    content_scale 缩的是整个图形，而环外径本来就只占 RING_OUTER。
    """
    return _draw(size, content_scale=(66 / 108) / RING_OUTER, transparent=True)


def main():
    written = []

    # ── iOS：单张 1024，无 alpha ──
    ios_dir = os.path.join(ROOT, "iosApp/iosApp/Assets.xcassets/AppIcon.appiconset")
    os.makedirs(ios_dir, exist_ok=True)
    p = os.path.join(ios_dir, "icon-1024.png")
    full_bleed(1024).save(p)                      # convert("RGB") 已去掉 alpha
    written.append(p)
    with open(os.path.join(ios_dir, "Contents.json"), "w") as f:
        json.dump({
            "images": [{"filename": "icon-1024.png", "idiom": "universal",
                        "platform": "ios", "size": "1024x1024"}],
            "info": {"author": "xcode", "version": 1},
        }, f, indent=2)
        f.write("\n")
    written.append(os.path.join(ios_dir, "Contents.json"))

    # Assets.xcassets 自己也要一个 Contents.json，否则 Xcode 不认这个目录
    xcassets = os.path.join(ROOT, "iosApp/iosApp/Assets.xcassets")
    with open(os.path.join(xcassets, "Contents.json"), "w") as f:
        json.dump({"info": {"author": "xcode", "version": 1}}, f, indent=2)
        f.write("\n")

    # ── Android：自适应前景 + legacy 满幅 ──
    # 自适应图层固定 108dp；legacy 图标 48dp。两套都按五档密度出。
    res = os.path.join(ROOT, "androidApp/src/main/res")
    for bucket, factor in [("mdpi", 1), ("hdpi", 1.5), ("xhdpi", 2),
                           ("xxhdpi", 3), ("xxxhdpi", 4)]:
        d = os.path.join(res, f"mipmap-{bucket}")
        os.makedirs(d, exist_ok=True)

        p = os.path.join(d, "ic_launcher_foreground.png")
        adaptive_foreground(round(108 * factor)).save(p)
        written.append(p)

        # legacy：Android 8.0 起自适应会接管，但 android:icon 仍要能解析出位图，
        # 而且部分启动器/系统界面还会去取这一份。
        legacy = full_bleed(round(48 * factor))
        for name in ("ic_launcher.png", "ic_launcher_round.png"):
            p = os.path.join(d, name)
            legacy.save(p)
            written.append(p)

    print(f"生成 {len(written)} 个文件")
    for p in written:
        print("  " + os.path.relpath(p, ROOT))


if __name__ == "__main__":
    main()
