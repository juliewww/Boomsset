package com.boomsset.ui.networth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.boomsset.domain.AllocationSeries
import com.boomsset.domain.AssetClass
import com.boomsset.domain.Money
import com.boomsset.domain.NetWorthSeries
import com.boomsset.domain.Period
import com.boomsset.domain.PortfolioCalculator
import com.boomsset.ui.chartAnimationSpec
import com.boomsset.ui.fallColor
import com.boomsset.ui.riseColor
import com.boomsset.ui.theme.chartColors
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.Zoom
import com.patrykandpatrick.vico.compose.cartesian.axis.Axis
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberAxisLabelComponent
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.compose.cartesian.data.columnModel
import com.patrykandpatrick.vico.compose.cartesian.data.lineModel
import com.patrykandpatrick.vico.compose.cartesian.layer.ColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoZoomState
import com.patrykandpatrick.vico.compose.common.Fill
import com.patrykandpatrick.vico.compose.common.component.rememberLineComponent
import com.patrykandpatrick.vico.compose.common.data.ExtraStore
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlinx.datetime.LocalDate

/** Which basis the chart is viewed in. */
enum class ChartMode {
    /** One column = total net worth. */
    TOTAL,

    /** One column stacked by the five classes; still only one column per period. */
    ALLOCATION,
}

/** What shape the chart is drawn as. */
enum class ChartStyle {
    /** Column chart: one column per period, with the growth rate relative to the previous column labeled above it. */
    COLUMN,

    /** Trend chart: area/line, focused on shape rather than period-over-period swings. */
    TREND,
}

private val CHART_HEIGHT = 220.dp

/**
 * The net worth page's chart. Four combinations: total assets/by-class × column chart/trend chart.
 *
 * ⚠️ **Each combination is wrapped in `key(mode, style)`, so the whole host leaves composition
 * on switch.** This isn't a style preference, it's one of Vico's hard constraints:
 * `CartesianChartModelProducer.collectAsState` contains
 * `check(previousHashCode == null || hashCode == previousHashCode)`, so swapping in a different
 * producer for the same host throws immediately. And the column chart and trend chart need
 * different model types (`columnModel` / `lineModel`); stuffing both into one producer doesn't
 * work either — the chart's y-axis range is computed from **all** of `model.models`, so the
 * model that isn't in use would still stretch the range. `key()` gives each combination its own
 * clean producer, isolated from the others.
 *
 * @param visibleClasses Which classes to draw when viewing by class; **must be sorted by
 *   [AssetClass.displayOrder]** — both stacking order and color order depend on it, and an
 *   out-of-order list would make the same class swap position between the column chart and the
 *   trend chart.
 * @param hideAmounts True when the eye icon is hiding amounts — **the entire y-axis tick label
 *   column is skipped**. The shape of the columns/lines and the growth-rate labels above them
 *   are both kept: those are relative quantities, and hiding them too would turn this page into
 *   a blank picture.
 */
@Composable
fun NetWorthChart(
    series: NetWorthSeries,
    allocationSeries: AllocationSeries,
    mode: ChartMode,
    style: ChartStyle,
    visibleClasses: List<AssetClass>,
    hideAmounts: Boolean = false,
    modifier: Modifier = Modifier,
) {
    key(mode, style) {
        when {
            style == ChartStyle.COLUMN && mode == ChartMode.TOTAL ->
                TotalColumnChart(series, hideAmounts, modifier)

            style == ChartStyle.COLUMN ->
                AllocationColumnChart(allocationSeries, visibleClasses, hideAmounts, modifier)

            mode == ChartMode.TOTAL -> TotalTrendChart(series, hideAmounts, modifier)

            else -> AllocationTrendChart(allocationSeries, visibleClasses, hideAmounts, modifier)
        }
    }
}

/**
 * A trend chart needs at least two points to draw a line segment — with only one point Vico
 * draws nothing at all, leaving the user looking at an empty chart with just axes (this is
 * exactly why the net worth chart was originally changed from a plain line chart to a column
 * chart, see AGENTS.md lesson 10). The trend chart is now something the user opts into, so when
 * there aren't enough points it must **say so explicitly**, rather than showing an empty chart
 * that looks broken.
 */
fun canDrawTrend(pointCount: Int): Boolean = pointCount >= 2

// ---------------------------------------------------------------- Column chart

