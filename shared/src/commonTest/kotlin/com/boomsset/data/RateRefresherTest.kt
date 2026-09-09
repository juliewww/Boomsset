package com.boomsset.data

import com.boomsset.domain.Asset
import com.boomsset.domain.AssetClass
import com.boomsset.domain.AssetSubtype
import com.boomsset.domain.ExchangeRate
import com.boomsset.domain.FxRate
import com.boomsset.domain.Money
import com.boomsset.domain.PortfolioData
import com.boomsset.domain.Quantity
import com.boomsset.domain.Quote
import com.boomsset.domain.UnitPrice
import com.boomsset.domain.TargetAllocation
import com.boomsset.domain.ValuationMode
import com.boomsset.network.FxRateSource
import com.boomsset.network.QuoteSource
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.test.Test
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class RateRefresherTest {

    private val epoch = Instant.fromEpochMilliseconds(1_785_000_000_000)
    private val zone = kotlinx.datetime.TimeZone.UTC

    /** 刷新器眼里的"今天"，和下面那个假时钟一致。 */
    private val today = epoch.toLocalDateTime(zone).date

    private fun manual(id: Long, assetId: Long, day: LocalDate, valueMinor: Long = 100_00) =
        com.boomsset.domain.Snapshot.Manual(
            id = id,
            assetId = assetId,
            asOf = day.atStartOfDayIn(zone),
            value = Money(valueMinor),
            recordedAt = epoch,
        )

    private fun rate(from: String, to: String, day: String) =
        FxRate(from, to, day, ExchangeRate(700_000_000))

    private fun asset(id: Long, currency: String, archived: Boolean = false) = Asset(
        id = id,
        name = "a$id",
        assetClass = AssetClass.LIQUID,
        subtypeId = 1,
        currency = currency,
        defaultValuationMode = ValuationMode.MANUAL,
        archivedAt = if (archived) epoch else null,
    )

    /** 手写 fake，不用 mock 框架 —— 见 docs/stack.md 关于 Mokkery 的说明。 */
    private class FakeRepository(private val data: PortfolioData) : PortfolioRepository {
        val writtenRates = mutableListOf<FxRate>()

        override fun observePortfolio(): Flow<PortfolioData> = flowOf(data)
        override fun observeActiveTarget(): Flow<TargetAllocation?> = flowOf(null)
        override fun observeSubtypes(): Flow<List<AssetSubtype>> = flowOf(emptyList())
        override suspend fun createAsset(
            name: String, assetClass: AssetClass, subtypeId: Long, currency: String,
            isLiability: Boolean, includeInAllocation: Boolean, mode: ValuationMode,
            quoteSymbol: String?, initialValue: Money?, initialQuantity: Quantity?,
            costBasis: Money?,
        ): Long = 0
        override suspend fun appendManualSnapshot(
            assetId: Long, value: Money, costBasis: Money?, asOf: Instant?,
        ) {}
        override suspend fun appendQuotedSnapshot(
            assetId: Long, quantity: Quantity, quoteSymbol: String, costBasis: Money?, asOf: Instant?,
        ) {}
        override suspend fun archiveAsset(assetId: Long) {}
        override suspend fun unarchiveAsset(assetId: Long) {}
        override suspend fun updateAssetMeta(
            assetId: Long, name: String, assetClass: AssetClass, subtypeId: Long,
            currency: String, includeInAllocation: Boolean,
            defaultValuationMode: ValuationMode, defaultQuoteSymbol: String?,
        ) {}
        override suspend fun createSubtype(
            name: String, assetClass: AssetClass, defaultValuationMode: ValuationMode,
        ): Long = 0
        override suspend fun upsertFxRate(rate: FxRate) { writtenRates += rate }
        override suspend fun upsertFxRates(rates: List<FxRate>) { writtenRates += rates }
        override suspend fun upsertQuote(quote: Quote) {}
        override fun observeAllocations(): Flow<List<TargetAllocation>> = flowOf(emptyList())
        override suspend fun setActiveAllocation(id: Long) {}
        override suspend fun saveAllocationTargets(id: Long, targetsBp: Map<AssetClass, Int>) {}
        override suspend fun createAllocation(name: String, targetsBp: Map<AssetClass, Int>): Long = 0
        override suspend fun renameAllocation(id: Long, name: String) {}
        override suspend fun deleteAllocation(id: Long) {}
    }

    /**
     * 汇率源的 fake。记下**请求了哪个币种的哪一段区间** —— 这次改动的核心就是
     * "区间对不对"，只记币种的话历史回补写错了也测不出来。
     *
     * 返回值按天枚举整段区间（真接口会跳过周末，这里不模拟那个 ——
     * 周末落到前一个营业日是 [com.boomsset.network.FrankfurterFxRateSource] 的责任，
     * 那边有自己的报文级测试）。
     */
    private class FakeFxSource(
        private val failFor: Set<String> = emptySet(),
    ) : FxRateSource {
        val requested = mutableListOf<String>()
        val ranges = mutableListOf<Triple<String, LocalDate, LocalDate>>()

        override suspend fun fetchRange(
            from: String,
            to: String,
            start: LocalDate,
            end: LocalDate,
        ): List<FxRate> {
            requested += from
            ranges += Triple(from, start, end)
            if (from in failFor) return emptyList()
            return generateSequence(start) { it.plus(1, DateTimeUnit.DAY) }
                .takeWhile { it <= end }
                .map { FxRate(from, to, it.toString(), ExchangeRate(700_000_000)) }
                .toList()
        }
    }

    /** 行情源的 fake。默认按代码返回固定价，可指定哪些代码取不到。 */
    private class FakeQuoteSource(private val missing: Set<String> = emptySet()) : QuoteSource {
        val requested = mutableListOf<String>()
        override suspend fun fetch(symbols: Set<String>, on: LocalDate): List<Quote> {
            requested += symbols
            return symbols.filterNot { it in missing }.map {
                Quote(it, on.toString(), UnitPrice.ofMajorUnits(100), "CNY", Instant.fromEpochMilliseconds(0))
            }
        }
    }

    private fun refresher(
        repo: PortfolioRepository,
        fx: FxRateSource,
        quotes: QuoteSource = FakeQuoteSource(),
    ) = RateRefresher(
        repository = repo,
        fxSource = fx,
        quoteSource = quotes,
        dispatcher = UnconfinedTestDispatcher(),
        clock = object : kotlin.time.Clock {
            override fun now() = epoch
        },
        zone = zone,
    )

    @Test
    fun `只查实际持有的外币 不做全量拉取`() = runTest {
        val data = PortfolioData(
            assets = listOf(asset(1, "CNY"), asset(2, "USD"), asset(3, "HKD")),
            snapshots = emptyList(), quotes = emptyList(), fxRates = emptyList(),
        )
        val fx = FakeFxSource()
        val repo = FakeRepository(data)

        refresher(repo, fx).refreshForHoldings("CNY")

        // 基准币种 CNY 自己不查（1:1），只查 USD 和 HKD
        fx.requested.sorted() shouldContainExactly listOf("HKD", "USD")
        repo.writtenRates.size shouldBe 2
    }

    // ---------- 历史区间 ----------

    @Test
    fun `补到最早那条快照那天 而不是只拉今天`() = runTest {
        // 这是这次修复的核心。净值曲线上每个历史时点都要用**当时的**汇率折算，
        // 只拉今天的话，除最新点以外全都取不到汇率 → 资产判成"无法估值" →
        // 那些点的净值算成 0（实机上表现为 8 月那根柱子是 0）
        val data = PortfolioData(
            assets = listOf(asset(1, "USD")),
            snapshots = listOf(
                manual(1, 1, LocalDate(2026, 5, 10)),
                manual(2, 1, LocalDate(2026, 6, 30)),
            ),
            quotes = emptyList(), fxRates = emptyList(),
        )
        val fx = FakeFxSource()

        refresher(FakeRepository(data), fx).refreshForHoldings("CNY")

        fx.ranges.single() shouldBe Triple("USD", LocalDate(2026, 5, 10), today)
    }

    @Test
    fun `更早的时点不补 因为那时资产还不存在`() = runTest {
        // 起点是第一条快照，不是"固定回看 12 个月" —— 昨天才加的资产只补昨天到今天
        val data = PortfolioData(
            assets = listOf(asset(1, "USD")),
            snapshots = listOf(manual(1, 1, today.minus(1, DateTimeUnit.DAY))),
            quotes = emptyList(), fxRates = emptyList(),
        )
        val fx = FakeFxSource()

        refresher(FakeRepository(data), fx).refreshForHoldings("CNY")

        fx.ranges.single() shouldBe Triple("USD", today.minus(1, DateTimeUnit.DAY), today)
    }

    @Test
    fun `全部归档的币种只补到最后一条快照那天`() = runTest {
        // 归档之后它不再贡献当前净值，今天的汇率对它没用；
        // 但历史时点上它还在，那段区间的汇率仍然要有 —— 否则回看时那些点又变成 0
        val data = PortfolioData(
            assets = listOf(asset(1, "USD", archived = true)),
            snapshots = listOf(
                manual(1, 1, LocalDate(2026, 5, 10)),
                manual(2, 1, LocalDate(2026, 6, 30), valueMinor = 0),   // 归档的归零快照
            ),
            quotes = emptyList(), fxRates = emptyList(),
        )
        val fx = FakeFxSource()

        refresher(FakeRepository(data), fx).refreshForHoldings("CNY")

        fx.ranges.single() shouldBe
            Triple("USD", LocalDate(2026, 5, 10), LocalDate(2026, 6, 30))
    }

    @Test
    fun `历史已经补过时只接着补最近几天`() = runTest {
        // 回补是一次性的。第二天再打开，只差昨天到今天这一两天，
        // 不该把三年的区间重新拉一遍
        val data = PortfolioData(
            assets = listOf(asset(1, "USD")),
            snapshots = listOf(manual(1, 1, LocalDate(2026, 5, 10))),
            quotes = emptyList(),
            fxRates = listOf(
                rate("USD", "CNY", "2026-05-08"),   // 比区间起点更早，说明历史补过了
                rate("USD", "CNY", "2026-07-20"),
            ),
        )
        val fx = FakeFxSource()

        refresher(FakeRepository(data), fx).refreshForHoldings("CNY")

        // 从已有的最后一天本身开始（不是它的次日）—— 那天可能是周五，
        // 而今天是周末，只有从周五起才拿得到报价
        fx.ranges.single() shouldBe Triple("USD", LocalDate(2026, 7, 20), today)
    }

    @Test
    fun `已经覆盖到今天就一个请求都不发`() = runTest {
        val data = PortfolioData(
            assets = listOf(asset(1, "USD")),
            snapshots = listOf(manual(1, 1, LocalDate(2026, 5, 10))),
            quotes = emptyList(),
            fxRates = listOf(
                rate("USD", "CNY", "2026-05-09"),
                rate("USD", "CNY", today.toString()),
            ),
        )
        val fx = FakeFxSource()

        val result = refresher(FakeRepository(data), fx).refreshForHoldings("CNY")

        fx.ranges.isEmpty() shouldBe true
        result.written shouldBe 0
        result.failed shouldBe 0
    }

    @Test
    fun `只有最近的汇率而没有历史时 整段重新补`() = runTest {
        // 这次修复之前存下来的数据就是这个样子：只有今天一条。
        // 老用户升级后必须能把历史补上，不能因为"最新的有了"就跳过
        val data = PortfolioData(
            assets = listOf(asset(1, "USD")),
            snapshots = listOf(manual(1, 1, LocalDate(2026, 5, 10))),
            quotes = emptyList(),
            fxRates = listOf(rate("USD", "CNY", today.toString())),
        )
        val fx = FakeFxSource()

        refresher(FakeRepository(data), fx).refreshForHoldings("CNY")

        fx.ranges.single() shouldBe Triple("USD", LocalDate(2026, 5, 10), today)
    }

    @Test
    fun `已归档资产的币种不再查`() = runTest {
        val data = PortfolioData(
            assets = listOf(asset(1, "USD"), asset(2, "JPY", archived = true)),
            snapshots = emptyList(), quotes = emptyList(), fxRates = emptyList(),
        )
        val fx = FakeFxSource()

        refresher(FakeRepository(data), fx).refreshForHoldings("CNY")

        fx.requested shouldContainExactly listOf("USD")
    }

    @Test
    fun `重复币种只查一次`() = runTest {
        val data = PortfolioData(
            assets = listOf(asset(1, "USD"), asset(2, "USD"), asset(3, "USD")),
            snapshots = emptyList(), quotes = emptyList(), fxRates = emptyList(),
        )
        val fx = FakeFxSource()

        refresher(FakeRepository(data), fx).refreshForHoldings("CNY")

        fx.requested shouldContainExactly listOf("USD")
    }

    @Test
    fun `部分失败不影响其余 且如实报告失败数`() = runTest {
        val data = PortfolioData(
            assets = listOf(asset(1, "USD"), asset(2, "TWD")),
            snapshots = emptyList(), quotes = emptyList(), fxRates = emptyList(),
        )
        // TWD 不在 ECB 列表里，注定失败
        val fx = FakeFxSource(failFor = setOf("TWD"))
        val repo = FakeRepository(data)

        val result = refresher(repo, fx).refreshForHoldings("CNY")

        result.written shouldBe 1
        result.failed shouldBe 1
        result.hasFailures shouldBe true
        // 成功的那条照常写入 —— 一个失败不该拖垮整次刷新
        repo.writtenRates.single().base shouldBe "USD"
    }

    @Test
    fun `切换基准币种后查的是新的币种对`() = runTest {
        val data = PortfolioData(
            assets = listOf(asset(1, "CNY"), asset(2, "USD")),
            snapshots = emptyList(), quotes = emptyList(), fxRates = emptyList(),
        )
        val fx = FakeFxSource()

        // 基准换成 USD 后，反过来要查 CNY→USD
        refresher(FakeRepository(data), fx).refreshForHoldings("USD")

        fx.requested shouldContainExactly listOf("CNY")
    }

    /**
     * 回归测试。写入 fx_rate 会让 portfolio 流重新发射，调用方会再次触发刷新 ——
     * 如果不记「已尝试」，成功时靠条件满足能停，但**失败时会无限重试**。
     */
    @Test
    fun `同一币种同一天只请求一次 即使被反复调用`() = runTest {
        val data = PortfolioData(
            assets = listOf(asset(1, "USD")),
            snapshots = emptyList(), quotes = emptyList(), fxRates = emptyList(),
        )
        val fx = FakeFxSource()
        val r = refresher(FakeRepository(data), fx)

        r.refreshForHoldings("CNY")
        r.refreshForHoldings("CNY")
        r.refreshForHoldings("CNY")

        fx.requested shouldContainExactly listOf("USD")
    }

    @Test
    fun `失败后本次会话不再重试 避免无限循环`() = runTest {
        val data = PortfolioData(
            assets = listOf(asset(1, "TWD")),
            snapshots = emptyList(), quotes = emptyList(), fxRates = emptyList(),
        )
        val fx = FakeFxSource(failFor = setOf("TWD"))
        val r = refresher(FakeRepository(data), fx)

        r.refreshForHoldings("CNY").failed shouldBe 1
        // 第二次调用不该再打一次请求 —— 否则失败的币种会把请求打爆
        r.refreshForHoldings("CNY").failed shouldBe 0
        fx.requested shouldContainExactly listOf("TWD")
    }

    @Test
    fun `换基准币种后是新的币种对 所以会重新请求`() = runTest {
        val data = PortfolioData(
            assets = listOf(asset(1, "USD")),
            snapshots = emptyList(), quotes = emptyList(), fxRates = emptyList(),
        )
        val fx = FakeFxSource()
        val r = refresher(FakeRepository(data), fx)

        r.refreshForHoldings("CNY")
        r.refreshForHoldings("HKD")   // USD→HKD 是没试过的组合

        fx.requested shouldContainExactly listOf("USD", "USD")
    }

    // ---------- 行情刷新 ----------

    @Test
    fun `行情代码取自快照而不是资产默认值`() = runTest {
        // 退市转 MANUAL 的资产，其历史 QUOTED 快照仍需要行情，
        // 而资产上的 defaultQuoteSymbol 已被清掉
        val a = asset(1, "CNY").copy(defaultQuoteSymbol = null)
        val data = PortfolioData(
            assets = listOf(a),
            snapshots = listOf(
                com.boomsset.domain.Snapshot.Quoted(
                    id = 1, assetId = 1, asOf = epoch,
                    quantity = Quantity.ofUnits(100), quoteSymbol = "sh600519",
                    recordedAt = epoch,
                ),
            ),
            quotes = emptyList(), fxRates = emptyList(),
        )
        val q = FakeQuoteSource()
        val repo = FakeRepository(data)

        refresher(repo, FakeFxSource(), q).refreshQuotes()

        q.requested shouldContainExactly listOf("sh600519")
    }

    @Test
    fun `同一代码同一天只请求一次`() = runTest {
        val data = PortfolioData(
            assets = listOf(asset(1, "CNY")),
            snapshots = listOf(
                com.boomsset.domain.Snapshot.Quoted(
                    id = 1, assetId = 1, asOf = epoch,
                    quantity = Quantity.ofUnits(1), quoteSymbol = "sh600519",
                    recordedAt = epoch,
                ),
            ),
            quotes = emptyList(), fxRates = emptyList(),
        )
        val q = FakeQuoteSource()
        val r = refresher(FakeRepository(data), FakeFxSource(), q)

        r.refreshQuotes()
        r.refreshQuotes()

        q.requested shouldContainExactly listOf("sh600519")
    }

    @Test
    fun `取不到的代码计入失败数而不是写零价`() = runTest {
        val data = PortfolioData(
            assets = listOf(asset(1, "CNY")),
            snapshots = listOf(
                com.boomsset.domain.Snapshot.Quoted(
                    id = 1, assetId = 1, asOf = epoch,
                    quantity = Quantity.ofUnits(1), quoteSymbol = "shbad",
                    recordedAt = epoch,
                ),
            ),
            quotes = emptyList(), fxRates = emptyList(),
        )
        val result = refresher(
            FakeRepository(data), FakeFxSource(), FakeQuoteSource(missing = setOf("shbad")),
        ).refreshQuotes()

        result.written shouldBe 0
        result.failed shouldBe 1
    }

    @Test
    fun `没有外币资产时不发任何请求`() = runTest {
        val data = PortfolioData(
            assets = listOf(asset(1, "CNY")),
            snapshots = emptyList(), quotes = emptyList(), fxRates = emptyList(),
        )
        val fx = FakeFxSource()

        refresher(FakeRepository(data), fx).refreshForHoldings("CNY")

        fx.requested.isEmpty() shouldBe true
    }
}
