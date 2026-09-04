package com.boomsset.domain

import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.math.abs
import kotlin.test.Test
import kotlin.time.Instant

/**
 * 「距目标还差多少钱」的算术。
 *
 * 光有偏离百分比不够用 —— 知道"超配 31%"不等于知道该动多少钱（实机反馈）。
 * 这里锁住 [AllocationView.rebalanceAmount] 的四件事：
 * 口径（总净资产不变）、加总为 0 的不变量、null 条件和 [AllocationView.deviationBp] 一致、
 * 以及**不能从已经截断过的偏离度反算**。
 */
class AllocationRebalanceTest {

    private val t = Instant.fromEpochMilliseconds(1_785_000_000_000)
    private val cny = "CNY"

    /** 平衡：流动 10% / 固收 35% / 权益 40% / 另类 5% / 保障 10%。 */
    private val balanced = TargetAllocation(
        id = 1,
        name = "平衡",
        isBuiltIn = true,
        isActive = true,
        targetsBp = mapOf(
            AssetClass.LIQUID to 1000,
            AssetClass.FIXED_INCOME to 3500,
            AssetClass.EQUITY to 4000,
            AssetClass.ALTERNATIVE to 500,
            AssetClass.PROTECTION to 1000,
        ),
    )

    /** 直接构造视图 —— 这一层是纯派生计算，不需要绕道资产和快照。 */
    private fun view(
        exposures: Map<AssetClass, Long>,
        target: TargetAllocation? = balanced,
    ): AllocationView {
        val full = AssetClass.displayOrder.associateWith { assetClass ->
            ClassExposure(
                assetClass = assetClass,
                assets = Money(exposures[assetClass] ?: 0L),
                liabilities = Money.ZERO,
            )
        }
        return AllocationView(
            asOf = t,
            baseCurrency = cny,
            netWorth = full.values.fold(Money.ZERO) { acc, e -> acc + e.netExposure },
            exposures = full,
            target = target,
        )
    }

    @Test
    fun `超配的类给出需要减少的金额`() {
        // 净资产 100 万，权益 71.25 万（71.25%），目标 40% → 目标额 40 万，需减 31.25 万
        val v = view(
            mapOf(
                AssetClass.LIQUID to 15_000_000L,
                AssetClass.FIXED_INCOME to 10_000_000L,
                AssetClass.EQUITY to 71_250_000L,
                AssetClass.ALTERNATIVE to 3_750_000L,
            ),
        )

        v.netWorth shouldBe Money(100_000_000L)
        v.shareBp(AssetClass.EQUITY) shouldBe 7125
        v.deviationBp(AssetClass.EQUITY) shouldBe 3125
        v.rebalanceAmount(AssetClass.EQUITY) shouldBe Money(-31_250_000L)
    }

    @Test
    fun `低配的类给出需要增加的金额`() {
        val v = view(
            mapOf(
                AssetClass.LIQUID to 15_000_000L,
                AssetClass.FIXED_INCOME to 10_000_000L,
                AssetClass.EQUITY to 71_250_000L,
                AssetClass.ALTERNATIVE to 3_750_000L,
            ),
        )

        // 固收 10 万（10%），目标 35% → 目标额 35 万，需增 25 万
        v.deviationBp(AssetClass.FIXED_INCOME) shouldBe -2500
        v.rebalanceAmount(AssetClass.FIXED_INCOME) shouldBe Money(25_000_000L)
        // 保障一分钱都没有 → 需增满额
        v.rebalanceAmount(AssetClass.PROTECTION) shouldBe Money(10_000_000L)
    }

