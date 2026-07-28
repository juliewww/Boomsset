package com.boomsset.domain

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.plus
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

    /** 整段区间的净值增长率（基点）。注意这**包含新增投入**，不是投资收益率。 */
    val growthBp: Int?
        get() {
            val from = earliest ?: return null
            val to = latest ?: return null
            if (from === to) return null
            return PortfolioCalculator.netWorthGrowthBp(from, to)
        }
}

/**
 * 把原始数据折成时间序列。纯函数。
 */
object PortfolioSeriesCalculator {

    /**
     * @param today 用户本地时区的今天。由调用方传入而不是内部取 Clock，这样可测。
     * @param pointCount 取样点数量。12 个月 / 12 个季度 / 12 年。
     */
    fun buildSeries(
        data: PortfolioData,
        period: Period,
        baseCurrency: String,
        today: LocalDate,
        zone: TimeZone,
        pointCount: Int = 12,
    ): NetWorthSeries {
        val dates = periodSampleDates(today, period, pointCount)
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
