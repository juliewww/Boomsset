package com.boomsset.ui.networth

import com.boomsset.domain.AllocationSeries
import com.boomsset.domain.AllocationView
import com.boomsset.domain.AssetClass
import com.boomsset.domain.ClassExposure
import com.boomsset.domain.Money
import com.boomsset.domain.Period
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.time.Instant

/**
 * The per-class numbers printed on a tapped column.
 *
 * What these lock down is **which period and which number a tap maps to**. Getting that wrong
 * doesn't crash and doesn't look broken — it quietly labels September's money as October's,
 * which is the kind of mistake a screenshot can't catch.
 */
class ColumnBreakdownTest {

    private val t = Instant.fromEpochMilliseconds(0)
    private val dates = listOf(
        LocalDate(2026, 8, 31),
        LocalDate(2026, 9, 30),
        LocalDate(2026, 10, 9),
    )

    /** One point per date; [exposures] is class → the net exposure (yuan) at each date. */
    private fun allocationSeries(exposures: Map<AssetClass, List<Long>>): AllocationSeries {
        val count = exposures.values.first().size
        return AllocationSeries(
            period = Period.MONTH,
            baseCurrency = "CNY",
            dates = dates.take(count),
            points = List(count) { index ->
                AllocationView(
                    asOf = t,
                    baseCurrency = "CNY",
                    netWorth = Money(exposures.values.sumOf { it[index] } * 100),
                    exposures = exposures.mapValues { (assetClass, values) ->
                        ClassExposure(assetClass, Money(values[index] * 100), Money.ZERO)
                    },
                    target = null,
                )
            },
        )
    }

    // ---------- By-class mode ----------

    /**
     * The point of the whole feature: **each class gets its own growth rate**, measured
     * against that class's own previous value.
     *
     * The example is built so the total barely moves while the parts move a lot — equity
     * halves, liquid funds double, and the column's height is unchanged. Read off the column
     * alone, nothing happened that month.
     */
    @Test
    fun `each class is measured against its own previous value, not against the total`() {
        val series = allocationSeries(
            mapOf(
                AssetClass.LIQUID to listOf(100_000, 200_000),
                AssetClass.EQUITY to listOf(200_000, 100_000),
            ),
        )
        val classes = listOf(AssetClass.LIQUID, AssetClass.EQUITY)

        val breakdown = allocationBreakdown(series, classes, index = 1)!!

        breakdown.label shouldBe "2026年9月"
        breakdown.rows shouldHaveSize 3 // two classes + the total
        breakdown.rows[0].assetClass shouldBe AssetClass.LIQUID
        breakdown.rows[0].amount shouldBe Money(200_000_00)
        breakdown.rows[0].growth shouldBe GrowthLabel("+100%", 1)
        breakdown.rows[1].assetClass shouldBe AssetClass.EQUITY
        breakdown.rows[1].amount shouldBe Money(100_000_00)
        breakdown.rows[1].growth shouldBe GrowthLabel("-50%", -1)
        // The total didn't move at all -- which is exactly why the per-class rows are needed
        breakdown.rows[2].assetClass.shouldBeNull()
        breakdown.rows[2].amount shouldBe Money(300_000_00)
        breakdown.rows[2].growth shouldBe GrowthLabel("0%", 0)
    }

    /**
     * The total covers **only the visible classes**, matching the column the user is looking
     * at: unchecking a class shortens the column, and a total that still counted it would
     * contradict what's on screen.
     */
    @Test
    fun `the total follows the visible classes, not every class`() {
        val series = allocationSeries(
            mapOf(
                AssetClass.LIQUID to listOf(100_000, 200_000),
                AssetClass.EQUITY to listOf(200_000, 100_000),
            ),
        )

        val breakdown = allocationBreakdown(series, listOf(AssetClass.LIQUID), index = 1)!!

        // One visible class: the total line would just repeat the row above it, so it's dropped
        breakdown.rows shouldHaveSize 1
        breakdown.rows[0].assetClass shouldBe AssetClass.LIQUID
        breakdown.rows[0].amount shouldBe Money(200_000_00)
    }

    @Test
    fun `a class with no exposure at this point reads as zero, not as missing`() {
        // A class can legitimately be empty at an earlier sample point (nothing held yet);
        // that's a real zero and belongs in the list, so the rows always line up with the legend
        val series = allocationSeries(
            mapOf(
                AssetClass.LIQUID to listOf(0, 50_000),
                AssetClass.EQUITY to listOf(100_000, 100_000),
            ),
        )
        val classes = listOf(AssetClass.LIQUID, AssetClass.EQUITY)

        val breakdown = allocationBreakdown(series, classes, index = 0)!!

        breakdown.rows[0].amount shouldBe Money.ZERO
        breakdown.rows[0].growth shouldBe GrowthLabel.MISSING
    }

    @Test
    fun `with no visible classes there is nothing to describe`() {
        val series = allocationSeries(mapOf(AssetClass.LIQUID to listOf(100_000)))

        allocationBreakdown(series, emptyList(), index = 0).shouldBeNull()
        allocationBreakdown(series, listOf(AssetClass.LIQUID), index = 7).shouldBeNull()
    }
}
