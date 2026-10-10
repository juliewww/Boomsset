package com.boomsset.domain

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.time.Instant

class UpdateHistoryTest {

    private val zone = TimeZone.UTC

    private fun at(year: Int, month: Int, day: Int): Instant =
        LocalDateTime(year, month, day, 12, 0).toInstant(zone)

    private fun asset(
        id: Long,
        name: String = "asset-$id",
        cls: AssetClass = AssetClass.LIQUID,
        currency: String = "CNY",
        archivedAt: Instant? = null,
    ) = Asset(
        id = id,
        name = name,
        assetClass = cls,
        subtypeId = 1,
        currency = currency,
        defaultValuationMode = ValuationMode.MANUAL,
        archivedAt = archivedAt,
    )

    private fun manual(id: Long, assetId: Long, on: Instant, value: Long, cost: Long? = null) =
        Snapshot.Manual(
            id = id,
            assetId = assetId,
            asOf = on,
            value = Money(value),
            costBasisMinor = cost?.let { Money(it) },
            recordedAt = on,
        )

    private fun quoted(
        id: Long,
        assetId: Long,
        on: Instant,
        units: Long,
        cost: Long? = null,
        symbol: String = "sh600519",
    ) = Snapshot.Quoted(
        id = id,
        assetId = assetId,
        asOf = on,
        quantity = Quantity.ofUnits(units),
        quoteSymbol = symbol,
        costBasisMinor = cost?.let { Money(it) },
        recordedAt = on,
    )

    private fun data(assets: List<Asset>, snapshots: List<Snapshot>) =
        PortfolioData(assets, snapshots, emptyList(), emptyList())

    @Test
    fun `an empty list when there are no snapshots`() {
        UpdateHistory.build(PortfolioData.EMPTY, zone).shouldHaveSize(0)
    }

    @Test
    fun `the newest record comes first`() {
        val records = UpdateHistory.build(
            data(
                assets = listOf(asset(1)),
                snapshots = listOf(
                    manual(1, 1, at(2026, 1, 1), 100_00),
                    manual(2, 1, at(2026, 3, 1), 300_00),
                    manual(3, 1, at(2026, 2, 1), 200_00),
                ),
            ),
            zone,
        )

        records.map { it.snapshot.id } shouldBe listOf(2L, 3L, 1L)
    }

    @Test
    fun `the first snapshot is a creation, later ones are updates`() {
        val records = UpdateHistory.build(
            data(
                assets = listOf(asset(1)),
                snapshots = listOf(
                    manual(1, 1, at(2026, 1, 1), 100_00),
                    manual(2, 1, at(2026, 2, 1), 200_00),
                ),
            ),
            zone,
        )

        records.map { it.kind } shouldBe listOf(UpdateKind.UPDATED, UpdateKind.CREATED)
    }

    @Test
    fun `the zeroing snapshot appended by archiving is labeled ARCHIVED`() {
        val archivedAt = at(2026, 3, 1)
        val records = UpdateHistory.build(
            data(
                assets = listOf(asset(1, archivedAt = archivedAt)),
                snapshots = listOf(
                    manual(1, 1, at(2026, 1, 1), 100_00),
                    manual(2, 1, archivedAt, 0),
                ),
            ),
            zone,
        )

        records.first().kind shouldBe UpdateKind.ARCHIVED
        records.first().previousValue shouldBe Money(100_00)
        records.first().value shouldBe Money.ZERO
    }

    @Test
    fun `after unarchiving, that record reverts to showing as a plain update`() {
        // unarchiveAsset only clears archivedAt; the zeroing snapshot stays -- since the
        // archiving was genuinely undone, that record should no longer call itself "archived".
        val records = UpdateHistory.build(
            data(
                assets = listOf(asset(1, archivedAt = null)),
                snapshots = listOf(
                    manual(1, 1, at(2026, 1, 1), 100_00),
                    manual(2, 1, at(2026, 3, 1), 0),
                ),
            ),
            zone,
        )

        records.first().kind shouldBe UpdateKind.UPDATED
    }