/**
 * The total-assets column chart.
 *
 * With only one point, Vico blows that column up to fill the entire x-axis, and `thickness` has
 * no effect whatsoever — verified on a real device: changing thickness from 10dp to 1dp produced
 * no visible change in column width at all. The reason is that `CartesianChartHost` by default
 * scales content to fill the viewport, and with only 1 x position the scale factor becomes huge.
 *
 * Changing the x-axis range (extending minX to the left) doesn't fix this: when Vico measures
 * the axis label widths, it also calls `valueFormatter` once for the extended "virtual" x
 * positions, and since those positions have no corresponding date, the formatter can only return
 * an empty string — which Vico explicitly disallows (`IllegalStateException`, whose message says
 * to use ItemPlacer instead).
 *
 * Uses **phantom series** instead: `MergeMode.Grouped` is borrowed to split the width at that one
 * x position into 5 slices, with the real data occupying the middle one and the other 4 being
 * placeholder series that are all zero (zero height = invisible). ⚠️ Grouped lays out sub-columns
 * left to right in the order `series()` was called, so the placeholder series must be **split
 * two on each side**, with the real series sandwiched in the exact middle — otherwise the column
 * would sit flush against the leftmost edge of that x position, while the axis label is drawn at
 * the center of the whole position, which looks like "the column doesn't match its date" (this
 * was actually seen in real-device feedback once).
 */
@Composable
private fun TotalColumnChart(series: NetWorthSeries, hideAmounts: Boolean, modifier: Modifier) {
    val modelProducer = remember { CartesianChartModelProducer() }
    val values = remember(series) { series.points.map { it.netWorth.toYuan() } }
    val labels = remember(series) { axisLabels(series.period, series.dates) }
    val growth = remember(series) { growthLabels(series.points.map { it.netWorth }) }

    LaunchedEffect(series) {
        if (values.isEmpty()) return@LaunchedEffect
        modelProducer.runTransaction {
            // Labels must land in **the same transaction** as this batch of data points — see [AxisLabelsKey]
            extras {
                it[AxisLabelsKey] = labels
                it[GrowthLabelsKey] = growth
            }
            columnModel {
                if (values.size == 1) {
                    repeat(PHANTOM_COLUMNS_PER_SIDE) { series(listOf(0.0)) }
                    series(values)
                    repeat(PHANTOM_COLUMNS_PER_SIDE) { series(listOf(0.0)) }
                } else {
                    series(values)
                }
            }
        }
    }

    // The column uses `colorScheme.primary` directly.
    // ⚠️ This choice is **tightly coupled to primary's lightness**: in the cream-yellow version,
    // primary only had a 1.78:1 contrast against the page background, which forced the column to
    // be split out into its own separate dark color (`ChartColors.brand`).
    // Now it's mid rose, at **4.34:1** against the page background, so the column can safely use
    // the single brand color.
    // (The bright rose used briefly in between was only 3.00:1 — right at the line — which was
    // also part of why it was switched to mid rose.)
    // **Whenever the brand color changes, this must be re-measured — don't assume the previous
    // version's conclusion still holds.**
    val brand = MaterialTheme.colorScheme.primary
    // **Solid color, no alpha.** This used to be `alpha = 0.5f` — a leftover from the version
    // where columns and a line were drawn overlapping (transparency was needed so the line showed
    // through). Once the line was removed, the alpha's only remaining effect was "make the column
    // fainter": measured at 0.5 alpha the column only had **1.95:1** contrast against the page
    // background (with the old purple), while the column is this page's primary data marker.
    // (This alpha had always been too low — 2.08:1 with the old `#BD4D03`, and 1.77:1 with the
    // Morandi `#918163` — it just had never been measured before.) The trend chart's area fill
    // has likewise been changed to opaque, see TotalTrendChart.
    val column = rememberLineComponent(
        fill = Fill(brand),
        thickness = 10.dp,
        shape = RoundedCornerShape(2.dp),
    )
    // It's fine for the phantom series to reuse the same LineComponent — their values are 0, so
    // they render at zero height and are invisible regardless of color.
    val columnCount = if (values.size == 1) PHANTOM_COLUMNS_PER_SIDE * 2 + 1 else 1
    val columnProvider = remember(columnCount, column) {
        ColumnCartesianLayer.ColumnProvider.series(List(columnCount) { column })
    }

    CartesianChartHost(
        chart = rememberCartesianChart(
            rememberColumnCartesianLayer(
                columnProvider = columnProvider,
                mergeMode = { ColumnCartesianLayer.MergeMode.Grouped(columnSpacing = 0.dp) },
            ),
            startAxis = rememberAmountAxis(hideAmounts),
            topAxis = rememberGrowthAxis(),
            bottomAxis = rememberPeriodAxis(),
        ),
        modelProducer = modelProducer,
        // Uses Zoom.Content alone (not clamped by Zoom.x): with 1 point this makes that single
        // x position fill the viewport exactly, with the real column occupying only 1/5 of it —
        // that's exactly where the phantom-series effect comes from.
        zoomState = rememberFittingZoomState(remember { Zoom.Content }),
        // Vico defaults to 500ms (see [chartAnimationSpec]); switched to the M3-recommended 300ms.
        animationSpec = chartAnimationSpec,
        modifier = modifier.fillMaxWidth().height(CHART_HEIGHT),
    )
}

