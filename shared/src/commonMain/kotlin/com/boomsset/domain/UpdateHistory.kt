package com.boomsset.domain

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * Which kind of event an update record belongs to.
 *
 * All three kinds are **derived from the snapshot itself**, not recorded separately —
 * there is no "events table" in the store; the `snapshot` chain, immutable and
 * append-only, is itself the transaction log (see the comment in Snapshot.sq).
 * Recording it a second time would just be one more source of truth that can drift out
 * of sync with the snapshots.
 */
enum class UpdateKind {
    /** The first snapshot for this asset — written together when the asset was created. */
    CREATED,

    /** An ordinary valuation update. */
    UPDATED,

    /** The zero-value snapshot appended when archiving. */
    ARCHIVED,
}

/**
 * A row in the "update history" at the bottom of the asset screen.
 *
 * Carries both [snapshot] and the chain's **immediately preceding entry**, [previous];
 * every "changed from X to Y" is computed on the fly from these two — no "change
 * amount" is ever written to the store. Storing a change amount would require keeping it
 * in sync whenever history is corrected, and since snapshots can be backfilled (appending
 * a more-recently-recorded entry at the same `asOf` is itself a correction), missing one
 * spot in that maintenance would silently produce an inconsistency.
 *
 * ⚠️ **A QUOTED snapshot has no market value stored, and none is computed here either.**
 * Market value = quantity × the quote at that time, and this project already knows that
 * "Quote hasn't had historical backfill done yet" (see the top of AGENTS.md) — most
 * historical points can't fetch a price, so computing it would either produce "cannot be
 * valued" or use today's price to explain a record from three months ago, which is
 * silently miscalculating. So a QUOTED row displays the **quantity and cost actually
 * stored on the snapshot**, not a derived market value.
 */
data class UpdateRecord(
    val asset: Asset,
    val snapshot: Snapshot,
    /**
     * The snapshot immediately preceding this one in the chain. Null = this is the first one.
     *
     * "Immediately preceding" is defined by `(asOf, id)`, the same ordering the
     * carry-forward rule uses to pick "the most recent one as of that point in time" —
     * using a different ordering would make the "previous value" shown here disagree
     * with the one the net worth curve actually carries forward.
     */
    val previous: Snapshot?,
    val kind: UpdateKind,
    /** [Snapshot.recordedAt] converted to a date in the local time zone, for the UI to display directly. */
    val recordedDate: LocalDate,
) {
    val recordedAt: Instant get() = snapshot.recordedAt

    /**
     * The previous entry used a different valuation mode (e.g. QUOTED converted to
     * MANUAL after delisting).
     *
     * In that case the **previous value isn't comparable**: one side is a quantity, the
     * other a market value, so no change amount can be subtracted out. The UI should
     * fall back to "show only the new value" — it must not treat `null` as 0 when
     * computing the difference.
     */
    val modeChanged: Boolean get() = previous != null && previous.mode != snapshot.mode

    /** The market value of a MANUAL snapshot; null for QUOTED. */
    val value: Money? get() = (snapshot as? Snapshot.Manual)?.value

    /** The market value of the previous MANUAL snapshot. Null if there's no previous entry or it isn't MANUAL. */
    val previousValue: Money? get() = (previous as? Snapshot.Manual)?.value

    /** The quantity of a QUOTED snapshot; null for MANUAL. */
    val quantity: Quantity? get() = (snapshot as? Snapshot.Quoted)?.quantity

    val previousQuantity: Quantity? get() = (previous as? Snapshot.Quoted)?.quantity

    /** Total cost. May be present in either mode, and may also be null (user didn't enter one). */
    val cost: Money? get() = snapshot.costBasisMinor

    val previousCost: Money? get() = previous?.costBasisMinor

    /** Change in market value. Only has a value when both ends are MANUAL — see [modeChanged]. */
    val valueChange: Money?
        get() {
            val now = value ?: return null
            val before = previousValue ?: return null
            return now - before
        }

    /** Change in quantity. Only has a value when both ends are QUOTED. */
    val quantityChange: Quantity?
        get() {
            val now = quantity ?: return null
            val before = previousQuantity ?: return null
            return now - before
        }

    /**
     * Change in cost. Only has a value when both ends have a cost entered.
     *
     * Broken out separately because it answers a different question: "was this an
     * add/reduce to the position, or just a market-price change". A cost change with no
     * quantity change is also meaningful (the user is correcting a mistyped cost).
     */
    val costChange: Money?
        get() {
            val now = cost ?: return null
            val before = previousCost ?: return null
            return now - before
        }

    /** Whether there's any displayable change amount. When there isn't, the UI shows only the new value, without drawing a "→". */
    val hasChange: Boolean get() = valueChange != null || quantityChange != null
}

