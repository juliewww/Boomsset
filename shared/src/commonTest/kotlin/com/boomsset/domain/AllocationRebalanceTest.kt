package com.boomsset.domain

import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.math.abs
import kotlin.test.Test
import kotlin.time.Instant

/**
 * The arithmetic of "how much money away from the target".
 *
 * A deviation percentage alone isn't enough -- knowing "31% overweight" doesn't tell you how
 * much money to move (real-device feedback). This locks in four things about
 * [AllocationView.rebalanceAmount]: the convention (total net worth stays fixed), the
 * invariant that the sum is 0, that the null condition matches [AllocationView.deviationBp],
 * and **that it must not be back-computed from an already-truncated deviation**.
 */
class AllocationRebalanceTest {

    private val t = Instant.fromEpochMilliseconds(1_785_000_000_000)
    private val cny = "CNY"

    /** Balanced: liquid 10% / fixed income 35% / equity 40% / alternative 5% / protection 10%. */
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

    /** Constructs the view directly -- this layer is a pure derived calculation and doesn't
     * need to go through assets and snapshots. */
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
    fun `an overweight class gives the amount that needs to be reduced`() {
        // Net worth 1,000,000, equity 712,500 (71.25%), target 40% -> target amount 400,000, reduce by 312,500
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
    fun `an underweight class gives the amount that needs to be increased`() {
        val v = view(
            mapOf(
                AssetClass.LIQUID to 15_000_000L,
                AssetClass.FIXED_INCOME to 10_000_000L,
                AssetClass.EQUITY to 71_250_000L,
                AssetClass.ALTERNATIVE to 3_750_000L,
            ),
        )

        // Fixed income 100,000 (10%), target 35% -> target amount 350,000, increase by 250,000
        v.deviationBp(AssetClass.FIXED_INCOME) shouldBe -2500
        v.rebalanceAmount(AssetClass.FIXED_INCOME) shouldBe Money(25_000_000L)
        // Protection has nothing at all -> needs to be increased by the full target amount
        v.rebalanceAmount(AssetClass.PROTECTION) shouldBe Money(10_000_000L)
    }

    /**
     * **Proof that the convention is self-consistent**: summing to 0 means "however much the
     * overweight classes get reduced by is exactly enough for the underweight classes to add".
     *
     * This invariant holds only because the target ratios always sum to
     * [TargetAllocation.TOTAL_BP] (guarded by `requireClosed` at the data layer). Rounding can
     * make the sum off by up to "number of classes minus 1" cents: each class's target amount
     * can lose less than 1 cent.
     */
    @Test
    fun `the rebalance amounts across all classes sum to 0`() {
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
     * Regression: **must not be written as `-deviation x net worth`.**
     *
     * [AllocationView.deviationBp] is computed by subtracting from [AllocationView.shareBp],
     * which has already been truncated to whole basis points -- 1 basis point times a net
     * worth in the billions is real money, and the two algorithms can differ by five figures.
     * This test pins down that gap: the target amount from the correct algorithm must be
     * **exactly** equal to `target basis points x net worth / 10000`, with error < 1 cent.
     */
    @Test
    fun `must not back-compute from a truncated deviation - the precision gap is five figures at large net worth`() {
        // Net worth ~= 999 million yuan; equity is only ~300,000, less than 1 basis point of the total
        val equity = 29_964_743L
        val v = view(
            mapOf(
                AssetClass.EQUITY to equity,
                AssetClass.LIQUID to 99_885_314_461L - equity,
            ),
        )
        val netWorth = v.netWorth.minorUnits

        // Correct algorithm: the target amount is truncated only once, matching the exact value to the cent
        val correct = v.rebalanceAmount(AssetClass.EQUITY)!!.minorUnits
        val exactGoal = 4000L * netWorth / TargetAllocation.TOTAL_BP
        correct shouldBe exactGoal - equity

        // The wrong path via back-computing from the deviation: a share of 2.9999 basis points
        // gets truncated to 2, and the error gets amplified by net worth
        v.shareBp(AssetClass.EQUITY) shouldBe 2
        val viaDeviation = -(v.deviationBp(AssetClass.EQUITY)!!.toLong() * netWorth /
            TargetAllocation.TOTAL_BP)
        // Measured gap: 9,987,680 cents = 99,876.80 yuan, i.e. on the order of "1 basis point of net worth"
        abs(correct - viaDeviation) shouldBeGreaterThanOrEqualTo 9_000_000L
        abs(correct - viaDeviation) shouldBeLessThanOrEqualTo 10_000_000L
    }

    @Test
    fun `a class with negative net exposure needs no special handling to reach its target`() {
        // Car 100,000 / car loan 150,000 -> alternative net exposure -50,000; net worth = 200,000 - 50,000 = 150,000
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
        // Target 5% x 150,000 = 7,500; moving from -50,000 to +7,500 requires 57,500
        v.rebalanceAmount(AssetClass.ALTERNATIVE) shouldBe Money(5_750_000L)
    }

    @Test
    fun `the rebalance amount is zero once the target is met`() {
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
     * The null condition must be **exactly the same** as [AllocationView.deviationBp] --
     * inconsistency would let the UI show "the percentage can't be computed, but the amount
     * gives a number" (or the reverse).
     */
    @Test
    fun `returns null when net worth is negative, consistent with the deviation`() {
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
    fun `returns null when there is no active target allocation`() {
        val v = view(mapOf(AssetClass.LIQUID to 100_000_00L), target = null)

        v.shareBp(AssetClass.LIQUID) shouldBe TargetAllocation.TOTAL_BP
        v.deviationBp(AssetClass.LIQUID).shouldBeNull()
        v.rebalanceAmount(AssetClass.LIQUID).shouldBeNull()
    }

    /**
     * Basis point conversion is "multiply by 10000 then divide", and **a Long overflow doesn't
     * throw, it silently wraps around**. It's better to show "--" on the whole page than to
     * give an absurd amount -- this app's entire value rests on the trustworthiness of its totals.
     */
    @Test
    fun `returns null instead of silently wrapping when the magnitude is too large`() {
        val huge = Long.MAX_VALUE / TargetAllocation.TOTAL_BP + 1
        val v = view(mapOf(AssetClass.LIQUID to huge))

        v.netWorth shouldBe Money(huge)
        v.shareBp(AssetClass.LIQUID).shouldBeNull()
        v.deviationBp(AssetClass.LIQUID).shouldBeNull()
        v.rebalanceAmount(AssetClass.LIQUID).shouldBeNull()
    }

    /** The guard must not also swallow legitimate magnitudes: up to one trillion yuan (1e12) must still compute normally. */
    @Test
    fun `ordinary magnitudes are unaffected by the overflow guard`() {
        val trillion = 1_000_000_000_000_00L // 1e12 yuan, in cents
        val v = view(mapOf(AssetClass.LIQUID to trillion))

        v.shareBp(AssetClass.LIQUID) shouldBe TargetAllocation.TOTAL_BP
        v.rebalanceAmount(AssetClass.LIQUID) shouldBe Money(-90_000_000_000_000L)
    }

    /** The sum of the five classes' deviations must also be 0 -- this is the same closure property as the amount test, stated two ways. */
    @Test
    fun `deviations across all classes sum to 0`() {
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
