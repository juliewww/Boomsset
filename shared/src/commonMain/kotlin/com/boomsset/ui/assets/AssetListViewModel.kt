package com.boomsset.ui.assets

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.boomsset.data.PortfolioRepository
import com.boomsset.domain.AssetClass
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
) {
    val isEmpty: Boolean get() = !loading && grouped.values.all { it.isEmpty() }
    val unpricedCount: Int get() = grouped.values.sumOf { list -> list.count { it.isUnpriced } }
}

class AssetListViewModel(
    private val repository: PortfolioRepository,
    private val clock: Clock = Clock.System,
    private val zone: TimeZone = TimeZone.currentSystemDefault(),
) : ViewModel() {

    private val baseCurrency = MutableStateFlow("CNY")

    val state: StateFlow<AssetListUiState> = combine(
        repository.observePortfolio(),
        baseCurrency,
    ) { data, currency ->
        val today = clock.now().toLocalDateTime(zone).date
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
}
