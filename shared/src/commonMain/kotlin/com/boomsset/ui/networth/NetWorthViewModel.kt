package com.boomsset.ui.networth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.boomsset.data.PortfolioRepository
import com.boomsset.data.RateRefresher
import com.boomsset.data.SettingsRepository
import com.boomsset.domain.AllocationSeries
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

/**
 * 净值页图表的展示选项。
 *
 * 四个字段合成一个对象、走**一条** flow，而不是四个 `MutableStateFlow` ——
 * `combine` 的具名重载最多 5 路，而且这四个都只影响"图表怎么画"、变化时机也一致，
 * 拆开只会让 combine 变长、可读性变差。
 */
data class ChartOptions(
    val period: Period = Period.MONTH,
    val mode: ChartMode = ChartMode.TOTAL,
    val style: ChartStyle = ChartStyle.COLUMN,
    /**
     * 图例里被**取消勾选**的大类。
     *
     * 存"隐藏"而不是"显示"是有意的：将来真加了第六个大类，它会默认可见，
     * 而不是因为不在这个集合里就被悄悄藏掉。
     */
    val hiddenClasses: Set<AssetClass> = emptySet(),
) {
    /** 要画的大类，**顺序永远是 [AssetClass.displayOrder]** —— 堆叠顺序和配色都依赖它。 */
    val visibleClasses: List<AssetClass>
        get() = AssetClass.displayOrder.filterNot { it in hiddenClasses }
}

