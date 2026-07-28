package com.boomsset.domain

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.time.Instant

/**
 * 这些测试对着 docs/domain.md 里那几条「写错了不报错、只是静默算错」的规则。
 * 每个测试的名字就是它守住的那条规则。
 */
class PortfolioCalculatorTest {

    private val t = Instant.fromEpochMilliseconds(1_700_000_000_000)
    private val cny = "CNY"

    private fun ctx(
        quotes: Map<String, Quote> = emptyMap(),
        rates: Map<String, ExchangeRate> = emptyMap(),
        base: String = cny,
    ) = ValuationContext(base, quotes, rates)

    private fun quote(symbol: String, priceMinor: Long) =
        Quote(symbol, "2026-07-28", Money(priceMinor), cny, t)

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

    // ---------- 估值分支看快照而不看资产 ----------

    @Test
    fun `退市转MANUAL后历史的QUOTED快照仍按份额估值`() {
        // 资产当前的默认模式已经是 MANUAL（退市了），但这条历史快照是 Quoted。
        // 如果按 asset.defaultValuationMode 去判，会去读 Quoted 快照里不存在的 value，
        // 历史净值就崩了。这是 domain.md 里点名的静默错误。
        val delisted = asset(1, AssetClass.EQUITY).copy(
            defaultValuationMode = ValuationMode.MANUAL,
            defaultQuoteSymbol = null,
        )
        val historical = quoted(1, units = 100, symbol = "600519")

        val value = PortfolioCalculator.localValue(
            historical,
            mapOf("600519" to quote("600519", 150_000)),
        )

        value shouldBe Money(15_000_000)  // 100 股 × 1500 元
    }

    @Test
    fun `快照自带quoteSymbol所以资产清掉symbol也能估值`() {
        val converted = asset(1, AssetClass.EQUITY).copy(defaultQuoteSymbol = null)
        val historical = quoted(1, units = 10, symbol = "OLD_TICKER")

        PortfolioCalculator.netWorth(
            t,
            listOf(converted),
            mapOf(1L to historical),
            ctx(quotes = mapOf("OLD_TICKER" to quote("OLD_TICKER", 10_000))),
        ).netWorth shouldBe Money(100_000)  // 10 份 × 100.00 元 = 1000.00 元
    }

    // ---------- 行情缺失不能当成 0 ----------

    @Test
    fun `行情缺失时资产被标记为未估值而不是计为零`() {
        val stock = asset(1, AssetClass.EQUITY)
        val cash = asset(2, AssetClass.LIQUID)

        val point = PortfolioCalculator.netWorth(
            t,
            listOf(stock, cash),
            mapOf(1L to quoted(1, 100, "NO_QUOTE"), 2L to manual(2, 500_00)),
            ctx(),  // 没有任何行情
        )

        // 股票没被算成 0 —— 它被单列出来，净值只反映能估值的部分
        point.unpricedAssetIds shouldContainExactly listOf(1L)
        point.hasUnpriced shouldBe true
        point.netWorth shouldBe Money(500_00)
    }

    // ---------- 配置比例：分子是净敞口，分母是净资产 ----------

    @Test
    fun `负债归属抵扣后各大类比例加总为百分之百`() {
        // domain.md 里那个例子：房 300 万 + 股 100 万，房贷 200 万 → 净资产 200 万。
        // 不做归属抵扣的话房产会算成 300/200 = 150%，合计 200%，饼图画不出来。
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
        // 房产净敞口 = 300万 - 200万 = 100万 → 50%
        view.shareBp(AssetClass.ALTERNATIVE) shouldBe 5000
        view.shareBp(AssetClass.EQUITY) shouldBe 5000

        val total = AssetClass.displayOrder.sumOf { view.shareBp(it) ?: 0 }
        total shouldBe TargetAllocation.TOTAL_BP
    }

    @Test
    fun `某大类净敞口可以为负且被显式标记`() {
        // 车值 10 万但车贷 15 万
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
        // 明细里给真实负值，饼图那层才 clamp 到 0
        view.shareBp(AssetClass.ALTERNATIVE)!! shouldBe -3333
    }

