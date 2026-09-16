package com.boomsset.domain

/**
 * Valuation mode.
 *
 * ⚠️ **Valuation always looks at [Snapshot.mode], never [Asset.defaultValuationMode]** —
 * the latter is only the default for new snapshots. After a stock is delisted and
 * converted to MANUAL, its historical by-quantity snapshots must still be valued as
 * QUOTED; deciding based on the asset's current mode would read the empty valueMinor on
 * old snapshots, breaking history. And this error **doesn't raise an error, it just
 * silently miscalculates** — it wouldn't surface until an asset actually undergoes a
 * mode conversion.
 *
 * Cost basis (costBasisMinor) **is unrelated to this enum — both modes can have it
 * filled in, and both display a return rate**. Don't mistakenly infer "cost is
 * read-only" from "market value is read-only".
 */
enum class ValuationMode {
    /** Market value = quantity × market unit price, read-only; the user edits quantity and cost. Participates in quote refreshing. */
    QUOTED,

    /** Market value is entered directly by the user, editable; does not participate in quote refreshing. */
    MANUAL,
}
