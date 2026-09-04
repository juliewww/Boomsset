package com.boomsset.domain

import kotlin.time.Instant

/**
 * 净值与配置的派生计算。**纯函数，无 IO** —— 所有外部数据由调用方查好后传进来，
 * 这样这些规则可以在不碰数据库的情况下被完整测试。
 *
 * 这里集中了 docs/domain.md 里那几条「写错了不报错、只是静默算错」的规则。
 * 改动前请读那份文档。
 */
object PortfolioCalculator {

    /**
     * 单项资产在其**自身币种**下的市值。
     *
     * ⚠️ 分支看 `snapshot.mode`（即 Snapshot 的具体类型），**不看 asset**。
     * 资产退市从 QUOTED 转成 MANUAL 后，历史快照仍是 Quoted，必须按份额估值。
     *
     * @return 行情缺失时返回 null —— **不返回 0**。返回 0 会静默低估净值。
     */
    fun localValue(snapshot: Snapshot, quotes: Map<String, Quote>): Money? =
        when (snapshot) {
            is Snapshot.Manual -> snapshot.value
            is Snapshot.Quoted -> {
                val quote = quotes[snapshot.quoteSymbol]
                // 份额 × 单价 在极端数值下会溢出 Long，[FixedPoint] 选择抛异常而不是回绕。
                // 那个选择是对的（不能静默算错），但异常绝不能逃到 UI —— 实跑时一次手误
                // 输入（1 亿股茅台）就让整个 App 崩了。这里降级成"无法估值"：
                // 既没有算错，也没有崩。
                quote?.let {
                    runCatching { snapshot.quantity.valueAt(it.price) }.getOrNull()
                }
            }
        }

    /**
     * 折算到基准币种。同样吞掉溢出 —— 汇率乘法也可能溢出，理由同 [localValue]。
     */
    private fun convertSafely(rate: ExchangeRate, amount: Money): Money? =
        runCatching { rate.convert(amount) }.getOrNull()

    /**
     * 某时点的净值。
     *
     * @param snapshots 每个资产在该时点前**最近的一条**快照（结转规则由查询层实现）。
     *   资产不在这个 map 里 = 该时点它还不存在，跳过。
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
     * 资产配置视图。
     *
     * 分母是**全部净资产**，分子是各大类**净敞口**（该类资产 − 归属到该类的负债）。
     * 这个组合是唯一能让比例加总为 100% 的算法 —— 详见 docs/domain.md「分母」。
     *
     * `includeInAllocation = false` 的资产**同时**从分子和分母里排除，否则比例不闭合。
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

        // 分母必须和分子同源：用计入配置的那部分算出的净资产，
        // 而不是复用 netWorth()（那个包含了 includeInAllocation = false 的资产）。
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
     * 单项资产的浮动盈亏（自身币种）。
     *
     * @return 没填成本、或行情缺失时返回 null。**不返回零值** —— "没有成本"和
     *   "成本为零导致盈亏等于市值"是两件完全不同的事。
     */
    fun profitAndLoss(snapshot: Snapshot, quotes: Map<String, Quote>): ProfitAndLoss? {
        val cost = snapshot.costBasisMinor ?: return null
        val value = localValue(snapshot, quotes) ?: return null
        return ProfitAndLoss(cost = cost, value = value)
    }

    /**
     * 组合层面的浮动盈亏（基准币种），**只累加填了成本的那部分资产**。
     *
     * 所以这个数的覆盖面可能远小于全部资产。UI 必须说明覆盖范围 ——
     * 否则用户会拿一个只覆盖三成资产的盈亏数去理解全部身家。
     * 返回值里的 [PortfolioPnL.coveredAssetIds] 就是为此准备的。
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
            // 负债没有"盈亏"可言
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
     * 净值增长率，基点。
     *
     * ⚠️ **这不是投资收益率。** 它包含新增投入 —— 这个月存 1 万工资进去，
     * 净值涨 1 万，这个数会显示为增长，但那不是"赚"的。
     * UI 上必须和浮动盈亏率并列显示且标签写清区别，见 docs/domain.md「增长率」。
     *
     * @return 期初净值 ≤ 0 时返回 null（增长率在数学上无意义）
     */
    fun netWorthGrowthBp(from: NetWorthPoint, to: NetWorthPoint): Int? =
        growthBp(from.netWorth.minorUnits, to.netWorth.minorUnits)

    /**
     * 增长率，基点。参数是同一口径下的期初、期末金额（最小单位）。
     *
     * 整段区间的净值增长（[netWorthGrowthBp]）和图上「这根柱子相比前一根」都走这里 ——
     * 两处各写一遍除法，「期初 ≤ 0 怎么办」这条判据迟早会写得不一样，
     * 于是同一屏上一个显示「—」、另一个显示某个凭空算出来的百分比。
     *
     * @return 期初 ≤ 0 时返回 null（分母为零或为负，增长率在数学上无意义）——
     *   **不是 0**，"没有变化"和"算不出来"对用户要做的事完全不同
     */
    fun growthBp(fromMinor: Long, toMinor: Long): Int? {
        if (fromMinor <= 0L) return null
        val delta = toMinor - fromMinor
        return (delta * TargetAllocation.TOTAL_BP / fromMinor).toInt()
    }
}

/** 组合盈亏 + 它实际覆盖了哪些资产。 */
data class PortfolioPnL(
    val pnl: ProfitAndLoss,
    val coveredAssetIds: List<Long>,
) {
    val hasCoverage: Boolean get() = coveredAssetIds.isNotEmpty()
}
