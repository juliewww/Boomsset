package com.boomsset.ui.networth

import com.boomsset.domain.Money
import com.boomsset.domain.NetWorthPoint
import com.boomsset.domain.NetWorthSeries
import com.boomsset.domain.Period
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.time.Instant

/**
 * The rules for the net worth chart's x-axis labels.
 *
 * These are pure functions, but what they guard is **two hard constraints of Vico**:
 * 1. An axis label **must not be a blank string** — Vico runs `check(isNotBlank())` on
 *    every label; returning an empty string throws immediately, which shows up as the
 *    app crashing (on a real device, switching "by month" to "by quarter/year" crashed
 *    exactly this way)
 * 2. `ItemPlacer.aligned()` requires `spacing > 0` and `offset >= 0`, otherwise its
 *    `require` fails
 *
 * Unit tests can't see what Vico actually draws, but they can lock down the other
 * half: "the values fed to Vico are always legal."
 */
class NetWorthChartAxisTest {

    private fun series(period: Period, dateCount: Int): NetWorthSeries {
        val t = Instant.fromEpochMilliseconds(0)
        val dates = List(dateCount) { LocalDate(2020 + it / 12, it % 12 + 1, 28) }
        return NetWorthSeries(
            period = period,
            baseCurrency = "CNY",
            dates = dates,
            points = dates.map { NetWorthPoint(t, "CNY", Money.ZERO, Money.ZERO) },
        )
    }

    @Test
    fun `labels for every period granularity are non-blank`() {
        Period.entries.forEach { period ->
            val labels = axisLabels(series(period, 12))
            labels.size shouldBe 12
            labels.forEach { it.isNotBlank() shouldBe true }
        }
    }

    /**
     * Regression test: **switching from by-month to by-quarter/by-year crashed the app.**
     *
     * `CartesianChartModelProducer` stays alive across period switches, while the model
     * update is a suspend transaction, so on the frame of the switch Vico still holds the
     * old model (12 points, by month) while already using the new label table (2 points,
     * by quarter). The old formatter did `dates.getOrNull(x) ?: ""` directly, so for
     * x=2..11 it returned an empty string → Vico threw.
     *
     * Now the labels travel with the model via ExtraStore, so this mismatch normally
     * can't happen; this test locks down the **fallback behavior**: even if an
     * out-of-range x is genuinely queried, it can only produce a placeholder, never a
     * blank string.
     */
    @Test
    fun `gives a placeholder instead of a blank string when the label table is shorter than x`() {
        val quarterly = axisLabels(series(Period.QUARTER, 2))
        (0..11).forEach { x ->
            axisLabelAt(quarterly, x.toDouble()).isNotBlank() shouldBe true
        }
        axisLabelAt(quarterly, 11.0) shouldBe MISSING_AXIS_LABEL
        axisLabelAt(quarterly, 1.0) shouldBe quarterly[1]
    }

    @Test
    fun `does not return a blank string when there is no label table either`() {
        axisLabelAt(null, 0.0).isNotBlank() shouldBe true
        axisLabelAt(emptyList(), 0.0).isNotBlank() shouldBe true
        // A negative x (Vico measures out-of-range positions to leave room around the axis)
        // must not return a blank string either
        axisLabelAt(axisLabels(series(Period.MONTH, 3)), -1.0).isNotBlank() shouldBe true
    }

    @Test
    fun `spacing and offset are legal values for any point count`() {
        (0..40).forEach { count ->
            val spacing = axisLabelSpacing(count)
            val offset = axisLabelOffset(count)
            spacing shouldBeGreaterThan 0
            offset shouldBeGreaterThanOrEqualTo 0
            offset shouldBeLessThan spacing
        }
    }

    @Test
    fun `labels get sparser with more points, but the last point is always labeled`() {
        val count = 12
        val spacing = axisLabelSpacing(count)
        spacing shouldBeGreaterThan 1
        // aligned() labels offset, offset+spacing, offset+2*spacing… the last index must
        // land among them, otherwise the point the user cares about most — "now" — has no label
        ((count - 1 - axisLabelOffset(count)) % spacing) shouldBe 0
    }

    @Test
    fun `every point is labeled when there are few points`() {
        axisLabelSpacing(1) shouldBe 1
        axisLabelSpacing(6) shouldBe 1
        axisLabelOffset(1) shouldBe 0
        axisLabelOffset(6) shouldBe 0
    }

