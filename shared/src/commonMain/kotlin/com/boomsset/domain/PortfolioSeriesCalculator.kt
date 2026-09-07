package com.boomsset.domain

import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * 全部原始数据。
 *
 * **设计取舍：一次全量加载，在内存里算。** 这个 App 是本地优先、单用户，
 * 资产数量几十条、快照几百到几千条，全量加载远比"每个取样点一次查询"简单，
 * 而且让所有派生逻辑保持纯函数、可测。
 *
 * 如果将来快照量级到了几万条（比如改成每天自动生成快照），这里要改成按范围查询 +
 * SQL 侧聚合。到时候会明显变慢，不会静默出错。
 */
data class PortfolioData(
    val assets: List<Asset>,
    val snapshots: List<Snapshot>,
    val quotes: List<Quote>,
    val fxRates: List<FxRate>,
) {
    val isEmpty: Boolean get() = assets.isEmpty()

    companion object {
        val EMPTY = PortfolioData(emptyList(), emptyList(), emptyList(), emptyList())
    }
}

/** 净值时间序列，点按时间升序。 */
data class NetWorthSeries(
    val period: Period,
    val baseCurrency: String,
    val dates: List<LocalDate>,
    val points: List<NetWorthPoint>,
) {
    val latest: NetWorthPoint? get() = points.lastOrNull()
    val earliest: NetWorthPoint? get() = points.firstOrNull()

    /**
     * 首尾两个端点，只有一个点时为 null（没有可比的期初）。
     *
     * 三个「整段区间」的派生值（增长率、变化额、基准日）都从这里取端点 ——
     * 各自判一次"点够不够"迟早会判得不一样，然后 UI 上出现"增长 —"却又
     * 写着"相比 2026年8月"这种自相矛盾的组合。
     */
    private val endpoints: Pair<NetWorthPoint, NetWorthPoint>?
        get() {
            val from = earliest ?: return null
            val to = latest ?: return null
            return if (from === to) null else from to to
        }

    /** 有没有可比的期初（点数 ≥ 2）。UI 要区分「只记过一次」和「记过但算不出」。 */
    val hasBaseline: Boolean get() = endpoints != null

    /**
     * 整段区间的净值增长率（基点）。注意这**包含新增投入**，不是投资收益率。
     *
     * 任一端有资产无法估值就返回 null：百分比的分母是净值本身，而一个**已知低估**的
     * 净值做分母会把涨幅按比例放大（房子估不出值时，股票涨 1 万可能显示成 +10%）。
     */
    val growthBp: Int?
        get() = endpoints
            ?.takeIf { (from, to) -> !from.hasUnpriced && !to.hasUnpriced }
            ?.let { (from, to) -> PortfolioCalculator.netWorthGrowthBp(from, to) }

    /**
     * 整段区间的净值变化**额**。和 [growthBp] 取同一对端点，一个绝对一个相对。
     *
     * 需要它是因为百分比单独看不出量级："+2%" 可能是两千也可能是二十万，
     * 而用户真正记得住的是那个金额。
     *
     * **两端估值覆盖面不同时返回 null。** 这不是洁癖，是实机踩到的：把查看币种切成 USD，
     * 8 月那天没有历史汇率 → 那个点的资产整个估不出值、净值算成 0，拿它当期初，
     * "净值增长"就变成"这个月从 0 涨到全部身家"（+$13,097 相比 8 月）——
     * 一个纯属虚构的好消息。覆盖面一样时差额仍然有意义（比较的是同一个子集），
     * 所以这里判的是"覆盖面变没变"，而不是"有没有估不出的资产"。
     */
    val growthAbsolute: Money?
        get() = endpoints
            ?.takeIf { (from, to) -> from.unpricedAssetIds.toSet() == to.unpricedAssetIds.toSet() }
            ?.let { (from, to) -> to.netWorth - from.netWorth }

    /**
     * [growthBp] / [growthAbsolute] 是**相比哪一天**算的。
     *
     * UI 必须把它显示出来：同一个"净值增长 +2%"在按月/按季/按年下比的是完全不同的
     * 起点，不说基准就等于没说清这个数是什么。
     */
    val baselineDate: LocalDate?
        get() = endpoints?.let { dates.firstOrNull() }
}