    @Test
    fun `净资产为负时比例返回null而不是乱数`() {
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
    fun `排除出配置的资产同时不进分子和分母`() {
        // 自住房关掉 includeInAllocation 后，比例应当只反映其余资产
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

        // 分母里没有自住房
        view.netWorth shouldBe Money(400_000_00)
        view.shareBp(AssetClass.ALTERNATIVE) shouldBe 0
        view.shareBp(AssetClass.EQUITY) shouldBe 7500
        view.shareBp(AssetClass.LIQUID) shouldBe 2500
    }

    // ---------- 汇率用当时的，不用今天的 ----------

    @Test
    fun `外币资产按传入的当期汇率折算`() {
        val usStock = asset(1, AssetClass.EQUITY, currency = "USD")
        val rate = ExchangeRate(7_00000000L)  // 1 USD = 7 CNY

        PortfolioCalculator.netWorth(
            t,
            listOf(usStock),
            mapOf(1L to manual(1, 100_00)),  // $100.00
            ctx(rates = mapOf(ValuationContext.rateKey("USD", cny) to rate)),
        ).netWorth shouldBe Money(700_00)  // ¥700.00
    }

    @Test
    fun `缺汇率的外币资产计入未估值而不是按一比一折算`() {
        val hkStock = asset(1, AssetClass.EQUITY, currency = "HKD")

        val point = PortfolioCalculator.netWorth(
            t,
            listOf(hkStock),
            mapOf(1L to manual(1, 100_00)),
            ctx(),  // 没有 HKD→CNY
        )

        point.unpricedAssetIds shouldContainExactly listOf(1L)
        point.netWorth shouldBe Money.ZERO
    }

    // ---------- 成本与盈亏 ----------

    @Test
    fun `QUOTED资产也能算盈亏`() {
        // 这是那条被表格写歪的规则：成本与估值模式无关，QUOTED 也有收益率
        val pnl = PortfolioCalculator.profitAndLoss(
            quoted(1, units = 100, symbol = "600519", costMinor = 10_000_00),
            mapOf("600519" to quote("600519", 150_00)),
        )!!

        pnl.value shouldBe Money(15_000_00)
        pnl.absolute shouldBe Money(5_000_00)
        pnl.returnBp shouldBe 5000  // +50%
    }

    @Test
    fun `没填成本返回null而不是零值盈亏`() {
        // "没有成本"和"成本为零导致盈亏等于市值"是两件完全不同的事
        PortfolioCalculator.profitAndLoss(manual(1, 500_00), emptyMap()).shouldBeNull()
    }

    @Test
    fun `组合盈亏只覆盖填了成本的资产并报告覆盖范围`() {
        val withCost = asset(1, AssetClass.EQUITY)
        val withoutCost = asset(2, AssetClass.ALTERNATIVE)
        val liability = asset(3, AssetClass.LIQUID, isLiability = true)

        val result = PortfolioCalculator.portfolioProfitAndLoss(
            listOf(withCost, withoutCost, liability),
            mapOf(
                1L to manual(1, 15_000_00, costMinor = 10_000_00),
                2L to manual(2, 5_000_000_00),                      // 没成本
                3L to manual(3, 100_000_00, costMinor = 50_000_00), // 负债不算盈亏
            ),
            ctx(),
        )

        result.coveredAssetIds shouldContainExactly listOf(1L)
        result.pnl.absolute shouldBe Money(5_000_00)
    }

    // ---------- 增长率 ≠ 收益率 ----------

    @Test
    fun `净值增长率包含新增投入这是有意为之`() {
        // 期初 10 万，期间存了 1 万工资进去，期末 11 万。
        // 净值增长率显示 +10% —— 但那不是"赚"的。
        // 这条测试锁住这个语义，防止有人"顺手修正"成剔除投入的口径：
        // 剔除投入的那个数是浮动盈亏率，是另一个函数。
        val from = NetWorthPoint(t, cny, Money(100_000_00), Money.ZERO)
        val to = NetWorthPoint(t, cny, Money(110_000_00), Money.ZERO)

        PortfolioCalculator.netWorthGrowthBp(from, to) shouldBe 1000  // +10%
    }

    @Test
    fun `期初净值为零或负时增长率无意义返回null`() {
        val zero = NetWorthPoint(t, cny, Money.ZERO, Money.ZERO)
        val later = NetWorthPoint(t, cny, Money(10_000_00), Money.ZERO)

        PortfolioCalculator.netWorthGrowthBp(zero, later).shouldBeNull()
    }
}
