package com.boomsset.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private val FIELD = Color(0xFFFFEFF4)
private val BODY = Color(0xFFF6A0C3)
private val SNOUT = Color(0xFFD66799)
private val WINGC = Color(0xFFFFE0EB)
private val WING_SHADE = Color(0xFFFFD0E0)
private val HOOF = Color(0xFFC94385)
private val EYE = Color(0xFF3F0F27)
private val GOLD = Color(0xFFEEBC4A)
private val GEDGE = Color(0xFFB07E10)

private const val TILT = -14f
private const val BODY_RX = 0.245f
private const val BODY_RY = 0.186f
private const val LEG_W = 0.036f
private const val HOOF_R = 0.020f
private const val EAR_R = 0.048f
private const val EYE_R = 0.027f
private const val SNOUT_R = 0.068f
private const val WING_L = 0.235f
private const val WING_W = 0.062f
private const val WING_ANG = -74f
private const val COIN_R = 0.048f
private const val COIN_HOLE = 0.34f

/** 三枚钱币的相对位置，和 [tools/appicon/generate.py] 的 `COIN_LAY` 完全一致。 */
private val COIN_LAY = listOf(-0.78f to 0.39f, 0.78f to 0.39f, 0f to -0.39f)

/**
 * 顶栏标题旁边的完整小猪 —— app icon（[tools/appicon/generate.py]）同一套几何和配色的
 * Compose 移植版，**不是重新设计的另一只猪**。
 *
 * ⚠️ **这是第二版。第一版只画了头（耳朵+鼻子+眼睛），被反馈"应该完整显示"。**
 * 改之前先用 Python 生成器实测过"完整猪缩到 24~40px 到底还剩多少细节"（截图对比见
 * 会话记录）：app icon 自带的安全边距在这个尺寸下会把猪挤成画面中间一小团、大部分
 * 留白，比头部特写还难认。**解法不是"删细节"，是去掉图标的安全边距、让猪本身
 * 填满这个小画布**——比例上相当于把猪整体放大到约 1/0.60 倍，缩放中心对齐猪的
 * 身体中心而不是整张图标画布的中心，这样耳朵、鼻子、腿、尾巴、翅膀、钱币才能
 * 在 28~32dp 下同时留下来。
 *
 * **翅膀简化了（7 层羽毛减到 3 片），钱币没有简化（还是 3 枚，和 app icon 一致）。**
 * ⚠️ 这条踩过一次坑：第一版把钱币也简化成了 2 枚，理由是"3 枚会糊成一团"——
 * 但那个判断是**凭印象写的，没有实测**。被问到"为什么钱币数量不一样"之后
 * 才拿 Python 生成器把真实的 3 枚布局缩到 28~36px 单独看了一遍：**3 枚在这个
 * 尺寸下和 2 枚一样看得清**，之前的简化没有必要，反而制造了一处和 app icon
 * 对不上的细节。**翅膀确实测过差别很小**（分层在这个尺寸下本来就模糊成一片），
 * 所以只有翅膀保留简化，钱币改回和图标一致。
 *
 * 颜色是固定的品牌粉，不跟 `MaterialTheme.colorScheme` 走——和 app icon 同理，
 * 这是吉祥物本身的颜色，不是某个语义角色。
 *
 * ⚠️ **Canvas 画的图形不产生无障碍节点**，纯装饰性——标题文字"猪满仓"已经完整
 * 表达了信息。
 */
