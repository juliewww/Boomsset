package com.boomsset.ui.assets

import com.boomsset.domain.Asset
import com.boomsset.domain.AssetClass
import com.boomsset.domain.Money
import com.boomsset.domain.Quantity
import com.boomsset.domain.Snapshot
import com.boomsset.domain.UpdateKind
import com.boomsset.domain.UpdateRecord
import com.boomsset.domain.ValuationMode
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.time.Instant

/** The "+¥10,000.00 · +10.00%" line under a history row's `X → Y`. */
class UpdateHistoryChangeTextTest {

    private val t = Instant.fromEpochMilliseconds(0)
    private val asset = Asset(
        id = 1, name = "a", assetClass = AssetClass.LIQUID, subtypeId = 1, currency = "CNY",
        defaultValuationMode = ValuationMode.MANUAL,
    )

    private fun manual(id: Long, yuan: Long) =
        Snapshot.Manual(id = id, assetId = 1, asOf = t, value = Money(yuan * 100), recordedAt = t)

    private fun quoted(id: Long, units: Long) = Snapshot.Quoted(
        id = id, assetId = 1, asOf = t, quantity = Quantity.ofUnits(units),
        quoteSymbol = "sh600519", recordedAt = t,
    )

    private fun record(snapshot: Snapshot, previous: Snapshot?) = UpdateRecord(
        asset = asset, snapshot = snapshot, previous = previous,
        kind = if (previous == null) UpdateKind.CREATED else UpdateKind.UPDATED,
        recordedDate = LocalDate(2026, 10, 9),
    )

    @Test
    fun `an increase shows a plus sign on both the amount and the rate`() {
        record(manual(2, 110_000), manual(1, 100_000)).changeSummaryText("CNY") shouldBe
            "+¥10,000.00 · +10.00%"
    }

    @Test
    fun `a decrease shows a minus sign on both`() {
        record(manual(2, 95_000), manual(1, 100_000)).changeSummaryText("CNY") shouldBe
            "-¥5,000.00 · -5.00%"
    }

    @Test
    fun `no change says so instead of printing zeros`() {
        record(manual(2, 100_000), manual(1, 100_000)).changeSummaryText("CNY") shouldBe "没有变化"
    }

    @Test
    fun `the amount survives when the rate can't be computed`() {
        record(manual(2, 50_000), manual(1, 0)).changeSummaryText("CNY") shouldBe "+¥50,000.00"
    }

    @Test
    fun `a quoted record describes the change in shares`() {
        record(quoted(2, 150), quoted(1, 100)).changeSummaryText("CNY") shouldBe "份额 +50 · +50.00%"
        record(quoted(2, 60), quoted(1, 100)).changeSummaryText("CNY") shouldBe "份额 -40 · -40.00%"
    }

    @Test
    fun `the first record and a mode switch have nothing to compare`() {
        record(manual(1, 100_000), null).changeSummaryText("CNY").shouldBeNull()
        record(manual(2, 100_000), quoted(1, 100)).changeSummaryText("CNY").shouldBeNull()
    }
}