    // ---------- Growth-rate label band ----------

    private fun money(vararg yuan: Long) = yuan.map { Money(it * 100) }

    @Test
    fun `growth label count equals column count, first column has no prior to compare against`() {
        val labels = growthLabels(money(100, 110, 99))

        labels shouldHaveSize 3
        labels[0] shouldBe GrowthLabel.MISSING
        labels[1] shouldBe GrowthLabel("+10%", 1)
        labels[2] shouldBe GrowthLabel("-10%", -1)
    }

    /**
     * When the starting value is ≤ 0, no percentage can be computed (the denominator is
     * meaningless) — it must be "—", not some number conjured out of thin air.
     *
     * This shares its criterion with [PortfolioCalculator.growthBp], used by the "growth
     * over the whole period" figure on the top card — if each place wrote its own
     * division, sooner or later one would show "—" while the other showed +∞ or some
     * such nonsense.
     */
    @Test
    fun `growth rate cannot be computed when the starting value is not positive`() {
        growthLabels(money(0, 100))[1] shouldBe GrowthLabel.MISSING
        growthLabels(money(-50, 100))[1] shouldBe GrowthLabel.MISSING
        // A negative ending value can be computed fine (net worth genuinely dropped
        // negative), the direction is down
        growthLabels(money(100, -50))[1].direction shouldBe -1
    }

    /**
     * Vico runs `check(isNotBlank())` on **every** axis label; a blank string throws
     * immediately. The growth-rate band is an axis (not a dataLabel), so the same
     * constraint applies here just the same.
     */
    @Test
    fun `growth rate label is never a blank string`() {
        val labels = growthLabels(money(0, 100, 100, -20, 0))
        labels.forEach { it.text.isNotBlank() shouldBe true }

        // Out-of-range, an empty table, and a null table (the frame before the model has
        // landed) can all only produce a placeholder
        (-2..9).forEach { x ->
            growthLabelAt(labels, x.toDouble()).text.isNotBlank() shouldBe true
            growthLabelAt(null, x.toDouble()) shouldBe GrowthLabel.MISSING
            growthLabelAt(emptyList(), x.toDouble()) shouldBe GrowthLabel.MISSING
        }
        growthLabelAt(labels, 1.0) shouldBe labels[1]
    }

    /**
     * Whole-number percentage, **rounded, not truncated.**
     *
     * With a dozen-plus columns side by side, each one only gets about twenty-something
     * dp, and a percentage with decimals would get truncated into "+12…" — a truncated
     * number is worse than none at all. And truncating to a whole number would display
     * +0.9% as "+0%", which reads as "unchanged" when it actually went up.
     */
    @Test
    fun `growth rate rounds to a whole number`() {
        formatGrowthPercent(1234) shouldBe "+12%"
        formatGrowthPercent(1250) shouldBe "+13%"
        formatGrowthPercent(-1250) shouldBe "-13%"
        formatGrowthPercent(-149) shouldBe "-1%"
        formatGrowthPercent(-150) shouldBe "-2%"
        // When rounded to 0, no sign is shown: "+0%" would make people think "it went up a
        // little but can't be shown"
        formatGrowthPercent(49) shouldBe "0%"
        formatGrowthPercent(-49) shouldBe "0%"
        formatGrowthPercent(50) shouldBe "+1%"
        formatGrowthPercent(0) shouldBe "0%"
    }

    /**
     * The color must also be neutral when rounded to 0.
     *
     * Caught in a real-device screenshot: a column labeled "0%" (rounded from +0.19%) was
     * still painted the "up" color — the text says unchanged, the color says up,
     * contradicting each other. The direction must be judged from **the number actually
     * displayed**.
     */
    @Test
    fun `does not apply the up-or-down color when rounded to 0`() {
        // 105,800 → 106,000, +0.19%
        val labels = growthLabels(listOf(Money(10_580_000), Money(10_600_000)))
        labels[1] shouldBe GrowthLabel("0%", 0)

        growthLabels(money(1000, 996))[1] shouldBe GrowthLabel("0%", 0)
    }

