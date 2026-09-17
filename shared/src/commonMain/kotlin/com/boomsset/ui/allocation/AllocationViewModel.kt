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
    /** All target allocations, used for switching and comparison. */
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
     * Save target ratios.
     *
     * They must sum to 100% — the UI should already block this at the button, this is a second
     * check here. Saving a configuration that doesn't sum to 100% would make every deviation
     * value wrong, and silently so.
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
     * Restore a built-in allocation to its factory values.
     *
     * Built-in allocations are **editable but not deletable** — domain.md requires that presets
     * be user-editable. But if edited into a bad state, there must be a way back, otherwise a
     * reference baseline like "conservative" would be permanently lost.
     */
    fun restoreBuiltIn(allocation: TargetAllocation) {
        val preset = BUILT_IN_PRESETS.firstOrNull { it.name == allocation.name } ?: return
        viewModelScope.launch {
            repository.saveAllocationTargets(allocation.id, preset.targetsBp)
        }
    }
}
