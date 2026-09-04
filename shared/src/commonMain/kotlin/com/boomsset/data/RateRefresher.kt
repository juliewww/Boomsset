package com.boomsset.data

import com.boomsset.domain.FxRate
import com.boomsset.domain.PortfolioData
import com.boomsset.network.FxRateSource
import com.boomsset.network.QuoteSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

/**
 * 打开 App 时刷新汇率。
 *
 * 只写 `fx_rate` 表，**不写 `snapshot`** —— 这是 docs/domain.md「行情 ≠ 快照」
 * 那条拆分的直接体现。刷新是高频的（每次打开），写快照会让表爆炸且污染历史曲线。
 *
 * 按天 upsert，所以一天开十次 App 也只留一条记录。
 */
class RateRefresher(
    private val repository: PortfolioRepository,
    private val fxSource: FxRateSource,
    private val quoteSource: QuoteSource,
    private val dispatcher: CoroutineDispatcher,
    private val clock: Clock = Clock.System,
    private val zone: TimeZone = TimeZone.currentSystemDefault(),
) {

    /** 本进程内已尝试过的 (币种, 日期)。防止失败时无限重试 —— 见下方说明。 */
    private val attempted = mutableSetOf<String>()
    private val mutex = Mutex()

    /**
     * 补齐当前持仓实际用到的币种对，**含历史**。
     *
     * 只取**需要的**币种对（资产币种 → 基准币种），不做全量拉取：
     * 少发请求，也少向服务方暴露信息。
     *
     * ## 为什么必须补历史，不能只拉今天
     *
     * 净值曲线一次画 12 个时点，每个时点都要用**当时的**汇率折算
     * （domain.md：用今天的汇率折算历史会污染曲线）。而这里原来只拉今天一天，
     * 于是把查看币种切成 USD 之后，除最新点以外的历史点全都取不到汇率、
     * 资产被判成"无法估值"、净值算成 0 —— 实机上表现为 8 月那根柱子是 0，
     * 而顶部卡片写着"净值增长 +$13,097 相比 2026年8月"（从零挣出全部身家）。
     *
     * 补的区间是「**这个币种实际被持有的那段时间**」（见 [requiredRanges]），
     * 不是固定回看 N 天：币种是三年前开始持有的就补三年，昨天才加的只补昨天到今天。
     *
     * 失败不抛异常 —— 取价失败应当退回 stale 汇率，而不是让界面挂掉。
     *
     * ## 为什么要记「已尝试」
     *
     * 调用方会在「需要的币种集合变化时」重新调用本方法（否则新增一个外币资产后
     * 永远拉不到它的汇率 —— 这是实跑时发现的 bug）。但写入 fx_rate 会让 portfolio
     * 流重新发射，进而再次触发调用。成功时条件已满足所以会停；**失败时会无限重试**。
     * 用 [attempted] 记下每个 (币种对, 区间) 只试一次，让失败也能收敛。
     * 区间进了 key，所以补了一段旧快照（区间变长）或者到了第二天都会重新尝试一次。
     *
     * 代价：一次失败后要等下次启动才重试。对汇率这种日更数据可以接受，
     * 而且失败时会退回 stale 汇率，不是没数据。
     */
    suspend fun refreshForHoldings(baseCurrency: String): RefreshResult =
        withContext(dispatcher) {
            val data = runCatching { repository.observePortfolio().first() }
                .getOrNull() ?: return@withContext RefreshResult(0, 0)

            val today = clock.now().toLocalDateTime(zone).date
            val required = requiredRanges(data, baseCurrency, today)

            val toFetch = mutex.withLock {
                required.filter { (currency, range) ->
                    attempted.add(attemptKey(currency, baseCurrency, range))
                }
            }
            if (toFetch.isEmpty()) return@withContext RefreshResult(0, 0)

            var written = 0
            var failed = 0
            toFetch.forEach { (currency, range) ->
                // 已经存过的那部分不重复请求 —— 回补是一次性的，之后每天只差一两天
                val missing = missingRange(data.fxRates, currency, baseCurrency, range)
                    ?: return@forEach
                val rates = fxSource.fetchRange(
                    from = currency,
                    to = baseCurrency,
                    start = missing.start,
                    end = missing.end,
                )
                if (rates.isNotEmpty()) {
                    repository.upsertFxRates(rates)
                    written++
                } else {
                    failed++
                }
            }
            RefreshResult(written = written, failed = failed)
        }

    private fun attemptKey(from: String, to: String, range: DayRange) =
        "$from>$to@${range.start}..${range.end}"

    /**
     * 每个币种对需要覆盖的日期区间。
     *
     * 起点是**这个币种最早那条快照的日期**：更早的时点上这些资产还不存在，
     * 净值里没有它们，不需要汇率。
     *
     * 终点分两种：只要还有一项**未归档**的资产用这个币种，就要到今天；
     * 全都归档了就到**最后一条快照那天** —— 归档之后它不再贡献当前净值，
     * 但历史时点上还在，那段区间的汇率仍然要有。
     *
     * 归档且一条快照都没有的资产直接跳过：它在任何时点上都没有值。
     */
    internal fun requiredRanges(
        data: PortfolioData,
        baseCurrency: String,
        today: LocalDate,
    ): Map<String, DayRange> {
        val byCurrency = data.assets.groupBy { it.currency }
        return byCurrency.mapNotNull { (currency, assets) ->
            if (currency == baseCurrency) return@mapNotNull null   // 1:1，不用查
            val ids = assets.map { it.id }.toSet()
            val days = data.snapshots
                .filter { it.assetId in ids }
                .map { it.asOf.toLocalDateTime(zone).date }
            val hasActive = assets.any { !it.isArchived }
            val end = when {
                hasActive -> today
                days.isEmpty() -> return@mapNotNull null
                else -> days.max()
            }
            val start = days.minOrNull() ?: today
            if (start > end) return@mapNotNull null
            currency to DayRange(start, end)
        }.toMap()
    }

    /**
     * 区间里还缺哪一段。
     *
     * - 一条都没有 → 整段都要
     * - 最早那条比区间起点还晚 → 历史没补过，整段重来（`INSERT OR REPLACE`，重复写无害）
     * - 只是最近几天没有 → 从已有的最后一天接着补（**从那天本身开始**，
     *   这样区间落在周末时服务方也能给出前一个营业日的报价）
     * - 已经覆盖到区间终点 → null，一个请求都不发
     *
     * 只看首尾、不检查中间有没有空洞：空洞只可能来自上一次部分失败，
     * 而结转规则是"取该时点前最近的一条"，空洞的后果是用稍旧的汇率，不是估不出值。
     */
    private fun missingRange(
        stored: List<FxRate>,
        from: String,
        to: String,
        need: DayRange,
    ): DayRange? {
        // asOfDay 是 ISO 字符串，字典序即时间序
        val days = stored.filter { it.base == from && it.quote == to }.map { it.asOfDay }
        val earliest = days.minOrNull() ?: return need
        if (earliest > need.start.toString()) return need
        val latest = days.max()
        if (latest >= need.end.toString()) return null
        val resumeFrom = runCatching { LocalDate.parse(latest) }.getOrNull() ?: return need
        return DayRange(resumeFrom, need.end)
    }

    /**
     * 刷新持仓里 QUOTED 快照用到的行情代码。
     *
     * 和汇率同样的收敛策略：每个 (代码, 日期) 只试一次，防止「写 quote → 数据流重发 →
     * 再刷新」在失败时变成无限循环。
     *
     * 代码取自**快照上的 quoteSymbol**，不是资产上的默认值 —— 退市转 MANUAL 的资产
     * 其历史快照仍需要行情，而资产上的 symbol 已经被清掉了。
     */
    suspend fun refreshQuotes(): RefreshResult = withContext(dispatcher) {
        val data = runCatching { repository.observePortfolio().first() }
            .getOrNull() ?: return@withContext RefreshResult(0, 0)

        val today = clock.now().toLocalDateTime(zone).date
        val activeIds = data.assets.filter { !it.isArchived }.map { it.id }.toSet()
        val symbols = data.snapshots
            .filterIsInstance<com.boomsset.domain.Snapshot.Quoted>()
            .filter { it.assetId in activeIds }
            .map { it.quoteSymbol }
            .toSet()

        val toFetch = mutex.withLock {
            symbols.filter { attempted.add("quote:$it@$today") }
        }.toSet()
        if (toFetch.isEmpty()) return@withContext RefreshResult(0, 0)

        val quotes = quoteSource.fetch(toFetch, today)
        quotes.forEach { repository.upsertQuote(it) }
        // 取不到的代码不会出现在返回值里，差额就是失败数
        RefreshResult(written = quotes.size, failed = toFetch.size - quotes.size)
    }
}

/** 闭区间的日期段，含首含尾。 */
data class DayRange(val start: LocalDate, val end: LocalDate)

/** [written] 数的是**币种对/代码**的个数，不是写入的行数。 */
data class RefreshResult(val written: Int, val failed: Int) {
    val hasFailures: Boolean get() = failed > 0
}
