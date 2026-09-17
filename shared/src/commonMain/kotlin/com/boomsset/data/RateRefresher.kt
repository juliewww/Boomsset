package com.boomsset.data

import com.boomsset.domain.FxRate
import com.boomsset.domain.PortfolioData
import com.boomsset.network.FxRateSource
import com.boomsset.network.QuoteSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

/**
 * Refreshes FX rates when the app opens.
 *
 * Only writes to the `fx_rate` table, **never `snapshot`** — this is the direct
 * embodiment of the "Quote ≠ Snapshot" split in docs/domain.md. Refreshing happens
 * frequently (every app open); writing snapshots would bloat the table and pollute the
 * historical curve.
 *
 * Upserted by day, so opening the app ten times in one day still leaves only one record.
 */
class RateRefresher(
    private val repository: PortfolioRepository,
    private val fxSource: FxRateSource,
    private val quoteSource: QuoteSource,
    private val dispatcher: CoroutineDispatcher,
    private val clock: Clock = Clock.System,
    private val zone: TimeZone = TimeZone.currentSystemDefault(),
) {

    /** (currency, date) pairs already attempted within this process. Prevents infinite retries on failure — see notes below. */
    private val attempted = mutableSetOf<String>()
    private val mutex = Mutex()

    /**
     * Backfills the currency pairs actually used by the current holdings, **including history**.
     *
     * Only fetches the currency pairs that are **needed** (asset currency → base currency),
     * not a full pull: fewer requests, and less information exposed to the service provider.
     *
     * ## Why history must be backfilled, not just today's rate
     *
     * The net worth curve plots 12 points at once, and each point must be converted using
     * **the rate at that point in time** (domain.md: converting history using today's rate
     * pollutes the curve). This used to only fetch a single day (today), so after switching
     * the view currency to USD, every historical point except the latest one couldn't find
     * a rate, the asset was judged "unable to value", and net worth came out as 0 — on a
     * real device this showed up as August's bar being 0, with the top card reading "net
     * worth growth +$13,097 vs. August 2026" (as if the entire fortune had been earned from
     * zero).
     *
     * The interval backfilled is "**the span during which this currency was actually
     * held**" (see [requiredRanges]), not a fixed N-day lookback: a currency held since
     * three years ago gets three years backfilled; one added yesterday only gets
     * yesterday-to-today.
     *
     * Failures don't throw — a failed price fetch should fall back to a stale rate rather
     * than crashing the UI.
     *
     * ## Why "attempted" is tracked
     *
     * The caller re-invokes this method whenever "the set of needed currencies changes"
     * (otherwise, adding a new foreign-currency asset would never get its rate fetched —
     * a bug found through real-device testing). But writing to fx_rate makes the portfolio
     * stream re-emit, which in turn triggers another call. On success the condition is
     * already satisfied so it stops; **on failure it would retry forever**. [attempted]
     * records each (currency pair, range) so it's only tried once, letting even failures
     * converge. The range is part of the key, so backfilling an older snapshot (lengthening
     * the range) or simply reaching the next day will trigger a retry.
     *
     * Trade-off: after one failure, the next retry only happens on the next app launch.
     * That's acceptable for daily-updated data like FX rates, and on failure it falls back
     * to a stale rate rather than having no data at all.
     */
    suspend fun refreshForHoldings(baseCurrency: String): RefreshResult =
        withContext(dispatcher) {
            val data = runCatching { repository.observePortfolio().first() }
                .getOrNull() ?: return@withContext RefreshResult(0, 0)

            val today = clock.now().toLocalDateTime(zone).date
            val required = requiredRanges(data, baseCurrency, today)

            val toFetch = mutex.withLock {
                required.filter { (currency, range) ->
                    attempted.add(attemptKey(currency, baseCurrency, range))
                }
            }
            if (toFetch.isEmpty()) return@withContext RefreshResult(0, 0)

            var written = 0
            var failed = 0
            toFetch.forEach { (currency, range) ->
                // Don't re-request what's already stored — backfilling is a one-time thing; after that it's only a day or two behind each day
                val missing = missingRange(data.fxRates, currency, baseCurrency, range)
                    ?: return@forEach
                val rates = fxSource.fetchRange(
                    from = currency,
                    to = baseCurrency,
                    start = missing.start,
                    end = missing.end,
                )
                if (rates.isNotEmpty()) {
                    repository.upsertFxRates(rates)
                    written++
                } else {
                    failed++
                }
            }
            RefreshResult(written = written, failed = failed)
        }

    private fun attemptKey(from: String, to: String, range: DayRange) =
        "$from>$to@${range.start}..${range.end}"

    /**
     * The date range each currency pair needs to cover.
     *
     * The start is **the date of that currency's earliest snapshot**: at earlier points in
     * time these assets didn't yet exist, they're not part of net worth, and no rate is needed.
     *
     * The end comes in two flavors: as long as at least one **unarchived** asset uses this
     * currency, the end is today; once all are archived, the end is **the day of the last
     * snapshot** — after archiving, the asset no longer contributes to current net worth,
     * but it still existed at historical points in time, so rates are still needed for
     * that span.
     *
     * Assets that are archived and have zero snapshots are skipped outright: they have no
     * value at any point in time.
     */
    internal fun requiredRanges(
        data: PortfolioData,
        baseCurrency: String,
        today: LocalDate,
    ): Map<String, DayRange> {
        val byCurrency = data.assets.groupBy { it.currency }
        return byCurrency.mapNotNull { (currency, assets) ->
            if (currency == baseCurrency) return@mapNotNull null   // 1:1, no lookup needed
            val ids = assets.map { it.id }.toSet()
            val days = data.snapshots
                .filter { it.assetId in ids }
                .map { it.asOf.toLocalDateTime(zone).date }
            val hasActive = assets.any { !it.isArchived }
            val end = when {
                hasActive -> today
                days.isEmpty() -> return@mapNotNull null
                else -> days.max()
            }
            val start = days.minOrNull() ?: today
            if (start > end) return@mapNotNull null
            currency to DayRange(start, end)
        }.toMap()
    }

    /**
     * Which portion of the range is still missing.
     *
     * - None at all → the whole range is needed
     * - The earliest record is later than the range's start → history was never backfilled,
     *   redo the whole range (`INSERT OR REPLACE`, so a duplicate write is harmless)
     * - Only the most recent few days are missing → resume from the last day already stored
     *   (**starting from that day itself**, so that if the range lands on a weekend the
     *   service can still return the previous business day's rate)
     * - Already covers the end of the range → null, no request is sent at all
     *
     * Only checks the first/last day, not whether there are gaps in between: gaps can only
     * come from a previous partial failure, and the carry-forward rule is "take the closest
     * one before that point in time" — the consequence of a gap is using a slightly stale
     * rate, not being unable to value at all.
     */
    private fun missingRange(
        stored: List<FxRate>,
        from: String,
        to: String,
        need: DayRange,
    ): DayRange? {
        // asOfDay is an ISO string, so lexicographic order matches chronological order
        val days = stored.filter { it.base == from && it.quote == to }.map { it.asOfDay }
        val earliest = days.minOrNull() ?: return need
        if (earliest > need.start.toString()) return need
        val latest = days.max()
        if (latest >= need.end.toString()) return null
        val resumeFrom = runCatching { LocalDate.parse(latest) }.getOrNull() ?: return need
        return DayRange(resumeFrom, need.end)
    }

    /**
     * Refreshes the quote symbols used by QUOTED snapshots in the holdings.
     *
     * Same convergence strategy as FX rates: each (symbol, date) is only tried once, to
     * prevent "write quote → data stream re-emits → refresh again" from becoming an
     * infinite loop on failure.
     *
     * The symbol is taken from **the snapshot's quoteSymbol**, not the asset's default —
     * an asset that's been delisted and converted to MANUAL still needs quotes for its
     * historical snapshots, even though the symbol on the asset itself has been cleared.
     */
    suspend fun refreshQuotes(): RefreshResult = withContext(dispatcher) {
        val data = runCatching { repository.observePortfolio().first() }
            .getOrNull() ?: return@withContext RefreshResult(0, 0)

        val today = clock.now().toLocalDateTime(zone).date
        val activeIds = data.assets.filter { !it.isArchived }.map { it.id }.toSet()
        val symbols = data.snapshots
            .filterIsInstance<com.boomsset.domain.Snapshot.Quoted>()
            .filter { it.assetId in activeIds }
            .map { it.quoteSymbol }
            .toSet()

        val toFetch = mutex.withLock {
            symbols.filter { attempted.add("quote:$it@$today") }
        }.toSet()
        if (toFetch.isEmpty()) return@withContext RefreshResult(0, 0)

        val quotes = quoteSource.fetch(toFetch, today)
        quotes.forEach { repository.upsertQuote(it) }
        // Symbols that couldn't be fetched don't appear in the return value; the difference is the failure count
        RefreshResult(written = quotes.size, failed = toFetch.size - quotes.size)
    }

    /**
     * Manual retry, called by the UI's "retry valuation" button.
     *
     * [refreshForHoldings]/[refreshQuotes] are only automatically re-invoked by the
     * ViewModel when "the set of needed currencies/symbols changes" (see both classes'
     * doc comments) — if the very first attempt fails (a one-off network hiccup, a slow
     * first request on cold start, etc.) and holdings and currencies don't change after
     * that, no event will ever trigger another attempt; `attempted` will remember this
     * failure and block retries until the next launch, leaving the user with no recourse
     * in the meantime — unlike quotes, which have a manual price override as a fallback
     * (see QuoteSource), FX rates have no manual override entry point at all.
     *
     * Clearing [attempted] and immediately re-fetching works around this "must wait for
     * the next launch" limitation.
     */
    suspend fun retryAll(baseCurrency: String): RefreshResult = withContext(dispatcher) {
        mutex.withLock { attempted.clear() }
        val rates = refreshForHoldings(baseCurrency)
        val quotes = refreshQuotes()
        RefreshResult(written = rates.written + quotes.written, failed = rates.failed + quotes.failed)
    }
}

/** A closed date interval, inclusive of both start and end. */
data class DayRange(val start: LocalDate, val end: LocalDate)

/** [written] counts the number of **currency pairs/symbols**, not the number of rows written. */
data class RefreshResult(val written: Int, val failed: Int) {
    val hasFailures: Boolean get() = failed > 0
}