    @Test
    fun `a MANUAL record's previous value is the immediately preceding one in the chain`() {
        val records = UpdateHistory.build(
            data(
                assets = listOf(asset(1)),
                snapshots = listOf(
                    manual(1, 1, at(2026, 1, 1), 100_00),
                    manual(2, 1, at(2026, 2, 1), 250_00),
                    manual(3, 1, at(2026, 3, 1), 220_00),
                ),
            ),
            zone,
        )

        val latest = records.first()
        latest.previousValue shouldBe Money(250_00)
        latest.valueChange shouldBe Money(-30_00)
        latest.hasChange.shouldBeTrue()

        val oldest = records.last()
        oldest.previousValue.shouldBeNull()
        oldest.valueChange.shouldBeNull()
        oldest.hasChange.shouldBeFalse()
    }

    @Test
    fun `QUOTED records the share count, it does not infer a market value`() {
        // Snapshots have no market-value field at all; market value depends on the quote at
        // that time, and quotes don't have historical backfill yet.
        // This locks in "don't guess at a historical market value".
        val records = UpdateHistory.build(
            data(
                assets = listOf(asset(1)),
                snapshots = listOf(
                    quoted(1, 1, at(2026, 1, 1), units = 100),
                    quoted(2, 1, at(2026, 2, 1), units = 150),
                ),
            ),
            zone,
        )

        val latest = records.first()
        latest.value.shouldBeNull()
        latest.previousValue.shouldBeNull()
        latest.quantity shouldBe Quantity.ofUnits(150)
        latest.previousQuantity shouldBe Quantity.ofUnits(100)
        latest.quantityChange shouldBe Quantity.ofUnits(50)
    }

    @Test
    fun `once the valuation mode changes, there is no comparable previous value`() {
        // Delisted, converted to MANUAL: the previous record is a share count, this one is a market value -- there's no change amount to subtract out.
        val records = UpdateHistory.build(
            data(
                assets = listOf(asset(1)),
                snapshots = listOf(
                    quoted(1, 1, at(2026, 1, 1), units = 100),
                    manual(2, 1, at(2026, 2, 1), 12_000_00),
                ),
            ),
            zone,
        )

        val latest = records.first()
        latest.modeChanged.shouldBeTrue()
        latest.valueChange.shouldBeNull()
        latest.quantityChange.shouldBeNull()
        latest.hasChange.shouldBeFalse()
        latest.value shouldBe Money(12_000_00)
    }

    @Test
    fun `an unchanged share count but a changed cost still counts as a meaningful update`() {
        val records = UpdateHistory.build(
            data(
                assets = listOf(asset(1)),
                snapshots = listOf(
                    quoted(1, 1, at(2026, 1, 1), units = 100, cost = 120_000_00),
                    quoted(2, 1, at(2026, 2, 1), units = 100, cost = 130_000_00),
                ),
            ),
            zone,
        )

        val latest = records.first()
        latest.quantityChange shouldBe Quantity.ZERO
        latest.costChange shouldBe Money(10_000_00)
    }

    @Test
    fun `when no cost is filled in, the cost change is null, not zero`() {
        val records = UpdateHistory.build(
            data(
                assets = listOf(asset(1)),
                snapshots = listOf(
                    manual(1, 1, at(2026, 1, 1), 100_00),
                    manual(2, 1, at(2026, 2, 1), 200_00),
                ),
            ),
            zone,
        )

        records.first().costChange.shouldBeNull()
    }

    @Test
    fun `records from multiple assets are merged into a single timeline by time`() {
        val records = UpdateHistory.build(
            data(
                assets = listOf(asset(1, name = "活期"), asset(2, name = "茅台")),
                snapshots = listOf(
                    manual(1, 1, at(2026, 1, 1), 100_00),
                    quoted(2, 2, at(2026, 1, 15), units = 100),
                    manual(3, 1, at(2026, 2, 1), 200_00),
                ),
            ),
            zone,
        )

        records.map { it.asset.name } shouldBe listOf("活期", "茅台", "活期")
        records.map { it.snapshot.id } shouldBe listOf(3L, 2L, 1L)
    }

    @Test
    fun `an archived asset's records still appear in the timeline`() {
        val archivedAt = at(2026, 3, 1)
        val records = UpdateHistory.build(
            data(
                assets = listOf(asset(1, archivedAt = archivedAt)),
                snapshots = listOf(
                    manual(1, 1, at(2026, 1, 1), 100_00),
                    manual(2, 1, archivedAt, 0),
                ),
            ),
            zone,
        )

        records shouldHaveSize 2
    }

