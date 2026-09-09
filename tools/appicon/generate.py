#!/usr/bin/env python3
"""生成 app icon 的全部尺寸。

**这是图标的唯一事实来源。** res/ 和 Assets.xcassets 里的 PNG 都是产物，
不要手改 —— 改了下次跑这个脚本就没了。改设计请改下面的常量。

    python3 tools/appicon/generate.py
    python3 tools/appicon/validate.py     # 改完必须跑

需要 Pillow（macOS 自带的 python3 通常已有）。构建本身不依赖它 ——
PNG 是提交进仓库的，所以 CI 和别人的机器不用装 Pillow。

两个平台的几何要求完全不同，这也是为什么必须用脚本而不是手切图：

* **iOS** 要满幅（full-bleed）1024×1024，**不能带 alpha 通道**，
  自己也不能画圆角 —— 系统会套 squircle 遮罩。带 alpha 会被 App Store 拒。
* **Android 自适应图标**的图层是 108dp，但只有中间 72dp 可见、
  且只有直径 66dp 的圆保证不被裁。启动器的遮罩形状由 OEM 决定
  （圆/方/squircle/水滴），所以图形必须缩进安全区。

── 设计：「驮着钱的飞猪」──────────────────────────────────────────

上一版是「破环而出」（分段环 + 三根穿出环外的柱），抽象、和"资产配置"直连，
但反馈是**太繁琐、颜色太多**。这一版换成一个具体的吉祥物：

* **会飞的猪** —— 猪 = 存钱罐/招财，翅膀 = 资产在增长（"会飞"）
* **侧身三枚铜钱** —— 钱币本身，同时借存钱罐的读感
* **前后两条腿 / 圆耳带小尖 / 眼睛高光** —— 可爱度，都是实机比过尺寸的

⚠️ **几何自适应，不再手算包围半径。** 上一版有个 `CONTENT_R` 常量是手算的，
**算错了 27%**（最远的点是最右那根柱的右上角，不是柱顶），自适应前景因此超出
安全圆、在圆形遮罩的启动器上会被削掉，而这**本地合成看不出来**。
这一版改成：先把图形画在透明画布上，**逐像素扫出真实的包围半径**，
再按目标半径缩放居中 —— 没有可以算错的常量。
"""
from PIL import Image, ImageDraw
import json
import math
import os

# ── 品牌 ──────────────────────────────────────────────────────────────
# 品牌色相角。⚠️ 必须和 shared/.../ui/theme/Theme.kt 里的 BrandRose (#C94385) 一致。
# 图标和界面脱节比图标丑更糟。
BRAND_HUE = 354                 # 中玫瑰

# 底色：近白淡紫粉。⚠️ 这是**图标自己的底**，和 App 的页面底（暖色 H70）不是同一个
# 色相 —— 界面的中性面按要求保持暖色，图标跟品牌色，这是刻意的。
# 它同时是 Android 自适应背景层的色值（colors.xml 的 ic_launcher_bg），
# 因为前景把缝隙掏成了透明，露出来的就是它。改这里要改那边（validate.py 会核对）。
# ⚠️ 色值是 **#F9F0FD 挪到品牌色相之后**的结果。挑底色时选的是 #F9F0FD（偏白、
# 不那么粉的那一版），但那个值是配**旧的紫色品牌色**解出来的，色相 315.7° ——
# 换成中玫瑰（H 354）之后就和品牌脱节了。同亮度同彩度挪到 H 354 得到 #FFEFF4，
# 两者 **ΔE 仅 1.3**（肉眼分不出），所以既保住了挑选时的观感，也保住了色相一致。
FIELD = (255, 239, 244)         # #FFEFF4

