package com.boomsset.domain

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.time.Instant

/**
 * These tests target the rules in docs/domain.md about things that "don't error when written
 * wrong, they just silently compute the wrong answer."
 * Each test's name is the rule it locks in.
 */
class PortfolioCalculatorTest {

    private val t = Instant.fromEpochMilliseconds(1_700_000_000_000)
    private val cny = "CNY"

    private fun ctx(
        quotes: Map<String, Quote> = emptyMap(),
        rates: Map<String, ExchangeRate> = emptyMap(),
        base: String = cny,
    ) = ValuationContext(base, quotes, rates)

    /** priceMinor stays in "cents", converted internally to a scale-8 UnitPrice, keeping existing expected values unchanged. */
    private fun quote(symbol: String, priceMinor: Long) =
        Quote(symbol, "2026-07-28", UnitPrice(priceMinor * 1_000_000L), cny, t)

    private fun asset(
        id: Long,
        assetClass: AssetClass,
        isLiability: Boolean = false,
        currency: String = cny,
        includeInAllocation: Boolean = true,
    ) = Asset(
        id = id,
        name = "asset-$id",
        assetClass = assetClass,
        subtypeId = 1,
        currency = currency,
        isLiability = isLiability,
        includeInAllocation = includeInAllocation,
        defaultValuationMode = ValuationMode.MANUAL,
    )

    private fun manual(assetId: Long, valueMinor: Long, costMinor: Long? = null) =
        Snapshot.Manual(
            id = assetId * 100,
            assetId = assetId,
            asOf = t,
            value = Money(valueMinor),
            costBasisMinor = costMinor?.let { Money(it) },
            recordedAt = t,
        )

    private fun quoted(assetId: Long, units: Long, symbol: String, costMinor: Long? = null) =
        Snapshot.Quoted(
            id = assetId * 100,
            assetId = assetId,
            asOf = t,
            quantity = Quantity.ofUnits(units),
            quoteSymbol = symbol,
            costBasisMinor = costMinor?.let { Money(it) },
            recordedAt = t,
        )

    // ---------- The valuation branch looks at the snapshot, not the asset ----------

    @Test
    fun `after delisting converts to MANUAL, historical QUOTED snapshots are still valued by share count`() {
        // The asset's current default mode is already MANUAL (delisted), but this historical
        // snapshot is Quoted. If we branched on asset.defaultValuationMode, we'd try to read
        // a `value` field that doesn't exist on a Quoted snapshot, and historical net worth
        // would break. This is one of the silent errors called out by name in domain.md.
        val delisted = asset(1, AssetClass.EQUITY).copy(
            defaultValuationMode = ValuationMode.MANUAL,
            defaultQuoteSymbol = null,
        )
        val historical = quoted(1, units = 100, symbol = "600519")

        val value = PortfolioCalculator.localValue(
            historical,
            mapOf("600519" to quote("600519", 150_000)),
        )

        value shouldBe Money(15_000_000)  // 100 shares x 1500 yuan
    }

    @Test
    fun `the snapshot carries its own quoteSymbol, so it can still be valued after the asset's symbol is cleared`() {
        val converted = asset(1, AssetClass.EQUITY).copy(defaultQuoteSymbol = null)
        val historical = quoted(1, units = 10, symbol = "OLD_TICKER")

        PortfolioCalculator.netWorth(
            t,
            listOf(converted),
            mapOf(1L to historical),
            ctx(quotes = mapOf("OLD_TICKER" to quote("OLD_TICKER", 10_000))),
        ).netWorth shouldBe Money(100_000)  // 10 shares x 100.00 yuan = 1000.00 yuan
    }

    // ---------- A missing quote must not be treated as 0 ----------

    @Test
    fun `when a quote is missing, the asset is flagged as unpriced instead of counted as zero`() {
        val stock = asset(1, AssetClass.EQUITY)
        val cash = asset(2, AssetClass.LIQUID)

        val point = PortfolioCalculator.netWorth(
            t,
            listOf(stock, cash),
            mapOf(1L to quoted(1, 100, "NO_QUOTE"), 2L to manual(2, 500_00)),
            ctx(),  // no quotes at all
        )

        // The stock isn't counted as 0 -- it's listed separately, and net worth reflects only what can be valued
        point.unpricedAssetIds shouldContainExactly listOf(1L)
        point.hasUnpriced shouldBe true
        point.netWorth shouldBe Money(500_00)
    }

    // ---------- Allocation ratio: numerator is net exposure, denominator is net worth ----------

