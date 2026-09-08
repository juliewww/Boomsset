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
    fun `没有快照时是空列表`() {
        UpdateHistory.build(PortfolioData.EMPTY, zone).shouldHaveSize(0)
    }

    @Test
    fun `最新的记录排在最前面`() {
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
    fun `第一条快照是新增，之后的是更新`() {
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
    fun `归档追加的那条归零快照标成归档`() {
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
    fun `取消归档后那条退回显示成普通更新`() {
        // unarchiveAsset 只清 archivedAt，归零快照留着 —— 归档确实被撤销了，
        // 那条记录就不该再自称「归档」。
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
    fun `MANUAL 的前值取链上紧邻的一条`() {
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
    fun `QUOTED 记的是份额，不推算市值`() {
        // 快照里根本没有市值字段；市值要靠当时的行情，而行情还没有历史回补。
        // 这条锁住「不去猜一个历史市值出来」。
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
    fun `估值方式变了就没有可比的前值`() {
        // 退市转 MANUAL：上一条是份额、这一条是市值，减不出变化量。
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
    fun `份额没动但成本动了也算一次有内容的更新`() {
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
    fun `没填成本时成本变化是 null 而不是零`() {
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
    fun `多个资产的记录按时间合并成一条流水`() {
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
    fun `已归档资产的记录仍然出现在流水里`() {
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
    fun `不做任何保留期截断，几年前的记录照样在`() {
        // 这条锁的是一个**产品决定**，不是实现细节：快照是净值曲线的唯一数据源，
        // 按「只留半年」去删会让一项半年没更新过的资产连今天都取不到快照，
        // 从净值里整个消失。分页只能做在 UI 侧。
        val old = (0 until 40).map { i ->
            manual(id = i + 1L, assetId = 1, on = at(2018 + i / 12, i % 12 + 1, 1), value = 100_00L + i)
        }
        val records = UpdateHistory.build(data(listOf(asset(1)), old), zone)

        records shouldHaveSize 40
        records.last().recordedDate shouldBe LocalDate(2018, 1, 1)
    }

    @Test
    fun `记录日期按传入的时区落地`() {
        val newYearEveInShanghai = LocalDateTime(2026, 1, 1, 3, 0).toInstant(TimeZone.UTC)
        val records = UpdateHistory.build(
            data(listOf(asset(1)), listOf(manual(1, 1, newYearEveInShanghai, 100_00))),
            TimeZone.of("Asia/Shanghai"),
        )

        // UTC 的 1 月 1 日 03:00 在东八区是 11:00 —— 同一天；换成 UTC-8 才会退回去年。
        records.single().recordedDate shouldBe LocalDate(2026, 1, 1)

        val utcMinus8 = UpdateHistory.build(
            data(listOf(asset(1)), listOf(manual(1, 1, newYearEveInShanghai, 100_00))),
            TimeZone.of("America/Los_Angeles"),
        )
        utcMinus8.single().recordedDate shouldBe LocalDate(2025, 12, 31)
    }
}
