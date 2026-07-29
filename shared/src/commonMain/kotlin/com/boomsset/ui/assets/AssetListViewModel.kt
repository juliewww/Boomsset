package com.boomsset.ui.assets

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.boomsset.data.PortfolioRepository
import com.boomsset.data.SettingsRepository
import com.boomsset.domain.AssetClass
import com.boomsset.domain.AssetSubtype
import com.boomsset.domain.AssetValuation
import com.boomsset.domain.Money
import com.boomsset.domain.PortfolioSeriesCalculator
import com.boomsset.domain.Quantity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

data class AssetListUiState(
    val loading: Boolean = true,
    val baseCurrency: String = "CNY",
    /** 按大类分组，组内按名字。已归档的不在这里。 */
    val grouped: Map<AssetClass, List<AssetValuation>> = emptyMap(),
    val archivedCount: Int = 0,
    /** 已归档的资产，供「取消归档」用。 */
    val archived: List<AssetValuation> = emptyList(),
    val subtypes: List<AssetSubtype> = emptyList(),
) {
    val isEmpty: Boolean get() = !loading && grouped.values.all { it.isEmpty() }
    val unpricedCount: Int get() = grouped.values.sumOf { list -> list.count { it.isUnpriced } }
}

class AssetListViewModel(
    private val repository: PortfolioRepository,
    private val settings: SettingsRepository,
    private val clock: Clock = Clock.System,
    private val zone: TimeZone = TimeZone.currentSystemDefault(),
) : ViewModel() {

    private val baseCurrency = settings.observeBaseCurrency()

    val state: StateFlow<AssetListUiState> = combine(
        repository.observePortfolio(),
        repository.observeSubtypes(),
        baseCurrency,
    ) { data, subtypes, currency ->
        val today = clock.now().toLocalDateTime(zone).date
        val withArchived = PortfolioSeriesCalculator.currentAssetValuations(
            data = data,
            baseCurrency = currency,
            today = today,
            zone = zone,
            includeArchived = true,
        )
        val valuations = PortfolioSeriesCalculator.currentAssetValuations(
            data = data,
            baseCurrency = currency,
            today = today,
            zone = zone,
        )
        AssetListUiState(
            loading = false,
            baseCurrency = currency,
            grouped = AssetClass.displayOrder.associateWith { assetClass ->
                valuations
                    .filter { it.asset.assetClass == assetClass }
                    .sortedBy { it.asset.name }
            },
            archivedCount = data.assets.count { it.isArchived },
            archived = withArchived.filter { it.asset.isArchived }.sortedBy { it.asset.name },
            subtypes = subtypes,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AssetListUiState(),
    )

    /**
     * 更新手动估值资产的市值。
     *
     * [costBasis] 由 UI 从上一条快照预填后传进来 —— **不要让它默认为 null**，
     * 那等于把成本抹掉，收益率会凭空消失。见 docs/domain.md「实现时容易写错的地方」第 5 条。
     */
    fun updateManualValue(assetId: Long, value: Money, costBasis: Money?) {
        viewModelScope.launch {
            repository.appendManualSnapshot(assetId, value, costBasis)
        }
    }

    /**
     * 更新按份额计值资产的持仓。
     *
     * 份额和成本一起收 —— 加仓意味着又投了钱，份额涨了而成本没涨会让收益率虚高。
     */
    fun updateQuotedHolding(
        assetId: Long,
        quantity: Quantity,
        quoteSymbol: String,
        costBasis: Money?,
    ) {
        viewModelScope.launch {
            repository.appendQuotedSnapshot(assetId, quantity, quoteSymbol, costBasis)
        }
    }

    /** 归档。会追加一条归零快照，历史曲线不受影响。 */
    fun archive(assetId: Long) {
        viewModelScope.launch { repository.archiveAsset(assetId) }
    }

    /**
     * 取消归档。只清 archivedAt，那条归零快照留着 ——
     * 所以资产会以 0 出现，用户需要自己再更新一次估值。
     */
    fun unarchive(assetId: Long) {
        viewModelScope.launch { repository.unarchiveAsset(assetId) }
    }

    fun editMeta(valuation: AssetValuation, edit: com.boomsset.ui.assets.AssetMetaEdit) {
        viewModelScope.launch {
            repository.updateAssetMeta(
                assetId = valuation.asset.id,
                name = edit.name,
                assetClass = edit.assetClass,
                subtypeId = edit.subtypeId,
                currency = edit.currency,
                includeInAllocation = edit.includeInAllocation,
                // 估值模式不在这里改 —— 转换要走「追加一条新模式快照」的流程
                defaultValuationMode = valuation.asset.defaultValuationMode,
                defaultQuoteSymbol = valuation.asset.defaultQuoteSymbol,
            )
        }
    }

    fun addSubtype(name: String, assetClass: AssetClass) {
        viewModelScope.launch {
            repository.createSubtype(
                name = name,
                assetClass = assetClass,
                defaultValuationMode = com.boomsset.domain.ValuationMode.MANUAL,
            )
        }
    }
}
