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
    fun `archived assets are excluded by default`() {
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
    fun `an asset with no snapshot is flagged rather than treated as zero`() {
        val data = PortfolioData(
            assets = listOf(asset(1)),
            snapshots = emptyList(),
            quotes = emptyList(),
            fxRates = emptyList(),
        )

        val row = PortfolioSeriesCalculator.currentAssetValuations(data, cny, today, zone).single()
        row.hasNoSnapshot shouldBe true
        row.baseValue.shouldBeNull()
        // hasNoSnapshot and isUnpriced are two different things: the former means it was
        // never recorded, the latter means it was recorded but can't be valued
        row.isUnpriced shouldBe false
    }

    @Test
    fun `a foreign currency asset missing an exchange rate is flagged as unpriced`() {
        val data = PortfolioData(
            assets = listOf(asset(1, currency = "USD")),
            snapshots = listOf(manual(1, 1, 100_00)),
            quotes = emptyList(),
            fxRates = emptyList(),
        )

        val row = PortfolioSeriesCalculator.currentAssetValuations(data, cny, today, zone).single()
        row.isUnpriced shouldBe true
        row.localValue shouldBe Money(100_00)   // known in its own currency
        row.baseValue.shouldBeNull()            // but can't be converted
    }

    @Test
    fun `an asset with a cost basis carries a gain or loss`() {
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
    fun `a QUOTED asset's average cost is a derived value`() {
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
                    costBasisMinor = Money(105_000),  // total cost 1050 yuan
                    recordedAt = epoch,
                ),
            ),
            quotes = listOf(Quote("X", "2026-07-01", UnitPrice.ofMajorUnits(15), cny, epoch)),
            fxRates = emptyList(),
        )

        val row = PortfolioSeriesCalculator.currentAssetValuations(data, cny, today, zone).single()
        // 1050 yuan / 100 shares = 10.50 yuan/share
        row.unitCost shouldBe Money(1050)
        row.baseValue shouldBe Money(150_000)  // 100 x 15.00 yuan
    }

    @Test
    fun `MANUAL assets have no concept of an average cost`() {
        val data = PortfolioData(
            assets = listOf(asset(1)),
            snapshots = listOf(manual(1, 1, 100_00, cost = 90_00)),
            quotes = emptyList(),
            fxRates = emptyList(),
        )

        // A house has no "shares", so average cost is meaningless
        PortfolioSeriesCalculator.currentAssetValuations(data, cny, today, zone)
            .single().unitCost.shouldBeNull()
    }

    @Test
    fun `an archived asset can be explicitly included`() {
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
