package com.boomsset.domain

/**
 * 哪些资产字段可以改、哪些不能。
 *
 * ## 判据：会不会**追溯性地重新解释已有快照**
 *
 * 快照记录的是「那一刻的事实」，不可变。但有两个 `Asset` 上的字段会改变**如何解读**
 * 全部历史快照 —— 改它们等于悄悄改写历史：
 *
 * - **`currency`**：快照里存的 `valueMinor` 没有币种。把 CNY 改成 USD，所有历史金额
 *   会被当成美元重新折算 —— 数字没动，含义全变了，净值曲线整体错位。
 * - **`isLiability`**：决定该资产是加项还是减项。翻转它会让全部历史净值变化
 *   **两倍于该资产的金额**，且不报错。
 *
 * 所以这两个字段**只在资产仅有一条快照时可改** —— 也就是"刚建好、还没有历史"。
 * 那种情况下改币种正是用户想要的（建的时候选错了）。
 *
 * 相比之下这些随时可改，因为它们只改变归类和展示，不改变任何记录下来的金额：
 * `name` / `assetClass` / `subtypeId` / `includeInAllocation`
 *
 * `assetClass` 值得单独说一句：改它会把该资产（或该负债的抵扣）移到另一个大类，
 * 影响配置比例。但它**不改变金额**，而且"我现在把这笔算作固定收益"是合理的用户意图，
 * 所以允许。
 */
object AssetEditPolicy {

    /**
     * 币种和负债标记是否可改。
     *
     * @param snapshotCount 该资产现有的快照条数
     */
    fun canChangeCurrencyAndLiability(snapshotCount: Int): Boolean = snapshotCount <= 1

    /**
     * 不可改时给用户的解释。**要说清为什么，而不是只把控件禁掉。**
     */
    fun lockedReason(snapshotCount: Int): String =
        "已有 $snapshotCount 条历史记录。改币种或负债标记会让全部历史被重新解读 —— " +
            "金额数字不变但含义变了，净值曲线会整体错位。" +
            "要换的话请新建一项资产、把这项归档。"

    /** 估值模式转换是否需要走「模式转换」流程（追加一条新模式的快照）。 */
    fun modeChangeNeedsSnapshot(from: ValuationMode, to: ValuationMode): Boolean = from != to
}
