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

/** Relative positions of the three coins, exactly matching `COIN_LAY` in
 * [tools/appicon/generate.py]. */
private val COIN_LAY = listOf(-0.78f to 0.39f, 0.78f to 0.39f, 0f to -0.39f)

/**
 * The full little pig next to the top bar title — a Compose port of the same geometry and
 * colors as the app icon ([tools/appicon/generate.py]), **not a separately redesigned pig**.
 *
 * ⚠️ **This is the second version. The first version only drew the head (ears + snout +
 * eyes), and feedback was "it should show the full body."**
 * Before making the change, a Python generator was used to actually test "how much detail
 * is left once the full pig is shrunk to 24~40px" (screenshot comparison is in the session
 * log): the app icon's built-in safe margin, at this size, squeezes the pig into a small
 * cluster in the middle of the frame with mostly empty space — even harder to make out than
 * a head close-up. **The fix isn't "remove detail," it's dropping the icon's safe margin so
 * the pig itself fills this small canvas** — proportionally equivalent to scaling the whole
 * pig up by about 1/0.60x, with the scale center aligned to the pig's body center rather
 * than the whole icon canvas's center, so the ears, snout, legs, tail, wings, and coins can
 * all still survive at 28~32dp.
 *
 * **The wings were simplified (7 feather layers reduced to 3), the coins were not
 * simplified (still 3, matching the app icon).**
 * ⚠️ This one hit a real pitfall: the first version also simplified the coins down to 2,
 * reasoning that "3 would blur into a blob" — but that judgment was **written from
 * impression, without actually testing it**. Only after being asked "why is the coin count
 * different" was the Python generator used to shrink the real 3-coin layout down to 28~36px
 * and look at it in isolation: **3 coins read just as clearly as 2 at this size**, so the
 * earlier simplification wasn't necessary and instead created a detail that didn't match
 * the app icon. **The wings genuinely were tested and the difference was small** (the
 * layering blurs together at this size regardless), so only the wings kept the
 * simplification, and the coins were reverted to match the icon.
 *
 * The color is a fixed brand pink, not following `MaterialTheme.colorScheme` — same
 * reasoning as the app icon: this is the mascot's own color, not a semantic role.
 *
 * ⚠️ **Shapes drawn on Canvas don't produce accessibility nodes**; purely decorative — the
 * title text "猪满仓" already fully conveys the information.
 */
@Composable
fun PigGlyph(modifier: Modifier = Modifier.size(32.dp)) {
    Canvas(modifier) {
        val n = size.minDimension
        // The pig body's actual share of "the whole app icon canvas" is about 0.60
        // (including the loose whitespace around wings/tail); dividing by it scales the pig
        // up to nearly fill this small canvas, with the scale center fixed at the pig's
        // body center (cx, cy below correspond to generate.py's n*0.50, n*0.52 — both use
        // the same reference frame).
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

            // Tail: a spiral laid out from a ring of gradually thinning dots, matching how
            // generate.py draws it
            val tail = at(-rx * 1.02f, -ry * 0.30f)
            for (i in 0 until 40) {
                val t = i / 39f
                val a = (-40 + t * 430) * PI.toFloat() / 180f
                val rr = vn * 0.050f * (1f - 0.45f * t)
                val p = Offset(tail.x + rr * cos(a), tail.y + rr * sin(a))
                drawCircle(BODY, vn * 0.0145f, p)
            }

            // Two legs (one front, one back); drawn once first as a base layer, then the
            // lower half is re-exposed after the body is drawn
            leg(-0.30f, -0.34f, 0.90f, 1.10f)
            leg(0.28f, 0.25f, 0.88f, 1.08f)

            // Body
            drawOval(BODY, topLeft = at(-rx, -ry), size = Size(rx * 2, ry * 2))

            leg(-0.30f, -0.34f, 0.90f, 1.10f)
            leg(0.28f, 0.25f, 0.88f, 1.08f)

            // Snout + two nostrils
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

            // Ear: a circle + a small upward point (union of circle and triangle), same
            // drawing method as the app icon
            val ear = at(rx * 0.46f, -ry * 0.86f)
            drawEar(ear, vn * EAR_R)

            // Eye + highlight
            val eye = at(rx * 0.52f, -ry * 0.30f)
            val er = vn * EYE_R
            drawCircle(EYE, er, eye)
            drawCircle(Color.White, er * 0.36f, Offset(eye.x - er * 0.34f, eye.y - er * 0.36f))

            // Wings (simplified to 3 feathers — the layering is already indistinguishable
            // at this size, see the note above)
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

            // Coins: three, same layout as the app icon.
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

/** One feather: narrow at the base, full in the middle, rounded at the tip. Ported from
 * generate.py's `_plume`. */
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

/** One coin: outer circle + square hole (filled with the pig-body color so the body shows
 * through the hole) + a deep-gold outline. */
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
