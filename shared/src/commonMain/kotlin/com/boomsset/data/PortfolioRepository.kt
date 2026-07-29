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

interface PortfolioRepository {
    /** 全量数据流。任何写入都会让它重新发射。 */
    fun observePortfolio(): Flow<PortfolioData>

    /** 生效中的目标配置。没有则发射 null。 */
    fun observeActiveTarget(): Flow<TargetAllocation?>

    /** 全部目标配置（内置 + 自定义）。允许多套并存对比。 */
    fun observeAllocations(): Flow<List<TargetAllocation>>

    fun observeSubtypes(): Flow<List<AssetSubtype>>

    /** 新建资产，同时写入第一条快照。返回资产 id。 */
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
     * 追加一条快照。**不修改已有记录** —— 快照不可变，修正历史就是追加。
     *
     * 调用方要把成本一并传进来（从上一条结转），别留空 ——
     * 留空等于把成本抹掉，收益率会凭空消失。
     */
    suspend fun appendManualSnapshot(assetId: Long, value: Money, costBasis: Money?)

    suspend fun appendQuotedSnapshot(
        assetId: Long,
        quantity: Quantity,
        quoteSymbol: String,
        costBasis: Money?,
    )

    suspend fun archiveAsset(assetId: Long)

    /** 按天 upsert 汇率。同一币种对同一天只留一条 —— 主键保证，不需要应用层查重。 */
    suspend fun upsertFxRate(rate: com.boomsset.domain.FxRate)

    /** 按天 upsert 行情。同上。 */
    suspend fun upsertQuote(quote: com.boomsset.domain.Quote)

    /** 切换生效的目标配置。同时只有一套生效。 */
    suspend fun setActiveAllocation(id: Long)

    /**
     * 保存某套配置的目标比例。
     *
     * 调用方必须先校验之和为 [TargetAllocation.TOTAL_BP] —— 不闭合的配置存进去
     * 会让偏离度全错，而且不报错。这里也再挡一道。
     */
    suspend fun saveAllocationTargets(id: Long, targetsBp: Map<AssetClass, Int>)

    /** 新建自定义配置，返回 id。 */
    suspend fun createAllocation(name: String, targetsBp: Map<AssetClass, Int>): Long

    suspend fun renameAllocation(id: Long, name: String)

    /** 只能删自定义的。内置的删不掉是有意为之。 */
    suspend fun deleteAllocation(id: Long)
}

class SqlDelightPortfolioRepository(
    private val db: BoomssetDatabase,
    /**
     * 数据库读写用的 dispatcher。
     *
     * 用 Default 而不是 IO：`Dispatchers.IO` 不在 commonMain 的公共 API 面上，
     * 而本地 SQLite 在这个数据量级下开销可忽略。真成为瓶颈时改成按平台注入 IO。
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
     * ⚠️ 必须 combine **两个**流。
     *
     * 原来只监听 allocation 表、在 map 里同步查 items —— 那样编辑目标比例（写 items 表）
     * 不会让这个流重新发射，配置页看不到改动。改成两个流 combine 之后，
     * 改名和改比例都会触发刷新。
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
    ): Unit = withContext(dispatcher) {
        val now = clock.now().toEpochMilliseconds()
        db.snapshotQueries.insertManual(
            asset_id = assetId,
            as_of = now,
            value_minor = value.minorUnits,
            cost_basis_minor = costBasis?.minorUnits,
            recorded_at = now,
        )
    }

    override suspend fun appendQuotedSnapshot(
        assetId: Long,
        quantity: Quantity,
        quoteSymbol: String,
        costBasis: Money?,
    ): Unit = withContext(dispatcher) {
        val now = clock.now().toEpochMilliseconds()
        db.snapshotQueries.insertQuoted(
            asset_id = assetId,
            as_of = now,
            quantity_scaled = quantity.scaled,
            quote_symbol = quoteSymbol,
            cost_basis_minor = costBasis?.minorUnits,
            recorded_at = now,
        )
    }

    /**
     * 归档 = 打 archivedAt + **追加一条归零快照**。
     *
     * 归零快照是必须的：结转规则会让最后一条快照永远续下去，不归零的话已卖出的资产
     * 会一直贡献净值。详见 docs/domain.md「归档」。
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
                // 没有历史快照的资产也补一条 0，保持"归档即归零"的一致性
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
        withContext(dispatcher) {
            db.fxRateQueries.upsert(
                base = rate.base,
                quote = rate.quote,
                as_of_day = rate.asOfDay,
                rate_scaled = rate.rate.scaled,
                fetched_at = clock.now().toEpochMilliseconds(),
            )
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
            // 先清再写：否则删掉某个大类的条目会残留旧值
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
        // SQL 里带了 is_built_in = 0 的条件，内置的删不掉
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