# ── 配色 ──────────────────────────────────────────────────────────────
# 猪身和蹄子同色相（H≈354），和品牌色是一家人。
# ⚠️ **蹄子直接用 `Theme.kt` 的 primary 色值本身**（#C94385）—— 图标和界面里
# 的 FAB、导航选中态是同一个色，这是"图标属于这个 App"最直接的证据。
BODY   = (246, 160, 195)        # #F6A0C3
SNOUT  = (214, 103, 153)        # #D66799  鼻子和内耳
HOOF   = (201,  67, 133)        # #C94385  = Theme.kt 的 primary
WINGC  = (255, 224, 235)        # #FFE0EB  翅膀
EYE    = ( 63,  15,  39)        # #3F0F27

# 铜钱：亮金 + 同色系的深金描边。
# ⚠️ **描边不能省，也不能用白色。** 金对猪身的对比度只有 **1.11:1**（两者亮度
# 几乎一样），没有描边钱币会直接糊进身体，三枚之间也没有边界。
# 白色描边试过，读起来像贴纸（反馈"不要有白色边缘"），所以改用**深金** ——
# 属于钱币自己的色系，像钱币的厚度边。
# 方孔**填猪身色**，让身体从孔里透出来，多一条形状线索。
GOLD   = (238, 188,  74)        # #EEBC4A
GEDGE  = (176, 126,  16)        # #B07E10

# ── 几何（全部相对画布宽）────────────────────────────────────────────
TILT      = -14                 # 身体上仰角，读作"在飞"
BODY_RX   = 0.245
BODY_RY   = 0.186
LEG_W     = 0.036
HOOF_R    = 0.020
EAR_R     = 0.048
EYE_R     = 0.027
SNOUT_R   = 0.068

# 铜钱：直径 24px @250 预览 → 半径比例 0.048。
# ⚠️ 尺寸是实机比出来的：再大会压过脸和翅膀，再小（22px）48px 下方孔就糊没了、
# 只剩一块金斑。**这一档是"还能看出是钱币"的下限附近。**
COIN_R    = 0.048
COIN_POS  = (0.12, 0.02)        # 相对身体中心（x 向后为正，y 向下为正）
COIN_LAY  = [(-0.78, 0.39), (0.78, 0.39), (0, -0.39)]   # 三枚的相对位置（单位=半径）
COIN_HOLE = 0.34                # 方孔半宽 / 钱币半径
COIN_EDGE = 0.025               # 描边宽 / 钱币半径。⚠️ 小尺寸下必然退化成抗锯齿的灰，
                                # 48px 的 legacy 图标上三枚会读成一簇金点 —— 已知且接受

WING_L    = 0.235
WING_W    = 0.062
WING_ANG  = -74

SS = 4                          # 超采样倍数 —— PIL 的 draw 没有抗锯齿
WORK = 1024                     # 画图形用的工作画布（再缩放到各目标尺寸）

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


# ── 绘图基元 ──────────────────────────────────────────────────────────
def _bez(p0, p1, p2, n=48):
    return [((1 - t) ** 2 * p0[0] + 2 * (1 - t) * t * p1[0] + t * t * p2[0],
             (1 - t) ** 2 * p0[1] + 2 * (1 - t) * t * p1[1] + t * t * p2[1])
            for t in (i / n for i in range(n + 1))]


def _frame(ox, oy, ang):
    a = math.radians(ang)
    ca, sa = math.cos(a), math.sin(a)
    return lambda x, y: (ox + x * ca - y * sa, oy + x * sa + y * ca)


def _plume(d, ox, oy, L, W, ang, col, bulge=1.0):
    """一根羽毛：根部窄、中段饱满、尖端圆。"""
    T = _frame(ox, oy, ang)
    up = _bez(T(0, 0), T(L * 0.42, -W * bulge), T(L, -W * 0.16))
    tip = [T(L, -W * 0.16), T(L + W * 0.22, 0), T(L, W * 0.16)]
    dn = _bez(T(L, W * 0.16), T(L * 0.46, W * 0.72 * bulge), T(0, 0))
    d.polygon(up + tip + dn, fill=col)


