package com.boomsset.data

import com.boomsset.domain.Asset
import com.boomsset.domain.AssetSubtype
import com.boomsset.domain.ExchangeRate
import com.boomsset.domain.FxRate
import com.boomsset.domain.Money
import com.boomsset.domain.Quantity
import com.boomsset.domain.Quote
import com.boomsset.domain.UnitPrice
import com.boomsset.domain.Snapshot
import com.boomsset.domain.TargetAllocation
import com.boomsset.domain.ValuationMode
import kotlin.time.Instant
import com.boomsset.db.Asset as AssetRow
import com.boomsset.db.Asset_subtype as SubtypeRow
import com.boomsset.db.Fx_rate as FxRateRow
import com.boomsset.db.Quote as QuoteRow
import com.boomsset.db.Snapshot as SnapshotRow
import com.boomsset.db.Target_allocation as AllocationRow
import com.boomsset.db.Target_allocation_item as AllocationItemRow

/**
 * Database row → domain model.
 *
 * This layer's job is to collapse a "loose tuple of nullable fields" down into a "domain
 * type that can't represent an invalid state". The database side has CHECK constraints
 * backing it up, so encountering a mismatched combination here means something has
 * **actually gone wrong** — throw an exception directly rather than silently filling in
 * a default value.
 */

internal fun AssetRow.toDomain(): Asset = Asset(
    id = id,
    name = name,
    assetClass = asset_class,
    subtypeId = subtype_id,
    currency = currency,
    isLiability = is_liability,
    includeInAllocation = include_in_allocation,
    defaultValuationMode = default_valuation_mode,
    defaultQuoteSymbol = default_quote_symbol,
    archivedAt = archived_at?.let { Instant.fromEpochMilliseconds(it) },
)

internal fun SubtypeRow.toDomain(): AssetSubtype = AssetSubtype(
    id = id,
    name = name,
    assetClass = asset_class,
    defaultValuationMode = default_valuation_mode,
    isBuiltIn = is_built_in,
    hidden = hidden,
)

internal fun SnapshotRow.toDomain(): Snapshot = when (mode) {
    ValuationMode.MANUAL -> Snapshot.Manual(
        id = id,
        assetId = asset_id,
        asOf = Instant.fromEpochMilliseconds(as_of),
        value = Money(
            requireNotNull(value_minor) {
                "MANUAL 快照 id=$id 缺 value_minor —— schema 的 CHECK 约束应该已经拦住这种行，" +
                    "出现在这里说明数据库被绕过写入过"
            },
        ),
        costBasisMinor = cost_basis_minor?.let { Money(it) },
        recordedAt = Instant.fromEpochMilliseconds(recorded_at),
    )

    ValuationMode.QUOTED -> Snapshot.Quoted(
        id = id,
        assetId = asset_id,
        asOf = Instant.fromEpochMilliseconds(as_of),
        quantity = Quantity(
            requireNotNull(quantity_scaled) {
                "QUOTED 快照 id=$id 缺 quantity_scaled —— 同上，schema 应该拦住了"
            },
        ),
        quoteSymbol = requireNotNull(quote_symbol) {
            "QUOTED 快照 id=$id 缺 quote_symbol —— 没有它历史快照无法取价"
        },
        costBasisMinor = cost_basis_minor?.let { Money(it) },
        recordedAt = Instant.fromEpochMilliseconds(recorded_at),
    )
}

internal fun QuoteRow.toDomain(): Quote = Quote(
    symbol = symbol,
    asOfDay = as_of_day,
    price = UnitPrice(price_scaled),
    currency = currency,
    fetchedAt = Instant.fromEpochMilliseconds(fetched_at),
)

internal fun FxRateRow.toDomain(): FxRate = FxRate(
    base = base,
    quote = quote,
    asOfDay = as_of_day,
    rate = ExchangeRate(rate_scaled),
)

internal fun AllocationRow.toDomain(items: List<AllocationItemRow>): TargetAllocation =
    TargetAllocation(
        id = id,
        name = name,
        isBuiltIn = is_built_in,
        isActive = is_active,
        targetsBp = items.associate { it.asset_class to it.target_percent_bp.toInt() },
    )
