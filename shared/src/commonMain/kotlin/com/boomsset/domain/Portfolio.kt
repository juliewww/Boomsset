package com.boomsset.domain

import kotlin.time.Instant

/**
 * Valuation context — all the external data needed for a given point in time T.
 *
 * Both [quotes] and [rates] should be the values **as of that point in time (or the
 * most recent one before it)**, not today's values. Using today's exchange rate to
 * convert a historical net worth value would pollute the curve, showing the user swings
 * they never actually experienced.
 */
data class ValuationContext(
    val baseCurrency: String,
    /** symbol → the most recent quote as of that point in time */
    val quotes: Map<String, Quote>,
    /** "USD→CNY" → the most recent rate as of that point in time. Same-currency pairs don't need to appear here. */
    val rates: Map<String, ExchangeRate>,
) {
    fun rateTo(fromCurrency: String): ExchangeRate? =
        if (fromCurrency == baseCurrency) ExchangeRate.IDENTITY
        else rates[rateKey(fromCurrency, baseCurrency)]

    companion object {
        fun rateKey(from: String, to: String): String = "$from→$to"
    }
}

/** Net exposure of a single top-level asset class. */
data class ClassExposure(
    val assetClass: AssetClass,
    /** Total assets in this class (base currency) */
    val assets: Money,
    /** Total liabilities attributed to this class (positive magnitude, base currency) */
    val liabilities: Money,
) {
    /**
     * Net exposure = assets − attributed liabilities. **Can be negative** (a car loan
     * exceeding the car's value, credit card debt exceeding liquid funds).
     *
     * This value, not [assets], is the numerator for allocation ratios — forgetting to
     * subtract liabilities would make the classes sum to more than 100%.
     */
    val netExposure: Money get() = assets - liabilities

    val isNegative: Boolean get() = netExposure < Money.ZERO
}

/** Net worth at a point in time. */
data class NetWorthPoint(
    val asOf: Instant,
    val baseCurrency: String,
    val totalAssets: Money,
    val totalLiabilities: Money,
    /**
     * IDs of assets that could not be valued — a QUOTED snapshot's symbol has no quote
     * at all.
     *
     * These assets are **not included** in the totals above. They aren't treated as 0
     * because that would silently understate net worth; the UI must surface this list.
     */
    val unpricedAssetIds: List<Long> = emptyList(),
) {
    val netWorth: Money get() = totalAssets - totalLiabilities
    val hasUnpriced: Boolean get() = unpricedAssetIds.isNotEmpty()

    /**
     * Liability ratio = total liabilities / total assets, in basis points.
     *
     * The denominator is **total assets**, not net worth — for "what fraction of my
     * wealth do I owe", using net worth as the denominator would produce a figure over
     * 100% under high leverage, which doesn't read as meaningful.
     *
     * @return null when total assets ≤ 0 (the denominator is meaningless). **Never
     *   returns 0** — 0% would read as "no liabilities", while "zero assets at all" is a
     *   different situation entirely.
     */
    val liabilityRatioBp: Int?
        get() {
            val assets = totalAssets.minorUnits
            if (assets <= 0L) return null
            val liabilities = totalLiabilities.minorUnits
            // Multiplication happens before division, so extreme magnitudes can overflow
            // Long. Overflow here wraps silently (unlike FixedPoint, which throws), so
            // this proactively degrades to null — better to show nothing than to show a
            // wrapped-around fake ratio. See AGENTS.md lesson 4.
            if (!fitsBpMath(liabilities)) return null
            return (liabilities * TargetAllocation.TOTAL_BP / assets).toInt()
        }
}