    @Test
    fun `after netting out attributed liabilities, each class's share sums to 100 percent`() {
        // The example from domain.md: a 3M house + a 1M stock position, with a 2M mortgage -> net worth 2M.
        // Without netting out the attribution, the house would compute as 300/200 = 150%, summing to 200%, and the pie chart couldn't be drawn.
        val house = asset(1, AssetClass.ALTERNATIVE)
        val stock = asset(2, AssetClass.EQUITY)
        val mortgage = asset(3, AssetClass.ALTERNATIVE, isLiability = true)

        val view = PortfolioCalculator.allocation(
            t,
            listOf(house, stock, mortgage),
            mapOf(
                1L to manual(1, 3_000_000_00),
                2L to manual(2, 1_000_000_00),
                3L to manual(3, 2_000_000_00),
            ),
            ctx(),
        )

        view.netWorth shouldBe Money(2_000_000_00)
        // House net exposure = 3M - 2M = 1M -> 50%
        view.shareBp(AssetClass.ALTERNATIVE) shouldBe 5000
        view.shareBp(AssetClass.EQUITY) shouldBe 5000

        val total = AssetClass.displayOrder.sumOf { view.shareBp(it) ?: 0 }
        total shouldBe TargetAllocation.TOTAL_BP
    }

    @Test
    fun `a class's net exposure can be negative and is flagged explicitly`() {
        // Car worth 100,000 but car loan is 150,000
        val car = asset(1, AssetClass.ALTERNATIVE)
        val carLoan = asset(2, AssetClass.ALTERNATIVE, isLiability = true)
        val cash = asset(3, AssetClass.LIQUID)

        val view = PortfolioCalculator.allocation(
            t,
            listOf(car, carLoan, cash),
            mapOf(
                1L to manual(1, 100_000_00),
                2L to manual(2, 150_000_00),
                3L to manual(3, 200_000_00),
            ),
            ctx(),
        )

        view.exposures[AssetClass.ALTERNATIVE]!!.netExposure shouldBe Money(-50_000_00)
        view.hasNegativeExposure shouldBe true
        // The breakdown shows the true negative value; only the pie-chart layer clamps it to 0
        view.shareBp(AssetClass.ALTERNATIVE)!! shouldBe -3333
    }

    @Test
    fun `when net worth is negative, the ratio returns null instead of a nonsense number`() {
        val cash = asset(1, AssetClass.LIQUID)
        val debt = asset(2, AssetClass.LIQUID, isLiability = true)

        val view = PortfolioCalculator.allocation(
            t,
            listOf(cash, debt),
            mapOf(1L to manual(1, 10_000_00), 2L to manual(2, 50_000_00)),
            ctx(),
        )

        view.netWorth shouldBe Money(-40_000_00)
        view.shareBp(AssetClass.LIQUID).shouldBeNull()
    }

    @Test
    fun `an asset excluded from allocation is left out of both the numerator and denominator`() {
        // Once includeInAllocation is turned off for a primary residence, the ratios should reflect only the remaining assets
        val home = asset(1, AssetClass.ALTERNATIVE, includeInAllocation = false)
        val stock = asset(2, AssetClass.EQUITY)
        val cash = asset(3, AssetClass.LIQUID)

        val view = PortfolioCalculator.allocation(
            t,
            listOf(home, stock, cash),
            mapOf(
                1L to manual(1, 5_000_000_00),
                2L to manual(2, 300_000_00),
                3L to manual(3, 100_000_00),
            ),
            ctx(),
        )

        // The primary residence is not in the denominator
        view.netWorth shouldBe Money(400_000_00)
        view.shareBp(AssetClass.ALTERNATIVE) shouldBe 0
        view.shareBp(AssetClass.EQUITY) shouldBe 7500
        view.shareBp(AssetClass.LIQUID) shouldBe 2500
    }

    // ---------- Use the exchange rate at the time, not today's ----------

    @Test
    fun `a foreign currency asset is converted using the exchange rate passed in for that period`() {
        val usStock = asset(1, AssetClass.EQUITY, currency = "USD")
        val rate = ExchangeRate(7_00000000L)  // 1 USD = 7 CNY

        PortfolioCalculator.netWorth(
            t,
            listOf(usStock),
            mapOf(1L to manual(1, 100_00)),  // $100.00
            ctx(rates = mapOf(ValuationContext.rateKey("USD", cny) to rate)),
        ).netWorth shouldBe Money(700_00)  // CNY 700.00
    }