/**
 * Column charts always use "fill content, no horizontal scrolling".
 *
 * Vico's default `initialZoom` is `Zoom.max(Zoom.fixed(), Zoom.Content)` — when columns laid out
 * at 1:1 would be wider than the viewport, it picks 1:1, making the chart horizontally
 * scrollable, and **it defaults to sitting at the far left**: the most recent period is off
 * screen, with no scroll hint at all. On a real device this is exactly what happened with 12
 * stacked columns — the September one got cut off. The net worth chart has at most 12 points, and
 * fitting them all on one screen is always better than hiding the most recent period.
 *
 * `minZoom` must be supplied alongside it: it defaults to `Zoom.Content`, and since Vico takes
 * `max(minZoom, initialZoom)`, changing `initialZoom` alone would just get pulled back.
 */
@Composable
private fun rememberFittingZoomState(zoom: Zoom) =
    rememberVicoZoomState(zoomEnabled = false, initialZoom = zoom, minZoom = zoom)

/**
 * The by-class stacked column chart — still one column per period, subdivided by the five classes.
 *
 * Each segment's basis is **net exposure** (that class's assets minus the liabilities
 * attributed to it), exactly consistent with the allocation page. A negative exposure gets drawn
 * by Vico **below** the zero line (natively supported by `MergeMode.Stacked`), meaning "one
 * column" can turn into an upper and a lower part in that case — this reflects reality and is
 * not hidden; there's an explanatory line elsewhere on the page.
 *
 * ⚠️ The single-point-fills-the-whole-width problem **cannot** be solved with phantom series
 * here: Stacked doesn't split the width at a single x position (that's what Grouped does), so
 * adding any number of zero-value series wouldn't narrow the column. Instead, `Zoom.x(n)` is used
 * to make the viewport hold at least n x-units, so the column naturally occupies only 1/n of it.
 * `minZoom` defaults to `Zoom.Content`, and **must be changed together with it**, otherwise Vico
 * takes whichever is larger and blows the column back up.
 */
@Composable
private fun AllocationColumnChart(
    series: AllocationSeries,
    classes: List<AssetClass>,
    hideAmounts: Boolean,
    modifier: Modifier,
) {
    val modelProducer = remember { CartesianChartModelProducer() }
    val labels = remember(series) { axisLabels(series.period, series.dates) }
    // Growth rate is computed from **the total of only the visible classes** — once a few
    // classes are unchecked, the column gets shorter, and a percentage computed from the
    // full total wouldn't match the column in front of you anymore.
    val growth = remember(series, classes) { growthLabels(series.totals(classes)) }
    val values = remember(series, classes) {
        classes.map { assetClass -> series.netExposures(assetClass).map { it.toYuan() } }
    }

    LaunchedEffect(values) {
        if (values.isEmpty() || values.first().isEmpty()) return@LaunchedEffect
        modelProducer.runTransaction {
            extras {
                it[AxisLabelsKey] = labels
                it[GrowthLabelsKey] = growth
            }
            columnModel { values.forEach { series(it) } }
        }
    }

    val palette = chartColors
    val columns = classes.map { assetClass ->
        rememberLineComponent(
            fill = Fill(palette.of(assetClass)),
            thickness = 16.dp,
            shape = RoundedCornerShape(2.dp),
        )
    }
    val columnProvider = remember(columns) {
        ColumnCartesianLayer.ColumnProvider.series(columns)
    }

    // One expression handles both ends: with many points, Content is smaller (shrinks to fit
    // exactly); with only 1 point, Content would stretch that column to fill the whole screen,
    // in which case Zoom.x is smaller — taking the min gets exactly the one that's wanted.
    val fit = remember { Zoom.min(Zoom.Content, Zoom.x(SINGLE_POINT_SLOTS)) }

    CartesianChartHost(
        chart = rememberCartesianChart(
            rememberColumnCartesianLayer(
                columnProvider = columnProvider,
                mergeMode = { ColumnCartesianLayer.MergeMode.Stacked },
            ),
            startAxis = rememberAmountAxis(hideAmounts),
            topAxis = rememberGrowthAxis(),
            bottomAxis = rememberPeriodAxis(),
        ),
        modelProducer = modelProducer,
        zoomState = rememberFittingZoomState(fit),
        animationSpec = chartAnimationSpec,
        modifier = modifier.fillMaxWidth().height(CHART_HEIGHT),
    )
}

// ---------------------------------------------------------------- Trend chart

