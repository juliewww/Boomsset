package com.boomsset.ui.assets

import com.boomsset.domain.Asset
import com.boomsset.domain.AssetClass
import com.boomsset.domain.AssetValuation
import com.boomsset.domain.Money
import com.boomsset.domain.ValuationMode
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.time.Instant

class AssetListUiStateTest {

    private val epoch = Instant.fromEpochMilliseconds(0)

    private fun valuation(id: Long, archived: Boolean) = AssetValuation(
        asset = Asset(
            id = id,
            name = "a$id",
            assetClass = AssetClass.LIQUID,
            subtypeId = 1,
            currency = "CNY",
            defaultValuationMode = ValuationMode.MANUAL,
            archivedAt = if (archived) epoch else null,
        ),
        snapshot = null,
        localValue = null,
        baseValue = Money.ZERO,
        pnl = null,
        snapshotCount = 1,
    )

    /**
     * Regression test. Found during a real run: after archiving the last remaining
     * asset, the assets page took the empty-state branch with an early return, while the
     * "view archived" expand button was only rendered inside the list — **that asset
     * became completely unreachable in the UI**, with no way to unarchive it ever again.
     *
     * The data layer was always correct (archivedAt was set, the zero-value snapshot was
     * there), it was purely a missing UI path. Now the empty state and the list share the
     * same render path, so the archived section is reachable in both cases.
     */
    @Test
    fun `still reports the archived count after everything is archived`() {
        val state = AssetListUiState(
            loading = false,
            grouped = AssetClass.displayOrder.associateWith { emptyList() },
            archivedCount = 1,
            archived = listOf(valuation(1, archived = true)),
        )

        // No active holdings
        state.isEmpty shouldBe true
        // But the archived count and list are both still present — the UI must offer an entry point from them
        state.archivedCount shouldBe 1
        state.archived.size shouldBe 1
    }

    @Test
    fun `not an empty state when there are active holdings`() {
        val state = AssetListUiState(
            loading = false,
            grouped = mapOf(AssetClass.LIQUID to listOf(valuation(1, archived = false))),
        )
        state.isEmpty shouldBe false
    }

    @Test
    fun `unpriced count accumulates across asset classes`() {
        val unpriced = AssetValuation(
            asset = valuation(1, false).asset,
            snapshot = com.boomsset.domain.Snapshot.Manual(
                id = 1, assetId = 1, asOf = epoch, value = Money(100), recordedAt = epoch,
            ),
            localValue = Money(100),
            baseValue = null,   // cannot be converted → isUnpriced
            pnl = null,
        )
        val state = AssetListUiState(
            loading = false,
            grouped = mapOf(
                AssetClass.LIQUID to listOf(unpriced),
                AssetClass.EQUITY to listOf(unpriced),
            ),
        )
        state.unpricedCount shouldBe 2
    }
}
