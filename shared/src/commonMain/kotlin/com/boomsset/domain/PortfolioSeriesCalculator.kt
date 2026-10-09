package com.boomsset.domain

import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * All raw data.
 *
 * **Design choice: load everything at once, compute in memory.** This app is
 * local-first, single-user, with a few dozen assets and a few hundred to a few thousand
 * snapshots — loading everything is far simpler than "one query per sample point", and
 * it keeps all the derived logic as pure, testable functions.
 *
 * If snapshot volume ever reaches tens of thousands (e.g. switching to automatic daily
 * snapshots), this should change to range-based queries + SQL-side aggregation. At that
 * point it would visibly slow down, not silently produce wrong results.
 */
data class PortfolioData(
    val assets: List<Asset>,
    val snapshots: List<Snapshot>,
    val quotes: List<Quote>,
    val fxRates: List<FxRate>,
) {
    val isEmpty: Boolean get() = assets.isEmpty()

    companion object {
        val EMPTY = PortfolioData(emptyList(), emptyList(), emptyList(), emptyList())
    }
}

/** Net worth time series, points in ascending time order. */
data class NetWorthSeries(
    val period: Period,
    val baseCurrency: String,
    val dates: List<LocalDate>,
    val points: List<NetWorthPoint>,
) {
    val latest: NetWorthPoint? get() = points.lastOrNull()
    val earliest: NetWorthPoint? get() = points.firstOrNull()

    /**
     * The two endpoints the overview compares: **the previous sample point and the
     * latest one**. Null when there is only one point (no comparable starting point).
     *
     * The three derived values (growth rate, change amount, baseline date) all take
     * their endpoints from here — judging "are there enough points" separately in each
     * place would eventually diverge, producing a self-contradictory combination in the
     * UI like "growth —" alongside "compared to August 2026".
     *
     * **It used to be `earliest to latest`, i.e. the whole window, and that was wrong in
     * two ways** (reported from real usage: first recorded in August, recorded again in
     * September, recorded again in October — and the card still said "compared to
     * August"):
     *
     * 1. The baseline never moved forward. In the monthly view the user reads this card
     *    as "how did I do this month"; anchoring it to the oldest point in the window
     *    turns it into a cumulative figure that grows stale-looking by one period every
     *    month, while the bar chart right below it — whose growth labels are
     *    period-over-period — showed a completely different percentage for the very same
     *    latest bar. Two numbers, one screen, different bases, no label saying so.
     * 2. The baseline silently depended on a **display** toggle: with
     *    `trimBeforeFirstSnapshot` off, "earliest" is 11 periods ago; with it on, it's
     *    the first snapshot. A chart-trimming switch must not redefine what the headline
     *    growth number means.
     *
     * Now it's the previous sample point, which matches the bar chart's labels exactly
     * (see `growthLabels` in NetWorthChart.kt) and stays stable regardless of trimming.
     */
    private val endpoints: Pair<NetWorthPoint, NetWorthPoint>?
        get() {
            val to = latest ?: return null
            val from = points.getOrNull(points.lastIndex - 1) ?: return null
            return from to to
        }

    /** Whether there is a comparable starting point (point count ≥ 2). The UI must distinguish "recorded only once" from "recorded, but can't be computed". */
    val hasBaseline: Boolean get() = endpoints != null

    /**
     * The net worth growth rate versus the previous sample point (basis points). Note
     * this **includes new contributions**, it is not an investment return rate.
     *
     * Returns null if either endpoint has an asset that can't be valued: the
     * percentage's denominator is net worth itself, and a net worth that is **known to
     * be understated** as the denominator would proportionally amplify the gain (if the
     * house can't be valued, a stock gaining 10,000 might show as +10%).
     */
    val growthBp: Int?
        get() = endpoints
            ?.takeIf { (from, to) -> !from.hasUnpriced && !to.hasUnpriced }
            ?.let { (from, to) -> PortfolioCalculator.netWorthGrowthBp(from, to) }

    /**
     * The **absolute** net worth change versus the previous sample point. Takes the same
     * pair of endpoints as [growthBp], one absolute, one relative.
     *
     * Needed because a percentage alone doesn't convey magnitude: "+2%" could mean two
     * thousand or two hundred thousand, and what the user actually remembers is the
     * amount.
     *
     * **Returns null when the two endpoints' valuation coverage differs.** This isn't
     * being overly cautious, it's something hit in real usage: switching the display
     * currency to USD, the day in August had no historical exchange rate → that point's
     * assets couldn't be valued at all and net worth computed as 0; using it as the
     * starting point turned "net worth growth" into "this month grew from 0 to my
     * entire net worth" (+$13,097 compared to August) — purely fabricated good news.
     * When coverage is the same, the difference is still meaningful (comparing the same
     * subset), so what's checked here is "did coverage change", not "are there any
     * assets that can't be valued".
     */
    val growthAbsolute: Money?
        get() = endpoints
            ?.takeIf { (from, to) -> from.unpricedAssetIds.toSet() == to.unpricedAssetIds.toSet() }
            ?.let { (from, to) -> to.netWorth - from.netWorth }

    /**
     * The date [growthBp] / [growthAbsolute] are computed **relative to**.
     *
     * The UI must display it: the same "net worth growth +2%" compares against
     * completely different starting points depending on month/quarter/year view — not
     * stating the baseline means not stating what this number actually is.
     *
     * Indexed off [dates] rather than off the point itself, because `NetWorthPoint.asOf`
     * is an end-of-day instant and the label wants the sample **date**. [dates] and
     * [points] are built from the same list in [PortfolioSeriesCalculator], so the
     * indices line up.
     */
    val baselineDate: LocalDate?
        get() = endpoints?.let { dates.getOrNull(dates.lastIndex - 1) }
}

