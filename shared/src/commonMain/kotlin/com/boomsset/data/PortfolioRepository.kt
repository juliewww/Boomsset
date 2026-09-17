package com.boomsset.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.boomsset.db.BoomssetDatabase
import com.boomsset.domain.AssetClass
import com.boomsset.domain.AssetSubtype
import com.boomsset.domain.Money
import com.boomsset.domain.PortfolioData
import com.boomsset.domain.Quantity
import com.boomsset.domain.TargetAllocation
import com.boomsset.domain.ValuationMode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.Instant

interface PortfolioRepository {
    /** The full data stream. Any write causes it to re-emit. */
    fun observePortfolio(): Flow<PortfolioData>

    /** The currently active target allocation. Emits null if there is none. */
    fun observeActiveTarget(): Flow<TargetAllocation?>

    /** All target allocations (built-in + custom). Multiple can coexist for comparison. */
    fun observeAllocations(): Flow<List<TargetAllocation>>

    fun observeSubtypes(): Flow<List<AssetSubtype>>

    /** Creates a new asset and writes its first snapshot at the same time. Returns the asset id. */
    suspend fun createAsset(
        name: String,
        assetClass: AssetClass,
        subtypeId: Long,
        currency: String,
        isLiability: Boolean,
        includeInAllocation: Boolean,
        mode: ValuationMode,
        quoteSymbol: String?,
        initialValue: Money?,
        initialQuantity: Quantity?,
        costBasis: Money?,
    ): Long

    /**
     * Appends a snapshot. **Never modifies an existing record** — snapshots are immutable;
     * correcting history means appending, not editing.
     *
     * The caller must pass along the cost basis (carried forward from the previous one) —
     * don't leave it null. Leaving it null erases the cost basis, and the return rate would
     * vanish for no reason.
     *
     * [asOf] is the point in time this valuation **belongs to**; null means "now". Supplying
     * it means **backfilling history**: `asOf` lands on the specified point in time, while
     * `recordedAt` is always the actual time of entry — the two are intentionally separate
     * (see docs/domain.md). The net worth curve is ordered by `asOf`, while the update history
     * is ordered by `recordedAt`, so backfilling last month's data won't make it show up as
     * "just recorded today".
     */
    suspend fun appendManualSnapshot(
        assetId: Long,
        value: Money,
        costBasis: Money?,
        asOf: Instant? = null,
    )

    suspend fun appendQuotedSnapshot(
        assetId: Long,
        quantity: Quantity,
        quoteSymbol: String,
        costBasis: Money?,
        asOf: Instant? = null,
    )

    suspend fun archiveAsset(assetId: Long)

    /**
     * Updates an asset's metadata.
     *
     * ⚠️ **`currency` and `isLiability` retroactively reinterpret every historical
     * snapshot**, so they may only be changed while the asset has just a single snapshot
     * (freshly created, no history yet) — the caller determines this via
     * [AssetEditPolicy], and this is checked here again as a second line of defense.
     *
     * `name` / `assetClass` / `subtypeId` / `includeInAllocation` can be changed at any
     * time: they only affect categorization and display, never any recorded amount.
     */
    suspend fun updateAssetMeta(
        assetId: Long,
        name: String,
        assetClass: AssetClass,
        subtypeId: Long,
        currency: String,
        includeInAllocation: Boolean,
        defaultValuationMode: ValuationMode,
        defaultQuoteSymbol: String?,
    )

    /**
     * Unarchives an asset. **Only clears `archivedAt`; doesn't touch snapshots.**
     *
     * The zero-value snapshot appended at archive time is a real record and can't be undone
     * — so after unarchiving, the asset will show 0 and the user needs to update its
     * valuation themselves. Fabricating a "restore original value" snapshot would be wrong.
     */
    suspend fun unarchiveAsset(assetId: Long)

    /** Adds a new custom subtype. domain.md requires that subtypes be user-extensible. */
    suspend fun createSubtype(
        name: String,
        assetClass: AssetClass,
        defaultValuationMode: ValuationMode,
    ): Long

    /** Upserts an FX rate by day. Only one record per currency pair per day — guaranteed by the primary key, so no app-level dedup is needed. */
    suspend fun upsertFxRate(rate: com.boomsset.domain.FxRate)

