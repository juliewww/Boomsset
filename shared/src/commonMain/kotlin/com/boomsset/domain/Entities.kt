package com.boomsset.domain

import kotlin.time.Instant

/** A subtype (second layer of classification). Serves bookkeeping categorization, user-extensible. */
data class AssetSubtype(
    val id: Long,
    val name: String,
    val assetClass: AssetClass,
    val defaultValuationMode: ValuationMode,
    val isBuiltIn: Boolean,
    val hidden: Boolean = false,
)

/**
 * An asset or liability.
 *
 * [defaultValuationMode] / [defaultQuoteSymbol] are only **defaults for new snapshots**;
 * valuation always looks at [Snapshot.mode].
 */
data class Asset(
    val id: Long,
    val name: String,
    val assetClass: AssetClass,
    val subtypeId: Long,
    val currency: String,
    val isLiability: Boolean = false,
    /** Whether it counts toward allocation ratios. Turning this off is a cheap way to exclude a primary residence. */
    val includeInAllocation: Boolean = true,
    val defaultValuationMode: ValuationMode,
    val defaultQuoteSymbol: String? = null,
    val archivedAt: Instant? = null,
) {
    val isArchived: Boolean get() = archivedAt != null
}

/**
 * The complete holdings state at a point in time. **Immutable, append-only** — correcting
 * history means appending a new record.
 *
 * Uses a sealed interface rather than "one class with a pile of nullable fields", so
 * that "a QUOTED snapshot has no quantity" is simply unconstructible at the type
 * level — the same intent as the CHECK constraint in the schema, as a second layer of
 * defense.
 */
sealed interface Snapshot {
    val id: Long
    val assetId: Long
    val asOf: Instant

    /** **Total cost** in the asset's own currency. Null = the user didn't enter one, excluded from P&L stats. Average cost is a derived value and isn't stored. */
    val costBasisMinor: Money?
    val recordedAt: Instant

    val mode: ValuationMode

    /** The user enters the market value directly. */
    data class Manual(
        override val id: Long,
        override val assetId: Long,
        override val asOf: Instant,
        val value: Money,
        override val costBasisMinor: Money? = null,
        override val recordedAt: Instant,
    ) : Snapshot {
        override val mode: ValuationMode get() = ValuationMode.MANUAL
    }

    /**
     * Recorded by quantity; market value = quantity × quoted unit price.
     *
     * [quoteSymbol] is recorded on the snapshot rather than the asset: after a stock is
     * delisted and converted to MANUAL, these historical snapshots still need to know
     * which symbol to use when looking up historical quotes. This also happens to
     * accommodate symbol changes.
     */
    data class Quoted(
        override val id: Long,
        override val assetId: Long,
        override val asOf: Instant,
        val quantity: Quantity,
        val quoteSymbol: String,
        override val costBasisMinor: Money? = null,
        override val recordedAt: Instant,
    ) : Snapshot {
        override val mode: ValuationMode get() = ValuationMode.QUOTED
    }
}

/** Market quote. Public data, unrelated to any user. At most one per symbol per day. */
data class Quote(
    val symbol: String,
    val asOfDay: String,
    /** Unit price uses scale-8 fixed point, not Money — see [UnitPrice] for the note about low-priced stocks and tokens. */
    val price: UnitPrice,
    val currency: String,
    val fetchedAt: Instant,
)

/** A target allocation. Multiple can coexist, with exactly one [isActive]. */
data class TargetAllocation(
    val id: Long,
    val name: String,
    val isBuiltIn: Boolean,
    val isActive: Boolean,
    /** Target ratio per top-level class, in **basis points** (100% = 10000). */
    val targetsBp: Map<AssetClass, Int>,
) {
    /** The ratios in one allocation must sum to 10000. The UI must validate this before saving. */
    val sumBp: Int get() = targetsBp.values.sum()
    val isValid: Boolean get() = sumBp == TOTAL_BP

    companion object {
        const val TOTAL_BP: Int = 10_000
    }
}