/**
 * 按大类拆开的净敞口时间序列，点按时间升序。
 *
 * 每个点就是一个完整的 [AllocationView]，也就是说**口径和配置页一模一样**：
 * 分子是各大类的净敞口（该类资产 − 归属到该类的负债），只算
 * `includeInAllocation = true` 的资产。这一点很重要 —— 它意味着这条序列的合计
 * **不等于** [NetWorthSeries] 的净值（后者包含不计入配置的资产）。
 * 两个数并排出现时 UI 必须说明，否则用户会以为其中一个算错了。
 *
 * 取样日期和 [NetWorthSeries] 用的是同一批（同一个 [PortfolioSeriesCalculator] 私有助手
 * 算出来的），所以两张图的 x 轴逐点对齐，可以直接在同一页上换着看。
 */
data class AllocationSeries(
    val period: Period,
    val baseCurrency: String,
    val dates: List<LocalDate>,
    val points: List<AllocationView>,
) {
    val latest: AllocationView? get() = points.lastOrNull()

    /** 某一类在每个取样点的净敞口，顺序与 [dates] 一致。缺失按 0 —— 该类此时确实没有敞口。 */
    fun netExposures(assetClass: AssetClass): List<Money> =
        points.map { it.exposures[assetClass]?.netExposure ?: Money.ZERO }

    /**
     * 这几类在每个取样点的净敞口之和。
     *
     * 图上柱子的高度就是这个和，所以「相比前一根涨了多少」必须拿它来算 ——
     * 用户勾掉几个大类之后，柱子变矮了，此时拿全量合计算出的增长率和眼前的柱子对不上。
     */
    fun totals(classes: Collection<AssetClass>): List<Money> =
        points.map { point ->
            classes.fold(Money.ZERO) { acc, assetClass ->
                acc + (point.exposures[assetClass]?.netExposure ?: Money.ZERO)
            }
        }

    /**
     * 这几类里**任何一个取样点**出现过负敞口（该类的负债超过该类资产）。
     *
     * 堆叠面积图是靠「累计值 + 不透明色后画的盖前画的」拼出来的，这个做法要求每一段
     * 都非负；出现负值时累计不再单调，画出来的分层就是错的。所以趋势图必须先问这个，
     * 命中就退回各类独立曲线，而不是画一张看起来正常、其实分层错位的图。
     */
    fun hasNegativeExposure(classes: Collection<AssetClass>): Boolean =
        classes.any { assetClass -> netExposures(assetClass).any { it.minorUnits < 0L } }
}

/**
 * 把原始数据折成时间序列。纯函数。
 */
object PortfolioSeriesCalculator {

    /**
     * @param today 用户本地时区的今天。由调用方传入而不是内部取 Clock，这样可测。
     * @param pointCount 取样点数量。12 个月 / 12 个季度 / 12 年。
     * @param trimBeforeFirstSnapshot 丢掉「第一条快照之前」的取样点。
     *
     * 默认 `false`，保持 [periodSampleDates] 原本"固定取 N 个周期"的行为不变——
     * 有一条测试（"资产创建之前的时点不计入"）明确依赖"资产建立前的周期显示为 0 值点"
     * 这条结转语义，trim 不应该改写那条语义，只是**在展示层决定要不要把那些点画出来**。
     *
     * 传 `true` 时：实跑反馈是"按年/按季看的时候，账号才用了几个月，
     * 前面一大截全是 0，还占满了图"。裁剪规则是丢掉**结束时刻早于最早快照时刻**的
     * 那些取样点——不是看"净值是不是 0"，因为账户清零之后的真实 0（比如全部资产
     * 归档）不该被当成"没数据"抹掉，那是历史的一部分。
     *
     * 永远至少保留最后一个点（今天所在的周期），哪怕它也早于最早快照 ——
     * 空状态由 `hasAssets` 单独判断，这里不需要再处理"一个点都不剩"的情况。
     */
    fun buildSeries(
        data: PortfolioData,
        period: Period,
        baseCurrency: String,
        today: LocalDate,
        zone: TimeZone,
        pointCount: Int = 12,
        trimBeforeFirstSnapshot: Boolean = false,
    ): NetWorthSeries {
        val dates = sampleDates(data, period, today, zone, pointCount, trimBeforeFirstSnapshot)
        val points = dates.map { date ->
            val at = date.endOfDayIn(zone)
            PortfolioCalculator.netWorth(
                asOf = at,
                assets = data.assets,
                snapshots = data.latestSnapshotsAt(at),
                context = data.valuationContextAt(date, baseCurrency),
            )
        }
        return NetWorthSeries(period, baseCurrency, dates, points)
    }

