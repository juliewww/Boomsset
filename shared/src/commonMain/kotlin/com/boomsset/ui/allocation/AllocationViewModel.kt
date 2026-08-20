package com.boomsset.ui.allocation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.boomsset.data.PortfolioRepository
import com.boomsset.data.SettingsRepository
import com.boomsset.domain.AllocationView
import com.boomsset.data.BUILT_IN_PRESETS
import com.boomsset.domain.AssetClass
import com.boomsset.domain.PortfolioSeriesCalculator
import com.boomsset.domain.TargetAllocation
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

data class AllocationUiState(
    val loading: Boolean = true,
    val view: AllocationView? = null,
    /** 全部目标配置，用于切换和对比。 */
    val allocations: List<TargetAllocation> = emptyList(),
    /**
     * 配置页专属的显示开关：比例的分子分母要不要扣负债。
     *
     * 默认 `true`（算净资产，即原来一直有的行为）——不改变任何人已经在用的默认体验。
     * **只影响配置页的展示**，不落 DataStore、不改 [view] 本身：净值页的净值、
     * 资产页的列表都还是走原来那条算法。`AllocationView.exposures` 里本来就分别
     * 存着 `assets` 和 `liabilities`，两种口径都能从同一份数据现算，不需要
     * 让 [com.boomsset.domain.PortfolioCalculator] 再算一遍或多存一份状态。
     */
    val includeLiabilities: Boolean = true,
) {
    val isEmpty: Boolean get() = !loading && view?.exposures?.values?.all { it.assets.isZero } != false
}

class AllocationViewModel(
    private val repository: PortfolioRepository,
    private val settings: SettingsRepository,
    private val clock: Clock = Clock.System,
    private val zone: TimeZone = TimeZone.currentSystemDefault(),
) : ViewModel() {

    private val baseCurrency = settings.observeBaseCurrency()
    private val includeLiabilities = MutableStateFlow(true)

    val state: StateFlow<AllocationUiState> = combine(
        repository.observePortfolio(),
        repository.observeAllocations(),
        baseCurrency,
        includeLiabilities,
    ) { data, allocations, currency, includeLiabilities ->
        val today = clock.now().toLocalDateTime(zone).date
        AllocationUiState(
            loading = false,
            view = PortfolioSeriesCalculator.currentAllocation(
                data = data,
                baseCurrency = currency,
                today = today,
                zone = zone,
                target = allocations.firstOrNull { it.isActive },
            ),
            allocations = allocations,
            includeLiabilities = includeLiabilities,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AllocationUiState(),
    )

    fun selectAllocation(id: Long) {
        viewModelScope.launch { repository.setActiveAllocation(id) }
    }

    fun setIncludeLiabilities(value: Boolean) {
        includeLiabilities.value = value
    }

    /**
     * 保存目标比例。
     *
     * 之和必须是 100% —— UI 应当在按钮上先挡住，这里再校验一次。
     * 不闭合的配置存进去会让偏离度全错，而且不报错。
     */
    fun saveTargets(id: Long, targetsBp: Map<AssetClass, Int>) {
        if (targetsBp.values.sum() != TargetAllocation.TOTAL_BP) return
        viewModelScope.launch { repository.saveAllocationTargets(id, targetsBp) }
    }

    fun createAllocation(name: String, targetsBp: Map<AssetClass, Int>) {
        if (targetsBp.values.sum() != TargetAllocation.TOTAL_BP) return
        viewModelScope.launch {
            val id = repository.createAllocation(name, targetsBp)
            repository.setActiveAllocation(id)
        }
    }

    fun deleteAllocation(id: Long) {
        viewModelScope.launch { repository.deleteAllocation(id) }
    }

    /**
     * 把内置配置恢复成出厂值。
     *
     * 内置配置是**可编辑但不可删除**的 —— domain.md 要求预设必须允许用户改。
     * 但改坏了要能回去，否则「稳健」这类参考基准就永久丢失了。
     */
    fun restoreBuiltIn(allocation: TargetAllocation) {
        val preset = BUILT_IN_PRESETS.firstOrNull { it.name == allocation.name } ?: return
        viewModelScope.launch {
            repository.saveAllocationTargets(allocation.id, preset.targetsBp)
        }
    }
}
