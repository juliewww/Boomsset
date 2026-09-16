package com.boomsset.ui.assets

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.boomsset.data.PortfolioRepository
import com.boomsset.data.SettingsRepository
import com.boomsset.domain.AssetClass
import com.boomsset.domain.AssetSubtype
import com.boomsset.domain.AssetValuation
import com.boomsset.domain.endOfDayIn
import com.boomsset.domain.Money
import com.boomsset.domain.PortfolioSeriesCalculator
import com.boomsset.domain.Quantity
import com.boomsset.domain.Quote
import com.boomsset.domain.UnitPrice
import com.boomsset.domain.UpdateHistory
import com.boomsset.domain.UpdateRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

data class AssetListUiState(
    val loading: Boolean = true,
    val baseCurrency: String = "CNY",
    /** Grouped by class, sorted by name within each group. Archived assets are not included here. */
    val grouped: Map<AssetClass, List<AssetValuation>> = emptyMap(),
    val archivedCount: Int = 0,
    /** Archived assets, used for "unarchive". */
    val archived: List<AssetValuation> = emptyList(),
    val subtypes: List<AssetSubtype> = emptyList(),
    /**
     * All update records, newest first. **No retention-period truncation is applied** —
     * pagination is done purely on the UI side; see [com.boomsset.domain.UpdateHistory] for why.
     */
    val history: List<UpdateRecord> = emptyList(),
    /**
     * Today. Only used by the update-history section to decide whether a date needs its year shown.
     *
     * This value isn't available before loading completes (the clock hasn't been subscribed to
     * yet), but at that point [history] is also empty and no row will use it, so a default that
     * is obviously a placeholder is enough — if it were ever actually used it would show up as
     * "1970...", instantly recognizable as a bug, rather than silently dropping a year.
     */
    val today: LocalDate = LocalDate(1970, 1, 1),
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
            history = UpdateHistory.build(data, zone),
            today = today,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AssetListUiState(),
    )

    /**
     * Update the market value of a manually-valued asset.
     *
     * [costBasis] is passed in after the UI prefills it from the previous snapshot — **don't let
     * it default to null**, which would wipe out the cost basis and make the return rate vanish
     * out of nowhere. See item 5 of "easy-to-get-wrong spots" in docs/domain.md.
     *
     * If [asOf] is omitted it means "now"; if given, it means **backfilling a historical
     * record** — this snapshot lands at the **end** of that day (`endOfDayIn`, consistent with
     * the net-worth-curve sampling semantics; see "time handling" in docs/domain.md).
     * `recordedAt` is unaffected and is always the actual moment "Save" was tapped.
     */
    fun updateManualValue(assetId: Long, value: Money, costBasis: Money?, asOf: LocalDate? = null) {
        viewModelScope.launch {
            repository.appendManualSnapshot(assetId, value, costBasis, asOf?.endOfDayIn(zone))
        }
    }

    /**
     * Update the holding of a share-quantity-valued asset.
     *
     * Quantity and cost basis are collected together — adding to a position means more money was
     * invested; if quantity goes up but cost basis doesn't, the return rate would be inflated.
     */
    fun updateQuotedHolding(
        assetId: Long,
        quantity: Quantity,
        quoteSymbol: String,
        costBasis: Money?,
        asOf: LocalDate? = null,
    ) {
        viewModelScope.launch {
            repository.appendQuotedSnapshot(assetId, quantity, quoteSymbol, costBasis, asOf?.endOfDayIn(zone))
        }
    }

    /** Archive. Appends a zero-value snapshot; the historical curve is unaffected. */
    fun archive(assetId: Long) {
        viewModelScope.launch { repository.archiveAsset(assetId) }
    }

    /**
     * Unarchive. Only clears archivedAt; the zero-value snapshot stays —
     * so the asset will appear at 0, and the user needs to update its valuation again themselves.
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
                // The valuation mode is not changed here — switching modes has to go through the
                // "append a new-mode snapshot" flow
                defaultValuationMode = valuation.asset.defaultValuationMode,
                defaultQuoteSymbol = valuation.asset.defaultQuoteSymbol,
            )
        }
    }

    /**
     * Manually set today's unit price for a given quote symbol.
     *
     * Reason it exists: the Tencent quote API is unofficial and can fail or return garbage data.
     * Without this entry point, once a price can't be fetched, a QUOTED asset would permanently
     * show "can't be valued" with no way for the user to recover.
     *
     * ⚠️ **Writes to the same `quote` table, so a successful automatic refresh later that same
     * day will overwrite it.** This is intentional: a manual price is a fallback for when the
     * automatic fetch fails — a real market price is naturally more accurate than a hand-entered
     * one. "Pinning" a price would require adding an is_manual flag to `quote`, which is a
     * separate change.
     */
    fun setManualPrice(symbol: String, price: UnitPrice, currency: String) {
        viewModelScope.launch {
            val today = clock.now().toLocalDateTime(zone).date
            repository.upsertQuote(
                Quote(
                    symbol = symbol,
                    asOfDay = today.toString(),
                    price = price,
                    currency = currency,
                    fetchedAt = clock.now(),
                ),
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
