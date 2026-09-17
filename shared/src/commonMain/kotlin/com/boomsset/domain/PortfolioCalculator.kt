package com.boomsset.domain

import kotlin.time.Instant

/**
 * Derived calculations for net worth and allocation. **Pure functions, no IO** — all
 * external data is fetched by the caller and passed in, so these rules can be fully
 * tested without touching the database.
 *
 * This is where the docs/domain.md rules that "silently miscalculate instead of
 * raising an error when done wrong" are concentrated. Read that document before making
 * changes here.
 */
object PortfolioCalculator {

    /**
     * The market value of a single asset in **its own currency**.
     *
     * ⚠️ Branches on `snapshot.mode` (i.e. the Snapshot's concrete type), **not on the
     * asset**. After an asset is delisted and converted from QUOTED to MANUAL, its
     * historical snapshots are still Quoted and must be valued by quantity.
     *
     * @return returns null when the quote is missing — **never returns 0**. Returning 0
     *   would silently understate net worth.
     */
    fun localValue(snapshot: Snapshot, quotes: Map<String, Quote>): Money? =
        when (snapshot) {
            is Snapshot.Manual -> snapshot.value
            is Snapshot.Quoted -> {
                val quote = quotes[snapshot.quoteSymbol]
                // quantity × unit price can overflow Long at extreme values; [FixedPoint]
                // chooses to throw rather than wrap. That choice is correct (must never
                // silently miscalculate), but the exception must never escape to the UI —
                // in real usage a single fat-finger input (100 million shares of Kweichow
                // Moutai) crashed the whole app. Here it's degraded to "cannot be valued":
                // neither wrong nor a crash.
                quote?.let {
                    runCatching { snapshot.quantity.valueAt(it.price) }.getOrNull()
                }
            }
        }

    /**
     * Converts to the base currency. Also swallows overflow — the exchange-rate
     * multiplication can overflow too, for the same reason as [localValue].
     */
    private fun convertSafely(rate: ExchangeRate, amount: Money): Money? =
        runCatching { rate.convert(amount) }.getOrNull()

    /**
     * Net worth at a point in time.
     *
     * @param snapshots each asset's **most recent** snapshot as of that point in time
     *   (the carry-forward rule is implemented by the query layer). An asset not present
     *   in this map = it didn't exist yet at that point in time, so it's skipped.
     */
    fun netWorth(
        asOf: Instant,
        assets: List<Asset>,
        snapshots: Map<Long, Snapshot>,
        context: ValuationContext,
    ): NetWorthPoint {
        var assetTotal = Money.ZERO
        var liabilityTotal = Money.ZERO
        val unpriced = mutableListOf<Long>()

        for (asset in assets) {
            val snapshot = snapshots[asset.id] ?: continue
            val local = localValue(snapshot, context.quotes)
            val rate = context.rateTo(asset.currency)

            val converted = if (local != null && rate != null) convertSafely(rate, local) else null
            if (converted == null) {
                unpriced += asset.id
                continue
            }

            if (asset.isLiability) liabilityTotal += converted else assetTotal += converted
        }

        return NetWorthPoint(
            asOf = asOf,
            baseCurrency = context.baseCurrency,
            totalAssets = assetTotal,
            totalLiabilities = liabilityTotal,
            unpricedAssetIds = unpriced,
        )
    }

    /**
     * The asset allocation view.
     *
     * The denominator is **total net worth**, and the numerator per class is its **net
     * exposure** (that class's assets − liabilities attributed to that class). This
     * combination is the only algorithm that makes the ratios sum to 100% — see
     * docs/domain.md, "denominator", for details.
     *
     * Assets with `includeInAllocation = false` are excluded from **both** the
     * numerator and the denominator, otherwise the ratios wouldn't close.
     */
    fun allocation(
        asOf: Instant,
        assets: List<Asset>,
        snapshots: Map<Long, Snapshot>,
        context: ValuationContext,
        target: TargetAllocation? = null,
    ): AllocationView {
        val counted = assets.filter { it.includeInAllocation }

        val assetsByClass = mutableMapOf<AssetClass, Money>()
        val liabilitiesByClass = mutableMapOf<AssetClass, Money>()

        for (asset in counted) {
            val snapshot = snapshots[asset.id] ?: continue
            val local = localValue(snapshot, context.quotes) ?: continue
            val rate = context.rateTo(asset.currency) ?: continue
            val converted = convertSafely(rate, local) ?: continue

            val bucket = if (asset.isLiability) liabilitiesByClass else assetsByClass
            bucket[asset.assetClass] = (bucket[asset.assetClass] ?: Money.ZERO) + converted
        }

        val exposures = AssetClass.displayOrder.associateWith { assetClass ->
            ClassExposure(
                assetClass = assetClass,
                assets = assetsByClass[assetClass] ?: Money.ZERO,
                liabilities = liabilitiesByClass[assetClass] ?: Money.ZERO,
            )
        }

        // The denominator must come from the same source as the numerator: net worth
        // computed from the portion that counts toward allocation, not reused from
        // netWorth() (which includes assets with includeInAllocation = false).
        val netWorth = exposures.values.fold(Money.ZERO) { acc, e -> acc + e.netExposure }

        return AllocationView(
            asOf = asOf,
            baseCurrency = context.baseCurrency,
            netWorth = netWorth,
            exposures = exposures,
            target = target,
        )
    }

