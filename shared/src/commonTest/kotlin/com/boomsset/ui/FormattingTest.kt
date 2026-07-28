package com.boomsset.ui

import com.boomsset.domain.Money
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class FormattingTest {

    @Test
    fun `金额千分位与两位小数`() {
        Money(123_456).formatAmount() shouldBe "1,234.56"
        Money(100).formatAmount() shouldBe "1.00"
        Money(5).formatAmount() shouldBe "0.05"
        Money(0).formatAmount() shouldBe "0.00"
    }

    @Test
    fun `负数金额`() {
        Money(-123_456).formatAmount() shouldBe "-1,234.56"
    }

    @Test
    fun `带币种符号`() {
        Money(123_456).formatWithCurrency("CNY") shouldBe "¥1,234.56"
        Money(123_456).formatWithCurrency("USD") shouldBe "$1,234.56"
    }

    @Test
    fun `基点转百分比`() {
        1234.bpToPercent() shouldBe "12.34%"
        10_000.bpToPercent() shouldBe "100.00%"
        (-500).bpToPercent() shouldBe "-5.00%"
        1000.bpToPercent(withSign = true) shouldBe "+10.00%"
        3000.bpToPercent(decimals = 0) shouldBe "30%"
    }

    // ---------- 输入解析：不经过 Double ----------

    @Test
    fun `元字符串转分`() {
        "1234.56".toMinorUnitsOrNull() shouldBe 123_456
        "100".toMinorUnitsOrNull() shouldBe 10_000
        "0.05".toMinorUnitsOrNull() shouldBe 5
        ".5".toMinorUnitsOrNull() shouldBe 50
        "-12.34".toMinorUnitsOrNull() shouldBe -1234
    }

    @Test
    fun `经典浮点陷阱不出错`() {
        // "0.07" * 100 用 Double 算是 7.000000000000001，转 Long 会截成 7 —— 恰好对了；
        // 但 "1.15" * 100 = 114.99999999999999，截断成 114，少一分钱。
        // 手工解析没有这个问题。
        "1.15".toMinorUnitsOrNull() shouldBe 115
        "0.07".toMinorUnitsOrNull() shouldBe 7
        "8.20".toMinorUnitsOrNull() shouldBe 820
    }

    @Test
    fun `非法输入返回null而不是猜`() {
        "abc".toMinorUnitsOrNull().shouldBeNull()
        "".toMinorUnitsOrNull().shouldBeNull()
        "1.2.3".toMinorUnitsOrNull().shouldBeNull()
        // 超过两位小数是非法输入，不静默截断 —— 用户以为记了 1.234 元
        "1.234".toMinorUnitsOrNull().shouldBeNull()
        "1,234".toMinorUnitsOrNull().shouldBeNull()
    }

    @Test
    fun `超大金额不静默溢出`() {
        "99999999999999999999".toMinorUnitsOrNull().shouldBeNull()
    }

    @Test
    fun `大额缩写用万和亿`() {
        Money(123_456_78).formatCompact("CNY") shouldBe "¥12.3万"
        Money(1_234_567_800_00).formatCompact("CNY") shouldBe "¥12.3亿"
        Money(500_00).formatCompact("CNY") shouldBe "¥500"
    }
}
