package com.boomsset.domain

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.time.Instant

class PortfolioSeriesCalculatorTest {

    private val zone = TimeZone.UTC
    private val today = LocalDate(2026, 7, 28)
    private val cny = "CNY"

    private fun asset(id: Long, cls: AssetClass = AssetClass.LIQUID, liability: Boolean = false) =
        Asset(
            id = id,
            name = "asset-$id",
            assetClass = cls,
            subtypeId = 1,
            currency = cny,
            isLiability = liability,
            defaultValuationMode = ValuationMode.MANUAL,
        )

    private fun manual(id: Long, assetId: Long, day: LocalDate, valueMinor: Long) =
        Snapshot.Manual(
            id = id,
            assetId = assetId,
            asOf = day.endOfDayIn(zone),
            value = Money(valueMinor),
            recordedAt = Instant.fromEpochMilliseconds(0),
        )

    // ---------- Sample dates ----------

    @Test
    fun `sampling by month, the last point is today, not the end of the month`() {
        // The current period hasn't ended yet; sampling at a future month-end would give a net worth that doesn't match "now"
        val dates = periodSampleDates(today, Period.MONTH, count = 3)
        dates shouldHaveSize 3
        dates.last() shouldBe today
        dates[1] shouldBe LocalDate(2026, 6, 30)
        dates[0] shouldBe LocalDate(2026, 5, 31)
    }

    @Test
    fun `sampling by quarter lands on quarter-end`() {
        val dates = periodSampleDates(today, Period.QUARTER, count = 3)
        dates.last() shouldBe today                      // 2026 Q3 hasn't ended
        dates[1] shouldBe LocalDate(2026, 6, 30)         // Q2
        dates[0] shouldBe LocalDate(2026, 3, 31)         // Q1
    }

    @Test
    fun `sampling by year lands on year-end`() {
        val dates = periodSampleDates(today, Period.YEAR, count = 3)
        dates.last() shouldBe today
        dates[1] shouldBe LocalDate(2025, 12, 31)
        dates[0] shouldBe LocalDate(2024, 12, 31)
    }

    // ---------- Carry-forward ----------

    @Test
    fun `a month with no new snapshot carries forward the last valuation`() {
        // 100,000 recorded in May, no update since -- both June and July should be 100,000, not 0
        val data = PortfolioData(
            assets = listOf(asset(1)),
            snapshots = listOf(manual(1, 1, LocalDate(2026, 5, 20), 100_000_00)),
            quotes = emptyList(),
            fxRates = emptyList(),
        )

        val series = PortfolioSeriesCalculator.buildSeries(
            data, Period.MONTH, cny, today, zone, pointCount = 3,
        )

        series.points.map { it.netWorth } shouldBe listOf(
            Money(100_000_00),
            Money(100_000_00),
            Money(100_000_00),
        )
    }

    @Test
    fun `points before an asset's creation are not counted`() {
        // An asset only recorded in July should show 0 net worth for May and June -- not as if it always existed
        val data = PortfolioData(
            assets = listOf(asset(1)),
            snapshots = listOf(manual(1, 1, LocalDate(2026, 7, 10), 50_000_00)),
            quotes = emptyList(),
            fxRates = emptyList(),
        )

        val series = PortfolioSeriesCalculator.buildSeries(
            data, Period.MONTH, cny, today, zone, pointCount = 3,
        )

        series.points.map { it.netWorth } shouldBe listOf(
            Money.ZERO,
            Money.ZERO,
            Money(50_000_00),
        )
    }

    @Test
    fun `a zeroing snapshot stops an archived asset from contributing to net worth`() {
        // The 0-value snapshot appended on archiving relies on the carry-forward rule to keep that asset at 0 afterward
        val data = PortfolioData(
            assets = listOf(asset(1)),
            snapshots = listOf(
                manual(1, 1, LocalDate(2026, 5, 20), 100_000_00),
                manual(2, 1, LocalDate(2026, 6, 15), 0),   // sold and archived
            ),
            quotes = emptyList(),
            fxRates = emptyList(),
        )

        val series = PortfolioSeriesCalculator.buildSeries(
            data, Period.MONTH, cny, today, zone, pointCount = 3,
        )

        series.points.map { it.netWorth } shouldBe listOf(
            Money(100_000_00),
            Money.ZERO,
            Money.ZERO,
        )
    }

    // ---------- Trimming sample points from "before the asset existed" ----------

