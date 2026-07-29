package com.boomsset.ui.allocation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.boomsset.data.PortfolioRepository
import com.boomsset.domain.AllocationView
import com.boomsset.domain.PortfolioSeriesCalculator
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
) {
    val isEmpty: Boolean get() = !loading && view?.exposures?.values?.all { it.assets.isZero } != false
}

class AllocationViewModel(
    private val repository: PortfolioRepository,
    private val clock: Clock = Clock.System,
    private val zone: TimeZone = TimeZone.currentSystemDefault(),
) : ViewModel() {

    private val baseCurrency = MutableStateFlow("CNY")

    val state: StateFlow<AllocationUiState> = combine(
        repository.observePortfolio(),
        repository.observeActiveTarget(),
        baseCurrency,
    ) { data, target, currency ->
        val today = clock.now().toLocalDateTime(zone).date
        AllocationUiState(
            loading = false,
            view = PortfolioSeriesCalculator.currentAllocation(
                data = data,
                baseCurrency = currency,
                today = today,
                zone = zone,
                target = target,
            ),
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AllocationUiState(),
    )
}
