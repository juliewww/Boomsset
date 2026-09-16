package com.boomsset.domain

/**
 * The valuation result for a single asset at a point in time, used by the asset list and
 * detail screens.
 *
 * The three nullable fields have distinct meanings — they are **not** "value is 0":
 * - [snapshot] is null: this asset had no snapshot yet at this point in time
 * - [localValue] is null: the QUOTED asset is missing a quote, so its market value can't be computed
 * - [baseValue] is null: either the above, or the exchange rate to the base currency is missing
 */
data class AssetValuation(
    val asset: Asset,
    val snapshot: Snapshot?,
    /** Market value in the asset's own currency */
    val localValue: Money?,
    /** Market value converted to the base currency */
    val baseValue: Money?,
    /** Unrealized P&L in the asset's own currency. Null if no cost basis was entered or valuation failed. */
    val pnl: ProfitAndLoss?,
    /** Number of existing snapshots for this asset. Used by [AssetEditPolicy] to decide whether currency/liability flag can be changed. */
    val snapshotCount: Int = 0,
    /** The quote used for valuation (QUOTED only). Used to show the price date and check staleness. */
    val quote: Quote? = null,
    /** Days since the quote was fetched. Null = not QUOTED, or there is no quote at all. */
    val priceAgeDays: Int? = null,
) {
    /** Cannot be valued — the UI must show this explicitly, never display it as 0. */
    val isUnpriced: Boolean get() = snapshot != null && baseValue == null

    /** No snapshot has ever been recorded. */
    val hasNoSnapshot: Boolean get() = snapshot == null

    /**
     * Whether the quote is stale enough to warrant a user warning.
     *
     * domain.md requires: "on a failed price fetch, use the last successfully fetched
     * unit price and mark it stale — the UI should make it visible that this price is
     * from 3 days ago."
     *
     * The threshold is 3 days rather than 1, because weekends and holidays naturally
     * have no quotes — a 1-day threshold would false-positive every Monday. **No
     * trading calendar is built here**: that would require maintaining a holiday table
     * per market, at a cost far exceeding the benefit, and a wrong holiday guess would
     * just add noise.
     */
    val isPriceStale: Boolean get() = (priceAgeDays ?: 0) > STALE_AFTER_DAYS

    companion object {
        const val STALE_AFTER_DAYS: Int = 3
    }

    /**
     * Average cost per unit, only present when QUOTED and a cost basis was entered.
     *
     * This is a **derived display value** — the store keeps total cost. See
     * docs/domain.md, "input form".
     */
    val unitCost: Money?
        get() {
            val quoted = snapshot as? Snapshot.Quoted ?: return null
            val cost = quoted.costBasisMinor ?: return null
            return cost.unitCostOver(quoted.quantity)
        }
}