    /**
     * Regression test. Real-world feedback: when viewing by year/quarter, an account that's
     * only a few months old ends up with most of the requested 12 sample points being 0-value
     * points from before any asset existed -- the chart gets filled with these "no data"
     * points, and the recent months get squeezed into a tiny sliver.
     *
     * `trimBeforeFirstSnapshot = true` should drop those points, keeping only the portion with real history.
     */
    @Test
    fun `when trimming, only sample points after the first snapshot are kept`() {
        val data = PortfolioData(
            assets = listOf(asset(1)),
            snapshots = listOf(manual(1, 1, LocalDate(2026, 7, 10), 50_000_00)),
            quotes = emptyList(),
            fxRates = emptyList(),
        )

        val series = PortfolioSeriesCalculator.buildSeries(
            data, Period.MONTH, cny, today, zone, pointCount = 3, trimBeforeFirstSnapshot = true,
        )

        // Without trimming there would be 3 points (May/June/July, first two at 0); after trimming, only July remains
        series.points.map { it.netWorth } shouldBe listOf(Money(50_000_00))
        series.dates shouldBe listOf(today)
    }

    /**
     * Archiving is not "no data" -- the asset genuinely existed, it was just zeroed out later.
     * That history should not be erased by trim as if "the account hadn't started yet".
     */
    @Test
    fun `trimming does not erase real history after a zeroing snapshot`() {
        val data = PortfolioData(
            assets = listOf(asset(1)),
            snapshots = listOf(
                manual(1, 1, LocalDate(2026, 5, 20), 100_000_00),
                manual(2, 1, LocalDate(2026, 6, 15), 0), // sold and archived
            ),
            quotes = emptyList(),
            fxRates = emptyList(),
        )

        val series = PortfolioSeriesCalculator.buildSeries(
            data, Period.MONTH, cny, today, zone, pointCount = 3, trimBeforeFirstSnapshot = true,
        )

        // The first snapshot is in May, so all three points (May/June/July) should be kept -- June/July at 0 is genuinely the post-archive state, not something trimmed away
        series.points.map { it.netWorth } shouldBe listOf(
            Money(100_000_00),
            Money.ZERO,
            Money.ZERO,
        )
    }

    /** With trimming off, the behavior must be exactly the same as before -- the default must not silently change existing callers' semantics. */
    @Test
    fun `behavior without trimming matches the previous default`() {
        val data = PortfolioData(
            assets = listOf(asset(1)),
            snapshots = listOf(manual(1, 1, LocalDate(2026, 7, 10), 50_000_00)),
            quotes = emptyList(),
            fxRates = emptyList(),
        )

        val series = PortfolioSeriesCalculator.buildSeries(
            data, Period.MONTH, cny, today, zone, pointCount = 3,
        )

        series.points.map { it.netWorth } shouldBe listOf(Money.ZERO, Money.ZERO, Money(50_000_00))
    }

    /** With zero snapshots (zero assets), trimming is meaningless -- it must not crash, nor should it reduce the point count to 0. */
    @Test
    fun `trimming does not error out when there are zero assets`() {
        val series = PortfolioSeriesCalculator.buildSeries(
            PortfolioData.EMPTY, Period.MONTH, cny, today, zone, pointCount = 3, trimBeforeFirstSnapshot = true,
        )

        series.points.map { it.netWorth } shouldBe listOf(Money.ZERO, Money.ZERO, Money.ZERO)
    }

    @Test
    fun `historical quotes are taken as of that period, not the latest price`() {
        // Unit price was 100 in June, rising to 200 in July. The June point must be computed using 100, or the historical curve gets contaminated by today's price
        val stock = asset(1, AssetClass.EQUITY)
        val data = PortfolioData(
            assets = listOf(stock),
            snapshots = listOf(
                Snapshot.Quoted(
                    id = 1,
                    assetId = 1,
                    asOf = LocalDate(2026, 6, 1).endOfDayIn(zone),
                    quantity = Quantity.ofUnits(100),
                    quoteSymbol = "X",
                    recordedAt = Instant.fromEpochMilliseconds(0),
                ),
            ),
            quotes = listOf(
                Quote("X", "2026-06-01", UnitPrice.ofMajorUnits(100), cny, Instant.fromEpochMilliseconds(0)),
                Quote("X", "2026-07-01", UnitPrice.ofMajorUnits(200), cny, Instant.fromEpochMilliseconds(0)),
            ),
            fxRates = emptyList(),
        )

        val series = PortfolioSeriesCalculator.buildSeries(
            data, Period.MONTH, cny, today, zone, pointCount = 2,
        )

        // End of June: 100 shares x 100 yuan = 10,000; July: 100 shares x 200 yuan = 20,000
        series.points.map { it.netWorth } shouldBe listOf(Money(10_000_00), Money(20_000_00))
    }