/**
 * A time series of net exposure broken down by top-level class, points in ascending
 * time order.
 *
 * Each point is a complete [AllocationView], meaning **the convention is identical to
 * the allocation screen**: the numerator is each class's net exposure (that class's
 * assets − liabilities attributed to that class), counting only assets with
 * `includeInAllocation = true`. This matters — it means this series' total is **not
 * equal to** [NetWorthSeries]'s net worth (the latter includes assets excluded from
 * allocation). Whenever the two numbers appear side by side the UI must explain this,
 * otherwise the user will think one of them is wrong.
 *
 * The sample dates are the same batch used for [NetWorthSeries] (computed by the same
 * private helper in [PortfolioSeriesCalculator]), so the two charts' x-axes line up
 * point-for-point and can be switched between on the same page.
 */
data class AllocationSeries(
    val period: Period,
    val baseCurrency: String,
    val dates: List<LocalDate>,
    val points: List<AllocationView>,
) {
    val latest: AllocationView? get() = points.lastOrNull()

    /** The net exposure for one class at each sample point, in the same order as [dates]. Missing values default to 0 — this class genuinely had no exposure at that time. */
    fun netExposures(assetClass: AssetClass): List<Money> =
        points.map { it.exposures[assetClass]?.netExposure ?: Money.ZERO }

    /**
     * The sum of net exposure across these classes at each sample point.
     *
     * The height of the bar in the chart is this sum, so "how much did it rise compared
     * to the previous bar" must be computed from it — after the user unchecks some
     * classes and the bar gets shorter, computing the growth rate from the full total
     * would no longer match the bar in front of them.
     */
    fun totals(classes: Collection<AssetClass>): List<Money> =
        points.map { point ->
            classes.fold(Money.ZERO) { acc, assetClass ->
                acc + (point.exposures[assetClass]?.netExposure ?: Money.ZERO)
            }
        }

    /**
     * Whether **any sample point** among these classes had a negative exposure (that
     * class's liabilities exceeded its assets).
     *
     * A stacked area chart is assembled from "cumulative value + opaque fill drawn over
     * the previous layer", which requires every segment to be non-negative; once a
     * negative value shows up the cumulative sum is no longer monotonic and the
     * resulting layers are wrong. So the trend chart must check this first, and falls
     * back to independent per-class lines when it hits, rather than drawing a chart that
     * looks normal but has its layers misaligned.
     */
    fun hasNegativeExposure(classes: Collection<AssetClass>): Boolean =
        classes.any { assetClass -> netExposures(assetClass).any { it.minorUnits < 0L } }
}

/**
 * Folds raw data into time series. Pure functions.
 */
object PortfolioSeriesCalculator {