    /**
     * Unrealized P&L for a single asset (in its own currency).
     *
     * @return returns null when cost isn't entered or the quote is missing. **Never
     *   returns a zero value** — "no cost entered" and "cost is zero so P&L equals
     *   market value" are two completely different things.
     */
    fun profitAndLoss(snapshot: Snapshot, quotes: Map<String, Quote>): ProfitAndLoss? {
        val cost = snapshot.costBasisMinor ?: return null
        val value = localValue(snapshot, quotes) ?: return null
        return ProfitAndLoss(cost = cost, value = value)
    }

    /**
     * Portfolio-level unrealized P&L (base currency), **only accumulating the assets
     * that have a cost entered**.
     *
     * So this figure's coverage may be far smaller than all assets. The UI must state
     * the coverage — otherwise the user might read a P&L figure covering only 30% of
     * assets as representing their whole net worth. [PortfolioPnL.coveredAssetIds] in
     * the return value exists for exactly this purpose.
     */
    fun portfolioProfitAndLoss(
        assets: List<Asset>,
        snapshots: Map<Long, Snapshot>,
        context: ValuationContext,
    ): PortfolioPnL {
        var cost = Money.ZERO
        var value = Money.ZERO
        val covered = mutableListOf<Long>()

        for (asset in assets) {
            // Liabilities have no "P&L" to speak of
            if (asset.isLiability) continue
            val snapshot = snapshots[asset.id] ?: continue
            val pnl = profitAndLoss(snapshot, context.quotes) ?: continue
            val rate = context.rateTo(asset.currency) ?: continue

            val convertedCost = convertSafely(rate, pnl.cost) ?: continue
            val convertedValue = convertSafely(rate, pnl.value) ?: continue
            cost += convertedCost
            value += convertedValue
            covered += asset.id
        }

        return PortfolioPnL(
            pnl = ProfitAndLoss(cost = cost, value = value),
            coveredAssetIds = covered,
        )
    }

    /**
     * Net worth growth rate, in basis points.
     *
     * ⚠️ **This is not an investment return rate.** It includes new contributions —
     * depositing 10,000 in salary this month raises net worth by 10,000, and this
     * figure would show that as growth, but it isn't "earnings". The UI must display it
     * alongside the unrealized P&L rate with clearly labeled distinctions, see
     * docs/domain.md, "growth rate".
     *
     * @return returns null when the starting net worth ≤ 0 (growth rate is mathematically meaningless)
     */
    fun netWorthGrowthBp(from: NetWorthPoint, to: NetWorthPoint): Int? =
        growthBp(from.netWorth.minorUnits, to.netWorth.minorUnits)

    /**
     * Growth rate, in basis points. Parameters are the starting and ending amounts
     * (minor units) under the same convention.
     *
     * Both the whole-range net worth growth ([netWorthGrowthBp]) and "this bar vs. the
     * previous bar" in the chart go through here — writing the division twice in two
     * places would eventually make the "what if starting value ≤ 0" rule diverge, and
     * one spot on the same screen would show "—" while another shows some number
     * conjured out of thin air.
     *
     * @return returns null when the starting value ≤ 0 (denominator zero or negative,
     *   growth rate is mathematically meaningless) — **not 0**; "no change" and
     *   "can't be computed" call for completely different user actions
     */
    fun growthBp(fromMinor: Long, toMinor: Long): Int? {
        if (fromMinor <= 0L) return null
        val delta = toMinor - fromMinor
        return (delta * TargetAllocation.TOTAL_BP / fromMinor).toInt()
    }
}

/** Portfolio-level P&L + which assets it actually covers. */
data class PortfolioPnL(
    val pnl: ProfitAndLoss,
    val coveredAssetIds: List<Long>,
) {
    val hasCoverage: Boolean get() = coveredAssetIds.isNotEmpty()
}
