package com.boomsset.ui.allocation

import com.boomsset.domain.AllocationView
import com.boomsset.domain.AssetClass
import com.boomsset.domain.ClassExposure
import com.boomsset.domain.Money
import com.boomsset.domain.TargetAllocation
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.time.Instant

/**
 * 配置页"算净资产 / 不算负债"这个显示开关的算式。
 *
 * 这两种口径共用同一份 [AllocationView.exposures]（本来就分别存着 assets 和
 * liabilities），开关只是决定分子分母从哪个字段取——这里锁住两种口径各自的
 * 加总、比例、偏离、负值判断都算对，尤其是有负债存在的场景两种口径必须给出
 * 不同的数字（否则这个开关就是摆设）。
 */
class AllocationDisplayModeTest {

    private val asOf = Instant.fromEpochMilliseconds(0)

    // 流动资金：资产 10 万、负债 2 万 → 净敞口 8 万
    // 权益类：资产 6 万、无负债 → 净敞口 6 万
    // 其余大类都是 0
    private fun view(target: TargetAllocation? = null): AllocationView {
        val exposures = AssetClass.displayOrder.associateWith { assetClass ->
            when (assetClass) {
                AssetClass.LIQUID -> ClassExposure(
                    assetClass = assetClass,
                    assets = Money(100_000_00),
                    liabilities = Money(20_000_00),
                )
                AssetClass.EQUITY -> ClassExposure(
                    assetClass = assetClass,
                    assets = Money(60_000_00),
                    liabilities = Money.ZERO,
                )
                else -> ClassExposure(assetClass, Money.ZERO, Money.ZERO)
            }
        }
        return AllocationView(
            asOf = asOf,
            baseCurrency = "CNY",
            netWorth = exposures.values.fold(Money.ZERO) { acc, e -> acc + e.netExposure },
            exposures = exposures,
            target = target,
        )
    }

    @Test
    fun `算净资产时分子分母都扣负债`() {
        val v = view()
        // 净敞口：流动资金 8 万 + 权益 6 万 = 14 万
        v.displayedTotal(includeLiabilities = true) shouldBe Money(140_000_00)
        v.exposures.getValue(AssetClass.LIQUID).displayed(includeLiabilities = true) shouldBe Money(80_000_00)
    }

    @Test
    fun `不算负债时分子分母都是资产原值`() {
        val v = view()
        // 资产总额：10 万 + 6 万 = 16 万，负债完全不参与
        v.displayedTotal(includeLiabilities = false) shouldBe Money(160_000_00)
        v.exposures.getValue(AssetClass.LIQUID).displayed(includeLiabilities = false) shouldBe Money(100_000_00)
    }

    @Test
    fun `同一份数据两种口径的比例不一样`() {
        val v = view()
        // 净敞口口径：8万 / 14万 ≈ 57.14%
        val netShare = v.displayedShareBp(AssetClass.LIQUID, includeLiabilities = true)
        // 资产口径：10万 / 16万 = 62.5%
        val grossShare = v.displayedShareBp(AssetClass.LIQUID, includeLiabilities = false)
        netShare shouldBe 5714
        grossShare shouldBe 6250
        (netShare == grossShare).shouldBeFalse()
    }

    @Test
    fun `偏离度按同一种口径和目标比较`() {
        val target = TargetAllocation(
            id = 1L,
            name = "test",
            isBuiltIn = false,
            isActive = true,
            targetsBp = mapOf(
                AssetClass.LIQUID to 5000,
                AssetClass.FIXED_INCOME to 0,
                AssetClass.EQUITY to 5000,
                AssetClass.ALTERNATIVE to 0,
                AssetClass.PROTECTION to 0,
            ),
        )
        val v = view(target)
        // 资产口径下流动资金 62.5%，目标 50% → 超配 12.5%
        v.displayedDeviationBp(AssetClass.LIQUID, includeLiabilities = false) shouldBe 1250
    }

    @Test
    fun `分母为零时两种口径都返回null而不是崩溃或乱数`() {
        val exposures = AssetClass.displayOrder.associateWith { ClassExposure(it, Money.ZERO, Money.ZERO) }
        val v = AllocationView(asOf, "CNY", Money.ZERO, exposures, target = null)
        v.displayedShareBp(AssetClass.LIQUID, includeLiabilities = true).shouldBeNull()
        v.displayedShareBp(AssetClass.LIQUID, includeLiabilities = false).shouldBeNull()
    }

    @Test
    fun `资产口径下永远没有负值警示`() {
        // 车贷超过车值：负债比资产多——净敞口为负，但资产本身还是正的
        val exposures = AssetClass.displayOrder.associateWith { assetClass ->
            if (assetClass == AssetClass.ALTERNATIVE) {
                ClassExposure(assetClass, assets = Money(50_000_00), liabilities = Money(80_000_00))
            } else {
                ClassExposure(assetClass, Money.ZERO, Money.ZERO)
            }
        }
        val v = AllocationView(asOf, "CNY", Money(-30_000_00), exposures, target = null)

        v.hasNegativeDisplayed(includeLiabilities = true).shouldBeTrue()
        v.hasNegativeDisplayed(includeLiabilities = false).shouldBeFalse()
    }
}