/**
 * Reconstructs "update records" from the raw snapshot stream. Pure function, no IO.
 *
 * ## Why there's no retention window
 *
 * This code **never truncates, filters, or deletes** any record; the UI only paginates
 * the display. Snapshots are the net worth curve's sole data source, and the
 * carry-forward rule takes "the most recent one as of that point in time" — after
 * deleting records "older than six months", an asset that hasn't been updated in six
 * months would fail to find a snapshot even for **today**, and would disappear entirely
 * from net worth, allocation, and the asset list. That isn't a loss of precision, it's
 * an asset vanishing into thin air, without any error (exactly the "silent
 * miscalculation" AGENTS.md repeatedly warns about). There's no storage benefit either:
 * one snapshot row is about 100 bytes, so 20 assets updated monthly for ten years is
 * under 250 KB.
 *
 * The scale ceiling follows the same one as [PortfolioData]: loading everything at tens
 * of thousands of snapshots would visibly slow down, but wouldn't silently produce
 * wrong results.
 */
object UpdateHistory {

    fun build(data: PortfolioData, zone: TimeZone): List<UpdateRecord> {
        val assetsById = data.assets.associateBy { it.id }
        return data.snapshots
            .groupBy { it.assetId }
            .flatMap { (assetId, chain) ->
                // An asset being deleted while its snapshots remain shouldn't happen in
                // theory (there's no entry point for deleting an asset), but this skips
                // it rather than throwing — this is derived data for display, not worth
                // crashing the whole screen over.
                val asset = assetsById[assetId] ?: return@flatMap emptyList<UpdateRecord>()
                val ordered = chain.sortedWith(compareBy({ it.asOf }, { it.id }))
                ordered.mapIndexed { index, snapshot ->
                    UpdateRecord(
                        asset = asset,
                        snapshot = snapshot,
                        previous = ordered.getOrNull(index - 1),
                        kind = kindOf(asset, snapshot, isFirst = index == 0),
                        recordedDate = snapshot.recordedAt.toLocalDateTime(zone).date,
                    )
                }
            }
            // Descending order, newest on top. Sorted by **recordedAt** (when it was
            // recorded), not asOf (which point in time's state it records) — this
            // section answers "what did I do most recently". Currently the two are
            // always equal (appending a snapshot uses the same `now` for both), they
            // will only diverge once a backfill-history entry point exists.
            .sortedWith(
                compareByDescending<UpdateRecord> { it.recordedAt }
                    .thenByDescending { it.snapshot.id },
            )
    }

    /**
     * Checks archived first, then first-entry.
     *
     * The order matters: for an asset that was "archived right after being created,
     * with only two snapshots", the second snapshot can't also satisfy "is the first
     * entry" — but if a "create-and-archive-immediately" path is ever added, the
     * archived status deserves to be shown more than "this is the first entry" would.
     *
     * The archived criterion is `archivedAt == asOf` — `archiveAsset` writes the same
     * `now` into both fields. Unarchiving clears `archivedAt` (the zero-value snapshot
     * itself is kept, see `unarchiveAsset`), so this record falls back to displaying as
     * a plain "updated (zeroed out)" — correct, because the archiving really was undone.
     */
    private fun kindOf(asset: Asset, snapshot: Snapshot, isFirst: Boolean): UpdateKind = when {
        asset.archivedAt != null && asset.archivedAt == snapshot.asOf -> UpdateKind.ARCHIVED
        isFirst -> UpdateKind.CREATED
        else -> UpdateKind.UPDATED
    }
}