    /**
     * Batch upserts FX rates by day, **in a single transaction**.
     *
     * A single historical backfill can write anywhere from hundreds to thousands of rows.
     * Writing them one at a time would make the data stream emit the same number of times,
     * triggering a net worth curve recalculation on every emission — the transaction
     * collapses them into a single emission.
     */
    suspend fun upsertFxRates(rates: List<com.boomsset.domain.FxRate>)

    /** Upserts a quote by day. Same as above. */
    suspend fun upsertQuote(quote: com.boomsset.domain.Quote)

    /** Switches the active target allocation. Only one can be active at a time. */
    suspend fun setActiveAllocation(id: Long)

    /**
     * Saves the target percentages for an allocation.
     *
     * The caller must first verify they sum to [TargetAllocation.TOTAL_BP] — saving an
     * unclosed allocation would make every deviation figure wrong, silently. This is
     * checked here again as a second line of defense.
     */
    suspend fun saveAllocationTargets(id: Long, targetsBp: Map<AssetClass, Int>)

    /** Creates a new custom allocation, returning its id. */
    suspend fun createAllocation(name: String, targetsBp: Map<AssetClass, Int>): Long

    suspend fun renameAllocation(id: Long, name: String)

    /** Only custom allocations can be deleted. Built-in ones being undeletable is intentional. */
    suspend fun deleteAllocation(id: Long)
}