    // ---------- Growth rate ----------

    @Test
    fun `the period growth rate is based on the previous and last points`() {
        val data = PortfolioData(
            assets = listOf(asset(1)),
            snapshots = listOf(
                manual(1, 1, LocalDate(2026, 5, 10), 100_000_00),
                manual(2, 1, LocalDate(2026, 7, 10), 110_000_00),
            ),
            quotes = emptyList(),
            fxRates = emptyList(),
        )

        val series = PortfolioSeriesCalculator.buildSeries(
            data, Period.MONTH, cny, today, zone, pointCount = 3,
        )

        series.growthBp shouldBe 1000  // +10%
    }

    @Test
    fun `the period growth rate is null when the starting value is zero`() {
        val data = PortfolioData(
            assets = listOf(asset(1)),
            snapshots = listOf(manual(1, 1, LocalDate(2026, 7, 10), 50_000_00)),
            quotes = emptyList(),
            fxRates = emptyList(),
        )

        val series = PortfolioSeriesCalculator.buildSeries(
            data, Period.MONTH, cny, today, zone, pointCount = 3,
        )

        series.growthBp.shouldBeNull()
    }

    @Test
    fun `the absolute change and the growth rate use the same pair of endpoints`() {
        // The top card shows both "+CNY 10,000" and "+10%" -- both must be computed from the
        // same period, otherwise the amount and percentage could contradict each other (one
        // could even say up while the other says down)
        val data = PortfolioData(
            assets = listOf(asset(1)),
            snapshots = listOf(
                manual(1, 1, LocalDate(2026, 5, 10), 100_000_00),
                manual(2, 1, LocalDate(2026, 7, 10), 110_000_00),
            ),
            quotes = emptyList(),
            fxRates = emptyList(),
        )

        val series = PortfolioSeriesCalculator.buildSeries(
            data, Period.MONTH, cny, today, zone, pointCount = 3,
        )

        series.growthAbsolute shouldBe Money(10_000_00)
        series.growthBp shouldBe 1000
        // The baseline date must be the sample point right before the last one; the UI writes
        // this as "compared to June 2026"
        series.baselineDate shouldBe series.dates[series.dates.lastIndex - 1]
    }

    @Test
    fun `the baseline moves forward as new periods are recorded`() {
        // Reported from real usage: first recorded in August, again in September, again in
        // October -- and the card still said "compared to August". The baseline has to follow
        // the latest period, otherwise the headline number silently becomes a cumulative
        // figure while the bar chart below it keeps showing period-over-period growth
        val data = PortfolioData(
            assets = listOf(asset(1)),
            snapshots = listOf(
                manual(1, 1, LocalDate(2026, 5, 10), 100_000_00),
                manual(2, 1, LocalDate(2026, 6, 10), 120_000_00),
                manual(3, 1, LocalDate(2026, 7, 10), 150_000_00),
            ),
            quotes = emptyList(),
            fxRates = emptyList(),
        )

        val series = PortfolioSeriesCalculator.buildSeries(
            data, Period.MONTH, cny, today, zone, pointCount = 3,
        )

        // May 31 = 100,000 / June 30 = 120,000 / July 28 = 150,000
        series.baselineDate shouldBe LocalDate(2026, 6, 30)
        series.growthAbsolute shouldBe Money(30_000_00)   // not 50,000, which is "since May"
        series.growthBp shouldBe 2500                     // +25%, not +50%
    }

    @Test
    fun `the baseline does not depend on the chart trimming toggle`() {
        // `trimBeforeFirstSnapshot` is a presentation switch for the chart -- it must not
        // redefine what the headline growth number is measured against
        val data = PortfolioData(
            assets = listOf(asset(1)),
            snapshots = listOf(
                manual(1, 1, LocalDate(2026, 6, 10), 120_000_00),
                manual(2, 1, LocalDate(2026, 7, 10), 150_000_00),
            ),
            quotes = emptyList(),
            fxRates = emptyList(),
        )

        fun build(trim: Boolean) = PortfolioSeriesCalculator.buildSeries(
            data, Period.MONTH, cny, today, zone,
            pointCount = 12, trimBeforeFirstSnapshot = trim,
        )

        build(trim = false).baselineDate shouldBe build(trim = true).baselineDate
        build(trim = false).growthAbsolute shouldBe build(trim = true).growthAbsolute
    }