    /**
     * **口径的自洽性证明**：加总为 0 意味着"超配的类减掉多少，正好够低配的类加上"。
     *
     * 这条不变量成立的前提是目标比例之和恒为 [TargetAllocation.TOTAL_BP]
     * （数据层有 `requireClosed` 挡着）。取整会让和差最多「大类数 − 1」分：
     * 每个大类的目标额各损失不到 1 分。
     */
    @Test
    fun `全部大类的调整额加总为0`() {
        val v = view(
            mapOf(
                AssetClass.LIQUID to 15_000_001L,
                AssetClass.FIXED_INCOME to 10_000_003L,
                AssetClass.EQUITY to 71_250_007L,
                AssetClass.ALTERNATIVE to 3_749_991L,
                AssetClass.PROTECTION to 13L,
            ),
        )

        val sum = AssetClass.displayOrder.sumOf { v.rebalanceAmount(it)!!.minorUnits }
        val slack = (AssetClass.displayOrder.size - 1).toLong()
        sum shouldBeGreaterThanOrEqualTo -slack
        sum shouldBeLessThanOrEqualTo 0L
    }

    /**
     * 回归：**不能写成 `−偏离度 × 净资产`。**
     *
     * [AllocationView.deviationBp] 是从已经截断到整基点的 [AllocationView.shareBp]
     * 减出来的，1 基点乘上净资产就是真金白银 —— 净资产 10 亿这档，两种算法能差到
     * 五位数。这条测试就是把那个差额钉住：正确算法给出的目标额必须**精确**等于
     * `目标基点 × 净资产 / 10000`，误差 < 1 分。
     */
    @Test
    fun `不从截断过的偏离度反算 精度差在大额净资产上是五位数`() {
        // 净资产 ≈ 9.99 亿元；权益只有约 30 万，占比不足 1 基点
        val equity = 29_964_743L
        val v = view(
            mapOf(
                AssetClass.EQUITY to equity,
                AssetClass.LIQUID to 99_885_314_461L - equity,
            ),
        )
        val netWorth = v.netWorth.minorUnits

        // 正确算法：目标额只截断一次，和精确值分毫不差
        val correct = v.rebalanceAmount(AssetClass.EQUITY)!!.minorUnits
        val exactGoal = 4000L * netWorth / TargetAllocation.TOTAL_BP
        correct shouldBe exactGoal - equity

        // 走偏离度反算的那条错路：占比 2.9999 基点被截断成 2，误差被净资产放大
        v.shareBp(AssetClass.EQUITY) shouldBe 2
        val viaDeviation = -(v.deviationBp(AssetClass.EQUITY)!!.toLong() * netWorth /
            TargetAllocation.TOTAL_BP)
        // 实测差 9,987,680 分 = ¥99,876.80，也就是"1 基点的净资产"这个量级
        abs(correct - viaDeviation) shouldBeGreaterThanOrEqualTo 9_000_000L
        abs(correct - viaDeviation) shouldBeLessThanOrEqualTo 10_000_000L
    }

    @Test
    fun `负净敞口的类要求补到目标 不需要特殊处理`() {
        // 车 10 万 / 车贷 15 万 → 另类净敞口 −5 万；净资产 = 20 万 − 5 万 = 15 万
        val exposures = AssetClass.displayOrder.associateWith { assetClass ->
            when (assetClass) {
                AssetClass.ALTERNATIVE -> ClassExposure(
                    assetClass, Money(10_000_000L), Money(15_000_000L),
                )
                AssetClass.LIQUID -> ClassExposure(assetClass, Money(20_000_000L), Money.ZERO)
                else -> ClassExposure(assetClass, Money.ZERO, Money.ZERO)
            }
        }
        val v = AllocationView(
            asOf = t,
            baseCurrency = cny,
            netWorth = exposures.values.fold(Money.ZERO) { acc, e -> acc + e.netExposure },
            exposures = exposures,
            target = balanced,
        )

        v.netWorth shouldBe Money(15_000_000L)
        v.exposures[AssetClass.ALTERNATIVE]!!.netExposure shouldBe Money(-5_000_000L)
        // 目标 5% × 15 万 = 7,500；从 −50,000 拉到 +7,500 要 57,500
        v.rebalanceAmount(AssetClass.ALTERNATIVE) shouldBe Money(5_750_000L)
    }

