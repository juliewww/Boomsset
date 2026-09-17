package com.boomsset.ui.allocation

import com.boomsset.data.BUILT_IN_PRESETS
import com.boomsset.domain.AssetClass
import com.boomsset.domain.PortfolioData
import com.boomsset.domain.PortfolioSeriesCalculator
import com.boomsset.domain.TargetAllocation
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test

/**
 * The state of the "Allocation" page when there are zero assets.
 *
 * **This page must still be useful when there are no assets at all.** It answers
 * "how do I intend to allocate", a question that doesn't depend on holdings — and the
 * target allocation is exactly the thing a user wants to set **before** recording their
 * first asset.
 */
class AllocationUiStateTest {

    private val zone = TimeZone.UTC
    private val today = LocalDate(2026, 7, 31)
    private val empty = PortfolioData(
        assets = emptyList(),
        snapshots = emptyList(),
        quotes = emptyList(),
        fxRates = emptyList(),
    )

    private fun allocations() = BUILT_IN_PRESETS.mapIndexed { i, preset ->
        TargetAllocation(
            id = i + 1L,
            name = preset.name,
            targetsBp = preset.targetsBp,
            isBuiltIn = true,
            isActive = preset.name == "平衡",
        )
    }

    private fun stateWithNoAssets(
        allocations: List<TargetAllocation> = allocations(),
    ) = AllocationUiState(
        loading = false,
        view = PortfolioSeriesCalculator.currentAllocation(
            data = empty,
            baseCurrency = "CNY",
            today = today,
            zone = zone,
            target = allocations.firstOrNull { it.isActive },
        ),
        allocations = allocations,
    )

    /**
     * Regression test. Found during a real run: with zero assets, `AllocationScreen` took
     * the `state.isEmpty` branch, which only rendered a single line ("no assets yet, go
     * add one on the net worth page"), while `AllocationPicker` — **the only entry point
     * for switching/editing/creating a target allocation** — was written in the `else`
     * branch and got skipped entirely. As a result, new users had no way at all to reach
     * the target allocation.
     *
     * The data layer was always correct: the presets come from `observeAllocations()`,
     * independent of holdings, and are present even at zero assets. It was purely a
     * missing UI path — the same class of bug as "can't unarchive after archiving
     * everything", the third occurrence.
     *
     * This test locks down the data contract (everything the entry point needs is
     * present); **that it's actually reachable in the UI** is covered by
     * `testAllocationTargetsReachableWithNoAssets` in `iosAppUITests/AssetFlowUITest`.
     */
    @Test
    fun `zero assets is still an empty state, but all the target allocation data is present`() {
        val state = stateWithNoAssets()

        state.isEmpty.shouldBeTrue()

        // All presets available for switching
        state.allocations.shouldNotBeEmpty()
        state.allocations.map { it.name } shouldBe listOf("稳健", "平衡", "激进")

        // The one currently being compared against, plus its ratios — the empty state
        // needs it to render the target preview
        val active = state.allocations.single { it.isActive }
        active.name shouldBe "平衡"
        active.targetsBp.values.sum() shouldBe TargetAllocation.TOTAL_BP
        AssetClass.displayOrder.forEach { active.targetsBp[it].shouldNotBeNull() }
    }

    /**
     * Even when there isn't a single target allocation (shouldn't happen in theory,
     * since the built-in presets are seeded) it still must not be a blank screen —
     * the UI needs to offer a "+ Create" way out. This locks down that the state itself
     * doesn't crash.
     */
    @Test
    fun `state is still usable when there are no target allocations at all`() {
        val state = stateWithNoAssets(allocations = emptyList())

        state.isEmpty.shouldBeTrue()
        state.allocations shouldBe emptyList()
        // view still computes (net worth is 0), not null — the UI doesn't need to handle "no data"
        state.view.shouldNotBeNull()
        state.view.target shouldBe null
    }

    @Test
    fun `not an empty state once there are assets`() {
        // The empty-state criterion is "every asset class has zero exposure"; adding one asset should flip it
        val state = stateWithNoAssets()
        state.isEmpty.shouldBeTrue()

        val view = state.view
        view.shouldNotBeNull()
        val withAsset = state.copy(
            view = view.copy(
                exposures = view.exposures.mapValues { (assetClass, exposure) ->
                    if (assetClass == AssetClass.LIQUID) {
                        exposure.copy(assets = com.boomsset.domain.Money(100_00))
                    } else {
                        exposure
                    }
                },
            ),
        )
        withAsset.isEmpty shouldBe false
    }
}
