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
 * Display options for the net worth page's chart.
 *
 * The four fields are combined into a single object flowing through **one** flow, rather than
 * four separate `MutableStateFlow`s — `combine`'s named overload tops out at 5 arguments, and
 * all four of these only affect "how the chart is drawn" and change on the same cadence;
 * splitting them up would only make the combine longer and hurt readability.
 */
data class ChartOptions(
    val period: Period = Period.MONTH,
    val mode: ChartMode = ChartMode.TOTAL,
    val style: ChartStyle = ChartStyle.COLUMN,
    /**
     * Classes that have been **unchecked** in the legend.
     *
     * Storing "hidden" rather than "shown" is intentional: if a sixth class is ever added in the
     * future, it will be visible by default, rather than silently hidden just because it's not
     * in this set.
     */
    val hiddenClasses: Set<AssetClass> = emptySet(),
) {
    /** The classes to draw, **always in [AssetClass.displayOrder]** — both stacking order and color depend on it. */
    val visibleClasses: List<AssetClass>
        get() = AssetClass.displayOrder.filterNot { it in hiddenClasses }
}

data class NetWorthUiState(
    val loading: Boolean = true,
    val series: NetWorthSeries? = null,
    /** The same time series broken out by class, with sample points aligned point-for-point with [series]. */
    val allocationSeries: AllocationSeries? = null,
    val chart: ChartOptions = ChartOptions(),
    val pnl: PortfolioPnL? = null,
    val baseCurrency: String = "CNY",
    val subtypes: List<AssetSubtype> = emptyList(),
    /** Number of assets that can't be valued — missing quote or FX rate. The UI must surface this, not silently understate net worth. */
    val unpricedCount: Int = 0,
    val hasAssets: Boolean = false,
    /**
     * The date of the most recent snapshot, and how many days ago that was.
     *
     * We record snapshots, not transactions, so net worth doesn't update on its own — these two
     * values are the user's only cue for judging "is the number at the top still fresh". See
     * [PortfolioSeriesCalculator.lastRecordedDate].
     */
    val lastRecordedDate: LocalDate? = null,
    val daysSinceLastRecord: Int? = null,
    /**
     * Whether amounts are hidden (the eye icon). **Only affects this page's display**, doesn't
     * rewrite any data.
     *
     * What's hidden is the **absolute amount** (net worth, total assets, total liabilities,
     * change amount, y-axis ticks) — percentages and ratios are shown as usual, since growth
     * rate, return rate, and liability ratio can't reveal net worth by themselves, and they are
     * exactly this page's value. Hiding everything would be equivalent to "turning off the net
     * worth page".
     */
    val amountsHidden: Boolean = false,
) {
    /**
     * The empty state looks at **whether there are any assets**, not `series.latest == null`.
     *
     * buildSeries generates a whole string of zero-net-worth points even at zero assets (the
     * sample dates are computed by period, independent of whether there's data), so `latest` is
     * never null — using that to test for emptiness would mean the empty state never appears,
     * and new users would see a flat zero line instead of onboarding guidance.
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

    // Base currency defaults to CNY and is switchable. Passed in as a query parameter, never
    // persisted onto Asset/Snapshot.
    private val baseCurrency = settings.observeBaseCurrency()

    init {
        // Refresh FX rates. Only writes fx_rate, never snapshot — see RateRefresher.
        //
        // ⚠️ Must be retriggered whenever the "set of required currencies" changes, not just run
        // once in init: on first launch there are no assets yet, so the required currency set is
        // empty; if a USD asset is added later, its exchange rate would never be fetched again.
        // This is a bug that was only discovered by running the app for real.
        //
        // RateRefresher internally tracks which (currency, date) pairs have already been
        // attempted, so the re-emission triggered by writing to fx_rate doesn't cause an
        // infinite loop.
        viewModelScope.launch {
            combine(
                repository.observePortfolio(),
                settings.observeBaseCurrency(),
            ) { data, currency ->
                // Refresh again whenever either the currency set or the quote-symbol set changes
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
        settings.observeAmountsHidden(),
    ) { data, subtypes, chart, currency, amountsHidden ->
        val today = clock.now().toLocalDateTime(zone).date
        val series = PortfolioSeriesCalculator.buildSeries(
            data = data,
            period = chart.period,
            baseCurrency = currency,
            today = today,
            zone = zone,
            // When viewing by year/quarter, the account might only have been used for a few
            // months — without trimming, a big leading chunk would all be zero-value points for
            // "the asset didn't exist yet", filling up the chart with no informational value.
            trimBeforeFirstSnapshot = true,
        )
        // Computed regardless of whether the by-class view is currently active — one
        // allocation() call per sample point is negligible at this data volume (at most 12
        // points x a few dozen assets), and in exchange the chart has data immediately when the
        // toggle is switched, without waiting for a recompute.
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
            amountsHidden = amountsHidden,
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
     * Check/uncheck a class in the legend.
     *
     * **Unchecking all classes is allowed** — in that case the page shows an explanatory line
     * instead of an empty chart. There's no "must keep at least one" enforcement: a checkbox
     * that refuses to respond is more confusing than an explanatory sentence.
     */
    fun toggleClassVisible(assetClass: AssetClass) {
        chartOptions.update { options ->
            val hidden = options.hiddenClasses
            options.copy(
                hiddenClasses = if (assetClass in hidden) hidden - assetClass else hidden + assetClass,
            )
        }
    }

    /**
     * The eye icon: hide/show amounts.
     *
     * Persisted to the settings table rather than left in the UI's `remember` — the latter would
     * reset the moment you switch tabs and come back, whereas this toggle's whole purpose is
     * "someone else is nearby", during which the user is quite likely to flip over to the
     * allocation page and back.
     */
    fun setAmountsHidden(hidden: Boolean) {
        viewModelScope.launch { settings.setAmountsHidden(hidden) }
    }

    fun selectBaseCurrency(code: String) {
        viewModelScope.launch {
            settings.setBaseCurrency(code)
            // Switching the base currency requires the corresponding exchange rate, otherwise
            // foreign-currency assets would turn into "can't be valued"
            rateRefresher.refreshForHoldings(code)
        }
    }

    /**
     * The "retry pricing" button: clears [RateRefresher]'s internal record of failed attempts,
     * then immediately re-fetches exchange rates and quotes.
     *
     * Automatic refresh only triggers when the "set of required currencies/symbols" changes —
     * if the request happened to fail right after a foreign-currency asset was added (a
     * transient cause like a network blip), nothing afterward would ever trigger a retry, and
     * the user's only option would be to restart the app. This button provides a path to recover
     * without a restart.
     */
    fun retryPricing() {
        viewModelScope.launch {
            rateRefresher.retryAll(state.value.baseCurrency)
        }
    }

    /**
     * Create a new asset. Both valuation modes go through the same entry point — parameter
     * validation happens where [com.boomsset.ui.NewAsset] is constructed (the dialog); this only
     * handles persisting it.
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