def _wing(d, ox, oy, L, W, ang, c, fld, shade):
    """天使翼：外层四根主羽 + 根部三根短覆羽（略深），两层才有"天使翅膀"的堆叠感。

    ⚠️ 前扫角度刻意收窄（最外侧 +20° 而不是 +32°），配合翅根后移，
    **否则前羽会盖住耳朵** —— 48px 下耳朵会整个消失。
    """
    for da, l in [(-34, 0.80), (-14, 0.98), (6, 1.0), (20, 0.80)]:
        _plume(d, ox, oy, L * l + L * 0.05, W * 1.30, ang + da, fld)
        _plume(d, ox, oy, L * l, W, ang + da, c)
    for da, l in [(-22, 0.44), (-4, 0.50), (12, 0.40)]:
        _plume(d, ox, oy, L * l + L * 0.05, W * 1.34, ang + da, fld)
        _plume(d, ox, oy, L * l, W * 0.98, ang + da, shade)


def _ear(d, cx, cy, r, col, icol):
    """圆形 + 一个朝上的小尖尖（圆和三角求并）。

    ⚠️ 纯三角的尖耳被否过（"耳朵不要太尖"）；而圆形没有尖会读成猫耳。
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
    """三枚铜钱堆叠（外圆内方）。层序后→前，后面的被前面压住一角。

    ⚠️ **元宝在这个位置上不成立。** 试过把元宝画在侧身，它是横向的船形，
    贴在圆身子上小尺寸会糊成一坨（48px 完全看不出是什么）。
    铜钱的外圆和身体的圆呼应、方孔在小尺寸下还能撑住，是被小尺寸逼出来的选择。
    """
    w = max(1, round(edge_w))
    for dx, dy in COIN_LAY:
        cx, cy = T(dx * r, dy * r)
        d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=GOLD, outline=GEDGE, width=w)
        h = r * COIN_HOLE
        d.polygon([(cx - h, cy - h), (cx + h, cy - h), (cx + h, cy + h), (cx - h, cy + h)],
                  fill=BODY, outline=GEDGE, width=w)


def _pig(n):
    """把猪画在 n×n 的**透明**画布上（只有图形，没有底色）。"""
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

    # 尾巴（一圈渐细的螺旋，用小圆点铺出来）
    tx, ty = T(-RX * 1.02, -RY * 0.30)
    for i in range(70):
        t = i / 69
        a = math.radians(-40 + t * 430)
        rr = n * 0.050 * (1.0 - 0.45 * t)
        x, y = tx + rr * math.cos(a), ty + rr * math.sin(a)
        d.ellipse([x - n * 0.0145, y - n * 0.0145, x + n * 0.0145, y + n * 0.0145], fill=B)

    # 只有两条腿（前后各一）。先画一次，身体盖住上半截；身体之后再画一次露出下半截。
    for _ in range(1):
        leg(-0.30, -0.34, 0.90, 1.10)
        leg(0.28, 0.25, 0.88, 1.08)

    # 身体（旋转过的椭圆）
    lay = Image.new("RGBA", (int(RX * 2) + 6, int(RY * 2) + 6), (0, 0, 0, 0))
    ImageDraw.Draw(lay).ellipse([3, 3, RX * 2 + 3, RY * 2 + 3], fill=B)
    lay = lay.rotate(-TILT, expand=True, resample=Image.BICUBIC)
    img.paste(lay, (int(cx - lay.size[0] / 2), int(cy - lay.size[1] / 2)), lay)
    d = ImageDraw.Draw(img)

    leg(-0.30, -0.34, 0.90, 1.10)
    leg(0.28, 0.25, 0.88, 1.08)

    # 鼻子 + 两个鼻孔
    sx, sy = T(RX * 0.92, -RY * 0.08)
    sr = n * SNOUT_R
    d.ellipse([sx - sr * 0.75, sy - sr * 0.80, sx + sr * 0.95, sy + sr * 0.80], fill=S)
    for k in (-1, 1):
        nr = n * 0.013
        d.ellipse([sx + n * 0.010 - nr, sy + k * n * 0.023 - nr,
                   sx + n * 0.010 + nr, sy + k * n * 0.023 + nr], fill=(168, 76, 122, 255))

    # 耳朵
    ex, ey = T(RX * 0.46, -RY * 0.86)
    _ear(d, ex, ey, n * EAR_R, B, S)

    # 眼睛 + 高光。⚠️ 高光在 48px 下会消失，这是正常的渐进细节，不是 bug。
    ox, oy = T(RX * 0.52, -RY * 0.30)
    er = n * EYE_R
    d.ellipse([ox - er, oy - er, ox + er, oy + er], fill=E)
    hr2 = er * 0.36
    d.ellipse([ox - er * 0.34 - hr2, oy - er * 0.36 - hr2,
               ox - er * 0.34 + hr2, oy - er * 0.36 + hr2], fill=(255, 255, 255, 255))

    # 翅膀。⚠️ 必须在钱币之前画：钱币在侧身、翅膀在背上，两者不重叠，
    # 但翅根靠后，画在钱币之后会压住身体轮廓。
    wx, wy = T(-RX * 0.30, -RY * 0.62)
    _wing(d, wx, wy, n * WING_L, n * WING_W, WING_ANG, W, FIELD + (255,),
          (255, 208, 224, 255))

    # 三枚铜钱（侧身）
    gx, gy = T(-RX * COIN_POS[0], RY * COIN_POS[1])
    _coins(d, _frame(gx, gy, TILT), n * COIN_R, n * COIN_R * COIN_EDGE)

    return img


def _fit(content, canvas, radius_frac):
    """把图形缩放居中，让**真实的**包围半径正好等于 radius_frac × 画布宽。

    ⚠️ **半径是逐像素扫出来的，不是算出来的。** 上一版用手算的常量，错了 27%
    （最远的点不在想当然的地方），自适应前景因此超出安全圆而本地看不出来。
    包围盒的角同样不能用 —— 那个角上往往根本没有像素，会低估缩放、白白缩小图形。
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
            if px[x, y] > 8:                      # 8：忽略抗锯齿边缘的近透明像素
                r2 = (x - cx) ** 2 + dy2
                if r2 > far:
                    far = r2
    far = math.sqrt(far)

    target = canvas * SS * radius_frac
    scale = target / far
    w, h = content.size
    small = content.resize((max(1, round(w * scale)), max(1, round(h * scale))), Image.LANCZOS)
    # 缩放后内容中心的位置
    ncx, ncy = cx * scale, cy * scale
    out = Image.new("RGBA", (canvas * SS, canvas * SS), (0, 0, 0, 0))
    out.paste(small, (round(canvas * SS / 2 - ncx), round(canvas * SS / 2 - ncy)), small)
    return out


def full_bleed(size):
    """满幅版：iOS 用，以及 Android 的 legacy 图标。无 alpha。"""
    content = _fit(_pig(WORK * SS), size, 0.40)
    out = Image.new("RGBA", content.size, FIELD + (255,))
    out.alpha_composite(content)
    return out.resize((size, size), Image.LANCZOS).convert("RGB")


def adaptive_foreground(size):
    """Android 自适应前景：透明底 + 缩进直径 66/108 的保证可见圆。"""
    # ⚠️ 乘 0.985 留一点余量：目标半径取成正好等于安全半径时，缩放和重采样的
    # 舍入会把最远的像素推出去约 0.3%（validate.py 实测 100.3%，判 FAIL）。
    content = _fit(_pig(WORK * SS), size, (66 / 108) / 2 * 0.985)
    return content.resize((size, size), Image.LANCZOS)


def main():
    written = []

    # ── iOS：单张 1024，无 alpha ──
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

    # ── Android：自适应前景 + legacy 满幅 ──
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
