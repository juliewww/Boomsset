package com.boomsset.domain

import kotlin.time.Instant

/**
 * 估值上下文 —— 某个时点 T 所需的全部外部数据。
 *
 * [quotes] 和 [rates] 都应该是**该时点（含之前最近一条）**的值，不是今天的值。
 * 用今天的汇率折算历史净值会污染曲线，让用户看到自己从没经历过的涨跌。
 */
data class ValuationContext(
    val baseCurrency: String,
    /** symbol → 该时点前最近的一条行情 */
    val quotes: Map<String, Quote>,
    /** "USD→CNY" → 该时点前最近的一条汇率。同币种不需要出现在这里。 */
    val rates: Map<String, ExchangeRate>,
) {
    fun rateTo(fromCurrency: String): ExchangeRate? =
        if (fromCurrency == baseCurrency) ExchangeRate.IDENTITY
        else rates[rateKey(fromCurrency, baseCurrency)]

    companion object {
        fun rateKey(from: String, to: String): String = "$from→$to"
    }
}

/** 某个大类的净敞口。 */
data class ClassExposure(
    val assetClass: AssetClass,
    /** 该类资产合计（基准币种） */
    val assets: Money,
    /** 归属到该类的负债合计（正数量级，基准币种） */
    val liabilities: Money,
) {
    /**
     * 净敞口 = 资产 − 归属负债。**可能为负**（车贷超过车值、信用卡欠款超过流动资金）。
     *
     * 配置比例的分子是这个值，不是 [assets] —— 忘了减负债会让各大类加总超过 100%。
     */
    val netExposure: Money get() = assets - liabilities

    val isNegative: Boolean get() = netExposure < Money.ZERO
}

/** 某时点的净值。 */
data class NetWorthPoint(
    val asOf: Instant,
    val baseCurrency: String,
    val totalAssets: Money,
    val totalLiabilities: Money,
    /**
     * 无法估值的资产 id —— QUOTED 快照的 symbol 一条行情都没有。
     *
     * 这些资产**没有计入**上面的合计。不把它们当成 0，是因为当成 0 会静默低估净值；
     * UI 必须把这个列表提示出来。
     */
    val unpricedAssetIds: List<Long> = emptyList(),
) {
    val netWorth: Money get() = totalAssets - totalLiabilities
    val hasUnpriced: Boolean get() = unpricedAssetIds.isNotEmpty()
}

/** 资产配置视图：当前比例 vs 目标比例。 */
data class AllocationView(
    val asOf: Instant,
    val baseCurrency: String,
    /** 分母。已定：全部净资产（含自住房，减负债）。 */
    val netWorth: Money,
    val exposures: Map<AssetClass, ClassExposure>,
    val target: TargetAllocation?,
) {
    /**
     * 当前比例，单位基点（100% = 10000）。可能为负（该类净敞口为负）。
     *
     * @return 净资产 ≤ 0 时返回 null —— 此时比例在数学上无意义（分母为零或负），
     *   UI 应当直说"净资产为负，配置比例无法计算"，而不是显示一个乱数。
     */
    fun shareBp(assetClass: AssetClass): Int? {
        if (netWorth.minorUnits <= 0L) return null
        val exposure = exposures[assetClass]?.netExposure ?: Money.ZERO
        return (exposure.minorUnits * TargetAllocation.TOTAL_BP / netWorth.minorUnits).toInt()
    }

    /** 目标比例，基点。没有生效的目标配置时返回 null。 */
    fun targetBp(assetClass: AssetClass): Int? = target?.targetsBp?.get(assetClass)

    /** 偏离 = 当前 − 目标，基点。正数超配、负数低配。 */
    fun deviationBp(assetClass: AssetClass): Int? {
        val current = shareBp(assetClass) ?: return null
        val goal = targetBp(assetClass) ?: return null
        return current - goal
    }

    /** 有任何大类净敞口为负 —— 饼图画不出负数，UI 要显式标注。 */
    val hasNegativeExposure: Boolean get() = exposures.values.any { it.isNegative }
}

/**
 * 浮动盈亏。这是**时点值**，不是区间值。
 *
 * 没有成本（用户没填、或负债）就没有盈亏，此时整个对象为 null 而不是零值 ——
 * 零和"未知"在这里意义完全不同。
 */
data class ProfitAndLoss(
    val cost: Money,
    val value: Money,
) {
    val absolute: Money get() = value - cost

    /**
     * 盈亏率，基点。成本为 0 时返回 null（不是 0，也不是除零）。
     */
    val returnBp: Int?
        get() {
            if (cost.minorUnits == 0L) return null
            return (absolute.minorUnits * TargetAllocation.TOTAL_BP / cost.minorUnits).toInt()
        }
}