/** Asset allocation view: current ratio vs. target ratio. */
data class AllocationView(
    val asOf: Instant,
    val baseCurrency: String,
    /** The denominator. Decided: total net worth (including primary residence, minus liabilities). */
    val netWorth: Money,
    val exposures: Map<AssetClass, ClassExposure>,
    val target: TargetAllocation?,
) {
    /**
     * Current ratio, in basis points (100% = 10000). Can be negative (if this class's net exposure is negative).
     *
     * @return null when net worth ≤ 0 — the ratio is mathematically meaningless at that
     *   point (the denominator is zero or negative); the UI should say plainly "net
     *   worth is negative, allocation ratio can't be computed" rather than display a
     *   garbage number. Also returns null when the magnitude is too large and the basis
     *   point conversion would wrap around, see [fitsBpMath].
     */
    fun shareBp(assetClass: AssetClass): Int? {
        if (netWorth.minorUnits <= 0L) return null
        val exposure = exposures[assetClass]?.netExposure ?: Money.ZERO
        if (!fitsBpMath(netWorth.minorUnits) || !fitsBpMath(exposure.minorUnits)) return null
        return (exposure.minorUnits * TargetAllocation.TOTAL_BP / netWorth.minorUnits).toInt()
    }

    /** Target ratio, in basis points. Returns null when there's no active target allocation. */
    fun targetBp(assetClass: AssetClass): Int? = target?.targetsBp?.get(assetClass)

    /** Deviation = current − target, in basis points. Positive means overweight, negative means underweight. */
    fun deviationBp(assetClass: AssetClass): Int? {
        val current = shareBp(assetClass) ?: return null
        val goal = targetBp(assetClass) ?: return null
        return current - goal
    }

    /**
     * The amount by which this class needs to be adjusted to reach its target ratio.
     * **Positive means increase, negative means decrease.**
     *
     * ## Convention: internal rebalancing, total net worth unchanged
     *
     * This figure assumes the adjustment happens **within** the portfolio (sell down the
     * overweight classes, buy an equal amount into the underweight classes), so the
     * denominator doesn't change. This gives an invariant that can be asserted:
     * **the adjustment amounts across all classes sum to 0** (because target ratios
     * always sum to [TargetAllocation.TOTAL_BP]) — however much the overweight classes
     * need to sell is exactly enough for the underweight classes to buy. Rounding can
     * introduce an error of up to "number of classes − 1" fen in this sum, see the tests.
     *
     * The other convention is "invest new money only, sell nothing", where the new money
     * also enters the denominator, giving the formula
     * `x = (target × net worth − net exposure) / (1 − target)`, which yields noticeably
     * larger numbers (in the 20%→40% example, 333,000 rather than 200,000).
     * **This wasn't chosen**: the figures computed independently per class don't add up
     * into one executable plan, whereas "sell overweight to top up underweight" is the
     * common meaning of rebalancing.
     *
     * ## Why not derive this from [deviationBp]
     *
     * `−deviation × net worth` looks equivalent but actually amplifies error:
     * [deviationBp] is subtracted from [shareBp], which is **already truncated to whole
     * basis points** — 1 basis point times net worth is real money — an error of ¥100
     * per ¥1,000,000 of net worth, which in randomized testing reached as much as
     * ¥99,876 (at roughly ¥1 billion net worth). Here, `target amount − net exposure`
     * truncates only once, with error < 1 fen.
     *
     * @return the null conditions are **exactly the same** as [deviationBp] (net worth ≤
     *   0, no active target, magnitude too large). Any inconsistency would let the UI
     *   show "ratio can't be computed" while still giving an amount.
     */
    fun rebalanceAmount(assetClass: AssetClass): Money? {
        if (netWorth.minorUnits <= 0L) return null
        val goal = targetBp(assetClass) ?: return null
        val exposure = exposures[assetClass]?.netExposure ?: Money.ZERO
        if (!fitsBpMath(netWorth.minorUnits) || !fitsBpMath(exposure.minorUnits)) return null
        val goalAmount = Money(goal * netWorth.minorUnits / TargetAllocation.TOTAL_BP)
        return goalAmount - exposure
    }

    /** Whether any top-level class has a negative net exposure — a pie chart can't render negative slices, so the UI must call this out explicitly. */
    val hasNegativeExposure: Boolean get() = exposures.values.any { it.isNegative }
}

/**
 * Whether multiplying this amount by [TargetAllocation.TOTAL_BP] would overflow Long.
 * Shared by the three places that do basis-point conversion: [NetWorthPoint.liabilityRatioBp],
 * [AllocationView.shareBp], [AllocationView.rebalanceAmount].
 *
 * Basis-point conversion always works by "multiply by 10000, then divide", and
 * **Long overflow doesn't throw, it silently wraps around** into an absurd number —
 * exactly the kind of failure this project cannot accept (AGENTS.md: better to fail to
 * display an amount than to silently miscalculate it). The threshold is about 9.2
 * trillion yuan, unreachable in practice, but the guard is only one line.
 *
 * Not written as `abs(this) <= ...`: `abs(Long.MIN_VALUE)` is itself negative, so the
 * comparison would give the wrong answer.
 */
private fun fitsBpMath(minorUnits: Long): Boolean {
    val limit = Long.MAX_VALUE / TargetAllocation.TOTAL_BP
    return minorUnits in -limit..limit
}

/**
 * Unrealized profit and loss. This is a **point-in-time value**, not a range value.
 *
 * With no cost basis (not entered by the user, or a liability), there is no P&L, in
 * which case the whole object is null rather than a zero value — zero and "unknown"
 * mean completely different things here.
 */
data class ProfitAndLoss(
    val cost: Money,
    val value: Money,
) {
    val absolute: Money get() = value - cost

    /**
     * Return rate, in basis points. Returns null when cost is 0 (not 0, and not a divide-by-zero).
     */
    val returnBp: Int?
        get() {
            if (cost.minorUnits == 0L) return null
            return (absolute.minorUnits * TargetAllocation.TOTAL_BP / cost.minorUnits).toInt()
        }
}
