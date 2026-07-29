package com.boomsset.data

import com.boomsset.domain.PortfolioData
import com.boomsset.network.FxRateSource
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
    private val dispatcher: CoroutineDispatcher,
    private val clock: Clock = Clock.System,
    private val zone: TimeZone = TimeZone.currentSystemDefault(),
) {

    /** 本进程内已尝试过的 (币种, 日期)。防止失败时无限重试 —— 见下方说明。 */
    private val attempted = mutableSetOf<String>()
    private val mutex = Mutex()

    /**
     * 刷新当前持仓实际用到的币种对。
     *
     * 只取**需要的**币种对（资产币种 → 基准币种），不做全量拉取：
     * 少发请求，也少向服务方暴露信息。
     *
     * 失败不抛异常 —— 取价失败应当退回 stale 汇率，而不是让界面挂掉。
     *
     * ## 为什么要记「已尝试」
     *
     * 调用方会在「需要的币种集合变化时」重新调用本方法（否则新增一个外币资产后
     * 永远拉不到它的汇率 —— 这是实跑时发现的 bug）。但写入 fx_rate 会让 portfolio
     * 流重新发射，进而再次触发调用。成功时条件已满足所以会停；**失败时会无限重试**。
     * 用 [attempted] 记下每个 (币种, 日期) 只试一次，让失败也能收敛。
     *
     * 代价：一次失败后要等下次启动才重试。对汇率这种日更数据可以接受，
     * 而且失败时会退回 stale 汇率，不是没数据。
     */
    suspend fun refreshForHoldings(baseCurrency: String): RefreshResult =
        withContext(dispatcher) {
            val data = runCatching { repository.observePortfolio().first() }
                .getOrNull() ?: return@withContext RefreshResult(0, 0)

            val today = clock.now().toLocalDateTime(zone).date
            val needed = neededCurrencies(data, baseCurrency)

            val toFetch = mutex.withLock {
                needed.filter { attempted.add(attemptKey(it, baseCurrency, today.toString())) }
            }
            if (toFetch.isEmpty()) return@withContext RefreshResult(0, 0)

            var written = 0
            var failed = 0
            toFetch.forEach { currency ->
                val rate = fxSource.fetch(from = currency, to = baseCurrency, on = today)
                if (rate != null) {
                    repository.upsertFxRate(rate)
                    written++
                } else {
                    failed++
                }
            }
            RefreshResult(written = written, failed = failed)
        }

    private fun attemptKey(from: String, to: String, day: String) = "$from>$to@$day"

    /**
     * 需要哪些币种对。排除基准币种自身（1:1，不需要查），也排除已归档资产。
     */
    internal fun neededCurrencies(data: PortfolioData, baseCurrency: String): Set<String> =
        data.assets
            .filter { !it.isArchived }
            .map { it.currency }
            .filter { it != baseCurrency }
            .toSet()
}

data class RefreshResult(val written: Int, val failed: Int) {
    val hasFailures: Boolean get() = failed > 0
}
