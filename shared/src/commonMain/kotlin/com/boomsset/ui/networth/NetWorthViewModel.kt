package com.boomsset.ui.networth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.boomsset.data.PortfolioRepository
import com.boomsset.domain.AssetClass
import com.boomsset.domain.AssetSubtype
import com.boomsset.domain.Money
import com.boomsset.domain.NetWorthSeries
import com.boomsset.domain.Period
import com.boomsset.domain.PortfolioPnL
import com.boomsset.domain.PortfolioSeriesCalculator
import com.boomsset.domain.Quantity
import com.boomsset.domain.ValuationMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

data class NetWorthUiState(
    val loading: Boolean = true,
    val series: NetWorthSeries? = null,
    val pnl: PortfolioPnL? = null,
    val period: Period = Period.MONTH,
    val baseCurrency: String = "CNY",
    val subtypes: List<AssetSubtype> = emptyList(),
    /** 无法估值的资产数量 —— 行情或汇率缺失。UI 必须提示，不能静默低估净值。 */
    val unpricedCount: Int = 0,
    val hasAssets: Boolean = false,
) {
    /**
     * 空状态看的是**有没有资产**，不是 `series.latest == null`。
     *
     * buildSeries 即使在零资产时也会生成一整串净值为 0 的点（取样日期是按周期算的，
     * 与有没有数据无关），所以 latest 永远非空 —— 用它判空会导致空状态永不出现，
     * 新用户看到的是一条平坦的零线而不是引导。
     */
    val isEmpty: Boolean get() = !loading && !hasAssets
}

class NetWorthViewModel(
    private val repository: PortfolioRepository,
    private val clock: Clock = Clock.System,
    private val zone: TimeZone = TimeZone.currentSystemDefault(),
) : ViewModel() {

    private val period = MutableStateFlow(Period.MONTH)

    // 基准币种默认 CNY、可切换。作为查询参数传入，不落到 Asset/Snapshot 上。
    private val baseCurrency = MutableStateFlow("CNY")

    val state: StateFlow<NetWorthUiState> = combine(
        repository.observePortfolio(),
        repository.observeSubtypes(),
        period,
        baseCurrency,
    ) { data, subtypes, period, currency ->
        val today = clock.now().toLocalDateTime(zone).date
        val series = PortfolioSeriesCalculator.buildSeries(
            data = data,
            period = period,
            baseCurrency = currency,
            today = today,
            zone = zone,
        )
        NetWorthUiState(
            loading = false,
            series = series,
            pnl = PortfolioSeriesCalculator.currentProfitAndLoss(data, currency, today, zone),
            period = period,
            baseCurrency = currency,
            subtypes = subtypes,
            unpricedCount = series.latest?.unpricedAssetIds?.size ?: 0,
            hasAssets = data.assets.any { !it.isArchived },
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = NetWorthUiState(),
    )

    fun selectPeriod(value: Period) {
        period.value = value
    }

    fun selectBaseCurrency(code: String) {
        baseCurrency.value = code
    }

    fun addManualAsset(
        name: String,
        assetClass: AssetClass,
        subtypeId: Long,
        currency: String,
        isLiability: Boolean,
        includeInAllocation: Boolean,
        value: Money,
        costBasis: Money?,
    ) {
        viewModelScope.launch {
            repository.createAsset(
                name = name,
                assetClass = assetClass,
                subtypeId = subtypeId,
                currency = currency,
                isLiability = isLiability,
                includeInAllocation = includeInAllocation,
                mode = ValuationMode.MANUAL,
                quoteSymbol = null,
                initialValue = value,
                initialQuantity = null,
                costBasis = costBasis,
            )
        }
    }

    fun addQuotedAsset(
        name: String,
        assetClass: AssetClass,
        subtypeId: Long,
        currency: String,
        quoteSymbol: String,
        quantity: Quantity,
        costBasis: Money?,
    ) {
        viewModelScope.launch {
            repository.createAsset(
                name = name,
                assetClass = assetClass,
                subtypeId = subtypeId,
                currency = currency,
                isLiability = false,
                includeInAllocation = true,
                mode = ValuationMode.QUOTED,
                quoteSymbol = quoteSymbol,
                initialValue = null,
                initialQuantity = quantity,
                costBasis = costBasis,
            )
        }
    }
}