    /**
     * @param today today in the user's local time zone. Passed in by the caller rather
     *   than reading Clock internally, so this stays testable.
     * @param pointCount number of sample points. 12 months / 12 quarters / 12 years.
     * @param trimBeforeFirstSnapshot drop the sample points that fall "before the first snapshot".
     *
     * Defaults to `false`, keeping [periodSampleDates]'s original "always take N
     * periods" behavior unchanged — a test ("time points before asset creation aren't
     * counted") explicitly depends on the carry-forward convention that "periods before
     * an asset was created show up as zero-valued points"; trimming shouldn't rewrite
     * that convention, it only **decides at the presentation layer whether to draw
     * those points**.
     *
     * When passed `true`: real-usage feedback was "when viewing by quarter/year, the
     * account has only been used for a few months, and a large leading stretch is all
     * zeros, filling up the chart". The trim rule drops sample points whose **end time
     * is earlier than the earliest snapshot's time** — not points where "net worth is
     * 0", because a genuine 0 after the account clears out (e.g. all assets archived)
     * shouldn't be erased as "no data" — that's part of the history.
     *
     * Always keeps at least the last point (the period containing today), even if it
     * too is earlier than the earliest snapshot — the empty state is judged separately
     * by `hasAssets`, so there's no need to also handle "not a single point left" here.
     */
    fun buildSeries(
        data: PortfolioData,
        period: Period,
        baseCurrency: String,
        today: LocalDate,
        zone: TimeZone,
        pointCount: Int = 12,
        trimBeforeFirstSnapshot: Boolean = false,
    ): NetWorthSeries {
        val dates = sampleDates(data, period, today, zone, pointCount, trimBeforeFirstSnapshot)
        val points = dates.map { date ->
            val at = date.endOfDayIn(zone)
            PortfolioCalculator.netWorth(
                asOf = at,
                assets = data.assets,
                snapshots = data.latestSnapshotsAt(at),
                context = data.valuationContextAt(date, baseCurrency),
            )
        }
        return NetWorthSeries(period, baseCurrency, dates, points)
    }

    /**
     * The time series broken down by top-level class, used by the net worth screen's
     * "view by class" mode. Parameters have exactly the same meaning as [buildSeries].
     *
     * Each sample point directly reuses [PortfolioCalculator.allocation] — net
     * exposure, the `includeInAllocation` decision, and how the denominator is
     * computed can only have one implementation, otherwise the net worth screen and the
     * allocation screen would produce two different sets of percentages for the same
     * data.
     *
     * `target` is passed as null: target ratios are the allocation screen's concern;
     * this series only cares how each class's **amount** changes over time.
     */
    fun buildAllocationSeries(
        data: PortfolioData,
        period: Period,
        baseCurrency: String,
        today: LocalDate,
        zone: TimeZone,
        pointCount: Int = 12,
        trimBeforeFirstSnapshot: Boolean = false,
    ): AllocationSeries {
        val dates = sampleDates(data, period, today, zone, pointCount, trimBeforeFirstSnapshot)
        val points = dates.map { date ->
            val at = date.endOfDayIn(zone)
            PortfolioCalculator.allocation(
                asOf = at,
                assets = data.assets,
                snapshots = data.latestSnapshotsAt(at),
                context = data.valuationContextAt(date, baseCurrency),
                target = null,
            )
        }
        return AllocationSeries(period, baseCurrency, dates, points)
    }

    /**
     * Sample dates + trimming out "points before the first snapshot".
     *
     * [buildSeries] and [buildAllocationSeries] **must share this one implementation** —
     * the two series get switched between on the same page, and if each computed its
     * own sample dates, any divergence in how the trim rule is written would offset the
     * two charts' x-axes by one slot, and that kind of misalignment looks in the UI like
     * just "the numbers are a bit off", hard to trace back to mismatched sample points.
     */
    private fun sampleDates(
        data: PortfolioData,
        period: Period,
        today: LocalDate,
        zone: TimeZone,
        pointCount: Int,
        trimBeforeFirstSnapshot: Boolean,
    ): List<LocalDate> {
        val allDates = periodSampleDates(today, period, pointCount)
        val earliestSnapshot = data.snapshots.minOfOrNull { it.asOf }
        if (!trimBeforeFirstSnapshot || earliestSnapshot == null) return allDates
        val firstWithData = allDates.indexOfFirst { it.endOfDayIn(zone) >= earliestSnapshot }
        return if (firstWithData < 0) listOf(allDates.last())
        else allDates.subList(firstWithData, allDates.size)
    }

    /** The allocation view at the current point in time. */
    fun currentAllocation(
        data: PortfolioData,
        baseCurrency: String,
        today: LocalDate,
        zone: TimeZone,
        target: TargetAllocation?,
    ): AllocationView {
        val at = today.endOfDayIn(zone)
        return PortfolioCalculator.allocation(
            asOf = at,
            assets = data.assets,
            snapshots = data.latestSnapshotsAt(at),
            context = data.valuationContextAt(today, baseCurrency),
            target = target,
        )
    }

