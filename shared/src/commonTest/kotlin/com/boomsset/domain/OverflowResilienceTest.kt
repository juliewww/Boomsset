package com.boomsset.domain

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.time.Instant

/**
 * 回归测试：**极端数值不能让 App 崩溃。**
 *
 * 实跑时输错一次份额（1 亿股茅台）就把 App 打死了 —— [FixedPoint] 的溢出保护抛
 * `ArithmeticException`，异常从 `localValue` 一路逃到 ViewModel 的 combine，
 * 整个进程挂掉。
 *
 * 抛异常本身是对的（金额绝不能静默回绕），但**异常不能到达 UI**。
 * 现在估值层把它降级成"无法估值"：既没有算错，也没有崩。
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

    /** 复现实跑那次崩溃的确切数值：1.0012e8 股 × 1331.99 元。 */
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
    fun `溢出时返回null而不是抛异常`() {
        PortfolioCalculator.localValue(absurdSnapshot(), realPrice).shouldBeNull()
    }

    @Test
    fun `净值计算把溢出的资产列为无法估值而不是崩掉`() {
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
    fun `配置视图跳过溢出的资产而不是崩掉`() {
        val view = PortfolioCalculator.allocation(
            asOf = t,
            assets = listOf(asset(1)),
            snapshots = mapOf(1L to absurdSnapshot()),
            context = ValuationContext(cny, realPrice, emptyMap()),
        )
        view.netWorth shouldBe Money.ZERO
    }

    @Test
    fun `汇率折算溢出也不崩`() {
        // 一笔巨额本币金额 × 一个巨大汇率
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
    fun `正常量级不受影响`() {
        // 确认上面的保护没有把合法计算也吞掉
        val normal = Snapshot.Quoted(
            id = 1, assetId = 1, asOf = t,
            quantity = Quantity.ofUnits(100),
            quoteSymbol = "sh600519",
            recordedAt = t,
        )
        PortfolioCalculator.localValue(normal, realPrice) shouldBe Money(133_199_00)
    }
}
