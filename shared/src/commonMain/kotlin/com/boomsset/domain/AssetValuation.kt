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
    /** 估值用到的那条行情（仅 QUOTED）。用于展示价格日期和判断是否过期。 */
    val quote: Quote? = null,
    /** 行情距今多少天。null = 不是 QUOTED，或者根本没有行情。 */
    val priceAgeDays: Int? = null,
) {
    /** 无法估值 —— UI 要显式提示，不能显示成 0。 */
    val isUnpriced: Boolean get() = snapshot != null && baseValue == null

    /** 还没录过任何快照。 */
    val hasNoSnapshot: Boolean get() = snapshot == null

    /**
     * 行情是否已经旧到需要提醒用户。
     *
     * domain.md 要求「取价失败时用最后一次成功的单价，并标记为 stale，
     * UI 上要能看出来这个价格是 3 天前的」。
     *
     * 阈值取 3 天而不是 1 天，是因为周末和节假日本来就没有行情 ——
     * 用 1 天会在每个周一之前都误报。**这里不建交易日历**：那需要维护各市场的
     * 节假日表，成本远高于收益，而且判断错了反而制造噪音。
     */
    val isPriceStale: Boolean get() = (priceAgeDays ?: 0) > STALE_AFTER_DAYS

    companion object {
        const val STALE_AFTER_DAYS: Int = 3
    }

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