    /**
     * The valuation of each asset at the current point in time, used by the asset list.
     *
     * Excludes archived assets by default — their history is still part of the net
     * worth curve, but they shouldn't show up in a "what do I currently hold" list.
     */
    fun currentAssetValuations(
        data: PortfolioData,
        baseCurrency: String,
        today: LocalDate,
        zone: TimeZone,
        includeArchived: Boolean = false,
    ): List<AssetValuation> {
        val at = today.endOfDayIn(zone)
        val snapshots = data.latestSnapshotsAt(at)
        val context = data.valuationContextAt(today, baseCurrency)
        val counts = data.snapshots.groupingBy { it.assetId }.eachCount()

        return data.assets
            .filter { includeArchived || !it.isArchived }
            .map { asset ->
                val snapshot = snapshots[asset.id]
                val local = snapshot?.let { PortfolioCalculator.localValue(it, context.quotes) }
                val rate = context.rateTo(asset.currency)
                val quote = (snapshot as? Snapshot.Quoted)?.let { context.quotes[it.quoteSymbol] }
                AssetValuation(
                    asset = asset,
                    snapshot = snapshot,
                    localValue = local,
                    baseValue = if (local != null && rate != null) {
                        runCatching { rate.convert(local) }.getOrNull()
                    } else {
                        null
                    },
                    pnl = snapshot?.let {
                        PortfolioCalculator.profitAndLoss(it, context.quotes)
                    },
                    snapshotCount = counts[asset.id] ?: 0,
                    quote = quote,
                    priceAgeDays = quote?.let { daysBetween(it.asOfDay, today) },
                )
            }
    }

    /** Portfolio-level unrealized P&L at the current point in time. */
    fun currentProfitAndLoss(
        data: PortfolioData,
        baseCurrency: String,
        today: LocalDate,
        zone: TimeZone,
    ): PortfolioPnL {
        val at = today.endOfDayIn(zone)
        return PortfolioCalculator.portfolioProfitAndLoss(
            assets = data.assets,
            snapshots = data.latestSnapshotsAt(at),
            context = data.valuationContextAt(today, baseCurrency),
        )
    }

    /**
     * The date of the most recently recorded snapshot (user's local time zone).
     *
     * This app **records snapshots, not transactions**, so "how fresh is the data"
     * directly determines whether the net worth figure at the top can be trusted — a
     * net worth that hasn't been updated in three months looks identical to one updated
     * today; without showing the date, the user has no way to tell whether they're
     * looking at a stale number.
     *
     * Only looks at **unarchived** assets: archiving appends a zero-value snapshot,
     * which is an "end of maintenance" action — treating it as the "most recent record"
     * would let a single archive action disguise the whole portfolio as just updated.
     *
     * @return returns null when there isn't a single snapshot (a new user).
     */
    fun lastRecordedDate(data: PortfolioData, zone: TimeZone): LocalDate? {
        val activeIds = data.assets.filterNot { it.isArchived }.map { it.id }.toSet()
        return data.snapshots
            .filter { it.assetId in activeIds }
            .maxOfOrNull { it.asOf }
            ?.toLocalDateTime(zone)
            ?.date
    }
}

/**
 * The number of days between two ISO dates. Returns null on a parse failure rather than
 * guessing.
 */
internal fun daysBetween(fromIsoDay: String, to: LocalDate): Int? {
    val from = runCatching { LocalDate.parse(fromIsoDay) }.getOrNull() ?: return null
    return from.daysUntil(to)
}

/**
 * The instant "at the end of this day" — taken as 1 millisecond before the start of the
 * next day, so that snapshots recorded that same day are still matched by `<=`.
 */
internal fun LocalDate.endOfDayIn(zone: TimeZone): Instant =
    plus(1, DateTimeUnit.DAY).atStartOfDayIn(zone) - kotlin.time.Duration.parse("1ms")

/**
 * Carry-forward rule: for each asset, take the **most recent** snapshot before [at].
 * When there are multiple at the same point in time (a historical correction), take the
 * one with the largest id, i.e. the one recorded last.
 */
internal fun PortfolioData.latestSnapshotsAt(at: Instant): Map<Long, Snapshot> =
    snapshots
        .filter { it.asOf <= at }
        .groupBy { it.assetId }
        .mapValues { (_, list) -> list.maxWith(compareBy({ it.asOf }, { it.id })) }

/**
 * Builds the valuation context for this date — both quotes and exchange rates take the
 * **most recent one as of that day or before**.
 *
 * Uses the historical exchange rate rather than today's, otherwise rate fluctuations
 * would pollute the historical curve.
 * `asOfDay` is an ISO string, whose lexicographic order matches time order, so it can be
 * compared directly.
 */
internal fun PortfolioData.valuationContextAt(
    day: LocalDate,
    baseCurrency: String,
): ValuationContext {
    val dayKey = day.toString()

    val latestQuotes = quotes
        .filter { it.asOfDay <= dayKey }
        .groupBy { it.symbol }
        .mapValues { (_, list) -> list.maxBy { it.asOfDay } }

    val latestRates = fxRates
        .filter { it.asOfDay <= dayKey }
        .groupBy { ValuationContext.rateKey(it.base, it.quote) }
        .mapValues { (_, list) -> list.maxBy { it.asOfDay }.rate }

    return ValuationContext(
        baseCurrency = baseCurrency,
        quotes = latestQuotes,
        rates = latestRates,
    )
}