    /**
     * The growth-rate band and the bottom period axis **must use the same spacing/offset**,
     * otherwise the newest column ends up unlabeled.
     *
     * Caught on a real device (12 columns): the growth band originally used `aligned()`'s
     * defaults (spacing=1, offset=0), but `aligned()` defaults `addExtremeLabelPadding` to
     * true, and Vico multiplies spacing by `ceil(maxLabelWidth / xSpacing)` to prevent
     * overlap — the actual interval became 2 while offset stayed 0, so labels landed on
     * 0/2/…/10, **leaving the rightmost (most recent period) unlabeled**.
     * The two axes naturally have the same label count (both equal to the number of
     * sample points), so sharing the same algorithm keeps them aligned.
     */
    @Test
    fun `growth band and period axis have matching label counts, and label the last column even when sparse`() {
        val s = series(Period.MONTH, 12)
        val growth = growthLabels(s.points.map { it.netWorth })

        growth shouldHaveSize axisLabels(s).size

        val spacing = axisLabelSpacing(growth.size)
        ((growth.size - 1 - axisLabelOffset(growth.size)) % spacing) shouldBe 0
    }

    // ---------- Trend chart ----------

    /**
     * Regression for lesson 10: with only 1 point, the line chart can't draw a line
     * segment, leaving only the axes in the chart area. The trend chart is now something
     * the user opts into, so an insufficient point count must be **stated explicitly**
     * rather than showing an empty chart.
     */
    @Test
    fun `trend chart needs at least two points`() {
        canDrawTrend(0) shouldBe false
        canDrawTrend(1) shouldBe false
        canDrawTrend(2) shouldBe true
    }

    /**
     * The two dates below the trend chart are **drawn by hand** (Vico's bottom axis
     * can't draw the trailing one, see the comment on [TrendChartFrame]), so this only
     * needs to lock down "first and last are each taken, both are full dates."
     */
    @Test
    fun `date labels are full dates and take both ends`() {
        val labels = dateLabels(
            listOf(LocalDate(2026, 1, 31), LocalDate(2026, 4, 30), LocalDate(2026, 7, 28)),
        )
        labels shouldBe listOf("2026-01-31", "2026-04-30", "2026-07-28")
        labels.first() shouldBe "2026-01-31"
        labels.last() shouldBe "2026-07-28"
    }

    /**
     * The stacked area is assembled from "cumulative boundaries + later draws covering
     * earlier ones" (Vico has no native stacked area chart). The cumulative values must
     * increase monotonically, otherwise the boundaries cross each other and the layering
     * is wrong — this presupposes every segment is non-negative, which
     * `AllocationSeries.hasNegativeExposure` gatekeeps before this is ever called.
     */
    @Test
    fun `cumulative boundaries increase layer by layer, and the top layer equals the total`() {
        val bands = stackedBands(
            listOf(
                listOf(10L, 20L),
                listOf(5L, 0L),
                listOf(1L, 3L),
            ),
        )

        bands shouldBe listOf(
            listOf(10L, 20L),
            listOf(15L, 20L),
            listOf(16L, 23L),
        )
        bands.last() shouldBe listOf(16L, 23L)
    }

    @Test
    fun `cumulative boundaries handle empty input`() {
        stackedBands(emptyList()) shouldBe emptyList()
        stackedBands(listOf(emptyList())) shouldBe listOf(emptyList())
        stackedBands(listOf(listOf(7L))) shouldBe listOf(listOf(7L))
    }

    // ---------- Y-axis amount ----------

    /** The y-axis is also a Vico axis label, and likewise must never be blank. */
    @Test
    fun `amount is abbreviated with wan and yi and is never blank`() {
        compactAmountLabel(0.0) shouldBe "0"
        compactAmountLabel(9999.0) shouldBe "9999"
        compactAmountLabel(10_000.0) shouldBe "1万"
        compactAmountLabel(12_500.0) shouldBe "1.3万"
        compactAmountLabel(1_234_567.0) shouldBe "123.5万"
        compactAmountLabel(100_000_000.0) shouldBe "1亿"
        compactAmountLabel(-25_000.0) shouldBe "-2.5万"
        // A tiny negative value rounded to 0 carries no minus sign — "-0" isn't a number
        compactAmountLabel(-0.4) shouldBe "0"

        listOf(-1e12, -1.0, 0.0, 0.5, 9_999.4, 1e12).forEach {
            compactAmountLabel(it).isNotBlank() shouldBe true
        }
    }
}