    @Test
    fun `with only one sample point, there is neither an absolute change nor a baseline date`() {
        // The "starting value is 0" test above covers "growth rate is meaningless"; this one
        // covers "there isn't even a starting point" -- this is the state right after the
        // first snapshot plus trim, and the UI should show "only recorded once" instead of CNY 0
        val data = PortfolioData(
            assets = listOf(asset(1)),
            snapshots = listOf(manual(1, 1, LocalDate(2026, 7, 10), 50_000_00)),
            quotes = emptyList(),
            fxRates = emptyList(),
        )

        val series = PortfolioSeriesCalculator.buildSeries(
            data, Period.MONTH, cny, today, zone,
            pointCount = 3, trimBeforeFirstSnapshot = true,
        )

        series.points shouldHaveSize 1
        series.growthAbsolute.shouldBeNull()
        series.baselineDate.shouldBeNull()
    }

    @Test
    fun `when valuation coverage differs between the two endpoints, neither the absolute change nor the growth rate is given`() {
        // Reproduced on a real device (USD view): August had no historical exchange rate ->
        // the asset for that point couldn't be valued at all, so net worth computed as 0.
        // Using it as the starting point, the top card would read "+$13,097.04 - compared to
        // August 2026" -- which reads as "earned an entire fortune from zero this month",
        // pure fiction
        val early = NetWorthPoint(
            asOf = LocalDate(2026, 8, 31).endOfDayIn(zone),
            baseCurrency = "USD",
            totalAssets = Money.ZERO,
            totalLiabilities = Money.ZERO,
            unpricedAssetIds = listOf(1L),
        )
        val late = NetWorthPoint(
            asOf = LocalDate(2026, 9, 4).endOfDayIn(zone),
            baseCurrency = "USD",
            totalAssets = Money(14_883_00),
            totalLiabilities = Money(1_785_96),
        )
        val series = NetWorthSeries(
            period = Period.MONTH,
            baseCurrency = "USD",
            dates = listOf(LocalDate(2026, 8, 31), LocalDate(2026, 9, 4)),
            points = listOf(early, late),
        )

        series.hasBaseline shouldBe true   // there really are two points here, it's not "only recorded once"
        series.growthAbsolute.shouldBeNull()
        series.growthBp.shouldBeNull()
    }

    @Test
    fun `when the same asset is unpriced at both endpoints, an absolute change is given but not a percentage`() {
        // The scenario where the quote API is down: the house can't be valued at either end,
        // so the absolute change on the remaining part is genuine (comparing the same subset),
        // but the percentage's denominator is a net worth known to be understated -- it would
        // inflate the growth rate
        fun point(day: LocalDate, assets: Long) = NetWorthPoint(
            asOf = day.endOfDayIn(zone),
            baseCurrency = cny,
            totalAssets = Money(assets),
            totalLiabilities = Money.ZERO,
            unpricedAssetIds = listOf(9L),
        )
        val series = NetWorthSeries(
            period = Period.MONTH,
            baseCurrency = cny,
            dates = listOf(LocalDate(2026, 6, 30), LocalDate(2026, 7, 28)),
            points = listOf(point(LocalDate(2026, 6, 30), 100_000_00), point(today, 110_000_00)),
        )

        series.growthAbsolute shouldBe Money(10_000_00)
        series.growthBp.shouldBeNull()
    }

    // ---------- Data freshness ----------

    @Test
    fun `the last recorded date is the latest snapshot across all assets`() {
        val data = PortfolioData(
            assets = listOf(asset(1), asset(2)),
            snapshots = listOf(
                manual(1, 1, LocalDate(2026, 5, 10), 100_000_00),
                manual(2, 2, LocalDate(2026, 7, 20), 20_000_00),
                manual(3, 1, LocalDate(2026, 6, 30), 105_000_00),
            ),
            quotes = emptyList(),
            fxRates = emptyList(),
        )

        PortfolioSeriesCalculator.lastRecordedDate(data, zone) shouldBe LocalDate(2026, 7, 20)
    }

