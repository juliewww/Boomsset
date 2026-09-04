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

    /**
     * 负债率 = 总负债 / 总资产，基点。
     *
     * 分母是**总资产**而不是净资产 —— "欠的钱占身家多大比例"这个问题里，
     * 净资产做分母会在高杠杆时给出超过 100% 的数，读不出意义。
     *
     * @return 总资产 ≤ 0 时返回 null（分母无意义）。**不返回 0** ——
     *   0% 会被读成"没有负债"，而"一分资产都没有"是另一回事。
     */
    val liabilityRatioBp: Int?
        get() {
            val assets = totalAssets.minorUnits
            if (assets <= 0L) return null
            val liabilities = totalLiabilities.minorUnits
            // 乘法先于除法，所以数量级极端时会溢出 Long。溢出是静默回绕（不像
            // FixedPoint 会抛），所以这里主动降级成 null —— 宁可不显示，
            // 不能显示一个回绕出来的假比率。见 AGENTS.md 教训 4。
            if (!fitsBpMath(liabilities)) return null
            return (liabilities * TargetAllocation.TOTAL_BP / assets).toInt()
        }
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
     *   量级过大导致基点换算会回绕时同样返回 null，见 [fitsBpMath]。
     */
    fun shareBp(assetClass: AssetClass): Int? {
        if (netWorth.minorUnits <= 0L) return null
        val exposure = exposures[assetClass]?.netExposure ?: Money.ZERO
        if (!fitsBpMath(netWorth.minorUnits) || !fitsBpMath(exposure.minorUnits)) return null
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

    /**
     * 要让这一类回到目标比例，需要调整的金额。**正数需增加、负数需减少。**
     *
     * ## 口径：内部调仓，总净资产不变
     *
     * 这个数假设调整是在组合**内部**发生的（卖掉超配的类、把等额买进低配的类），
     * 所以分母不变。由此得到一条可以断言的不变量：**全部大类的调整额加总为 0**
     * （因为目标比例之和恒为 [TargetAllocation.TOTAL_BP]）—— 超配的类要卖出多少，
     * 正好够低配的类买入。取整会让这个和有最多「大类数 − 1」分的误差，见测试。
     *
     * 另一个口径是"只投新钱、什么都不卖"，那时新钱同时进分母，算式是
     * `x = (目标 × 净资产 − 净敞口) / (1 − 目标)`，数字明显更大（20%→40% 的例子里
     * 是 33.3 万而不是 20 万）。**没有选它**：各类独立算出来的数加不起来，
     * 拼不成一个可执行的方案，而"卖超配补低配"是再平衡的通行含义。
     *
     * ## 为什么不从 [deviationBp] 反算
     *
     * `−偏离 × 净资产` 看起来等价，实际会放大误差：[deviationBp] 是从**已经截断到
     * 整基点**的 [shareBp] 减出来的，1 基点乘上净资产就是真金白银 ——
     * 每 100 万净资产误差 ¥100，随机对照跑到过 ¥99,876（净资产约 10 亿那档）。
     * 这里 `目标额 − 净敞口` 只截断一次，误差 < 1 分。
     *
     * @return null 的条件和 [deviationBp] **完全一致**（净资产 ≤ 0、没有生效目标、
     *   量级过大）。不一致会让 UI 出现"比例说算不出来、金额却给了个数"。
     */
    fun rebalanceAmount(assetClass: AssetClass): Money? {
        if (netWorth.minorUnits <= 0L) return null
        val goal = targetBp(assetClass) ?: return null
        val exposure = exposures[assetClass]?.netExposure ?: Money.ZERO
        if (!fitsBpMath(netWorth.minorUnits) || !fitsBpMath(exposure.minorUnits)) return null
        val goalAmount = Money(goal * netWorth.minorUnits / TargetAllocation.TOTAL_BP)
        return goalAmount - exposure
    }

    /** 有任何大类净敞口为负 —— 饼图画不出负数，UI 要显式标注。 */
    val hasNegativeExposure: Boolean get() = exposures.values.any { it.isNegative }
}

/**
 * 这个金额乘上 [TargetAllocation.TOTAL_BP] 会不会溢出 Long。
 * 三处基点换算共用它：[NetWorthPoint.liabilityRatioBp]、[AllocationView.shareBp]、
 * [AllocationView.rebalanceAmount]。
 *
 * 基点换算全都是「先乘 10000 再除」，而 **Long 溢出不抛异常，会安静地回绕**成一个
 * 荒谬的数 —— 那正是这个项目最不能接受的失败方式（AGENTS.md：金额宁可显示不出来，
 * 也不能静默算错）。阈值约 9.2 万亿元，现实里到不了，但闸门只要一行。
 *
 * 不写成 `abs(this) <= ...`：`abs(Long.MIN_VALUE)` 本身就是负数，比较会给出错的答案。
 */
private fun fitsBpMath(minorUnits: Long): Boolean {
    val limit = Long.MAX_VALUE / TargetAllocation.TOTAL_BP
    return minorUnits in -limit..limit
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