    @Test
    fun `已达标时调整额为零`() {
        val v = view(
            mapOf(
                AssetClass.LIQUID to 10_000_000L,
                AssetClass.FIXED_INCOME to 35_000_000L,
                AssetClass.EQUITY to 40_000_000L,
                AssetClass.ALTERNATIVE to 5_000_000L,
                AssetClass.PROTECTION to 10_000_000L,
            ),
        )

        AssetClass.displayOrder.forEach { assetClass ->
            v.deviationBp(assetClass) shouldBe 0
            v.rebalanceAmount(assetClass) shouldBe Money.ZERO
        }
    }

    /**
     * null 条件必须和 [AllocationView.deviationBp] **一模一样** ——
     * 不一致会让 UI 出现"比例说算不出来、金额却给了个数"，或者反过来。
     */
    @Test
    fun `净资产为负时返回null 和偏离度一致`() {
        val exposures = AssetClass.displayOrder.associateWith { assetClass ->
            if (assetClass == AssetClass.LIQUID) {
                ClassExposure(assetClass, Money(10_000_00L), Money(50_000_00L))
            } else {
                ClassExposure(assetClass, Money.ZERO, Money.ZERO)
            }
        }
        val v = AllocationView(
            asOf = t,
            baseCurrency = cny,
            netWorth = exposures.values.fold(Money.ZERO) { acc, e -> acc + e.netExposure },
            exposures = exposures,
            target = balanced,
        )

        v.netWorth shouldBe Money(-40_000_00L)
        AssetClass.displayOrder.forEach { assetClass ->
            v.deviationBp(assetClass).shouldBeNull()
            v.rebalanceAmount(assetClass).shouldBeNull()
        }
    }

    @Test
    fun `没有生效的目标配置时返回null`() {
        val v = view(mapOf(AssetClass.LIQUID to 100_000_00L), target = null)

        v.shareBp(AssetClass.LIQUID) shouldBe TargetAllocation.TOTAL_BP
        v.deviationBp(AssetClass.LIQUID).shouldBeNull()
        v.rebalanceAmount(AssetClass.LIQUID).shouldBeNull()
    }

    /**
     * 基点换算是「先乘 10000 再除」，而 **Long 溢出不抛异常、会安静地回绕**。
     * 宁可整页显示"—"，也不能给一个荒谬的金额 —— 这个 App 的价值全在总数的可信度上。
     */
    @Test
    fun `量级过大时返回null而不是静默回绕`() {
        val huge = Long.MAX_VALUE / TargetAllocation.TOTAL_BP + 1
        val v = view(mapOf(AssetClass.LIQUID to huge))

        v.netWorth shouldBe Money(huge)
        v.shareBp(AssetClass.LIQUID).shouldBeNull()
        v.deviationBp(AssetClass.LIQUID).shouldBeNull()
        v.rebalanceAmount(AssetClass.LIQUID).shouldBeNull()
    }

    /** 闸门不能把合法量级也吞掉：一万亿元（¥1e12）以内必须照常算。 */
    @Test
    fun `正常量级不受溢出闸门影响`() {
        val trillion = 1_000_000_000_000_00L // ¥1e12，分
        val v = view(mapOf(AssetClass.LIQUID to trillion))

        v.shareBp(AssetClass.LIQUID) shouldBe TargetAllocation.TOTAL_BP
        v.rebalanceAmount(AssetClass.LIQUID) shouldBe Money(-90_000_000_000_000L)
    }

    /** 五大类的偏离度加总也必须是 0 —— 和金额那条是同一个闭合性的两种表述。 */
    @Test
    fun `偏离度加总为0`() {
        val v = view(
            mapOf(
                AssetClass.LIQUID to 15_000_000L,
                AssetClass.FIXED_INCOME to 10_000_000L,
                AssetClass.EQUITY to 71_250_000L,
                AssetClass.ALTERNATIVE to 3_750_000L,
            ),
        )

        val sum = AssetClass.displayOrder.sumOf { v.deviationBp(it)!! }
        sum shouldBeGreaterThanOrEqualTo -(AssetClass.displayOrder.size - 1)
        sum shouldBeLessThanOrEqualTo 0
    }
}
