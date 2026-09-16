package com.boomsset.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * Bottom nav bar icons for the three tabs. Hand-drawn on [Canvas] rather than pulling in
 * `material-icons-extended` — same call already made for the amount-visibility eye icon
 * (see [AmountVisibilityToggle]): one glyph isn't worth a whole extra dependency, and a
 * Canvas glyph tracks [LocalContentColor] automatically so selected/unselected tinting
 * (driven by `NavigationBarItemDefaults.colors()`) needs no extra plumbing here.
 */
private val ICON_SIZE = 24.dp
private const val STROKE_WIDTH = 0.09f

@Composable
fun NetWorthTabIcon(modifier: Modifier = Modifier.size(ICON_SIZE)) {
    val color = LocalContentColor.current
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = h * STROKE_WIDTH, cap = StrokeCap.Round)
        // Small ascending sparkline — this tab is the net-worth trend view.
        val points = listOf(
            Offset(w * 0.14f, h * 0.68f),
            Offset(w * 0.38f, h * 0.46f),
            Offset(w * 0.58f, h * 0.58f),
            Offset(w * 0.86f, h * 0.20f),
        )
        val path = Path().apply {
            moveTo(points[0].x, points[0].y)
            for (i in 1 until points.size) lineTo(points[i].x, points[i].y)
        }
        drawPath(path, color, style = stroke)
        drawCircle(color, radius = h * 0.045f, center = points.last())
    }
}

@Composable
fun AllocationTabIcon(modifier: Modifier = Modifier.size(ICON_SIZE)) {
    val color = LocalContentColor.current
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val cx = w / 2f
        val cy = h / 2f
        val r = minOf(w, h) * 0.38f
        val ringStroke = Stroke(width = h * STROKE_WIDTH)
        drawCircle(color, radius = r, center = Offset(cx, cy), style = ringStroke)
        // One radius line to read as a pie/allocation wedge rather than a plain ring.
        drawLine(
            color = color,
            start = Offset(cx, cy),
            end = Offset(cx, cy - r),
            strokeWidth = h * STROKE_WIDTH,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
fun AssetListTabIcon(modifier: Modifier = Modifier.size(ICON_SIZE)) {
    val color = LocalContentColor.current
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val strokeWidth = h * STROKE_WIDTH
        val rowYs = listOf(h * 0.28f, h * 0.50f, h * 0.72f)
        val lineLengths = listOf(0.62f, 0.50f, 0.40f)
        rowYs.forEachIndexed { index, y ->
            drawCircle(color, radius = strokeWidth * 0.55f, center = Offset(w * 0.16f, y))
            drawLine(
                color = color,
                start = Offset(w * 0.30f, y),
                end = Offset(w * (0.30f + lineLengths[index]), y),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round,
            )
        }
    }
}