/** Total-assets trend: a line + a faint fill below it. A single series uses the brand color rather than any class's color. */
@Composable
private fun TotalTrendChart(series: NetWorthSeries, hideAmounts: Boolean, modifier: Modifier) {
    val modelProducer = remember { CartesianChartModelProducer() }
    val values = remember(series) { series.points.map { it.netWorth.toYuan() } }

    LaunchedEffect(series) {
        if (values.isEmpty()) return@LaunchedEffect
        modelProducer.runTransaction { lineModel { series(values) } }
    }

    val brand = MaterialTheme.colorScheme.primary
    // Area fill: opaque. This was gotten wrong twice — first `brand.copy(alpha = 0.16f)`
    // (16% is nearly no fill at all), then after switching to opaque, `primaryContainer` was
    // picked, which is the hero card's background, the lightest tone in the whole palette, and
    // real-device feedback still called it "not solid". Now `chartColors.trendArea` is used,
    // whose value **tracks primary's lightness** — it has to be re-derived every time the brand
    // color changes; the rationale and the history of the three attempts are documented on
    // ChartColors's trendArea.
    val area = chartColors.trendArea
    TrendChartFrame(series.dates, modifier) {
        CartesianChartHost(
            chart = rememberCartesianChart(
                rememberLineCartesianLayer(
                    lineProvider = LineCartesianLayer.LineProvider.series(
                        LineCartesianLayer.rememberLine(
                            fill = LineCartesianLayer.LineFill.single(Fill(brand)),
                            areaFill = LineCartesianLayer.AreaFill.single(Fill(area)),
                        ),
                    ),
                ),
                startAxis = rememberAmountAxis(hideAmounts),
            ),
            modelProducer = modelProducer,
            animationSpec = chartAnimationSpec,
            modifier = Modifier.fillMaxWidth().height(CHART_HEIGHT),
        )
    }
}

/**
 * The trend chart + the first/last dates below it.
 *
 * A trend chart is about shape, so the intermediate tick marks don't help judge the trend and
 * just crowd things — but "which day to which day does this curve span" absolutely must be
 * stated, otherwise an upward-sloping curve could be three months or could be three years.
 *
 * ⚠️ **These two dates are drawn manually, not via Vico's bottom axis.** This was tried and
 * couldn't be made to work: `AlignedHorizontalAxisItemPlacer.getLabelValues` skips any value that
 * falls exactly on the x-range's endpoint (it breaks the moment
 * `potentialValue == fullXRange.endInclusive`), while the line layer leaves no padding at either
 * end and the last point is exactly that endpoint, so **the trailing date is never drawn** (on a
 * real device with 12 points, only the starting date showed, and it didn't even get its own
 * vertical gridline). Per the documentation, adding padding via `layerPadding` should make room
 * around the endpoint, but tested on a real device at three settings — 1dp / 32dp / 150dp — the
 * result was **pixel-identical each time**; that parameter simply has no effect in this
 * combination. Two strings of explanatory text aren't worth further fighting with the layout
 * engine — drawing them manually just works, every time.
 */
@Composable
private fun TrendChartFrame(
    dates: List<LocalDate>,
    modifier: Modifier,
    chart: @Composable () -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        chart()
        val labels = dateLabels(dates)
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            // An empty list can't be drawn here anyway; the caller already guards against it
            // (canDrawTrend), so falling back to an empty string is harmless — this isn't one of
            // Vico's axes, an empty string here just doesn't display, it doesn't throw
            TrendDateLabel(labels.firstOrNull())
            TrendDateLabel(labels.lastOrNull())
        }
    }
}

