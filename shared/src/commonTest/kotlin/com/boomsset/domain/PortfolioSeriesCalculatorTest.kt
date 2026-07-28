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

    // ---------- 取样日期 ----------

    @Test
    fun `按月取样最后一个点是今天而不是月末`() {
        // 当前周期还没结束，用未来的月末取样会得到和"现在"不符的净值
        val dates = periodSampleDates(today, Period.MONTH, count = 3)
        dates shouldHaveSize 3
        dates.last() shouldBe today
        dates[1] shouldBe LocalDate(2026, 6, 30)
        dates[0] shouldBe LocalDate(2026, 5, 31)
    }

    @Test
    fun `按季取样落在季度末`() {
        val dates = periodSampleDates(today, Period.QUARTER, count = 3)
        dates.last() shouldBe today                      // 2026 Q3 未结束
        dates[1] shouldBe LocalDate(2026, 6, 30)         // Q2
        dates[0] shouldBe LocalDate(2026, 3, 31)         // Q1
    }

    @Test
    fun `按年取样落在年末`() {
        val dates = periodSampleDates(today, Period.YEAR, count = 3)
        dates.last() shouldBe today
        dates[1] shouldBe LocalDate(2025, 12, 31)
        dates[0] shouldBe LocalDate(2024, 12, 31)
    }

    // ---------- 结转 ----------

    @Test
    fun `没有新快照的月份沿用上次估值`() {
        // 5 月录了一次 10 万，之后没再更新 —— 6 月和 7 月都应该是 10 万，不是 0
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
    fun `资产创建之前的时点不计入`() {
        // 7 月才录入的资产，5、6 月的净值应当是 0 —— 不是把它当成一直存在
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
    fun `归零快照让已归档资产停止贡献净值`() {
        // 归档时追加的 0 值快照，靠结转规则让该资产之后一直是 0
        val data = PortfolioData(
            assets = listOf(asset(1)),
            snapshots = listOf(
                manual(1, 1, LocalDate(2026, 5, 20), 100_000_00),
                manual(2, 1, LocalDate(2026, 6, 15), 0),   // 卖出归档
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

    @Test
    fun `历史行情按当期取值而不是用最新价`() {
        // 6 月单价 100，7 月涨到 200。6 月那个点必须用 100 算，否则历史曲线被今天的价格污染
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
                Quote("X", "2026-06-01", Money(100_00), cny, Instant.fromEpochMilliseconds(0)),
                Quote("X", "2026-07-01", Money(200_00), cny, Instant.fromEpochMilliseconds(0)),
            ),
            fxRates = emptyList(),
        )

        val series = PortfolioSeriesCalculator.buildSeries(
            data, Period.MONTH, cny, today, zone, pointCount = 2,
        )

        // 6 月末：100 股 × 100 元 = 1 万；7 月：100 股 × 200 元 = 2 万
        series.points.map { it.netWorth } shouldBe listOf(Money(10_000_00), Money(20_000_00))
    }

    // ---------- 增长率 ----------

    @Test
    fun `区间增长率基于首尾两点`() {
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
    fun `期初为零时区间增长率为null`() {
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

    // ---------- 配置视图 ----------

    @Test
    fun `当前配置用当天数据且负债归属抵扣`() {
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
}