    @Test
    fun `a foreign currency asset missing an exchange rate is counted as unpriced, not converted 1-to-1`() {
        val hkStock = asset(1, AssetClass.EQUITY, currency = "HKD")

        val point = PortfolioCalculator.netWorth(
            t,
            listOf(hkStock),
            mapOf(1L to manual(1, 100_00)),
            ctx(),  // no HKD->CNY rate
        )

        point.unpricedAssetIds shouldContainExactly listOf(1L)
        point.netWorth shouldBe Money.ZERO
    }

    // ---------- Cost basis and gain/loss ----------

    @Test
    fun `a QUOTED asset can also have its gain-loss computed`() {
        // This is the rule that got misstated in a table somewhere: cost basis is independent of the valuation mode, QUOTED assets have a return rate too
        val pnl = PortfolioCalculator.profitAndLoss(
            quoted(1, units = 100, symbol = "600519", costMinor = 10_000_00),
            mapOf("600519" to quote("600519", 150_00)),
        )!!

        pnl.value shouldBe Money(15_000_00)
        pnl.absolute shouldBe Money(5_000_00)
        pnl.returnBp shouldBe 5000  // +50%
    }

    @Test
    fun `no cost basis filled in returns null instead of a zero-value gain-loss`() {
        // "no cost basis" and "cost basis of zero making gain-loss equal to market value" are two entirely different things
        PortfolioCalculator.profitAndLoss(manual(1, 500_00), emptyMap()).shouldBeNull()
    }

    @Test
    fun `portfolio gain-loss covers only assets with a cost basis and reports its coverage`() {
        val withCost = asset(1, AssetClass.EQUITY)
        val withoutCost = asset(2, AssetClass.ALTERNATIVE)
        val liability = asset(3, AssetClass.LIQUID, isLiability = true)

        val result = PortfolioCalculator.portfolioProfitAndLoss(
            listOf(withCost, withoutCost, liability),
            mapOf(
                1L to manual(1, 15_000_00, costMinor = 10_000_00),
                2L to manual(2, 5_000_000_00),                      // no cost basis
                3L to manual(3, 100_000_00, costMinor = 50_000_00), // liabilities don't count toward gain-loss
            ),
            ctx(),
        )

        result.coveredAssetIds shouldContainExactly listOf(1L)
        result.pnl.absolute shouldBe Money(5_000_00)
    }

    // ---------- Growth rate != return rate ----------

    @Test
    fun `net worth growth rate deliberately includes new contributions`() {
        // Starting net worth 100,000; a 10,000 salary deposit came in during the period; ending net worth 110,000.
        // The net worth growth rate shows +10% -- but that isn't "earnings".
        // This test locks in that semantics, to keep someone from "helpfully fixing" it into
        // a contribution-adjusted measure: the number with contributions stripped out is the
        // floating gain-loss rate, which is a different function.
        val from = NetWorthPoint(t, cny, Money(100_000_00), Money.ZERO)
        val to = NetWorthPoint(t, cny, Money(110_000_00), Money.ZERO)

        PortfolioCalculator.netWorthGrowthBp(from, to) shouldBe 1000  // +10%
    }

    @Test
    fun `when starting net worth is zero or negative, the growth rate is meaningless and returns null`() {
        val zero = NetWorthPoint(t, cny, Money.ZERO, Money.ZERO)
        val later = NetWorthPoint(t, cny, Money(10_000_00), Money.ZERO)

        PortfolioCalculator.netWorthGrowthBp(zero, later).shouldBeNull()
    }

    // ---------- Liability ratio ----------

    @Test
    fun `the liability ratio's denominator is total assets, not net worth`() {
        // A 3M house + a 2M mortgage: liability ratio = 200/300 = 66.67%.
        // Using net worth (1M) as the denominator would give 200% -- a number that means nothing
        val point = NetWorthPoint(t, cny, Money(3_000_000_00), Money(2_000_000_00))

        point.liabilityRatioBp shouldBe 6666  // 66.66% (truncated by integer basis-point division)
        point.netWorth shouldBe Money(1_000_000_00)
    }

    @Test
    fun `the liability ratio is zero when there are no liabilities`() {
        NetWorthPoint(t, cny, Money(100_000_00), Money.ZERO).liabilityRatioBp shouldBe 0
    }

    @Test
    fun `the liability ratio is null, not zero, when total assets are zero`() {
        // 0% would read as "no liabilities", but the actual fact here is "no assets to use as a denominator".
        // This is the state when there are only liabilities and no assets (e.g. only a credit card was recorded)
        NetWorthPoint(t, cny, Money.ZERO, Money(10_000_00)).liabilityRatioBp.shouldBeNull()
    }
}
