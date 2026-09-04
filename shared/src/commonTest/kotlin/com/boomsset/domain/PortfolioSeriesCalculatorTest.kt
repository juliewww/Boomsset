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

    // ---------- 裁剪"资产存在之前"的取样点 ----------

    /**
     * 回归测试。实际使用反馈：按年/按季看的时候，账号才用了几个月，
     * 请求的 12 个取样点里前面一大截全是资产还不存在时的 0 值 —— 图表被这些
     * "没有数据"的点占满，最近几个月反而挤在很小的一段里。
     *
     * `trimBeforeFirstSnapshot = true` 应当丢掉那些点，只留下有真实历史的部分。
     */
    @Test
    fun `裁剪时只保留第一条快照之后的取样点`() {
        val data = PortfolioData(
            assets = listOf(asset(1)),
            snapshots = listOf(manual(1, 1, LocalDate(2026, 7, 10), 50_000_00)),
            quotes = emptyList(),
            fxRates = emptyList(),
        )

        val series = PortfolioSeriesCalculator.buildSeries(
            data, Period.MONTH, cny, today, zone, pointCount = 3, trimBeforeFirstSnapshot = true,
        )

        // 不裁的话是 3 个点（5、6、7 月，前两个是 0）；裁剪后只剩 7 月这一个
        series.points.map { it.netWorth } shouldBe listOf(Money(50_000_00))
        series.dates shouldBe listOf(today)
    }

    /**
     * 归档不是"没有数据" —— 资产真实存在过，只是后来清零了。
     * 那段历史不该被 trim 当成"账户还没开始"抹掉。
     */
    @Test
    fun `裁剪不会抹掉归零之后的真实历史`() {
        val data = PortfolioData(
            assets = listOf(asset(1)),
            snapshots = listOf(
                manual(1, 1, LocalDate(2026, 5, 20), 100_000_00),
                manual(2, 1, LocalDate(2026, 6, 15), 0), // 卖出归档
            ),
            quotes = emptyList(),
            fxRates = emptyList(),
        )

        val series = PortfolioSeriesCalculator.buildSeries(
            data, Period.MONTH, cny, today, zone, pointCount = 3, trimBeforeFirstSnapshot = true,
        )

        // 第一条快照在 5 月，5/6/7 三个点都该保留 —— 6、7 月是 0 是真实归档后的状态，不是被裁掉了
        series.points.map { it.netWorth } shouldBe listOf(
            Money(100_000_00),
            Money.ZERO,
            Money.ZERO,
        )
    }

    /** 不开裁剪时行为必须和以前完全一样 —— 默认值不能悄悄改变现有调用方的语义。 */
    @Test
    fun `不裁剪时行为和默认一致`() {
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

    /** 一条快照都没有（零资产）时裁剪没有意义，不该崩、也不该把点数削成 0。 */
    @Test
    fun `零资产时裁剪不报错`() {
        val series = PortfolioSeriesCalculator.buildSeries(
            PortfolioData.EMPTY, Period.MONTH, cny, today, zone, pointCount = 3, trimBeforeFirstSnapshot = true,
        )

        series.points.map { it.netWorth } shouldBe listOf(Money.ZERO, Money.ZERO, Money.ZERO)
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
                Quote("X", "2026-06-01", UnitPrice.ofMajorUnits(100), cny, Instant.fromEpochMilliseconds(0)),
                Quote("X", "2026-07-01", UnitPrice.ofMajorUnits(200), cny, Instant.fromEpochMilliseconds(0)),
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

    @Test
    fun `变化额和增长率取同一对端点`() {
        // 顶部卡片同时显示 "+¥10,000" 和 "+10%" —— 两个必须是同一段区间算出来的，
        // 否则金额和百分比互相矛盾（一个说涨一个说跌都可能）
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
        // 基准日期必须是首个取样点，UI 上写成"相比 2026年5月"
        series.baselineDate shouldBe series.dates.first()
    }

    @Test
    fun `只有一个取样点时没有变化额也没有基准日期`() {
        // 期初为 0 那条测的是"增长率无意义"；这条测的是"连期初都不存在"——
        // 第一次记完快照 + trim 之后就是这个状态，UI 要显示"只有一次记录"而不是 ¥0
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
    fun `两端估值覆盖面不同时不给变化额也不给增长率`() {
        // 实机复现（USD 视图）：8 月那天没有历史汇率 → 那个点的资产整个估不出值、
        // 净值算成 0。拿它当期初，顶部卡片会写成"+$13,097.04 · 相比 2026年8月"——
        // 读起来像"这个月从零挣出了全部身家"，纯属虚构
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

        series.hasBaseline shouldBe true   // 点是有两个的，不是"只记过一次"
        series.growthAbsolute.shouldBeNull()
        series.growthBp.shouldBeNull()
    }

    @Test
    fun `两端都估不出同一项资产时给变化额但不给百分比`() {
        // 行情接口挂掉那种情况：房子两端都估不出，剩下部分的变化额是真实的
        // （比较的是同一个子集），但百分比的分母是个已知低估的净值 —— 会把涨幅放大
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

    // ---------- 数据新鲜度 ----------

    @Test
    fun `最近记录日期取全部资产里最新的那条快照`() {
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
    fun `已归档资产的归零快照不算最近记录`() {
        // 归档会追加一条 0 值快照。拿它当"最近记录"会让一次归档把整个组合
        // 伪装成刚更新过 —— 而用户其实好几个月没维护过任何估值了
        val active = asset(1)
        val archived = asset(2).copy(archivedAt = Instant.fromEpochMilliseconds(1))
        val data = PortfolioData(
            assets = listOf(active, archived),
            snapshots = listOf(
                manual(1, 1, LocalDate(2026, 5, 10), 100_000_00),
                manual(2, 2, LocalDate(2026, 7, 20), 0),   // 归档的归零快照
            ),
            quotes = emptyList(),
            fxRates = emptyList(),
        )

        PortfolioSeriesCalculator.lastRecordedDate(data, zone) shouldBe LocalDate(2026, 5, 10)
    }

    @Test
    fun `一条快照都没有时最近记录日期为null`() {
        val data = PortfolioData(
            assets = listOf(asset(1)),
            snapshots = emptyList(),
            quotes = emptyList(),
            fxRates = emptyList(),
        )

        PortfolioSeriesCalculator.lastRecordedDate(data, zone).shouldBeNull()
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
