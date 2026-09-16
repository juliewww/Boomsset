package com.boomsset.ui.allocation

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.boomsset.domain.AssetClass
import com.boomsset.domain.Money
import com.boomsset.ui.chartAnimationSpec
import com.boomsset.ui.formatWithCurrency
import com.boomsset.ui.label
import com.boomsset.ui.theme.chartColors
import com.patrykandpatrick.vico.compose.common.Fill
import com.patrykandpatrick.vico.compose.pie.PieChart
import com.patrykandpatrick.vico.compose.pie.PieChartHost
import com.patrykandpatrick.vico.compose.pie.PieSize
import com.patrykandpatrick.vico.compose.pie.data.PieChartModelProducer
import com.patrykandpatrick.vico.compose.pie.data.pieSeries
import com.patrykandpatrick.vico.compose.pie.rememberPieChart
import kotlin.math.atan2
import kotlin.math.min
import kotlin.math.sqrt

private const val START_ANGLE_DEG = -90f
private val INNER_RADIUS = 64.dp

/**
 * The donut chart for asset allocation shares, with **tappable** slices.
 *
 * This is a **complement** to the [ClassRow] progress bars, not a replacement — the bars answer
 * "how far off target is each class", while this chart answers "how is everything split right
 * now", which reads as a proportion more directly at a glance. So it's drawn on the same screen,
 * reusing the same five fixed-order class colors from [chartColors] rather than defining a new
 * palette.
 *
 * By default the center of the ring shows total net worth; **tapping a slice switches it to show
 * that class's name and amount** (real-device feedback: color and angle alone don't let you guess
 * the actual amount), and tapping again collapses it back.
 *
 * Vico's `PieChart` has no built-in tap callback, so hit testing is hand-written: get the [Box]'s
 * actual pixel size ([androidx.compose.ui.layout.onSizeChanged]), convert the tap coordinate into
 * an angle and radius relative to the center, then use the angle ranges accumulated from [values]
 * to determine which slice it falls in. The angle conversion uses the canvas convention shared by
 * Android/Vico: `atan2(dy, dx)` in screen coordinates (y pointing down) is directly the angle
 * "measured clockwise from the 3 o'clock direction", no extra sign flip needed — this convention
 * is the same one used by [rememberPieChart]'s `startAngle` parameter, which is why we explicitly
 * pass `startAngle = -90f` here (start drawing from the 12 o'clock direction) instead of relying
 * on the library's internal default, so the angle baseline used for hit testing is guaranteed to
 * match the actual rendering exactly.
 *
 * @param shares Each class's **net exposure**, passed in [AssetClass.displayOrder] order; the
 *   order must match [chartColors]'s order ([PieChart.SliceProvider.series] pairs colors by index,
 *   so passing the wrong order won't raise an error — it will just silently **mismatch colors and
 *   classes**, the kind of bug compile time can't catch).
 * @param netWorth The total amount shown by default at the center of the ring.
 */
@Composable
fun AllocationDonut(
    shares: List<Pair<AssetClass, Money>>,
    netWorth: Money,
    baseCurrency: String,
    modifier: Modifier = Modifier,
) {
    val modelProducer = remember { PieChartModelProducer() }

    // Pie entries require non-negative values — a negative net exposure (liabilities in that
    // class exceed assets) can't be drawn as a slice; same reasoning as ClassRow's progress bar:
    // the actual value is already shown as text, this is only responsible for the share shape.
    val values = shares.map { (_, money) -> money.minorUnits.coerceAtLeast(0L).toFloat() }
    val total = values.sum()

    LaunchedEffect(values) {
        if (total == 0f) return@LaunchedEffect
        modelProducer.runTransaction {
            pieSeries { series(values) }
        }
    }

    // `chartColors` is a @Composable property (reads a CompositionLocal), so it can't be called
    // inside a remember{} lambda — that lambda is annotated @DisallowComposableCalls. So we
    // extract it as a plain value in composition scope first, then build the slice list from it.
    val colors = chartColors
    val slices = shares.map { (assetClass, _) -> PieChart.Slice(fill = Fill(colors.of(assetClass))) }
    val pieChart = rememberPieChart(
        sliceProvider = PieChart.SliceProvider.series(slices),
        innerSize = PieSize.Inner.fixed(INNER_RADIUS),
        startAngle = START_ANGLE_DEG,
    )

    var selected by remember(shares) { mutableStateOf<AssetClass?>(null) }
    var boxSizePx by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current

    Box(
        modifier
            .fillMaxWidth()
            .height(180.dp)
            .onSizeChanged { boxSizePx = it }
            .pointerInput(shares, total) {
                if (total == 0f) return@pointerInput
                detectTapGestures { offset ->
                    val hit = hitTestSlice(offset, boxSizePx, density, values, total)
                    selected = if (hit != null) shares.getOrNull(hit)?.first else null
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        PieChartHost(
            chart = pieChart,
            modelProducer = modelProducer,
            // Vico's pie chart defaults to 1000ms (see [chartAnimationSpec]); switched to the M3-recommended 300ms.
            animationSpec = chartAnimationSpec,
            modifier = Modifier.fillMaxWidth().height(180.dp),
        )
        val selectedShare = selected?.let { assetClass -> shares.firstOrNull { it.first == assetClass } }
        if (selectedShare != null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(selectedShare.first.label(), style = MaterialTheme.typography.labelMedium)
                Text(
                    selectedShare.second.formatWithCurrency(baseCurrency),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        } else {
            Text(
                netWorth.formatWithCurrency(baseCurrency),
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

/** Returns the index of the hit slice in [values]; hitting the hole, outside the circle, or a gap between slices all count as no hit. */
private fun hitTestSlice(
    offset: Offset,
    boxSize: IntSize,
    density: Density,
    values: List<Float>,
    total: Float,
): Int? {
    if (boxSize.width == 0 || boxSize.height == 0) return null
    val cx = boxSize.width / 2f
    val cy = boxSize.height / 2f
    val dx = offset.x - cx
    val dy = offset.y - cy
    val dist = sqrt(dx * dx + dy * dy)

    val outerRadius = min(boxSize.width, boxSize.height) / 2f
    val innerRadius = with(density) { INNER_RADIUS.toPx() }
    if (dist < innerRadius || dist > outerRadius) return null

    val rawDeg = atan2(dy, dx) * 180f / kotlin.math.PI.toFloat()
    var relative = (rawDeg - START_ANGLE_DEG) % 360f
    if (relative < 0f) relative += 360f

    var cumulative = 0f
    values.forEachIndexed { index, value ->
        val sweep = value / total * 360f
        if (relative < cumulative + sweep) return index
        cumulative += sweep
    }
    return null
}
