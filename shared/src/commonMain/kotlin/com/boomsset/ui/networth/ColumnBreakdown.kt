package com.boomsset.ui.networth

import com.boomsset.domain.AllocationSeries
import com.boomsset.domain.AssetClass
import com.boomsset.domain.Money
import com.boomsset.ui.periodLabel

/** One class's line for a tapped column. [assetClass] null marks the total of the visible classes. */
internal data class BreakdownRow(
    val assetClass: AssetClass?,
    val amount: Money,
    val growth: GrowthLabel,
)

/** What the by-class chart prints on one tapped column. [label] is the period ("2026年10月"). */
internal data class ColumnBreakdown(
    val label: String,
    val currency: String,
    val rows: List<BreakdownRow>,
)

/**
 * The breakdown of a single column in by-class mode: one row per visible class, plus a total.
 *
 * Each class gets **its own** growth rate, measured against that class's own value in the
 * previous period — which is the point. The stacked column only shows how the total moved; a
 * class that halved while another doubled is invisible in the column's height.
 *
 * [classes] must be the **visible** classes, in display order: row *i* is drawn on segment *i*,
 * and the total has to match the column the user is looking at (the column's height drops as
 * soon as a class is unchecked). The total row is dropped when only one class is visible — it
 * would repeat the row above it.
 *
 * @return null when [index] isn't a real sample point or nothing is visible. Never substitutes a
 *   neighbouring column, which would silently label the wrong period.
 */
internal fun allocationBreakdown(
    series: AllocationSeries,
    classes: List<AssetClass>,
    index: Int,
): ColumnBreakdown? {
    val date = series.dates.getOrNull(index) ?: return null
    if (index !in series.points.indices || classes.isEmpty()) return null
    val perClass = classes.map { assetClass ->
        val amounts = series.netExposures(assetClass)
        BreakdownRow(assetClass, amounts[index], growthLabels(amounts)[index])
    }
    val totals = series.totals(classes)
    val total = BreakdownRow(null, totals[index], growthLabels(totals)[index])
    return ColumnBreakdown(
        label = date.periodLabel(series.period),
        currency = series.baseCurrency,
        rows = if (classes.size > 1) perClass + total else perClass,
    )
}