    @Test
    fun `an archived asset's zeroing snapshot does not count as the last recorded date`() {
        // Archiving appends a 0-value snapshot. Treating it as the "last recorded date" would
        // let a single archive action disguise the whole portfolio as freshly updated --
        // when in fact the user hasn't touched any valuation in months
        val active = asset(1)
        val archived = asset(2).copy(archivedAt = Instant.fromEpochMilliseconds(1))
        val data = PortfolioData(
            assets = listOf(active, archived),
            snapshots = listOf(
                manual(1, 1, LocalDate(2026, 5, 10), 100_000_00),
                manual(2, 2, LocalDate(2026, 7, 20), 0),   // the zeroing snapshot from archiving
            ),
            quotes = emptyList(),
            fxRates = emptyList(),
        )

        PortfolioSeriesCalculator.lastRecordedDate(data, zone) shouldBe LocalDate(2026, 5, 10)
    }

    @Test
    fun `the last recorded date is null when there are no snapshots at all`() {
        val data = PortfolioData(
            assets = listOf(asset(1)),
            snapshots = emptyList(),
            quotes = emptyList(),
            fxRates = emptyList(),
        )

        PortfolioSeriesCalculator.lastRecordedDate(data, zone).shouldBeNull()
    }

    // ---------- Allocation view ----------

    @Test
    fun `the current allocation uses today's data with liability attribution netted out`() {
        val house = asset(1, AssetClass.ALTERNATIVE)
        val mortgage = asset(2, AssetClass.ALTERNATIVE, liability = true)
        val cash = asset(3, AssetClass.LIQUID)

        val data = PortfolioData(
            assets = listOf(house, mortgage, cash),
            snapshots = listOf(
                manual(1, 1, LocalDate(2026, 7, 1), 3_000_000_00),
                manual(2, 2, LocalDate(2026, 7, 1), 2_000_000_00),
                manual(3, 3, LocalDate(2026, 7, 1), 1_000_000_00),
            ),
            quotes = emptyList(),
            fxRates = emptyList(),
        )

        val view = PortfolioSeriesCalculator.currentAllocation(data, cny, today, zone, null)

        view.netWorth shouldBe Money(2_000_000_00)
        view.shareBp(AssetClass.ALTERNATIVE) shouldBe 5000
        view.shareBp(AssetClass.LIQUID) shouldBe 5000
    }

    // ---------- Time series by class ----------

    /**
     * The two series are toggled between on the same page (via a single switch), so the x-axis
     * **must line up point for point**.
     *
     * Computing sample dates separately for each doesn't work: if even one trimming criterion
     * is written slightly differently, the two charts will be off by a step, and on screen
     * that misalignment shows up only as "the numbers look a bit odd", which is hard to trace
     * back to mismatched sample points.
     */
    @Test
    fun `the by-class series' sample dates exactly match the net worth series'`() {
        val data = PortfolioData(
            assets = listOf(asset(1)),
            snapshots = listOf(manual(1, 1, LocalDate(2026, 6, 10), 50_000_00)),
            quotes = emptyList(),
            fxRates = emptyList(),
        )

        Period.entries.forEach { period ->
            listOf(false, true).forEach { trim ->
                val net = PortfolioSeriesCalculator.buildSeries(
                    data, period, cny, today, zone, pointCount = 5, trimBeforeFirstSnapshot = trim,
                )
                val byClass = PortfolioSeriesCalculator.buildAllocationSeries(
                    data, period, cny, today, zone, pointCount = 5, trimBeforeFirstSnapshot = trim,
                )
                byClass.dates shouldBe net.dates
                byClass.points shouldHaveSize net.points.size
            }
        }
    }

    /** The carry-forward semantics apply to the by-class series too: a month with no new snapshot carries forward the last valuation, not 0. */
    @Test
    fun `the by-class series also carries forward the last valuation`() {
        val data = PortfolioData(
            assets = listOf(asset(1, AssetClass.EQUITY)),
            snapshots = listOf(manual(1, 1, LocalDate(2026, 5, 20), 80_000_00)),
            quotes = emptyList(),
            fxRates = emptyList(),
        )

        val series = PortfolioSeriesCalculator.buildAllocationSeries(
            data, Period.MONTH, cny, today, zone, pointCount = 3,
        )

        series.netExposures(AssetClass.EQUITY) shouldBe listOf(
            Money(80_000_00),
            Money(80_000_00),
            Money(80_000_00),
        )
        // A class with no exposure gives 0, not a missing entry -- that segment on the chart is just 0 height
        series.netExposures(AssetClass.PROTECTION) shouldBe listOf(Money.ZERO, Money.ZERO, Money.ZERO)
    }

