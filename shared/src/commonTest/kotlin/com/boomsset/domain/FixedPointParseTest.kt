package com.boomsset.domain

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * 三种定点值（金额 scale=2、份额 scale=8、汇率 scale=8）共用一个解析器。
 * 这些测试锁住共用之后各自的 scale 和符号规则仍然正确。
 */
class FixedPointParseTest {

    @Test
    fun `金额按两位小数`() {
        parseMoneyMinor("1234.56") shouldBe 123_456
        parseMoneyMinor("0.05") shouldBe 5
        parseMoneyMinor("100") shouldBe 10_000
    }

    @Test
    fun `金额允许负数用于负债`() {
        parseMoneyMinor("-1234.56") shouldBe -123_456
    }

    @Test
    fun `份额按八位小数且不允许负数`() {
        parseQuantity("1.5") shouldBe Quantity(150_000_000)
        parseQuantity("0.00000001") shouldBe Quantity(1)
        parseQuantity("-1").shouldBeNull()
    }

    @Test
    fun `汇率按八位小数且不允许负数`() {
        parseExchangeRate("6.7855") shouldBe ExchangeRate(678_550_000)
        parseExchangeRate("1") shouldBe ExchangeRate.IDENTITY
        parseExchangeRate("-6.78").shouldBeNull()
    }

    @Test
    fun `超出各自 scale 的小数位一律判非法`() {
        parseMoneyMinor("1.234").shouldBeNull()           // 金额只到分
        parseQuantity("0.123456789").shouldBeNull()       // 份额只到 1e-8
        parseExchangeRate("6.123456789").shouldBeNull()   // 汇率同上
    }

    @Test
    fun `千分位逗号一律拒绝`() {
        // 容忍它会让 "1,23" 变成 123，而用户可能想输 1.23 —— 100 倍的静默错误
        parseMoneyMinor("1,234").shouldBeNull()
        parseQuantity("1,234").shouldBeNull()
        parseExchangeRate("1,23").shouldBeNull()
    }

    @Test
    fun `溢出返回null不回绕`() {
        parseMoneyMinor("99999999999999999999").shouldBeNull()
        parseQuantity("99999999999999999999").shouldBeNull()
    }

    @Test
    fun `经典浮点陷阱`() {
        // "1.15" * 100 用 Double 是 114.99999999999999
        parseMoneyMinor("1.15") shouldBe 115
        // 7.12345678 在 Double 里也不精确
        parseExchangeRate("7.12345678") shouldBe ExchangeRate(712_345_678)
    }
}
