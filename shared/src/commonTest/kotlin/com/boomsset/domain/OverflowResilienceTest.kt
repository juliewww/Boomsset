package com.boomsset.domain

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.time.Instant

/**
 * Regression test: **extreme values must not crash the app.**
 *
 * On a real run, a single mistyped share count (100 million shares of Moutai) killed the app --
 * [FixedPoint]'s overflow guard threw an `ArithmeticException`, and the exception escaped all
 * the way from `localValue` up to the ViewModel's `combine`, taking down the whole process.
 *
 * Throwing is correct in itself (amounts must never silently wrap around), but **the exception
 * must never reach the UI**. The valuation layer now downgrades it to "cannot be valued":
 * neither a wrong number nor a crash.
 */
class OverflowResilienceTest {

    private val t = Instant.fromEpochMilliseconds(1_785_000_000_000)
    private val cny = "CNY"

    private fun asset(id: Long, currency: String = cny) = Asset(
        id = id,
        name = "asset-$id",
        assetClass = AssetClass.EQUITY,
        subtypeId = 1,
        currency = currency,
        defaultValuationMode = ValuationMode.QUOTED,
    )

    /** Reproduces the exact numbers from that real-run crash: 1.0012e8 shares x 1331.99 yuan. */
    private fun absurdSnapshot() = Snapshot.Quoted(
        id = 1,
        assetId = 1,
        asOf = t,
        quantity = Quantity(10_012_000_000_000_000L),
        quoteSymbol = "sh600519",
        recordedAt = t,
    )

    private val realPrice = mapOf(
        "sh600519" to Quote("sh600519", "2026-07-29", UnitPrice(133_199_000_000L), cny, t),
    )

    @Test
    fun `overflow returns null instead of throwing`() {
        PortfolioCalculator.localValue(absurdSnapshot(), realPrice).shouldBeNull()
    }

    @Test
    fun `net worth calculation lists an overflowing asset as unpriced instead of crashing`() {
        val point = PortfolioCalculator.netWorth(
            asOf = t,
            assets = listOf(asset(1)),
            snapshots = mapOf(1L to absurdSnapshot()),
            context = ValuationContext(cny, realPrice, emptyMap()),
        )

        point.unpricedAssetIds shouldContainExactly listOf(1L)
        point.netWorth shouldBe Money.ZERO
    }

    @Test
    fun `the allocation view skips an overflowing asset instead of crashing`() {
        val view = PortfolioCalculator.allocation(
            asOf = t,
            assets = listOf(asset(1)),
            snapshots = mapOf(1L to absurdSnapshot()),
            context = ValuationContext(cny, realPrice, emptyMap()),
        )
        view.netWorth shouldBe Money.ZERO
    }

    @Test
    fun `exchange rate conversion overflow does not crash either`() {
        // A huge local-currency amount x a huge exchange rate
        val huge = Snapshot.Manual(
            id = 1, assetId = 1, asOf = t,
            value = Money(Long.MAX_VALUE / 2), recordedAt = t,
        )
        val point = PortfolioCalculator.netWorth(
            asOf = t,
            assets = listOf(asset(1, currency = "USD")),
            snapshots = mapOf(1L to huge),
            context = ValuationContext(
                cny,
                emptyMap(),
                mapOf(ValuationContext.rateKey("USD", cny) to ExchangeRate(700_000_000_000L)),
            ),
        )
        point.unpricedAssetIds shouldContainExactly listOf(1L)
    }

    @Test
    fun `ordinary magnitudes are unaffected`() {
        // Confirms the guard above doesn't also swallow legitimate calculations
        val normal = Snapshot.Quoted(
            id = 1, assetId = 1, asOf = t,
            quantity = Quantity.ofUnits(100),
            quoteSymbol = "sh600519",
            recordedAt = t,
        )
        PortfolioCalculator.localValue(normal, realPrice) shouldBe Money(133_199_00)
    }
}