@Composable
fun PigGlyph(modifier: Modifier = Modifier.size(32.dp)) {
    Canvas(modifier) {
        val n = size.minDimension
        // 猪本体相对"整张 app icon 画布"的实际占比约 0.60（含翅膀/尾巴的松散留白），
        // 除以它等于把猪放大到几乎填满这个小画布，缩放中心固定在猪的身体中心
        // （下面 cx,cy 对应 generate.py 里的 n*0.50, n*0.52，两者用的是同一个参照系）。
        val vn = n / 0.60f
        val cx = size.width / 2f
        val cy = size.height / 2f

        rotate(degrees = TILT, pivot = Offset(cx, cy)) {
            val rx = vn * BODY_RX
            val ry = vn * BODY_RY

            fun at(dx: Float, dy: Float) = Offset(cx + dx, cy + dy)

            fun leg(ax: Float, bx: Float, ay0: Float, by1: Float) {
                val p0 = at(rx * ax, ry * ay0)
                val p1 = at(rx * bx, ry * by1)
                val lw = vn * LEG_W
                drawLine(BODY, p0, p1, strokeWidth = lw, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                val hr = vn * HOOF_R
                drawCircle(HOOF, hr, p1)
            }

            // 尾巴：一圈渐细的小圆点铺出螺旋，和 generate.py 的画法一致
            val tail = at(-rx * 1.02f, -ry * 0.30f)
            for (i in 0 until 40) {
                val t = i / 39f
                val a = (-40 + t * 430) * PI.toFloat() / 180f
                val rr = vn * 0.050f * (1f - 0.45f * t)
                val p = Offset(tail.x + rr * cos(a), tail.y + rr * sin(a))
                drawCircle(BODY, vn * 0.0145f, p)
            }

            // 两条腿（前后各一），先画一次垫底，身体画完再露出下半截
            leg(-0.30f, -0.34f, 0.90f, 1.10f)
            leg(0.28f, 0.25f, 0.88f, 1.08f)

            // 身体
            drawOval(BODY, topLeft = at(-rx, -ry), size = Size(rx * 2, ry * 2))

            leg(-0.30f, -0.34f, 0.90f, 1.10f)
            leg(0.28f, 0.25f, 0.88f, 1.08f)

            // 鼻子 + 两个鼻孔
            val snout = at(rx * 0.92f, -ry * 0.08f)
            val sr = vn * SNOUT_R
            drawOval(
                SNOUT,
                topLeft = Offset(snout.x - sr * 0.85f, snout.y - sr * 0.90f),
                size = Size(sr * 1.90f, sr * 1.60f),
            )
            val nr = vn * 0.013f
            drawCircle(EYE.copy(alpha = 0.55f), nr, Offset(snout.x + vn * 0.010f, snout.y - vn * 0.023f))
            drawCircle(EYE.copy(alpha = 0.55f), nr, Offset(snout.x + vn * 0.010f, snout.y + vn * 0.023f))

            // 耳朵：圆 + 一个朝上的小尖尖（圆和三角求并），和 app icon 同一个画法
            val ear = at(rx * 0.46f, -ry * 0.86f)
            drawEar(ear, vn * EAR_R)

            // 眼睛 + 高光
            val eye = at(rx * 0.52f, -ry * 0.30f)
            val er = vn * EYE_R
            drawCircle(EYE, er, eye)
            drawCircle(Color.White, er * 0.36f, Offset(eye.x - er * 0.34f, eye.y - er * 0.36f))

            // 翅膀（简化到 3 片羽毛——分层在这个尺寸下已经看不出来，见上面的说明）
            val wing = at(-rx * 0.30f, -ry * 0.62f)
            val wl = vn * WING_L
            val ww = vn * WING_W
            for ((da, l, shade) in listOf(
                Triple(-24f, 0.78f, false),
                Triple(4f, 1.0f, false),
                Triple(22f, 0.72f, true),
            )) {
                drawPlume(wing, WING_ANG + da, wl * l + wl * 0.06f, ww * 1.30f, WINGC)
                drawPlume(wing, WING_ANG + da, wl * l, ww, if (shade) WING_SHADE else WINGC.copy(alpha = 0.9f))
            }

            // 钱币：三枚，和 app icon 同一个布局。
            val coinC = at(-rx * 0.12f, ry * 0.02f)
            val cr = vn * COIN_R
            for ((ddx, ddy) in COIN_LAY) {
                drawCoin(Offset(coinC.x + ddx * cr, coinC.y + ddy * cr), cr)
            }
        }
    }
}

private fun DrawScope.drawEar(center: Offset, r: Float) {
    val a = -PI.toFloat() / 2f
    val tip = 0.55f
    val ax = center.x + r * (1 + tip) * cos(a)
    val ay = center.y + r * (1 + tip) * sin(a)
    val spread = 62f * PI.toFloat() / 180f
    val b1 = Offset(center.x + r * cos(a - spread), center.y + r * sin(a - spread))
    val b2 = Offset(center.x + r * cos(a + spread), center.y + r * sin(a + spread))
    val path = Path().apply {
        moveTo(ax, ay)
        lineTo(b1.x, b1.y)
        lineTo(b2.x, b2.y)
        close()
    }
    drawPath(path, BODY)
    drawCircle(BODY, r, center)
    val ri = r * 0.50f
    drawCircle(SNOUT, ri, Offset(center.x + r * 0.10f * cos(a), center.y + r * 0.10f * sin(a)))
}

/** 一根羽毛：根部窄、中段饱满、尖端圆。移植自 generate.py 的 `_plume`。 */
private fun DrawScope.drawPlume(origin: Offset, angDeg: Float, l: Float, w: Float, color: Color) {
    val a = angDeg * PI.toFloat() / 180f
    val ca = cos(a)
    val sa = sin(a)
    fun t(x: Float, y: Float) = Offset(origin.x + x * ca - y * sa, origin.y + x * sa + y * ca)

    val p0 = t(0f, 0f)
    val c1 = t(l * 0.42f, -w)
    val e1 = t(l, -w * 0.16f)
    val tipMid = t(l + w * 0.22f, 0f)
    val e2 = t(l, w * 0.16f)
    val c2 = t(l * 0.46f, w * 0.72f)
    val path = Path().apply {
        moveTo(p0.x, p0.y)
        quadraticTo(c1.x, c1.y, e1.x, e1.y)
        lineTo(tipMid.x, tipMid.y)
        lineTo(e2.x, e2.y)
        quadraticTo(c2.x, c2.y, p0.x, p0.y)
        close()
    }
    drawPath(path, color)
}

/** 一枚铜钱：外圆 + 方孔（填猪身色，让身体从孔里透出来）+ 深金描边。 */
private fun DrawScope.drawCoin(center: Offset, r: Float) {
    drawCircle(GEDGE, r * 1.12f, center)
    drawCircle(GOLD, r, center)
    val h = r * COIN_HOLE
    val path = Path().apply {
        moveTo(center.x - h, center.y - h)
        lineTo(center.x + h, center.y - h)
        lineTo(center.x + h, center.y + h)
        lineTo(center.x - h, center.y + h)
        close()
    }
    drawPath(path, BODY)
}
