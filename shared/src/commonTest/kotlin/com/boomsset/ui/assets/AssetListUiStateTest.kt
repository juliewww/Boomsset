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
     * 回归测试。实跑时发现：把最后一项资产归档后，资产页走空状态分支提前 return，
     * 而「查看已归档」的展开按钮只渲染在列表里 —— **那项资产在 UI 上彻底不可达**，
     * 再也取消不了归档。
     *
     * 数据层一直是对的（archivedAt 已设、0 值快照在），纯粹是 UI 路径缺失。
     * 现在空状态和列表走同一条渲染路径，已归档区块在两种情况下都可达。
     */
    @Test
    fun `全部归档后仍然报告已归档数量`() {
        val state = AssetListUiState(
            loading = false,
            grouped = AssetClass.displayOrder.associateWith { emptyList() },
            archivedCount = 1,
            archived = listOf(valuation(1, archived = true)),
        )

        // 在持为空
        state.isEmpty shouldBe true
        // 但已归档的数量和列表都还在 —— UI 必须据此给出入口
        state.archivedCount shouldBe 1
        state.archived.size shouldBe 1
    }

    @Test
    fun `有在持资产时不是空状态`() {
        val state = AssetListUiState(
            loading = false,
            grouped = mapOf(AssetClass.LIQUID to listOf(valuation(1, archived = false))),
        )
        state.isEmpty shouldBe false
    }

    @Test
    fun `无法估值的数量跨大类累加`() {
        val unpriced = AssetValuation(
            asset = valuation(1, false).asset,
            snapshot = com.boomsset.domain.Snapshot.Manual(
                id = 1, assetId = 1, asOf = epoch, value = Money(100), recordedAt = epoch,
            ),
            localValue = Money(100),
            baseValue = null,   // 折算不出来 → isUnpriced
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
