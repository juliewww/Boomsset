package com.boomsset.domain

/**
 * 单项资产在某时点的估值结果，供资产列表和详情页使用。
 *
 * 三个可空字段的语义要分清 —— 它们**不是**"值为 0"：
 * - [snapshot] 为 null：该时点这项资产还没有任何快照
 * - [localValue] 为 null：QUOTED 资产的行情缺失，无法算出市值
 * - [baseValue] 为 null：上面那种，或者缺少折算到基准币种的汇率
 */
data class AssetValuation(
    val asset: Asset,
    val snapshot: Snapshot?,
    /** 资产自身币种下的市值 */
    val localValue: Money?,
    /** 折算到基准币种后的市值 */
    val baseValue: Money?,
    /** 自身币种下的浮动盈亏。没填成本或无法估值时为 null。 */
    val pnl: ProfitAndLoss?,
    /** 该资产现有的快照条数。用于 [AssetEditPolicy] 判断币种/负债标记能否改。 */
    val snapshotCount: Int = 0,
) {
    /** 无法估值 —— UI 要显式提示，不能显示成 0。 */
    val isUnpriced: Boolean get() = snapshot != null && baseValue == null

    /** 还没录过任何快照。 */
    val hasNoSnapshot: Boolean get() = snapshot == null

    /**
     * 成本均价，仅 QUOTED 且填了成本时有值。
     *
     * 这是**派生显示值** —— 库里存的是总成本。见 docs/domain.md「录入形式」。
     */
    val unitCost: Money?
        get() {
            val quoted = snapshot as? Snapshot.Quoted ?: return null
            val cost = quoted.costBasisMinor ?: return null
            return cost.unitCostOver(quoted.quantity)
        }
}
