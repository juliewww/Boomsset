package com.boomsset.ui.allocation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.boomsset.domain.TargetAllocation
import com.boomsset.ui.theme.chartColors

private val TRACK_HEIGHT = 10.dp
private val MARKER_WIDTH = 3.dp
/** How far the marker line overhangs the track on each side — if perfectly flush, it would be nearly invisible against the fill color. */
private val MARKER_OVERHANG = 3.dp
/** Width of the light halo stroke around the marker line, per side. */
private val MARKER_HALO = 1.dp

/**
 * The allocation bar: **fill = one ratio, marker line = another ratio.**
 *
 * Replaces the original `LinearProgressIndicator` — it can't draw a second marker, and
 * "where is current vs. where is target" needs to be on the same bar to be comparable
 * (otherwise the target is just a line of text below, and the user has to do the mental math).
 *
 * ## The scale is fixed at 0~100%, not per-row adaptive
 *
 * Cross-row comparability is the whole point of this bar: all five rows share the same ruler,
 * so the "alternatives 71%" bar should be longer than the "protection 10%" bar. An adaptive
 * scale would make them the same length and the bar would degrade into pure decoration. The cost
 * is that small values like a 5% target / 2% current only differ by a dozen-odd dp on the bar,
 * making them hard to distinguish — the precise numbers are in the text on the same card, so this
 * tradeoff is intentional.
 *
 * ## The marker line's meaning **must not be conveyed by graphics alone**
 *
 * Every row also displays "target X%" as text (see `ClassRow`); the marker line is only there
 * to make it scannable. This continues the hard rule from [com.boomsset.ui.theme.ChartColors]:
 * identity and values must not be encoded by color or shape alone. **Don't remove that text when
 * redesigning the layout.**
 *
 * No accessibility semantics are attached (so the progress semantics that `LinearProgressIndicator`
 * provides out of the box are lost here): the four numbers on this row — current share / target /
 * deviation / converted amount — are already readable text, and adding a description to the bar
 * itself would just read the same information twice.
 *
 * @param fillBp Which ratio (in basis points) to fill up to. Null or negative is treated as 0 —
 *   a negative net exposure can't be drawn as a length; the actual value is given as text by the caller.
 * @param markerBp Which ratio (in basis points) the marker line is drawn at. Null means don't draw it
 *   (in the zero-asset preview the fill itself already is the target, so drawing a coincident marker
 *   line would be redundant).
 */
@Composable
fun AllocationBar(
    fillBp: Int?,
    markerBp: Int?,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val trackColor = chartColors.track
    // The halo takes the card's background color so the marker line still reads against the
    // fill color. Use `surface` rather than a fixed white: in dark mode a white halo would stand
    // out more than the marker line itself.
    val haloColor = MaterialTheme.colorScheme.surface
    val markerColor = MaterialTheme.colorScheme.onSurface

    val fillFraction = ((fillBp ?: 0).coerceAtLeast(0).toFloat() / TargetAllocation.TOTAL_BP)
        .coerceIn(0f, 1f)
    val markerFraction = markerBp
        ?.let { (it.toFloat() / TargetAllocation.TOTAL_BP).coerceIn(0f, 1f) }

    Canvas(
        modifier
            .fillMaxWidth()
            .height(TRACK_HEIGHT + MARKER_OVERHANG * 2),
    ) {
        val trackH = TRACK_HEIGHT.toPx()
        val top = (size.height - trackH) / 2f
        val radius = CornerRadius(trackH / 2f)

        drawRoundRect(
            color = trackColor,
            topLeft = Offset(0f, top),
            size = Size(size.width, trackH),
            cornerRadius = radius,
        )

        if (fillFraction > 0f) {
            drawRoundRect(
                color = color,
                topLeft = Offset(0f, top),
                size = Size(size.width * fillFraction, trackH),
                cornerRadius = radius,
            )
        }

        markerFraction?.let { fraction ->
            val markerW = MARKER_WIDTH.toPx()
            val haloW = markerW + MARKER_HALO.toPx() * 2
            // Clamp both ends so that at a target of 0% / 100% the marker line still stays
            // entirely within the bar instead of being clipped in half
            val centerX = (size.width * fraction).coerceIn(haloW / 2f, size.width - haloW / 2f)
            val markerTop = top - MARKER_OVERHANG.toPx()
            val markerH = trackH + MARKER_OVERHANG.toPx() * 2

            drawRoundRect(
                color = haloColor,
                topLeft = Offset(centerX - haloW / 2f, markerTop),
                size = Size(haloW, markerH),
                cornerRadius = CornerRadius(haloW / 2f),
            )
            drawRoundRect(
                color = markerColor,
                topLeft = Offset(centerX - markerW / 2f, markerTop),
                size = Size(markerW, markerH),
                cornerRadius = CornerRadius(markerW / 2f),
            )
        }
    }
}
