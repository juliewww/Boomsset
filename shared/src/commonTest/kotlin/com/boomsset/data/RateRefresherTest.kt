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
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.time.Instant

class RateRefresherTest {

    private val epoch = Instant.fromEpochMilliseconds(1_785_000_000_000)

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
        override suspend fun appendManualSnapshot(assetId: Long, value: Money, costBasis: Money?) {}
        override suspend fun appendQuotedSnapshot(
            assetId: Long, quantity: Quantity, quoteSymbol: String, costBasis: Money?,
        ) {}
        override suspend fun archiveAsset(assetId: Long) {}
        override suspend fun upsertFxRate(rate: FxRate) { writtenRates += rate }
        override suspend fun upsertQuote(quote: Quote) {}
        override fun observeAllocations(): Flow<List<TargetAllocation>> = flowOf(emptyList())
        override suspend fun setActiveAllocation(id: Long) {}
        override suspend fun saveAllocationTargets(id: Long, targetsBp: Map<AssetClass, Int>) {}
        override suspend fun createAllocation(name: String, targetsBp: Map<AssetClass, Int>): Long = 0
        override suspend fun renameAllocation(id: Long, name: String) {}
        override suspend fun deleteAllocation(id: Long) {}
    }

    private class FakeFxSource(
        private val failFor: Set<String> = emptySet(),
    ) : FxRateSource {
        val requested = mutableListOf<String>()
        override suspend fun fetch(from: String, to: String, on: LocalDate): FxRate? {
            requested += from
            if (from in failFor) return null
            return FxRate(from, to, on.toString(), ExchangeRate(700_000_000))
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
        zone = kotlinx.datetime.TimeZone.UTC,
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
