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
  照 iOS 那张满幅图直接拿来当前景，图形会被裁掉一圈。

── 设计：「破环而出」──────────────────────────────────────────────

图标要同时说清三件事，而且是**同一个手势**在说：

* **配置** —— 闭合的分段环，五段对应五个大类
* **管理** —— 环把它们收拢成一个有序的整体，做柱子的底盘
* **钱越来越多** —— 三根上升的柱，最高一根**穿出环外**

上一版是「配置环 + 旺字」，只说了配置。
"破环"这一下必须**真的穿出去**：闭合环 + 柱高小于环径时，几何上做不到，
所以环被刻意缩小、柱子做主体。第一版试过大环小柱，柱顶还在环内，
只是被间隙衬开，读起来像"柱子插在环里"而不是"长出来"。
"""
from PIL import Image, ImageDraw
import json
import os

# ── 设计常量 ──────────────────────────────────────────────────────────
# 品牌色相角。⚠️ 必须和 shared/.../ui/theme/Theme.kt 里的 BrandOlive (#918163) 一致 ——
# 那套配色也是从这个 H 解出来的。图标和界面脱节比图标丑更糟。
BRAND_HUE = 82                  # 橄榄金

# 底色：暖麦金。**刻意不是近白** —— 上一版墨蓝配近乎灰白的底，实机上显得寡淡，
# 在一屏彩色图标里没有存在感。图标不受任何碰撞约束（永远不和图表同屏），
# 所以彩度给到 0.078，比 UI 的 0.047 高 —— 阳光感主要来自这里。
# 亮度从 0.761 提到 0.800：环和柱**全都比底暗**，底太低会让整张图压在暗部
# （"太沉闷"那条反馈的一半原因在这里，另一半是柱子本身，见下方 BARS）。
FIELD = (215, 185, 132)         # #D7B984，= OKLCH(0.800, 0.078, 82)

# 五段配置环。**这一版是五个色相，不是单色相明度梯度。**
#
# 上一版的注释写着"四个互不相干的色相在这个尺寸上互相打架"—— 那是真的，
# 但原因不是"多色相"，是**没有控制亮度**。修法和图表配色是同一套纪律：
# 色相可以各走各的，**亮度必须单调递增**，这样 48px 下即使色相读不出来，
# 段与段仍然靠明暗分开。
#
# 五个色相取自本 App 的五个大类色（流动/固收/权益/另类/保障），
# 亮度重新铺成 L 0.360 → 0.622 的单调梯度。
#
# 两条判据（改色值要重跑 tools/appicon/validate.py）：
# 1. **亮度单调递增** —— 这是小尺寸可读性的保证
# 2. **相邻段色盲分离度 ΔE ≥ 8**（OKLab ×100，protan/deutan 模拟）
#    实测 8.7。段序**不是**大类展示顺序，是在 120 种排列里搜出的 ——
#    按大类顺序排只有 6.1，蓝/青和绿/橙在色盲下会并到一起。
#    图标不是图例，段序不承载语义，可以为可读性让路。
#
# 2b. **柱与任一环段的 ΔE ≥ 10**（实测 11.1）。柱和环之间虽然有底色间隙隔开，
#    但色值太接近时会读成"柱子是环的一部分"。第一版提亮柱子后，最短那根
#    （L 0.52 暖褐）和「权益类」那段（L 0.517 橙）只差 ΔE 3.9 —— 段序是搜出来的，
#    所以把这条也加进搜索条件一起解，不是事后手调。
#
# 3. **每段对底 ≥ 1.8:1**，否则那一段会读成环上的缺口。
#    这条限制了梯度上端：底色是 L 0.800，**梯度不能跨过它** ——
#    越接近底色对比度越塌，压深到合规又会破坏单调性（试过，断言抓到了）。
#    所以整条梯度压在底色之下，上端止于 L 0.622。
RING = [
    (0.26, (0, 62, 113)),       # #003E71  流动资金 251°    L 0.360  对底 5.79:1
    (0.22, (0, 88, 107)),       # #00586B  另类实物 219°    L 0.426  对底 4.34:1
    (0.20, (0, 115, 82)),       # #007352  固定收益 165°    L 0.491  对底 3.24:1
    (0.17, (103, 108, 173)),    # #676CAD  保障类   279°    L 0.557  对底 2.44:1
    (0.15, (184, 117, 53)),     # #B87535  权益类    62°    L 0.622  对底 1.98:1
]
GAP_DEG = 1.6                   # 段间留白，让"分段"读得出来

# 三根上升柱走品牌色相的明度梯度 —— 环已经是五个色相了，柱子再多色会吵。
# 最高那根最深（穿出环外，是整个图标的落点）。
#
# ⚠️ **不要再往深里调。** 第一版是 L 0.50/0.43/0.265，反馈是"太沉闷" ——
# 根因是**八个元素全都比底色暗**，而最高那根柱（近乎黑的 #322200）是最大的
# 单块暗部，整张图的重量都压在它上面。现在提到 L 0.52/0.46/0.385，
# 底色也从 L 0.761 提到 0.800 给出空间，最暗元素从 L 0.265 抬到 0.360。
BARS = [(133, 98, 20), (112, 80, 0), (89, 63, 0)]   # #856214 #705000 #593F00

# ── 几何 ──────────────────────────────────────────────────────────────
# 全部相对画布宽给。环刻意做小、柱子做主体，否则"穿出"做不到（见文件头）。
RING_R      = 0.268             # 环外半径 / 画布宽
RING_W      = 0.100             # 环笔画宽。笔画刻意粗：Pixel Launcher 会对自适应
                                # 图标**再缩一次**（Launcher3 的图标归一化，不在
                                # AdaptiveIconDrawable 规范里，本地合成看不出来）
RING_CY     = 0.048             # 环心相对画布中心下移 —— 给上方的柱子让位
BAR_W       = 0.084
BAR_GAP     = 0.028
BAR_HALO    = 0.021             # 柱子周围的底色描边。**没有它，柱子和环的深色段
                                # 会黏成一块**，"穿出"读不出来（第一版就是这样）
BAR_TOPS    = (0.028, -0.082, -0.335)   # 相对环心 / 相对画布中心（最高那根）
# 柱底相对环内半径。**不能取太大**：三根柱加间隙的横向跨度（0.350）比环的内孔直径
# （0.336）还宽，柱子必然压在环上 —— 那是"穿过"的一部分，但柱底伸太低会把环的
# 整个下沿连成一片啃掉，看起来像环缺了一块（启动画面那种大尺寸下尤其明显，
# 48px 的联络表上反而看不出来）。0.60 让环的下沿在中间保持连续。
BAR_BASE    = 0.60

# 图形的最大半径（相对画布宽）。自适应前景的缩放靠它算。
#
# ⚠️ **最远的点是最右那根柱的右上角，不是柱顶正上方** ——
#   sqrt(((3*BAR_W + 2*BAR_GAP) / 2)² + |BAR_TOPS[2]|²) = sqrt(0.154² + 0.335²)
# 第一版按柱顶算成 0.345，自适应前景实际超出安全圆 27%，
# 装到圆形/水滴遮罩的启动器上会把穿出去那根柱削掉 —— 而这**本地合成看不出来**。
# 改任何几何常量都要重跑 tools/appicon/validate.py，它是**逐像素读生成物**验的，
# 不看这个常量。
CONTENT_R   = 0.369

SS = 4                          # 超采样倍数 —— PIL 的 draw 没有抗锯齿

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


def _radius(want, height):
    """把圆角夹到这个高度画得下的范围内。

    PIL 的 `rounded_rectangle` 内部要画一条 `[y0 + r + 1, y1 - r - 1]` 的竖条，
    所以要求**高度 ≥ 2r + 2**，只满足 2r 还会抛 "y1 must be greater than or equal to y0"。
    最短那根柱在 mdpi（48px）下只差 1.1px 就崩 —— 而这只在最小的那档密度上发作，
    大尺寸全都正常，很容易漏。
    """
    return max(1.0, min(want, height / 2 - 1.5))


def _draw(canvas, content_scale, transparent):
    """画一张 canvas×canvas 的图。

    content_scale 是图形相对画布的缩放 —— 满幅版 1.0，
    Android 自适应前景要缩进安全区所以小于 1。
    """
    n = canvas * SS
    s = content_scale
    img = Image.new("RGBA", (n, n), (0, 0, 0, 0) if transparent else FIELD + (255,))
    d = ImageDraw.Draw(img)

    cx, cy = n / 2, n / 2 + n * RING_CY * s
    R, w = n * RING_R * s, n * RING_W * s

    # ── 配置环（底盘）──
    box = [cx - R, cy - R, cx + R, cy + R]
    angle = -90                                  # 从 12 点开始
    for frac, color in RING:
        sweep = frac * 360
        d.pieslice(box, angle + GAP_DEG, angle + sweep - GAP_DEG, fill=color)
        angle += sweep

    # 掏空中心。透明前景要真的掏成透明（自适应背景层在下面），满幅版贴回底色。
    hole = R - w
    ring_hole = [cx - hole, cy - hole, cx + hole, cy + hole]
    if transparent:
        mask = Image.new("L", (n, n), 0)
        ImageDraw.Draw(mask).ellipse(ring_hole, fill=255)
        img.paste((0, 0, 0, 0), (0, 0), mask)
    else:
        d.ellipse(ring_hole, fill=FIELD)

    # ── 上升柱 ──
    # 每根柱周围先留一圈间隙再画柱子本身，否则柱子会和环的深色段黏成一块。
    #
    # ⚠️ **透明前景上这圈间隙要真的掏成透明**，不能填成底色。
    # Android 13+ 的主题图标（monochrome）**只取这张图的 alpha 通道**当剪影 ——
    # 填成底色的话间隙会被算进剪影，柱子和环重新粘成一块，"破环"在主题图标下就没了。
    # 掏成透明在视觉上没有区别：自适应背景层就是同一个 FIELD 实色（见
    # androidApp/.../drawable/ic_launcher_background.xml，改一边要改另一边）。
    bw, gap = n * BAR_W * s, n * BAR_GAP * s
    x0 = cx - (3 * bw + 2 * gap) / 2
    base_y = cy + hole * BAR_BASE
    tops = (cy + n * BAR_TOPS[0] * s,
            cy + n * BAR_TOPS[1] * s,
            n / 2 + n * BAR_TOPS[2] * s)
    halo = n * BAR_HALO * s
    boxes = [[x0 + i * (bw + gap) - halo, top - halo,
              x0 + i * (bw + gap) + bw + halo, base_y + halo]
             for i, top in enumerate(tops)]
    if transparent:
        mask = Image.new("L", (n, n), 0)
        md = ImageDraw.Draw(mask)
        for b in boxes:
            md.rounded_rectangle(b, radius=_radius(bw * 0.55, b[3] - b[1]), fill=255)
        img.paste((0, 0, 0, 0), (0, 0), mask)
    else:
        d = ImageDraw.Draw(img)
        for b in boxes:
            d.rounded_rectangle(b, radius=_radius(bw * 0.55, b[3] - b[1]), fill=FIELD)

    d = ImageDraw.Draw(img)
    for i, (top, color) in enumerate(zip(tops, BARS)):
        x = x0 + i * (bw + gap)
        d.rounded_rectangle([x, top, x + bw, base_y],
                            radius=_radius(bw * 0.40, base_y - top), fill=color)

    return img.resize((canvas, canvas), Image.LANCZOS)


def full_bleed(size):
    """满幅版：iOS 用，以及 Android 的 legacy 图标。"""
    return _draw(size, content_scale=1.0, transparent=False).convert("RGB")


def adaptive_foreground(size):
    """Android 自适应前景：透明底 + 缩进安全区。

    图形缩到直径 66/108 的保证可见圆内。除以 CONTENT_R 是因为 content_scale
    缩的是整个图形，而图形的最大半径本来就只占 CONTENT_R。
    """
    return _draw(size, content_scale=(66 / 108) / 2 / CONTENT_R, transparent=True)


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