    /**
     * The sum of the segments must equal that point's `AllocationView.netWorth`.
     *
     * The bar's height is the sum of its segments, while the growth rate label on top is
     * computed from the total -- if even one of these uses a different convention (say, the
     * total omits liabilities), the bar and the percentage above it will contradict each other.
     */
    @Test
    fun `the sum across classes equals that point's allocation-basis net worth`() {
        val house = asset(1, AssetClass.ALTERNATIVE)
        val mortgage = asset(2, AssetClass.ALTERNATIVE, liability = true)
        val cash = asset(3, AssetClass.LIQUID)
        val data = PortfolioData(
            assets = listOf(house, mortgage, cash),
            snapshots = listOf(
                manual(1, 1, LocalDate(2026, 6, 1), 3_000_000_00),
                manual(2, 2, LocalDate(2026, 6, 1), 2_000_000_00),
                manual(3, 3, LocalDate(2026, 6, 1), 1_000_000_00),
            ),
            quotes = emptyList(),
            fxRates = emptyList(),
        )

        val series = PortfolioSeriesCalculator.buildAllocationSeries(
            data, Period.MONTH, cny, today, zone, pointCount = 2,
        )

        series.totals(AssetClass.displayOrder) shouldBe series.points.map { it.netWorth }
        series.totals(AssetClass.displayOrder).last() shouldBe Money(2_000_000_00)
    }

    /** An asset excluded from allocation is counted in net worth but not in this series. The UI must explain that gap, so it's locked in here first. */
    @Test
    fun `an asset excluded from allocation is not in the by-class series`() {
        val ownHome = asset(1, AssetClass.ALTERNATIVE).copy(includeInAllocation = false)
        val cash = asset(2, AssetClass.LIQUID)
        val data = PortfolioData(
            assets = listOf(ownHome, cash),
            snapshots = listOf(
                manual(1, 1, LocalDate(2026, 6, 1), 5_000_000_00),
                manual(2, 2, LocalDate(2026, 6, 1), 100_000_00),
            ),
            quotes = emptyList(),
            fxRates = emptyList(),
        )

        val byClass = PortfolioSeriesCalculator.buildAllocationSeries(
            data, Period.MONTH, cny, today, zone, pointCount = 2,
        )
        val net = PortfolioSeriesCalculator.buildSeries(
            data, Period.MONTH, cny, today, zone, pointCount = 2,
        )

        byClass.netExposures(AssetClass.ALTERNATIVE).last() shouldBe Money.ZERO
        byClass.totals(AssetClass.displayOrder).last() shouldBe Money(100_000_00)
        net.points.last().netWorth shouldBe Money(5_100_000_00)
    }

    /**
     * A negative exposure must be detectable.
     *
     * A stacked area chart only works because cumulative values are monotonically increasing;
     * a negative segment throws the layering out of alignment. The trend chart must check this
     * flag first, and fall back to independent per-class curves when it's tripped. Failing to
     * detect it results in a chart that looks fine but is actually drawn wrong.
     */
    @Test
    fun `a negative exposure is detected when a car loan exceeds the car's value`() {
        val car = asset(1, AssetClass.ALTERNATIVE)
        val loan = asset(2, AssetClass.ALTERNATIVE, liability = true)
        val data = PortfolioData(
            assets = listOf(car, loan),
            snapshots = listOf(
                manual(1, 1, LocalDate(2026, 6, 1), 100_000_00),
                manual(2, 2, LocalDate(2026, 6, 1), 150_000_00),
            ),
            quotes = emptyList(),
            fxRates = emptyList(),
        )

        val series = PortfolioSeriesCalculator.buildAllocationSeries(
            data, Period.MONTH, cny, today, zone, pointCount = 2,
        )

        series.netExposures(AssetClass.ALTERNATIVE).last() shouldBe Money(-50_000_00)
        series.hasNegativeExposure(AssetClass.displayOrder) shouldBe true
        // Looking only at classes with no negative exposure, the stacked area is still safe -- the check must follow "the visible classes"
        series.hasNegativeExposure(listOf(AssetClass.LIQUID, AssetClass.EQUITY)) shouldBe false
    }
}
