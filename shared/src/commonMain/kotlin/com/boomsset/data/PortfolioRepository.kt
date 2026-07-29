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

    override fun observeActiveTarget(): Flow<TargetAllocation?> =
        db.targetAllocationQueries.selectAll().asFlow().mapToList(dispatcher).map { rows ->
            val active = rows.firstOrNull { it.is_active } ?: return@map null
            val items = db.targetAllocationQueries.selectItems(active.id).executeAsList()
            active.toDomain(items)
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
}
