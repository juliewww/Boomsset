package com.boomsset.ui

import com.boomsset.domain.Money
import com.boomsset.domain.Period
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import kotlin.test.Test

class FormattingTest {

    @Test
    fun `amount uses thousands separators and two decimal places`() {
        Money(123_456).formatAmount() shouldBe "1,234.56"
        Money(100).formatAmount() shouldBe "1.00"
        Money(5).formatAmount() shouldBe "0.05"
        Money(0).formatAmount() shouldBe "0.00"
    }

    @Test
    fun `negative amount`() {
        Money(-123_456).formatAmount() shouldBe "-1,234.56"
    }

    @Test
    fun `with currency symbol`() {
        Money(123_456).formatWithCurrency("CNY") shouldBe "¥1,234.56"
        Money(123_456).formatWithCurrency("USD") shouldBe "$1,234.56"
        // The minus sign sits outside the currency symbol, not "¥-1,234.56"
        Money(-123_456).formatWithCurrency("CNY") shouldBe "-¥1,234.56"
    }

    @Test
    fun `signed change amount has an explicit sign, but zero does not`() {
        // The "net worth growth" on the top card is a change amount; without a plus sign
        // on positives, the direction can't be read at a glance
        Money(56_400).formatSigned("CNY") shouldBe "+¥564.00"
        Money(-56_400).formatSigned("CNY") shouldBe "-¥564.00"
        // "+¥0.00" reads as "grew by 0", when the fact is there was no change at all
        Money.ZERO.formatSigned("CNY") shouldBe "¥0.00"
        // The rebalance amount ("distance to target") on the allocation page is a planning-level
        // magnitude; two decimal places would be false precision
        Money(-31_250_000).formatSigned("CNY", showDecimals = false) shouldBe "-¥312,500"
        Money(25_000_000).formatSigned("USD", showDecimals = false) shouldBe "+$250,000"
    }

    @Test
    fun `date and period labels`() {
        LocalDate(2026, 8, 4).monthDayLabel() shouldBe "8月4日"
        // A period label always carries the year: looking back up to 12 months by month,
        // "September" alone would be ambiguous once it spans a year boundary
        LocalDate(2025, 9, 30).periodLabel(Period.MONTH) shouldBe "2025年9月"
        LocalDate(2025, 9, 30).periodLabel(Period.QUARTER) shouldBe "2025年Q3"
        LocalDate(2025, 12, 31).periodLabel(Period.QUARTER) shouldBe "2025年Q4"
        LocalDate(2025, 12, 31).periodLabel(Period.YEAR) shouldBe "2025年"
    }

    @Test
    fun `freshness description of the most recent record`() {
        val day = LocalDate(2026, 8, 4)
        lastRecordDescription(day, 0) shouldBe "最近记录 8月4日 · 今天"
        lastRecordDescription(day, 1) shouldBe "最近记录 8月4日 · 昨天"
        lastRecordDescription(day, 12) shouldBe "最近记录 8月4日 · 12 天前"
        // No snapshot at all → this line should not be shown, instead of showing a
        // "never recorded" line that just takes up space
        lastRecordDescription(null, null).shouldBeNull()
    }

    @Test
    fun `basis points to percent`() {
        1234.bpToPercent() shouldBe "12.34%"
        10_000.bpToPercent() shouldBe "100.00%"
        (-500).bpToPercent() shouldBe "-5.00%"
        1000.bpToPercent(withSign = true) shouldBe "+10.00%"
        3000.bpToPercent(decimals = 0) shouldBe "30%"
    }

    @Test
    fun `percent for a change amount does not sign zero`() {
        1000.bpToSignedPercent() shouldBe "+10.00%"
        (-500).bpToSignedPercent() shouldBe "-5.00%"
        0.bpToSignedPercent() shouldBe "0.00%"
    }

    // ---------- Input parsing: never goes through Double ----------

    @Test
    fun `yuan string to minor units (cents)`() {
        "1234.56".toMinorUnitsOrNull() shouldBe 123_456
        "100".toMinorUnitsOrNull() shouldBe 10_000
        "0.05".toMinorUnitsOrNull() shouldBe 5
        ".5".toMinorUnitsOrNull() shouldBe 50
        "-12.34".toMinorUnitsOrNull() shouldBe -1234
    }

    @Test
    fun `classic floating-point pitfalls do not cause errors`() {
        // "0.07" * 100 computed as a Double is 7.000000000000001, which truncates to Long
        // as 7 — happens to work out; but "1.15" * 100 = 114.99999999999999, which
        // truncates to 114, a cent short. Manual parsing doesn't have this problem.
        "1.15".toMinorUnitsOrNull() shouldBe 115
        "0.07".toMinorUnitsOrNull() shouldBe 7
        "8.20".toMinorUnitsOrNull() shouldBe 820
    }

    @Test
    fun `invalid input returns null instead of guessing`() {
        "abc".toMinorUnitsOrNull().shouldBeNull()
        "".toMinorUnitsOrNull().shouldBeNull()
        "1.2.3".toMinorUnitsOrNull().shouldBeNull()
        // More than two decimal places is invalid input, not silently truncated —
        // otherwise the user would think they recorded 1.234 yuan
        "1.234".toMinorUnitsOrNull().shouldBeNull()
        "1,234".toMinorUnitsOrNull().shouldBeNull()
    }

    @Test
    fun `very large amount does not silently overflow`() {
        "99999999999999999999".toMinorUnitsOrNull().shouldBeNull()
    }

    @Test
    fun `large amounts are abbreviated with wan and yi`() {
        Money(123_456_78).formatCompact("CNY") shouldBe "¥12.3万"
        Money(1_234_567_800_00).formatCompact("CNY") shouldBe "¥12.3亿"
        Money(500_00).formatCompact("CNY") shouldBe "¥500"
    }
}
