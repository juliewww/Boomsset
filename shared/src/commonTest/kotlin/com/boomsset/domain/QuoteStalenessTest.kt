package com.boomsset.domain

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.time.Instant

/**
 * Quote freshness.
 *
 * Why this matters: the Tencent API is **unofficial** and can stop working at any time.
 * When it does, `RateRefresher` writes nothing, and the carry-forward rule keeps using the
 * last stored quote -- the number itself won't be wrong, but the user will think they're
 * looking at the current market price. domain.md therefore requires that "the UI must make
 * it visible that this price is 3 days old."
 */
class QuoteStalenessTest {

    private val zone = TimeZone.UTC
    private val today = LocalDate(2026, 7, 30)
    private val cny = "CNY"
    private val epoch = Instant.fromEpochMilliseconds(0)

    private fun stockWithQuote(quoteDay: String): AssetValuation {
        val data = PortfolioData(
            assets = listOf(
                Asset(
                    id = 1, name = "茅台", assetClass = AssetClass.EQUITY, subtypeId = 1,
                    currency = cny, defaultValuationMode = ValuationMode.QUOTED,
                ),
            ),
            snapshots = listOf(
                Snapshot.Quoted(
                    id = 1, assetId = 1, asOf = LocalDate(2026, 7, 1).endOfDayIn(zone),
                    quantity = Quantity.ofUnits(100), quoteSymbol = "sh600519",
                    recordedAt = epoch,
                ),
            ),
            quotes = listOf(
                Quote("sh600519", quoteDay, UnitPrice.ofMajorUnits(1300), cny, epoch),
            ),
            fxRates = emptyList(),
        )
        return PortfolioSeriesCalculator.currentAssetValuations(data, cny, today, zone).single()
    }

    @Test
    fun `a same-day quote does not count as stale`() {
        val row = stockWithQuote("2026-07-30")
        row.priceAgeDays shouldBe 0
        row.isPriceStale shouldBe false
    }

    @Test
    fun `within three days does not count as stale, since weekends have no quotes anyway`() {
        // The threshold is 3 days, not 1: weekends and holidays would make a 1-day threshold
        // falsely trigger before every Monday. We don't build a trading calendar here --
        // maintaining per-market holiday tables costs far more than it's worth.
        stockWithQuote("2026-07-28").isPriceStale shouldBe false   // 2 days ago
        stockWithQuote("2026-07-27").isPriceStale shouldBe false   // 3 days ago
    }

    @Test
    fun `more than three days counts as stale`() {
        val row = stockWithQuote("2026-07-25")
        row.priceAgeDays shouldBe 5
        row.isPriceStale shouldBe true
    }

    @Test
    fun `having no quote is not stale, it's absent`() {
        val data = PortfolioData(
            assets = listOf(
                Asset(
                    id = 1, name = "茅台", assetClass = AssetClass.EQUITY, subtypeId = 1,
                    currency = cny, defaultValuationMode = ValuationMode.QUOTED,
                ),
            ),
            snapshots = listOf(
                Snapshot.Quoted(
                    id = 1, assetId = 1, asOf = LocalDate(2026, 7, 1).endOfDayIn(zone),
                    quantity = Quantity.ofUnits(100), quoteSymbol = "sh600519",
                    recordedAt = epoch,
                ),
            ),
            quotes = emptyList(),
            fxRates = emptyList(),
        )
        val row = PortfolioSeriesCalculator.currentAssetValuations(data, cny, today, zone).single()

        row.quote.shouldBeNull()
        row.priceAgeDays.shouldBeNull()
        // "no quote" and "stale quote" are two different things -- the former should prompt
        // the user to fill in a value manually, the latter is just a heads-up
        row.isPriceStale shouldBe false
        row.isUnpriced shouldBe true
    }

    @Test
    fun `MANUAL assets have no concept of a quote`() {
        val data = PortfolioData(
            assets = listOf(
                Asset(
                    id = 1, name = "房子", assetClass = AssetClass.ALTERNATIVE, subtypeId = 1,
                    currency = cny, defaultValuationMode = ValuationMode.MANUAL,
                ),
            ),
            snapshots = listOf(
                Snapshot.Manual(
                    id = 1, assetId = 1, asOf = LocalDate(2026, 7, 1).endOfDayIn(zone),
                    value = Money(100_000_00), recordedAt = epoch,
                ),
            ),
            quotes = emptyList(), fxRates = emptyList(),
        )
        val row = PortfolioSeriesCalculator.currentAssetValuations(data, cny, today, zone).single()
        row.quote.shouldBeNull()
        row.isPriceStale shouldBe false
    }

    @Test
    fun `an invalid date string returns null instead of crashing or guessing`() {
        daysBetween("not-a-date", today).shouldBeNull()
        daysBetween("2026-07-25", today) shouldBe 5
    }
}
