package com.boomsset.domain

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.time.Instant

/**
 * 行情新鲜度。
 *
 * 这块的意义：腾讯接口是**非官方**的，随时可能失效。失效时 `RateRefresher` 什么都不写，
 * 结转规则会继续用最后一条已存行情 —— 数值不会错，但用户会以为看的是当前市价。
 * domain.md 因此要求「UI 上要能看出来这个价格是 3 天前的」。
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
    fun `当天的行情不算过期`() {
        val row = stockWithQuote("2026-07-30")
        row.priceAgeDays shouldBe 0
        row.isPriceStale shouldBe false
    }

    @Test
    fun `三天内不算过期 因为周末本来就没有行情`() {
        // 阈值取 3 天而不是 1 天：周末+节假日会让 1 天在每个周一之前都误报。
        // 这里不建交易日历 —— 维护各市场节假日表的成本远高于收益。
        stockWithQuote("2026-07-28").isPriceStale shouldBe false   // 2 天前
        stockWithQuote("2026-07-27").isPriceStale shouldBe false   // 3 天前
    }

    @Test
    fun `超过三天算过期`() {
        val row = stockWithQuote("2026-07-25")
        row.priceAgeDays shouldBe 5
        row.isPriceStale shouldBe true
    }

    @Test
    fun `没有行情时不是过期而是没有`() {
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
        // 「没有行情」和「行情过期」是两件事 —— 前者要引导用户手填，后者只是提醒
        row.isPriceStale shouldBe false
        row.isUnpriced shouldBe true
    }

    @Test
    fun `MANUAL 资产没有行情概念`() {
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
    fun `非法日期字符串返回null而不是崩溃或猜`() {
        daysBetween("not-a-date", today).shouldBeNull()
        daysBetween("2026-07-25", today) shouldBe 5
    }
}