data class NetWorthUiState(
    val loading: Boolean = true,
    val series: NetWorthSeries? = null,
    /** 按大类拆开的同一段时间序列，取样点与 [series] 逐点对齐。 */
    val allocationSeries: AllocationSeries? = null,
    val chart: ChartOptions = ChartOptions(),
    val pnl: PortfolioPnL? = null,
    val baseCurrency: String = "CNY",
    val subtypes: List<AssetSubtype> = emptyList(),
    /** 无法估值的资产数量 —— 行情或汇率缺失。UI 必须提示，不能静默低估净值。 */
    val unpricedCount: Int = 0,
    val hasAssets: Boolean = false,
    /**
     * 最近一次记快照的日期，和距今多少天。
     *
     * 记快照不记流水，所以净值不会自己更新 —— 这两个值是用户判断"顶上那个数还新不新"
     * 的唯一线索。见 [PortfolioSeriesCalculator.lastRecordedDate]。
     */
    val lastRecordedDate: LocalDate? = null,
    val daysSinceLastRecord: Int? = null,
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
    private val settings: SettingsRepository,
    private val rateRefresher: RateRefresher,
    private val clock: Clock = Clock.System,
    private val zone: TimeZone = TimeZone.currentSystemDefault(),
) : ViewModel() {

    private val chartOptions = MutableStateFlow(ChartOptions())

    // 基准币种默认 CNY、可切换。作为查询参数传入，不落到 Asset/Snapshot 上。
    private val baseCurrency = settings.observeBaseCurrency()

    init {
        // 刷新汇率。只写 fx_rate，不写 snapshot —— 见 RateRefresher。
        //
        // ⚠️ 必须随「需要的币种集合」变化重新触发，不能只在 init 跑一次：
        // 首次启动时还没有任何资产，需要的币种是空集；之后新增一个 USD 资产就永远
        // 拉不到它的汇率了。这是实跑时发现的 bug。
        //
        // RateRefresher 内部记录已尝试的 (币种, 日期)，所以写入 fx_rate 引起的
        // 重新发射不会造成无限循环。
        viewModelScope.launch {
            combine(
                repository.observePortfolio(),
                settings.observeBaseCurrency(),
            ) { data, currency ->
                // 币种集合或行情代码集合任一变化都要重新刷新
                val currencies = data.assets.filter { !it.isArchived }.map { it.currency }.toSet()
                val symbols = data.snapshots
                    .filterIsInstance<com.boomsset.domain.Snapshot.Quoted>()
                    .map { it.quoteSymbol }.toSet()
                (currencies + symbols) to currency
            }.distinctUntilChanged().collect { (_, currency) ->
                rateRefresher.refreshForHoldings(currency)
                rateRefresher.refreshQuotes()
            }
        }
    }

    val state: StateFlow<NetWorthUiState> = combine(
        repository.observePortfolio(),
        repository.observeSubtypes(),
        chartOptions,
        baseCurrency,
    ) { data, subtypes, chart, currency ->
        val today = clock.now().toLocalDateTime(zone).date
        val series = PortfolioSeriesCalculator.buildSeries(
            data = data,
            period = chart.period,
            baseCurrency = currency,
            today = today,
            zone = zone,
            // 按年/按季看的时候，账号可能才用了几个月 —— 不裁的话前面一大截
            // 全是「资产还不存在」的 0 值点，占满图表还没有信息量。
            trimBeforeFirstSnapshot = true,
        )
        // 无论当前看的是不是大类，都算 —— 每个取样点一次 allocation()，
        // 对这个数据量（最多 12 个点 × 几十项资产）可以忽略，
        // 换来的是切换开关时图表立刻就有数据，不用等一轮重算。
        val allocationSeries = PortfolioSeriesCalculator.buildAllocationSeries(
            data = data,
            period = chart.period,
            baseCurrency = currency,
            today = today,
            zone = zone,
            trimBeforeFirstSnapshot = true,
        )
        val lastRecorded = PortfolioSeriesCalculator.lastRecordedDate(data, zone)
        NetWorthUiState(
            loading = false,
            series = series,
            allocationSeries = allocationSeries,
            chart = chart,
            pnl = PortfolioSeriesCalculator.currentProfitAndLoss(data, currency, today, zone),
            baseCurrency = currency,
            subtypes = subtypes,
            unpricedCount = series.latest?.unpricedAssetIds?.size ?: 0,
            hasAssets = data.assets.any { !it.isArchived },
            lastRecordedDate = lastRecorded,
            daysSinceLastRecord = lastRecorded?.daysUntil(today),
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = NetWorthUiState(),
    )

    fun selectPeriod(value: Period) {
        chartOptions.update { it.copy(period = value) }
    }

    fun selectChartMode(value: ChartMode) {
        chartOptions.update { it.copy(mode = value) }
    }

    fun selectChartStyle(value: ChartStyle) {
        chartOptions.update { it.copy(style = value) }
    }

    /**
     * 图例上勾/取消勾一个大类。
     *
     * **允许把所有大类都取消勾选** —— 那时候页面显示一行说明而不是空图表。
     * 不做"至少留一个"的强制：一个点不动的复选框比一句说明更让人困惑。
     */
    fun toggleClassVisible(assetClass: AssetClass) {
        chartOptions.update { options ->
            val hidden = options.hiddenClasses
            options.copy(
                hiddenClasses = if (assetClass in hidden) hidden - assetClass else hidden + assetClass,
            )
        }
    }

    fun selectBaseCurrency(code: String) {
        viewModelScope.launch {
            settings.setBaseCurrency(code)
            // 换了基准币种就要有对应的汇率，否则外币资产会变成"无法估值"
            rateRefresher.refreshForHoldings(code)
        }
    }

    /**
     * 新建资产。两种估值模式走同一个入口 —— 参数校验在 [com.boomsset.ui.NewAsset]
     * 的构造处（对话框）完成，这里只负责落库。
     */
    fun addAsset(newAsset: com.boomsset.ui.NewAsset) {
        viewModelScope.launch {
            repository.createAsset(
                name = newAsset.name,
                assetClass = newAsset.assetClass,
                subtypeId = newAsset.subtypeId,
                currency = newAsset.currency,
                isLiability = newAsset.isLiability,
                includeInAllocation = newAsset.includeInAllocation,
                mode = newAsset.mode,
                quoteSymbol = newAsset.quoteSymbol,
                initialValue = newAsset.value,
                initialQuantity = newAsset.quantity,
                costBasis = newAsset.costBasis,
            )
        }
    }
}