    /**
     * 按大类拆开的时间序列，用于净值页「按大类看」。参数含义与 [buildSeries] 完全一致。
     *
     * 每个取样点直接复用 [PortfolioCalculator.allocation] —— 净敞口、
     * `includeInAllocation` 的取舍、分母怎么算，这些规则只能有一份实现，
     * 否则净值页和配置页会对同一批数据给出两套百分比。
     *
     * `target` 传 null：目标比例是配置页的事，这条序列只关心各类**金额**随时间怎么变。
     */
    fun buildAllocationSeries(
        data: PortfolioData,
        period: Period,
        baseCurrency: String,
        today: LocalDate,
        zone: TimeZone,
        pointCount: Int = 12,
        trimBeforeFirstSnapshot: Boolean = false,
    ): AllocationSeries {
        val dates = sampleDates(data, period, today, zone, pointCount, trimBeforeFirstSnapshot)
        val points = dates.map { date ->
            val at = date.endOfDayIn(zone)
            PortfolioCalculator.allocation(
                asOf = at,
                assets = data.assets,
                snapshots = data.latestSnapshotsAt(at),
                context = data.valuationContextAt(date, baseCurrency),
                target = null,
            )
        }
        return AllocationSeries(period, baseCurrency, dates, points)
    }

    /**
     * 取样日期 + 「丢掉第一条快照之前那些点」的裁剪。
     *
     * [buildSeries] 和 [buildAllocationSeries] **必须共用这一份** ——
     * 两条序列会在同一页上换着看，各自算一遍取样日期的话，
     * 只要有一处的裁剪判据写得不一样，两张图的 x 轴就会错开一格，
     * 而这种错位在界面上看起来只是"数字有点怪"，很难联想到是取样点对不上。
     */
    private fun sampleDates(
        data: PortfolioData,
        period: Period,
        today: LocalDate,
        zone: TimeZone,
        pointCount: Int,
        trimBeforeFirstSnapshot: Boolean,
    ): List<LocalDate> {
        val allDates = periodSampleDates(today, period, pointCount)
        val earliestSnapshot = data.snapshots.minOfOrNull { it.asOf }
        if (!trimBeforeFirstSnapshot || earliestSnapshot == null) return allDates
        val firstWithData = allDates.indexOfFirst { it.endOfDayIn(zone) >= earliestSnapshot }
        return if (firstWithData < 0) listOf(allDates.last())
        else allDates.subList(firstWithData, allDates.size)
    }

    /** 当前时点的配置视图。 */
    fun currentAllocation(
        data: PortfolioData,
        baseCurrency: String,
        today: LocalDate,
        zone: TimeZone,
        target: TargetAllocation?,
    ): AllocationView {
        val at = today.endOfDayIn(zone)
        return PortfolioCalculator.allocation(
            asOf = at,
            assets = data.assets,
            snapshots = data.latestSnapshotsAt(at),
            context = data.valuationContextAt(today, baseCurrency),
            target = target,
        )
    }

    /**
     * 当前时点每项资产的估值，用于资产列表。
     *
     * 默认排除已归档的 —— 它们的历史仍在净值曲线里，但不该出现在"我现在持有什么"的列表里。
     */
    fun currentAssetValuations(
        data: PortfolioData,
        baseCurrency: String,
        today: LocalDate,
        zone: TimeZone,
        includeArchived: Boolean = false,
    ): List<AssetValuation> {
        val at = today.endOfDayIn(zone)
        val snapshots = data.latestSnapshotsAt(at)
        val context = data.valuationContextAt(today, baseCurrency)
        val counts = data.snapshots.groupingBy { it.assetId }.eachCount()

        return data.assets
            .filter { includeArchived || !it.isArchived }
            .map { asset ->
                val snapshot = snapshots[asset.id]
                val local = snapshot?.let { PortfolioCalculator.localValue(it, context.quotes) }
                val rate = context.rateTo(asset.currency)
                val quote = (snapshot as? Snapshot.Quoted)?.let { context.quotes[it.quoteSymbol] }
                AssetValuation(
                    asset = asset,
                    snapshot = snapshot,
                    localValue = local,
                    baseValue = if (local != null && rate != null) {
                        runCatching { rate.convert(local) }.getOrNull()
                    } else {
                        null
                    },
                    pnl = snapshot?.let {
                        PortfolioCalculator.profitAndLoss(it, context.quotes)
                    },
                    snapshotCount = counts[asset.id] ?: 0,
                    quote = quote,
                    priceAgeDays = quote?.let { daysBetween(it.asOfDay, today) },
                )
            }
    }

