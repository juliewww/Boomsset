package com.boomsset.domain

/**
 * Which asset fields can be edited, and which cannot.
 *
 * ## Criterion: does it **retroactively reinterpret existing snapshots**?
 *
 * A snapshot records "the fact at that moment" and is immutable. But two fields on
 * `Asset` change **how all historical snapshots are interpreted** — editing them is
 * quietly rewriting history:
 *
 * - **`currency`**: the `valueMinor` stored in a snapshot has no currency of its own.
 *   Changing CNY to USD causes every historical amount to be reinterpreted as US
 *   dollars — the numbers don't move but their meaning completely changes, throwing off
 *   the entire net worth curve.
 * - **`isLiability`**: determines whether this asset adds to or subtracts from net
 *   worth. Flipping it shifts every historical net worth value by **twice this asset's
 *   amount**, silently.
 *
 * So these two fields are **only editable while the asset has a single snapshot** —
 * i.e. "just created, no history yet". In that situation, changing the currency is
 * exactly what the user wants (they picked the wrong one when creating it).
 *
 * By contrast, the following are always editable, because they only change
 * classification and display, never any recorded amount:
 * `name` / `assetClass` / `subtypeId` / `includeInAllocation`
 *
 * `assetClass` deserves a special note: changing it moves this asset (or this
 * liability's offset) to a different top-level class, affecting allocation ratios. But
 * it **does not change any amount**, and "I now consider this fixed income" is a
 * reasonable user intent, so it's allowed.
 */
object AssetEditPolicy {

    /**
     * Whether currency and the liability flag can be changed.
     *
     * @param snapshotCount the number of existing snapshots for this asset
     */
    fun canChangeCurrencyAndLiability(snapshotCount: Int): Boolean = snapshotCount <= 1

    /**
     * The explanation shown to the user when editing isn't allowed. **Must explain why,
     * not just gray out the control.**
     */
    fun lockedReason(snapshotCount: Int): String =
        "已有 $snapshotCount 条历史记录。改币种或负债标记会让全部历史被重新解读 —— " +
            "金额数字不变但含义变了，净值曲线会整体错位。" +
            "要换的话请新建一项资产、把这项归档。"

    /** Whether a valuation mode switch needs to go through the "mode change" flow (appending a new snapshot in the new mode). */
    fun modeChangeNeedsSnapshot(from: ValuationMode, to: ValuationMode): Boolean = from != to
}