@Composable
private fun TrendDateLabel(text: String?) {
    Text(
        text = text.orEmpty(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * The by-class trend chart — normally a **stacked area** chart.
 *
 * Vico has no native stacked area chart. This is assembled from "cumulative boundaries + opaque
 * fill + later draws covering earlier ones": the k-th line draws the cumulative value of the
 * first k+1 classes, each filled down to 0, drawn starting from the highest one, with each
 * subsequent line covering part of the previous one, leaving exactly that class's band visible.
 *
 * ⚠️ This technique **requires every segment to be non-negative**. Net exposure can be negative
 * (a car loan exceeding the car's value, credit card debt exceeding liquid assets), and once
 * there's a negative value the cumulative sum is no longer monotonic and the boundaries cross
 * each other, producing a chart that looks normal but has its layers scrambled. So
 * [AllocationSeries.hasNegativeExposure] is checked first, and if it's true, it **falls back to
 * independent per-class lines** (unfilled), with an explanation shown on the page at the same
 * time — better to switch to a different representation than to silently render a wrong chart.
 */
@Composable
private fun AllocationTrendChart(
    series: AllocationSeries,
    classes: List<AssetClass>,
    hideAmounts: Boolean,
    modifier: Modifier,
) {
    val modelProducer = remember { CartesianChartModelProducer() }
    val stacked = remember(series, classes) { !series.hasNegativeExposure(classes) }
    val lineValues = remember(series, classes, stacked) {
        val perClass = classes.map { assetClass ->
            series.netExposures(assetClass).map { it.minorUnits }
        }
        val bands = if (stacked) stackedBands(perClass) else perClass
        bands.map { band -> band.map { it / 100.0 } }
    }
    // When stacked, drawing starts from the highest line (later draws cover earlier ones); when
    // falling back to independent lines the order doesn't matter, so the class display order is
    // kept so the legend reads consistently with the allocation page.
    val order = remember(lineValues, stacked) {
        if (stacked) lineValues.indices.reversed().toList() else lineValues.indices.toList()
    }

    LaunchedEffect(lineValues, order) {
        if (lineValues.isEmpty() || lineValues.first().isEmpty()) return@LaunchedEffect
        modelProducer.runTransaction {
            lineModel { order.forEach { index -> series(lineValues[index]) } }
        }
    }

    val palette = chartColors
    val lines = order.map { index ->
        val color = palette.of(classes[index])
        LineCartesianLayer.rememberLine(
            fill = LineCartesianLayer.LineFill.single(Fill(color)),
            areaFill = if (stacked) LineCartesianLayer.AreaFill.single(Fill(color)) else null,
        )
    }

    TrendChartFrame(series.dates, modifier) {
        CartesianChartHost(
            chart = rememberCartesianChart(
                rememberLineCartesianLayer(
                    lineProvider = LineCartesianLayer.LineProvider.series(lines),
                ),
                startAxis = rememberAmountAxis(hideAmounts),
            ),
            modelProducer = modelProducer,
            animationSpec = chartAnimationSpec,
            modifier = Modifier.fillMaxWidth().height(CHART_HEIGHT),
        )
    }
}

// ---------------------------------------------------------------- Axes

/**
 * The growth-rate label band at the top — above each column, "how much it moved relative to the
 * previous one".
 *
 * **Why this is an axis rather than Vico's built-in `dataLabel`:** dataLabel's formatter only has
 * access to the y value, not x, so it has to look things up via a "y value → label" mapping
 * table. But this app's carry-forward semantics make **identical net worth in adjacent
 * periods** common (if no new snapshot was recorded, the previous valuation is carried forward,
 * and this is locked in by tests) — two columns of the same height would share the same key, and
 * the growth-rate label would end up on the wrong one — a silent miscalculation that's
 * unacceptable. An axis's formatter has access to x and looks values up by index, so this problem
 * doesn't arise.
 *
 * Per-label coloring (red for up, green for down) relies on returning an [AnnotatedString]:
 * Vico's TextComponent passes the `CharSequence` through to `TextMeasurer` as-is, and when it's
 * an AnnotatedString the span-aware overload is used.
 */
@Composable
private fun rememberGrowthAxis(): HorizontalAxis<Axis.Position.Horizontal.Top> {
    val rise = riseColor()
    val fall = fallColor()
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    return HorizontalAxis.rememberTop(
        // Line, tick, and gridline all removed: this isn't really an axis, it's just borrowing
        // its positioning ability to lay out a row of labels
        line = null,
        label = rememberAxisLabelComponent(
            style = MaterialTheme.typography.labelSmall.copy(color = neutral),
        ),
        valueFormatter = remember(rise, fall, neutral) {
            CartesianValueFormatter { context, x, _ ->
                val label = growthLabelAt(context.model.extraStore.getOrNull(GrowthLabelsKey), x)
                label.annotated(rise = rise, fall = fall, neutral = neutral)
            }
        },
        tick = null,
        guideline = null,
        // spacing/offset are **exactly the same** as the period axis at the bottom: this way a
        // column labeled with a growth rate always also has a month label beneath it, reading as
        // one group like "Aug +12%" rather than two rows each labeled independently.
        //
        // ⚠️ Don't take the shortcut of using `aligned()`'s defaults (spacing = 1, offset = 0).
        // `aligned()` defaults to `addExtremeLabelPadding = true`, under which Vico multiplies
        // spacing by `ceil(maxLabelWidth / xSpacing)` to prevent labels from overlapping — with
        // 12 columns the effective spacing becomes 2, while offset stays 0, so labels land on
        // 0/2/4/6/8/10, **and the last one (the most recent period) is exactly the one that gets
        // skipped**. Confirmed via a real-device screenshot: at 12 months there were only 6
        // percentages, and the rightmost column had nothing above it.
        itemPlacer = remember {
            HorizontalAxis.ItemPlacer.aligned(
                spacing = { extras -> axisLabelSpacing(extras.labelCount(GrowthLabelsKey)) },
                offset = { extras -> axisLabelOffset(extras.labelCount(GrowthLabelsKey)) },
            )
        },
    )
}

/** The period label at the bottom ("Aug" / "Q3" / "2026"); thinned out per [axisLabelSpacing] when there are many points. */
@Composable
private fun rememberPeriodAxis(): HorizontalAxis<Axis.Position.Horizontal.Bottom> =
    HorizontalAxis.rememberBottom(
        // The x-to-label mapping is always read from **the model currently being drawn**, never
        // from a `series` captured by composition — see [AxisLabelsKey], which is the fix for a
        // real crash.
        valueFormatter = { context, x, _ ->
            axisLabelAt(context.model.extraStore.getOrNull(AxisLabelsKey), x)
        },
        itemPlacer = remember {
            HorizontalAxis.ItemPlacer.aligned(
                spacing = { extras -> axisLabelSpacing(extras.labelCount(AxisLabelsKey)) },
                offset = { extras -> axisLabelOffset(extras.labelCount(AxisLabelsKey)) },
            )
        },
    )

/**
 * The y-axis amount, abbreviated to "10K/100M" — the full number (600900.36) would take up
 * thirty or forty dp of drawing width.
 *
 * When [hideAmounts] is set, `label` is set to null entirely (Vico's `rememberStart` allows this;
 * the label-drawing step is `label ?: return@forEach`), while **the tick marks and gridlines are
 * kept** — without them the columns would float in empty space with no readable scale.
 *
 * Not going the "formatter returns a placeholder" route: Vico would draw that placeholder once
 * per tick, turning into a vertical column of repeated `••••••`, noisier than drawing nothing.
 * (This incidentally also avoids `check(isNotBlank())`.)
 *
 * ⚠️ **`label = null` must be changed together with `itemPlacer`** (only discovered from a
 * simulator screenshot). Both placers have **special-case branches** when
 * `maxLabelHeight == 0` (= no labels):
 * - `step()` (the default) skips the anti-overlap step and uses
 *   `10^(floor(log10(maxY))-1)` directly as the step size — at a net worth of ¥2.38M the step
 *   becomes ¥100K, giving **23 horizontal gridlines**, smearing the columns into stripes.
 * - `count()` has a short-circuit: when the label height is 0, it returns only `minY` and
 *   `maxY`, so the horizontal gridlines disappear entirely. **This is exactly what's wanted** —
 *   gridlines exist to help read label values, and with no labels they're just decoration. The
 *   vertical month gridlines come from the bottom axis and are unaffected.
 *
 * The number passed to `count` isn't actually used on this path (the short-circuit happens
 * before it), but 2 is written to make the intent explicit: just the two ends. If Vico ever
 * removes that short-circuit in the future, `count(2)` would still draw 2 lines rather than
 * reverting to stripes.
 *
 * **General rule: before setting some component to null, first find out who else is reading its
 * size to compute something else.**
 */
@Composable
private fun rememberAmountAxis(hideAmounts: Boolean): VerticalAxis<Axis.Position.Vertical.Start> =
    VerticalAxis.rememberStart(
        label = if (hideAmounts) null else rememberAxisLabelComponent(),
        valueFormatter = remember {
            CartesianValueFormatter { _, value, _ -> compactAmountLabel(value) }
        },
        itemPlacer = if (hideAmounts) {
            remember { VerticalAxis.ItemPlacer.count({ AXIS_ENDS_ONLY }) }
        } else {
            remember { VerticalAxis.ItemPlacer.step() }
        },
    )

/** Only the two ends of the y-axis (min and max), no gridlines in between. See [rememberAmountAxis]. */
private const val AXIS_ENDS_ONLY = 2

// ---------------------------------------------------------------- Pure functions

private fun Money.toYuan(): Double = minorUnits / 100.0

/**
 * When there's only one sample point, artificially make the viewport "hold" this many x-units,
 * so the column occupies only one slot within it.
 *
 * 2 was arrived at through real-device comparison: the column width Vico draws is roughly
 * "viewport width ÷ (2 × slot count)"; with a slot count of 2, that column's width comes out
 * identical to a column **when there are two points** — reading as "the first of two, the
 * second not recorded yet" rather than a solid rectangle filling the entire width (the look that
 * drew feedback in AGENTS.md lesson 13). A larger slot count (6 was tried) makes the column shrink
 * to a sliver with a big empty gap on the right, which looks broken in its own way.
 */
private const val SINGLE_POINT_SLOTS = 2.0

/** The number of phantom series on each side, with the real series sandwiched exactly in the middle. See the comment on [TotalColumnChart]. */
private const val PHANTOM_COLUMNS_PER_SIDE = 2

/**
 * The x-axis labels **travel together with the model**, pushed into the same `runTransaction`
 * via [ExtraStore].
 *
 * This fixes a real-device crash: **switching from monthly to quarterly/yearly caused an
 * immediate crash**. `modelProducer` stays alive across period switches, while the model update
 * happens inside a **suspend transaction** in `LaunchedEffect` (with a transition animation on
 * top). During the frame where the period is switched, composition already has the new `series`
 * (say, only 1 point for yearly), but Vico still holds the old model (12 points for monthly) —
 * previously the formatter directly closed over `series.dates`, so when asked about an
 * out-of-range x it returned an empty string, and since Vico runs `check(isNotBlank())` on every
 * axis label, **an empty string threw immediately**.
 *
 * `itemPlacer`'s spacing/offset is the other half of the same pitfall: `getFirstLabelValue()`
 * asks the formatter using `minX + offset * xStep`, and **this x is not range-clipped**.
 *
 * The rule: **anything formatter/ItemPlacer needs must live in the same transaction as the data
 * points, never captured from composition.** Otherwise there is necessarily a frame where "UI
 * state" and "chart model" are out of sync.
 */
private val AxisLabelsKey = ExtraStore.Key<List<String>>()

/** Growth-rate labels, for the same reason as [AxisLabelsKey]. */
private val GrowthLabelsKey = ExtraStore.Key<List<GrowthLabel>>()

private fun <T> ExtraStore.labelCount(key: ExtraStore.Key<List<T>>): Int = getOrNull(key)?.size ?: 0

private fun formatAxisLabel(date: LocalDate, period: Period): String = when (period) {
    Period.MONTH -> "${date.month.ordinal + 1}月"
    Period.QUARTER -> "Q${date.month.ordinal / 3 + 1}"
    Period.YEAR -> date.year.toString()
}

internal fun axisLabels(period: Period, dates: List<LocalDate>): List<String> =
    dates.map { formatAxisLabel(it, period) }

internal fun axisLabels(series: NetWorthSeries): List<String> =
    axisLabels(series.period, series.dates)

internal fun dateLabels(dates: List<LocalDate>): List<String> = dates.map { it.toString() }

/**
 * Beyond this many labels, they'd crowd into overlapping/truncated text on a phone-width chart
 * (observed: 12 monthly labels crammed together, with "Oct"/"Nov"/"Dec" truncated to "10…"/"11…"/"12…").
 */
private const val MAX_AXIS_LABELS = 6

/** Vico's default itemPlacer is "one label per point"; once the point count exceeds the threshold, only label every few points. */
internal fun axisLabelSpacing(labelCount: Int): Int =
    if (labelCount <= MAX_AXIS_LABELS) 1
    else (labelCount + MAX_AXIS_LABELS - 1) / MAX_AXIS_LABELS

/**
 * `aligned` by default counts from minX (index 0), labeling every `spacing` points — when the
 * point count isn't a multiple of spacing, **the last point (the period containing today) can
 * end up not landing on a label**, and that's exactly the point the user cares about most.
 * Taking "the last index mod spacing" makes the label sequence align from the last point
 * backwards instead.
 */
internal fun axisLabelOffset(labelCount: Int): Int {
    val spacing = axisLabelSpacing(labelCount)
    return if (spacing <= 1 || labelCount == 0) 0 else (labelCount - 1) % spacing
}

/**
 * When a label can't be found, a placeholder is given rather than **an empty string** — Vico
 * runs `check(isNotBlank())` on every axis label, and returning an empty string would throw and
 * crash the app. Labels now travel with the model, so under normal circumstances this branch is
 * never hit; it's kept because "crashing" and "one extra placeholder point" are not remotely
 * equivalent outcomes — the fallback must be cheap and safe.
 */
internal const val MISSING_AXIS_LABEL = "·"

internal fun axisLabelAt(labels: List<String>?, x: Double): String =
    labels?.getOrNull(x.toInt())?.takeIf { it.isNotBlank() } ?: MISSING_AXIS_LABEL

/**
 * A column's growth-rate label relative to the previous one.
 *
 * [direction] is 1 / -1 / 0, used by the UI to color it — **do not parse the sign from [text]**;
 * the "can't be computed" placeholder happens to look like a minus sign but is not one.
 */
internal data class GrowthLabel(val text: String, val direction: Int) {
    companion object {
        /**
         * Can't be computed: the first column has no previous one, or the previous one is <= 0
         * (the denominator would be meaningless).
         *
         * **Must not be a blank string** — it would be caught by Vico's `check(isNotBlank())`.
         * Also not written as "0%": that means "no change", which is entirely different from
         * "can't be computed".
         */
        val MISSING = GrowthLabel("—", 0)
    }
}

/** Colored on the UI side. Red for up, green for down reflects the Chinese stock market convention; see [com.boomsset.ui.GainLossColors]. */
private fun GrowthLabel.annotated(rise: Color, fall: Color, neutral: Color): AnnotatedString {
    val color = when {
        direction > 0 -> rise
        direction < 0 -> fall
        else -> neutral
    }
    return buildAnnotatedString { withStyle(SpanStyle(color = color)) { append(text) } }
}

/**
 * Each column's growth rate relative to the **previous** one.
 *
 * Reuses [PortfolioCalculator.growthBp], sharing the same "can't be computed when the starting
 * value is <= 0" rule with the top card's "net worth growth over the whole period" — if the
 * division were written twice in two places, sooner or later one would show "—" while the other
 * showed some percentage computed out of thin air.
 */
internal fun growthLabels(values: List<Money>): List<GrowthLabel> =
    values.mapIndexed { index, value ->
        val previous = values.getOrNull(index - 1) ?: return@mapIndexed GrowthLabel.MISSING
        val bp = PortfolioCalculator.growthBp(previous.minorUnits, value.minorUnits)
            ?: return@mapIndexed GrowthLabel.MISSING
        // Direction is judged from the number **after rounding**, not the raw basis points:
        // +0.19% displays as "0%", and coloring it "up" red at that point would be
        // self-contradictory (the text says no change, the color says it went up). Color
        // follows the number that's actually visible.
        val percent = roundToPercent(bp)
        GrowthLabel(text = formatGrowthPercent(bp), direction = percent.compareTo(0))
    }

internal fun growthLabelAt(labels: List<GrowthLabel>?, x: Double): GrowthLabel =
    labels?.getOrNull(x.toInt()) ?: GrowthLabel.MISSING

/**
 * The growth rate is formatted as an **integer percentage**, rounded.
 *
 * This isn't laziness: with 12 columns side by side, each only gets a couple dozen dp, and
 * "+12.34%" wouldn't fit and would get truncated to "+12…" — a truncated number is worse than no
 * number. The decimal-precision figure lives on the top card (where there's only one, so it fits).
 *
 * Rounded rather than truncated: truncating +0.9% to "+0%" would read as "no change", when it
 * actually went up.
 */
internal fun roundToPercent(bp: Int): Int = (bp + if (bp >= 0) 50 else -50) / 100

internal fun formatGrowthPercent(bp: Int): String {
    val rounded = roundToPercent(bp)
    return when {
        rounded > 0 -> "+$rounded%"
        rounded < 0 -> "$rounded%"
        // Genuinely rounds to 0 (e.g. +0.3%): give "0%" rather than "+0%" —
        // the latter would suggest "went up a tiny bit that just can't be displayed"
        else -> "0%"
    }
}

/**
 * The y-axis amount label: abbreviated to **10K/100M** (万/亿).
 *
 * Consistent with [com.boomsset.ui.formatCompact]'s thresholds (10K/100M rather than K/M, per
 * Chinese convention), but the input here is the Double (yuan) that Vico provides, not a Money —
 * a tick value on the axis is a range computed by Vico itself and doesn't correspond to any real
 * amount, so there's no need to go through the fixed-point path.
 *
 * Never returns an empty string: Vico runs `check(isNotBlank())` on axis labels.
 */
internal fun compactAmountLabel(yuan: Double): String {
    val magnitude = abs(yuan)
    val body = when {
        magnitude >= 100_000_000.0 -> "${(magnitude / 100_000_000.0).oneDecimal()}亿"
        magnitude >= 10_000.0 -> "${(magnitude / 10_000.0).oneDecimal()}万"
        else -> magnitude.roundToLong().toString()
    }
    return if (yuan < 0 && body != "0") "-$body" else body
}

/** Keeps one decimal place, dropping a trailing .0. Computed with Long to avoid Double's scientific notation. */
private fun Double.oneDecimal(): String {
    val scaled = (this * 10).roundToLong()
    val whole = scaled / 10
    val frac = abs(scaled % 10)
    return if (frac == 0L) whole.toString() else "$whole.$frac"
}

/**
 * Cumulative boundaries for the stacked area chart: the k-th line = the sum of the first k+1 segments.
 *
 * The technique is that each boundary is filled down to 0 on its own, drawn starting from the
 * highest one, with each later draw covering part of the previous one, so what's left visible is
 * exactly that class's band.
 *
 * ⚠️ **Requires every segment to be non-negative**, otherwise the cumulative sum isn't monotonic
 * and the boundaries cross each other, producing an incorrectly layered chart. Callers must
 * check [AllocationSeries.hasNegativeExposure] first.
 */
internal fun stackedBands(seriesByClass: List<List<Long>>): List<List<Long>> {
    val running = LongArray(seriesByClass.firstOrNull()?.size ?: 0)
    return seriesByClass.map { values ->
        values.mapIndexed { index, value ->
            running[index] += value
            running[index]
        }
    }
}