    @Test
    fun `no retention-period truncation is applied - records from years ago are still there`() {
        // This locks in a **product decision**, not an implementation detail: snapshots are
        // the sole data source for the net worth curve. Deleting anything older than "the last
        // six months" would make an asset that hasn't been updated in six months fail to
        // resolve a snapshot even for today, vanishing from net worth entirely. Pagination can
        // only be done on the UI side.
        val old = (0 until 40).map { i ->
            manual(id = i + 1L, assetId = 1, on = at(2018 + i / 12, i % 12 + 1, 1), value = 100_00L + i)
        }
        val records = UpdateHistory.build(data(listOf(asset(1)), old), zone)

        records shouldHaveSize 40
        records.last().recordedDate shouldBe LocalDate(2018, 1, 1)
    }

    @Test
    fun `the recorded date lands according to the given time zone`() {
        val newYearEveInShanghai = LocalDateTime(2026, 1, 1, 3, 0).toInstant(TimeZone.UTC)
        val records = UpdateHistory.build(
            data(listOf(asset(1)), listOf(manual(1, 1, newYearEveInShanghai, 100_00))),
            TimeZone.of("Asia/Shanghai"),
        )

        // UTC January 1st 03:00 is 11:00 in UTC+8 -- same day; only switching to UTC-8 rolls it back to the previous year.
        records.single().recordedDate shouldBe LocalDate(2026, 1, 1)

        val utcMinus8 = UpdateHistory.build(
            data(listOf(asset(1)), listOf(manual(1, 1, newYearEveInShanghai, 100_00))),
            TimeZone.of("America/Los_Angeles"),
        )
        utcMinus8.single().recordedDate shouldBe LocalDate(2025, 12, 31)
    }

    // ---------- Change rate ----------

    @Test
    fun `a manual update carries both the amount and the rate of change`() {
        val record = UpdateHistory.build(
            data(
                listOf(asset(1)),
                listOf(
                    manual(1, 1, at(2026, 8, 1), 100_000_00),
                    manual(2, 1, at(2026, 9, 1), 113_000_00),
                ),
            ),
            zone,
        ).first()

        record.valueChange shouldBe Money(13_000_00)
        record.valueChangeBp shouldBe 1300
    }

    @Test
    fun `the rate is null when the previous value is zero, while the amount is still there`() {
        // 0 -> 50,000: "+50,000" is true and useful, "+infinity%" is not. Same rule as the net worth card.
        val record = UpdateHistory.build(
            data(
                listOf(asset(1)),
                listOf(
                    manual(1, 1, at(2026, 8, 1), 0),
                    manual(2, 1, at(2026, 9, 1), 50_000_00),
                ),
            ),
            zone,
        ).first()

        record.valueChange shouldBe Money(50_000_00)
        record.valueChangeBp.shouldBeNull()
    }

    @Test
    fun `a decrease is negative`() {
        val record = UpdateHistory.build(
            data(
                listOf(asset(1)),
                listOf(
                    manual(1, 1, at(2026, 8, 1), 200_000_00),
                    manual(2, 1, at(2026, 9, 1), 150_000_00),
                ),
            ),
            zone,
        ).first()

        record.valueChange shouldBe Money(-50_000_00)
        record.valueChangeBp shouldBe -2500
    }

    @Test
    fun `a quoted update reports the change in quantity, not in market value`() {
        // A QUOTED snapshot stores no market value and this layer derives none, so the rate is of
        // the quantity: 100 -> 150 shares is +50%.
        val record = UpdateHistory.build(
            data(
                listOf(asset(1)),
                listOf(
                    quoted(1, 1, at(2026, 8, 1), 100),
                    quoted(2, 1, at(2026, 9, 1), 150),
                ),
            ),
            zone,
        ).first()

        record.valueChange.shouldBeNull()
        record.valueChangeBp.shouldBeNull()
        record.quantityChangeBp shouldBe 5000
    }

    @Test
    fun `the first record has nothing to compare against`() {
        val record = UpdateHistory.build(
            data(listOf(asset(1)), listOf(manual(1, 1, at(2026, 8, 1), 100_000_00))),
            zone,
        ).single()

        record.valueChangeBp.shouldBeNull()
        record.quantityChangeBp.shouldBeNull()
    }
}