    /** 当前时点的组合浮动盈亏。 */
    fun currentProfitAndLoss(
        data: PortfolioData,
        baseCurrency: String,
        today: LocalDate,
        zone: TimeZone,
    ): PortfolioPnL {
        val at = today.endOfDayIn(zone)
        return PortfolioCalculator.portfolioProfitAndLoss(
            assets = data.assets,
            snapshots = data.latestSnapshotsAt(at),
            context = data.valuationContextAt(today, baseCurrency),
        )
    }

    /**
     * 最近一次记录快照的日期（用户本地时区）。
     *
     * 这个 App **记快照不记流水**，所以"数据有多新"直接决定顶上那个净值可不可信 ——
     * 三个月没更新的净值和今天刚更新的净值长得一模一样，不把日期显示出来，
     * 用户没有任何线索判断自己在看的是不是过期数字。
     *
     * 只看**未归档**资产：归档会追加一条 0 值快照，那是"结束维护"的动作，
     * 拿它当"最近记录"会让一次归档把整个组合伪装成刚更新过。
     *
     * @return 一条快照都没有时返回 null（新用户）。
     */
    fun lastRecordedDate(data: PortfolioData, zone: TimeZone): LocalDate? {
        val activeIds = data.assets.filterNot { it.isArchived }.map { it.id }.toSet()
        return data.snapshots
            .filter { it.assetId in activeIds }
            .maxOfOrNull { it.asOf }
            ?.toLocalDateTime(zone)
            ?.date
    }
}

/**
 * 两个 ISO 日期之间相差多少天。解析失败返回 null 而不是猜。
 */
internal fun daysBetween(fromIsoDay: String, to: LocalDate): Int? {
    val from = runCatching { LocalDate.parse(fromIsoDay) }.getOrNull() ?: return null
    return from.daysUntil(to)
}

/**
 * 「这一天结束时」的瞬时值 —— 取次日零点前 1 毫秒，这样当天录入的快照都能被 `<=` 命中。
 */
internal fun LocalDate.endOfDayIn(zone: TimeZone): Instant =
    plus(1, DateTimeUnit.DAY).atStartOfDayIn(zone) - kotlin.time.Duration.parse("1ms")

/**
 * 结转规则：每个资产取 [at] 之前**最近的一条**快照。
 * 同一时点有多条（修正历史）时取 id 最大的，即最后录入的那条。
 */
internal fun PortfolioData.latestSnapshotsAt(at: Instant): Map<Long, Snapshot> =
    snapshots
        .filter { it.asOf <= at }
        .groupBy { it.assetId }
        .mapValues { (_, list) -> list.maxWith(compareBy({ it.asOf }, { it.id })) }

/**
 * 构造该日期的估值上下文 —— 行情和汇率都取**当天或之前最近的一条**。
 *
 * 用历史汇率而不是今天的，否则汇率波动会污染历史曲线。
 * `asOfDay` 是 ISO 字符串，字典序即时间序，所以可以直接比较。
 */
internal fun PortfolioData.valuationContextAt(
    day: LocalDate,
    baseCurrency: String,
): ValuationContext {
    val dayKey = day.toString()

    val latestQuotes = quotes
        .filter { it.asOfDay <= dayKey }
        .groupBy { it.symbol }
        .mapValues { (_, list) -> list.maxBy { it.asOfDay } }

    val latestRates = fxRates
        .filter { it.asOfDay <= dayKey }
        .groupBy { ValuationContext.rateKey(it.base, it.quote) }
        .mapValues { (_, list) -> list.maxBy { it.asOfDay }.rate }

    return ValuationContext(
        baseCurrency = baseCurrency,
        quotes = latestQuotes,
        rates = latestRates,
    )
}
