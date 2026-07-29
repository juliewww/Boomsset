package com.boomsset.domain

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.time.Instant

class AssetValuationTest {

    private val zone = TimeZone.UTC
    private val today = LocalDate(2026, 7, 28)
    private val cny = "CNY"
    private val epoch = Instant.fromEpochMilliseconds(0)

    private fun asset(
        id: Long,
        cls: AssetClass = AssetClass.LIQUID,
        archived: Boolean = false,
        currency: String = cny,
    ) = Asset(
        id = id,
        name = "asset-$id",
        assetClass = cls,
        subtypeId = 1,
        currency = currency,
        defaultValuationMode = ValuationMode.MANUAL,
        archivedAt = if (archived) epoch else null,
    )

    private fun manual(id: Long, assetId: Long, value: Long, cost: Long? = null) =
        Snapshot.Manual(
            id = id,
            assetId = assetId,
            asOf = LocalDate(2026, 7, 1).endOfDayIn(zone),
            value = Money(value),
            costBasisMinor = cost?.let { Money(it) },
            recordedAt = epoch,
        )

    @Test
    fun `默认排除已归档资产`() {
        val data = PortfolioData(
            assets = listOf(asset(1), asset(2, archived = true)),
            snapshots = listOf(manual(1, 1, 100_00), manual(2, 2, 200_00)),
            quotes = emptyList(),
            fxRates = emptyList(),
        )

        val rows = PortfolioSeriesCalculator.currentAssetValuations(data, cny, today, zone)
        rows shouldHaveSize 1
        rows.single().asset.id shouldBe 1L
    }

    @Test
    fun `没有快照的资产被标记而不是当成零`() {
        val data = PortfolioData(
            assets = listOf(asset(1)),
            snapshots = emptyList(),
            quotes = emptyList(),
            fxRates = emptyList(),
        )

        val row = PortfolioSeriesCalculator.currentAssetValuations(data, cny, today, zone).single()
        row.hasNoSnapshot shouldBe true
        row.baseValue.shouldBeNull()
        // hasNoSnapshot 和 isUnpriced 是两件不同的事：前者是没录过，后者是录了但估不出来
        row.isUnpriced shouldBe false
    }

    @Test
    fun `缺汇率的外币资产标记为无法估值`() {
        val data = PortfolioData(
            assets = listOf(asset(1, currency = "USD")),
            snapshots = listOf(manual(1, 1, 100_00)),
            quotes = emptyList(),
            fxRates = emptyList(),
        )

        val row = PortfolioSeriesCalculator.currentAssetValuations(data, cny, today, zone).single()
        row.isUnpriced shouldBe true
        row.localValue shouldBe Money(100_00)   // 自身币种下是知道的
        row.baseValue.shouldBeNull()            // 但折算不出来
    }

    @Test
    fun `填了成本的资产带盈亏`() {
        val data = PortfolioData(
            assets = listOf(asset(1)),
            snapshots = listOf(manual(1, 1, value = 125_000_50, cost = 120_000_00)),
            quotes = emptyList(),
            fxRates = emptyList(),
        )

        val row = PortfolioSeriesCalculator.currentAssetValuations(data, cny, today, zone).single()
        row.pnl!!.absolute shouldBe Money(5_000_50)
    }

    @Test
    fun `QUOTED 资产的成本均价是派生值`() {
        val stock = asset(1, AssetClass.EQUITY)
        val data = PortfolioData(
            assets = listOf(stock),
            snapshots = listOf(
                Snapshot.Quoted(
                    id = 1,
                    assetId = 1,
                    asOf = LocalDate(2026, 7, 1).endOfDayIn(zone),
                    quantity = Quantity.ofUnits(100),
                    quoteSymbol = "X",
                    costBasisMinor = Money(105_000),  // 总成本 1050 元
                    recordedAt = epoch,
                ),
            ),
            quotes = listOf(Quote("X", "2026-07-01", Money(1500), cny, epoch)),
            fxRates = emptyList(),
        )

        val row = PortfolioSeriesCalculator.currentAssetValuations(data, cny, today, zone).single()
        // 1050 元 / 100 份 = 10.50 元/份
        row.unitCost shouldBe Money(1050)
        row.baseValue shouldBe Money(150_000)  // 100 × 15.00 元
    }

    @Test
    fun `MANUAL 资产没有成本均价概念`() {
        val data = PortfolioData(
            assets = listOf(asset(1)),
            snapshots = listOf(manual(1, 1, 100_00, cost = 90_00)),
            quotes = emptyList(),
            fxRates = emptyList(),
        )

        // 房子没有"份额"，均价无意义
        PortfolioSeriesCalculator.currentAssetValuations(data, cny, today, zone)
            .single().unitCost.shouldBeNull()
    }

    @Test
    fun `归档后的资产可以显式包含进来`() {
        val data = PortfolioData(
            assets = listOf(asset(1, archived = true)),
            snapshots = listOf(manual(1, 1, 100_00)),
            quotes = emptyList(),
            fxRates = emptyList(),
        )

        PortfolioSeriesCalculator
            .currentAssetValuations(data, cny, today, zone, includeArchived = true)
            .shouldHaveSize(1)
    }
}