class SqlDelightPortfolioRepository(
    private val db: BoomssetDatabase,
    /**
     * The dispatcher used for database reads/writes.
     *
     * Uses Default rather than IO: `Dispatchers.IO` isn't part of commonMain's public API
     * surface, and local SQLite has negligible overhead at this data volume. If it ever
     * becomes a real bottleneck, switch to injecting IO per platform.
     */
    private val dispatcher: CoroutineDispatcher,
    private val clock: Clock = Clock.System,
) : PortfolioRepository {

    override fun observePortfolio(): Flow<PortfolioData> = combine(
        db.assetQueries.selectAll().asFlow().mapToList(dispatcher),
        db.snapshotQueries.selectAllSnapshots().asFlow().mapToList(dispatcher),
        db.quoteQueries.selectAllQuotes().asFlow().mapToList(dispatcher),
        db.fxRateQueries.selectAllRates().asFlow().mapToList(dispatcher),
    ) { assets, snapshots, quotes, rates ->
        PortfolioData(
            assets = assets.map { it.toDomain() },
            snapshots = snapshots.map { it.toDomain() },
            quotes = quotes.map { it.toDomain() },
            fxRates = rates.map { it.toDomain() },
        )
    }

    /**
     * ⚠️ Must combine **two** streams.
     *
     * Originally this only observed the allocation table and synchronously queried items
     * inside the map — which meant editing target percentages (writing the items table)
     * never made this stream re-emit, so the allocation screen wouldn't see the change.
     * After combining two streams, both renaming and changing percentages trigger a refresh.
     */
    override fun observeActiveTarget(): Flow<TargetAllocation?> =
        observeAllocations().map { list -> list.firstOrNull { it.isActive } }

    override fun observeAllocations(): Flow<List<TargetAllocation>> = combine(
        db.targetAllocationQueries.selectAll().asFlow().mapToList(dispatcher),
        db.targetAllocationQueries.selectAllItems().asFlow().mapToList(dispatcher),
    ) { allocations, items ->
        val byAllocation = items.groupBy { it.allocation_id }
        allocations.map { row -> row.toDomain(byAllocation[row.id].orEmpty()) }
    }

    override fun observeSubtypes(): Flow<List<AssetSubtype>> =
        db.assetSubtypeQueries.selectAll().asFlow().mapToList(dispatcher)
            .map { rows -> rows.map { it.toDomain() } }

    override suspend fun createAsset(
        name: String,
        assetClass: AssetClass,
        subtypeId: Long,
        currency: String,
        isLiability: Boolean,
        includeInAllocation: Boolean,
        mode: ValuationMode,
        quoteSymbol: String?,
        initialValue: Money?,
        initialQuantity: Quantity?,
        costBasis: Money?,
    ): Long = withContext(dispatcher) {
        val now = clock.now().toEpochMilliseconds()
        var assetId = -1L

        db.transaction {
            db.assetQueries.insert(
                name = name,
                asset_class = assetClass,
                subtype_id = subtypeId,
                currency = currency,
                is_liability = isLiability,
                include_in_allocation = includeInAllocation,
                default_valuation_mode = mode,
                default_quote_symbol = quoteSymbol,
                created_at = now,
            )
            assetId = db.assetQueries.lastInsertedId().executeAsOne()

            when (mode) {
                ValuationMode.MANUAL -> db.snapshotQueries.insertManual(
                    asset_id = assetId,
                    as_of = now,
                    value_minor = requireNotNull(initialValue) {
                        "MANUAL 资产必须给初始市值"
                    }.minorUnits,
                    cost_basis_minor = costBasis?.minorUnits,
                    recorded_at = now,
                )

                ValuationMode.QUOTED -> db.snapshotQueries.insertQuoted(
                    asset_id = assetId,
                    as_of = now,
                    quantity_scaled = requireNotNull(initialQuantity) {
                        "QUOTED 资产必须给初始份额"
                    }.scaled,
                    quote_symbol = requireNotNull(quoteSymbol) {
                        "QUOTED 资产必须给行情代码"
                    },
                    cost_basis_minor = costBasis?.minorUnits,
                    recorded_at = now,
                )
            }
        }
        assetId
    }

    override suspend fun appendManualSnapshot(
        assetId: Long,
        value: Money,
        costBasis: Money?,
        asOf: Instant?,
    ): Unit = withContext(dispatcher) {
        val now = clock.now().toEpochMilliseconds()
        db.snapshotQueries.insertManual(
            asset_id = assetId,
            as_of = asOf?.toEpochMilliseconds() ?: now,
            value_minor = value.minorUnits,
            cost_basis_minor = costBasis?.minorUnits,
            recorded_at = now,   // Even when backfilling, recordedAt is still the real entry time, not asOf
        )
    }

    override suspend fun appendQuotedSnapshot(
        assetId: Long,
        quantity: Quantity,
        quoteSymbol: String,
        costBasis: Money?,
        asOf: Instant?,
    ): Unit = withContext(dispatcher) {
        val now = clock.now().toEpochMilliseconds()
        db.snapshotQueries.insertQuoted(
            asset_id = assetId,
            as_of = asOf?.toEpochMilliseconds() ?: now,
            quantity_scaled = quantity.scaled,
            quote_symbol = quoteSymbol,
            cost_basis_minor = costBasis?.minorUnits,
            recorded_at = now,
        )
    }

    /**
     * Archiving = setting archivedAt + **appending a zeroed-out snapshot**.
     *
     * The zeroed snapshot is mandatory: the carry-forward rule keeps repeating the last
     * snapshot indefinitely, so without zeroing it out, a sold asset would keep
     * contributing to net worth forever. See docs/domain.md, "Archiving", for details.
     */
    override suspend fun archiveAsset(assetId: Long): Unit = withContext(dispatcher) {
        val now = clock.now().toEpochMilliseconds()
        db.transaction {
            val last = db.snapshotQueries.selectLatestForAsset(assetId).executeAsOneOrNull()
            when (last?.mode) {
                ValuationMode.QUOTED -> db.snapshotQueries.insertQuoted(
                    asset_id = assetId,
                    as_of = now,
                    quantity_scaled = 0,
                    quote_symbol = last.quote_symbol!!,
                    cost_basis_minor = last.cost_basis_minor,
                    recorded_at = now,
                )
                // Assets with no prior snapshot also get a 0 record, to keep "archiving means zeroing out" consistent
                else -> db.snapshotQueries.insertManual(
                    asset_id = assetId,
                    as_of = now,
                    value_minor = 0,
                    cost_basis_minor = last?.cost_basis_minor,
                    recorded_at = now,
                )
            }
            db.assetQueries.archive(archived_at = now, id = assetId)
        }
    }

    override suspend fun upsertFxRate(rate: com.boomsset.domain.FxRate): Unit =
        upsertFxRates(listOf(rate))

    override suspend fun upsertFxRates(rates: List<com.boomsset.domain.FxRate>): Unit =
        withContext(dispatcher) {
            if (rates.isEmpty()) return@withContext
            val now = clock.now().toEpochMilliseconds()
            // A single transaction: a single historical backfill is hundreds to thousands of
            // rows; committing one at a time would make the selectAllRates stream emit that
            // many times, recalculating the whole net worth curve every time (≈2500 times for
            // ten years of data)
            db.transaction {
                rates.forEach {
                    db.fxRateQueries.upsert(
                        base = it.base,
                        quote = it.quote,
                        as_of_day = it.asOfDay,
                        rate_scaled = it.rate.scaled,
                        fetched_at = now,
                    )
                }
            }
        }

    override suspend fun updateAssetMeta(
        assetId: Long,
        name: String,
        assetClass: AssetClass,
        subtypeId: Long,
        currency: String,
        includeInAllocation: Boolean,
        defaultValuationMode: ValuationMode,
        defaultQuoteSymbol: String?,
    ): Unit = withContext(dispatcher) {
        db.assetQueries.updateMeta(
            name = name,
            asset_class = assetClass,
            subtype_id = subtypeId,
            currency = currency,
            include_in_allocation = includeInAllocation,
            default_valuation_mode = defaultValuationMode,
            default_quote_symbol = defaultQuoteSymbol,
            id = assetId,
        )
    }

    override suspend fun unarchiveAsset(assetId: Long): Unit = withContext(dispatcher) {
        db.assetQueries.unarchive(assetId)
    }

    override suspend fun createSubtype(
        name: String,
        assetClass: AssetClass,
        defaultValuationMode: ValuationMode,
    ): Long = withContext(dispatcher) {
        var newId = -1L
        db.transaction {
            db.assetSubtypeQueries.insertCustom(name, assetClass, defaultValuationMode)
            newId = db.assetSubtypeQueries.lastInsertedId().executeAsOne()
        }
        newId
    }

    override suspend fun setActiveAllocation(id: Long): Unit = withContext(dispatcher) {
        db.transaction {
            db.targetAllocationQueries.clearActive()
            db.targetAllocationQueries.setActive(id)
        }
    }

    override suspend fun saveAllocationTargets(
        id: Long,
        targetsBp: Map<AssetClass, Int>,
    ): Unit = withContext(dispatcher) {
        requireClosed(targetsBp)
        db.transaction {
            // Clear then rewrite: otherwise removing an asset class's entry would leave a stale value behind
            db.targetAllocationQueries.deleteItems(id)
            targetsBp.forEach { (assetClass, bp) ->
                db.targetAllocationQueries.upsertItem(id, assetClass, bp.toLong())
            }
        }
    }

    override suspend fun createAllocation(
        name: String,
        targetsBp: Map<AssetClass, Int>,
    ): Long = withContext(dispatcher) {
        requireClosed(targetsBp)
        var newId = -1L
        db.transaction {
            db.targetAllocationQueries.insertAllocation(
                name = name,
                is_built_in = false,
                is_active = false,
                created_at = clock.now().toEpochMilliseconds(),
            )
            newId = db.targetAllocationQueries.lastInsertedId().executeAsOne()
            targetsBp.forEach { (assetClass, bp) ->
                db.targetAllocationQueries.upsertItem(newId, assetClass, bp.toLong())
            }
        }
        newId
    }

    override suspend fun renameAllocation(id: Long, name: String): Unit =
        withContext(dispatcher) {
            db.targetAllocationQueries.updateName(name, id)
        }

    override suspend fun deleteAllocation(id: Long): Unit = withContext(dispatcher) {
        // The SQL includes an is_built_in = 0 condition, so built-in ones can't be deleted
        db.targetAllocationQueries.deleteAllocation(id)
    }

    private fun requireClosed(targetsBp: Map<AssetClass, Int>) {
        val sum = targetsBp.values.sum()
        require(sum == TargetAllocation.TOTAL_BP) {
            "目标比例之和是 $sum 基点，必须是 ${TargetAllocation.TOTAL_BP}（100%）。" +
                "不闭合的配置会让偏离度全错，而且不会报错。"
        }
    }

    override suspend fun upsertQuote(quote: com.boomsset.domain.Quote): Unit =
        withContext(dispatcher) {
            db.quoteQueries.upsert(
                symbol = quote.symbol,
                as_of_day = quote.asOfDay,
                price_scaled = quote.price.scaled,
                currency = quote.currency,
                fetched_at = clock.now().toEpochMilliseconds(),
            )
        }
}
