package com.boomsset.data

import com.boomsset.domain.Asset
import com.boomsset.domain.AssetClass
import com.boomsset.domain.AssetSubtype
import com.boomsset.domain.ExchangeRate
import com.boomsset.domain.FxRate
import com.boomsset.domain.Money
import com.boomsset.domain.PortfolioData
import com.boomsset.domain.Quantity
import com.boomsset.domain.Quote
import com.boomsset.domain.UnitPrice
import com.boomsset.domain.TargetAllocation
import com.boomsset.domain.ValuationMode
import com.boomsset.network.FxRateSource
import com.boomsset.network.QuoteSource
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.test.Test
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class RateRefresherTest {

    private val epoch = Instant.fromEpochMilliseconds(1_785_000_000_000)
    private val zone = kotlinx.datetime.TimeZone.UTC

    /** What the refresher considers "today", matching the fake clock below. */
    private val today = epoch.toLocalDateTime(zone).date

    private fun manual(id: Long, assetId: Long, day: LocalDate, valueMinor: Long = 100_00) =
        com.boomsset.domain.Snapshot.Manual(
            id = id,
            assetId = assetId,
            asOf = day.atStartOfDayIn(zone),
            value = Money(valueMinor),
            recordedAt = epoch,
        )

    private fun rate(from: String, to: String, day: String) =
        FxRate(from, to, day, ExchangeRate(700_000_000))

    private fun asset(id: Long, currency: String, archived: Boolean = false) = Asset(
        id = id,
        name = "a$id",
        assetClass = AssetClass.LIQUID,
        subtypeId = 1,
        currency = currency,
        defaultValuationMode = ValuationMode.MANUAL,
        archivedAt = if (archived) epoch else null,
    )

    /** A hand-written fake, not a mocking framework -- see docs/stack.md for the note on Mokkery. */
    private class FakeRepository(private val data: PortfolioData) : PortfolioRepository {
        val writtenRates = mutableListOf<FxRate>()

        override fun observePortfolio(): Flow<PortfolioData> = flowOf(data)
        override fun observeActiveTarget(): Flow<TargetAllocation?> = flowOf(null)
        override fun observeSubtypes(): Flow<List<AssetSubtype>> = flowOf(emptyList())
        override suspend fun createAsset(
            name: String, assetClass: AssetClass, subtypeId: Long, currency: String,
            isLiability: Boolean, includeInAllocation: Boolean, mode: ValuationMode,
            quoteSymbol: String?, initialValue: Money?, initialQuantity: Quantity?,
            costBasis: Money?,
        ): Long = 0
        override suspend fun appendManualSnapshot(
            assetId: Long, value: Money, costBasis: Money?, asOf: Instant?,
        ) {}
        override suspend fun appendQuotedSnapshot(
            assetId: Long, quantity: Quantity, quoteSymbol: String, costBasis: Money?, asOf: Instant?,
        ) {}
        override suspend fun archiveAsset(assetId: Long) {}
        override suspend fun unarchiveAsset(assetId: Long) {}
        override suspend fun updateAssetMeta(
            assetId: Long, name: String, assetClass: AssetClass, subtypeId: Long,
            currency: String, includeInAllocation: Boolean,
            defaultValuationMode: ValuationMode, defaultQuoteSymbol: String?,
        ) {}
        override suspend fun createSubtype(
            name: String, assetClass: AssetClass, defaultValuationMode: ValuationMode,
        ): Long = 0
        override suspend fun upsertFxRate(rate: FxRate) { writtenRates += rate }
        override suspend fun upsertFxRates(rates: List<FxRate>) { writtenRates += rates }
        override suspend fun upsertQuote(quote: Quote) {}
        override fun observeAllocations(): Flow<List<TargetAllocation>> = flowOf(emptyList())
        override suspend fun setActiveAllocation(id: Long) {}
        override suspend fun saveAllocationTargets(id: Long, targetsBp: Map<AssetClass, Int>) {}
        override suspend fun createAllocation(name: String, targetsBp: Map<AssetClass, Int>): Long = 0
        override suspend fun renameAllocation(id: Long, name: String) {}
        override suspend fun deleteAllocation(id: Long) {}
    }

    /**
     * A fake exchange rate source. Records **which currency was requested for which date
     * range** -- the whole point of this change is "is the range correct", and recording only
     * the currency wouldn't catch a broken historical backfill.
     *
     * The return value enumerates the entire range day by day (the real endpoint skips
     * weekends, which is not simulated here -- shifting a weekend back to the prior business
     * day is [com.boomsset.network.FrankfurterFxRateSource]'s responsibility, which has its
     * own response-level tests).
     */
    private class FakeFxSource(
        private val failFor: Set<String> = emptySet(),
    ) : FxRateSource {
        val requested = mutableListOf<String>()
        val ranges = mutableListOf<Triple<String, LocalDate, LocalDate>>()

        override suspend fun fetchRange(
            from: String,
            to: String,
            start: LocalDate,
            end: LocalDate,
        ): List<FxRate> {
            requested += from
            ranges += Triple(from, start, end)
            if (from in failFor) return emptyList()
            return generateSequence(start) { it.plus(1, DateTimeUnit.DAY) }
                .takeWhile { it <= end }
                .map { FxRate(from, to, it.toString(), ExchangeRate(700_000_000)) }
                .toList()
        }
    }

    /** A fake quote source. Returns a fixed price per symbol by default; specific symbols can be marked as unavailable. */
    private class FakeQuoteSource(private val missing: Set<String> = emptySet()) : QuoteSource {
        val requested = mutableListOf<String>()
        override suspend fun fetch(symbols: Set<String>, on: LocalDate): List<Quote> {
            requested += symbols
            return symbols.filterNot { it in missing }.map {
                Quote(it, on.toString(), UnitPrice.ofMajorUnits(100), "CNY", Instant.fromEpochMilliseconds(0))
            }
        }
    }

    private fun refresher(
        repo: PortfolioRepository,
        fx: FxRateSource,
        quotes: QuoteSource = FakeQuoteSource(),
    ) = RateRefresher(
        repository = repo,
        fxSource = fx,
        quoteSource = quotes,
        dispatcher = UnconfinedTestDispatcher(),
        clock = object : kotlin.time.Clock {
            override fun now() = epoch
        },
        zone = zone,
    )

    @Test
    fun `only foreign currencies actually held are queried, not a full pull`() = runTest {
        val data = PortfolioData(
            assets = listOf(asset(1, "CNY"), asset(2, "USD"), asset(3, "HKD")),
            snapshots = emptyList(), quotes = emptyList(), fxRates = emptyList(),
        )
        val fx = FakeFxSource()
        val repo = FakeRepository(data)

        refresher(repo, fx).refreshForHoldings("CNY")

        // The base currency CNY itself is not queried (it's 1:1); only USD and HKD are
        fx.requested.sorted() shouldContainExactly listOf("HKD", "USD")
        repo.writtenRates.size shouldBe 2
    }

    // ---------- Historical range ----------

    @Test
    fun `backfills to the date of the earliest snapshot, not just today`() = runTest {
        // This is the core of this fix. Every historical point on the net worth curve must be
        // converted using the exchange rate **at that time**. Pulling only today's rate would
        // leave every point but the latest without a rate -> the asset gets judged "cannot be
        // valued" -> net worth at those points computes as 0 (on a real device this showed up
        // as the August bar being 0)
        val data = PortfolioData(
            assets = listOf(asset(1, "USD")),
            snapshots = listOf(
                manual(1, 1, LocalDate(2026, 5, 10)),
                manual(2, 1, LocalDate(2026, 6, 30)),
            ),
            quotes = emptyList(), fxRates = emptyList(),
        )
        val fx = FakeFxSource()

        refresher(FakeRepository(data), fx).refreshForHoldings("CNY")

        fx.ranges.single() shouldBe Triple("USD", LocalDate(2026, 5, 10), today)
    }

    @Test
    fun `earlier points are not backfilled, because the asset did not exist yet`() = runTest {
        // The starting point is the first snapshot, not "always look back 12 months" -- an asset added yesterday only gets backfilled from yesterday to today
        val data = PortfolioData(
            assets = listOf(asset(1, "USD")),
            snapshots = listOf(manual(1, 1, today.minus(1, DateTimeUnit.DAY))),
            quotes = emptyList(), fxRates = emptyList(),
        )
        val fx = FakeFxSource()

        refresher(FakeRepository(data), fx).refreshForHoldings("CNY")

        fx.ranges.single() shouldBe Triple("USD", today.minus(1, DateTimeUnit.DAY), today)
    }

    @Test
    fun `a fully archived currency is only backfilled up to its last snapshot's date`() = runTest {
        // Once archived, it no longer contributes to current net worth, so today's rate is of
        // no use to it; but at historical points it still existed, and rates for that range
        // are still needed -- otherwise those points would revert to 0 when looking back
        val data = PortfolioData(
            assets = listOf(asset(1, "USD", archived = true)),
            snapshots = listOf(
                manual(1, 1, LocalDate(2026, 5, 10)),
                manual(2, 1, LocalDate(2026, 6, 30), valueMinor = 0),   // the zeroing snapshot from archiving
            ),
            quotes = emptyList(), fxRates = emptyList(),
        )
        val fx = FakeFxSource()

        refresher(FakeRepository(data), fx).refreshForHoldings("CNY")

        fx.ranges.single() shouldBe
            Triple("USD", LocalDate(2026, 5, 10), LocalDate(2026, 6, 30))
    }

    @Test
    fun `when history has already been backfilled, only the most recent days are fetched`() = runTest {
        // Backfilling is a one-time thing. Opening the app again the next day, only a day or
        // two are missing between yesterday and today -- it should not re-pull a three-year range
        val data = PortfolioData(
            assets = listOf(asset(1, "USD")),
            snapshots = listOf(manual(1, 1, LocalDate(2026, 5, 10))),
            quotes = emptyList(),
            fxRates = listOf(
                rate("USD", "CNY", "2026-05-08"),   // earlier than the range start, meaning history was already backfilled
                rate("USD", "CNY", "2026-07-20"),
            ),
        )
        val fx = FakeFxSource()

        refresher(FakeRepository(data), fx).refreshForHoldings("CNY")

        // Starts from the existing last day itself (not the day after it) -- that day might be
        // a Friday, and if today is the weekend, only starting from Friday would get a quote
        fx.ranges.single() shouldBe Triple("USD", LocalDate(2026, 7, 20), today)
    }

    @Test
    fun `not a single request is sent once coverage already reaches today`() = runTest {
        val data = PortfolioData(
            assets = listOf(asset(1, "USD")),
            snapshots = listOf(manual(1, 1, LocalDate(2026, 5, 10))),
            quotes = emptyList(),
            fxRates = listOf(
                rate("USD", "CNY", "2026-05-09"),
                rate("USD", "CNY", today.toString()),
            ),
        )
        val fx = FakeFxSource()

        val result = refresher(FakeRepository(data), fx).refreshForHoldings("CNY")

        fx.ranges.isEmpty() shouldBe true
        result.written shouldBe 0
        result.failed shouldBe 0
    }

    @Test
    fun `when only the most recent rate exists with no history, the entire range gets backfilled`() = runTest {
        // Data saved before this fix looks exactly like this: just one entry for today.
        // Existing users upgrading must be able to have their history backfilled -- it must
        // not be skipped just because "the latest one is already there"
        val data = PortfolioData(
            assets = listOf(asset(1, "USD")),
            snapshots = listOf(manual(1, 1, LocalDate(2026, 5, 10))),
            quotes = emptyList(),
            fxRates = listOf(rate("USD", "CNY", today.toString())),
        )
        val fx = FakeFxSource()

        refresher(FakeRepository(data), fx).refreshForHoldings("CNY")

        fx.ranges.single() shouldBe Triple("USD", LocalDate(2026, 5, 10), today)
    }

    @Test
    fun `an archived asset's currency is no longer queried`() = runTest {
        val data = PortfolioData(
            assets = listOf(asset(1, "USD"), asset(2, "JPY", archived = true)),
            snapshots = emptyList(), quotes = emptyList(), fxRates = emptyList(),
        )
        val fx = FakeFxSource()

        refresher(FakeRepository(data), fx).refreshForHoldings("CNY")

        fx.requested shouldContainExactly listOf("USD")
    }

    @Test
    fun `a repeated currency is only queried once`() = runTest {
        val data = PortfolioData(
            assets = listOf(asset(1, "USD"), asset(2, "USD"), asset(3, "USD")),
            snapshots = emptyList(), quotes = emptyList(), fxRates = emptyList(),
        )
        val fx = FakeFxSource()

        refresher(FakeRepository(data), fx).refreshForHoldings("CNY")

        fx.requested shouldContainExactly listOf("USD")
    }

    @Test
    fun `a partial failure does not affect the rest, and the failure count is reported accurately`() = runTest {
        val data = PortfolioData(
            assets = listOf(asset(1, "USD"), asset(2, "TWD")),
            snapshots = emptyList(), quotes = emptyList(), fxRates = emptyList(),
        )
        // TWD isn't in the ECB's list, so it's bound to fail
        val fx = FakeFxSource(failFor = setOf("TWD"))
        val repo = FakeRepository(data)

        val result = refresher(repo, fx).refreshForHoldings("CNY")

        result.written shouldBe 1
        result.failed shouldBe 1
        result.hasFailures shouldBe true
        // The successful one is still written as usual -- one failure should not take down the whole refresh
        repo.writtenRates.single().base shouldBe "USD"
    }

    @Test
    fun `after switching the base currency, the new currency pair is queried`() = runTest {
        val data = PortfolioData(
            assets = listOf(asset(1, "CNY"), asset(2, "USD")),
            snapshots = emptyList(), quotes = emptyList(), fxRates = emptyList(),
        )
        val fx = FakeFxSource()

        // Once the base switches to USD, it should query CNY->USD instead
        refresher(FakeRepository(data), fx).refreshForHoldings("USD")

        fx.requested shouldContainExactly listOf("CNY")
    }

    /**
     * Regression test. Writing to fx_rate makes the portfolio flow re-emit, and the caller
     * triggers a refresh again -- without tracking "already attempted", a success can stop on
     * its own once the condition is met, but **a failure would retry indefinitely**.
     */
    @Test
    fun `the same currency on the same day is only requested once, even when called repeatedly`() = runTest {
        val data = PortfolioData(
            assets = listOf(asset(1, "USD")),
            snapshots = emptyList(), quotes = emptyList(), fxRates = emptyList(),
        )
        val fx = FakeFxSource()
        val r = refresher(FakeRepository(data), fx)

        r.refreshForHoldings("CNY")
        r.refreshForHoldings("CNY")
        r.refreshForHoldings("CNY")

        fx.requested shouldContainExactly listOf("USD")
    }

    @Test
    fun `after a failure, this session does not retry again, avoiding an infinite loop`() = runTest {
        val data = PortfolioData(
            assets = listOf(asset(1, "TWD")),
            snapshots = emptyList(), quotes = emptyList(), fxRates = emptyList(),
        )
        val fx = FakeFxSource(failFor = setOf("TWD"))
        val r = refresher(FakeRepository(data), fx)

        r.refreshForHoldings("CNY").failed shouldBe 1
        // A second call must not fire off another request -- otherwise a failing currency would flood the requests
        r.refreshForHoldings("CNY").failed shouldBe 0
        fx.requested shouldContainExactly listOf("TWD")
    }

    @Test
    fun `switching the base currency creates a new currency pair, so it requests again`() = runTest {
        val data = PortfolioData(
            assets = listOf(asset(1, "USD")),
            snapshots = emptyList(), quotes = emptyList(), fxRates = emptyList(),
        )
        val fx = FakeFxSource()
        val r = refresher(FakeRepository(data), fx)

        r.refreshForHoldings("CNY")
        r.refreshForHoldings("HKD")   // USD->HKD is a combination that hasn't been tried yet

        fx.requested shouldContainExactly listOf("USD", "USD")
    }

    // ---------- Quote refresh ----------

    @Test
    fun `the quote symbol is taken from the snapshot, not the asset's default`() = runTest {
        // An asset delisted and converted to MANUAL still needs quotes for its historical
        // QUOTED snapshots, even though the asset's defaultQuoteSymbol has been cleared
        val a = asset(1, "CNY").copy(defaultQuoteSymbol = null)
        val data = PortfolioData(
            assets = listOf(a),
            snapshots = listOf(
                com.boomsset.domain.Snapshot.Quoted(
                    id = 1, assetId = 1, asOf = epoch,
                    quantity = Quantity.ofUnits(100), quoteSymbol = "sh600519",
                    recordedAt = epoch,
                ),
            ),
            quotes = emptyList(), fxRates = emptyList(),
        )
        val q = FakeQuoteSource()
        val repo = FakeRepository(data)

        refresher(repo, FakeFxSource(), q).refreshQuotes()

        q.requested shouldContainExactly listOf("sh600519")
    }

    @Test
    fun `the same symbol on the same day is only requested once`() = runTest {
        val data = PortfolioData(
            assets = listOf(asset(1, "CNY")),
            snapshots = listOf(
                com.boomsset.domain.Snapshot.Quoted(
                    id = 1, assetId = 1, asOf = epoch,
                    quantity = Quantity.ofUnits(1), quoteSymbol = "sh600519",
                    recordedAt = epoch,
                ),
            ),
            quotes = emptyList(), fxRates = emptyList(),
        )
        val q = FakeQuoteSource()
        val r = refresher(FakeRepository(data), FakeFxSource(), q)

        r.refreshQuotes()
        r.refreshQuotes()

        q.requested shouldContainExactly listOf("sh600519")
    }

    @Test
    fun `a symbol that can't be fetched counts toward the failure count, instead of a zero price being written`() = runTest {
        val data = PortfolioData(
            assets = listOf(asset(1, "CNY")),
            snapshots = listOf(
                com.boomsset.domain.Snapshot.Quoted(
                    id = 1, assetId = 1, asOf = epoch,
                    quantity = Quantity.ofUnits(1), quoteSymbol = "shbad",
                    recordedAt = epoch,
                ),
            ),
            quotes = emptyList(), fxRates = emptyList(),
        )
        val result = refresher(
            FakeRepository(data), FakeFxSource(), FakeQuoteSource(missing = setOf("shbad")),
        ).refreshQuotes()

        result.written shouldBe 0
        result.failed shouldBe 1
    }

    @Test
    fun `no requests at all are sent when there are no foreign currency assets`() = runTest {
        val data = PortfolioData(
            assets = listOf(asset(1, "CNY")),
            snapshots = emptyList(), quotes = emptyList(), fxRates = emptyList(),
        )
        val fx = FakeFxSource()

        refresher(FakeRepository(data), fx).refreshForHoldings("CNY")

        fx.requested.isEmpty() shouldBe true
    }
}
