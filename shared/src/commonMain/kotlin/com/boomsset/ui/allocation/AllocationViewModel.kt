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

    val state: StateFlow<AllocationUiState> = combine(
        repository.observePortfolio(),
        repository.observeAllocations(),
        baseCurrency,
    ) { data, allocations, currency ->
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
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AllocationUiState(),
    )

    fun selectAllocation(id: Long) {
        viewModelScope.launch { repository.setActiveAllocation(id) }
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
