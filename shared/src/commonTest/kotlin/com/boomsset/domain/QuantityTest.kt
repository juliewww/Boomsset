package com.boomsset.domain

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.test.assertFailsWith

class QuantityTest {

    @Test
    fun `整数份额乘单价`() {
        // 100 股 × 15.00 元 = 1500.00 元
        Quantity.ofUnits(100).valueAt(UnitPrice.ofMajorUnits(15)) shouldBe Money(150_000)
    }

    @Test
    fun `小数份额乘单价并四舍五入`() {
        // 1.5 份 × 3.33 元 = 4.995 元 → 5.00 元（四舍五入到分）
        val oneAndHalf = Quantity(Quantity.ONE + Quantity.ONE / 2)
        oneAndHalf.valueAt(UnitPrice(3_33_000000L)) shouldBe Money(500)
    }

    @Test
    fun `比特币级别的小数精度不丢`() {
        // 0.00000001 BTC（1 satoshi）× 500000.00 元/BTC = 0.005 元 → 0.01 元
        Quantity(1).valueAt(UnitPrice.ofMajorUnits(500_000)) shouldBe Money(1)
    }

    @Test
    fun `大额不会静默溢出回绕`() {
        // 朴素写法 scaled * price 会超过 Long.MAX_VALUE 并回绕成负数。
        // 拆成整数部分和小数部分之后，这个量级是能正确算出来的。
        val oneMillionShares = Quantity.ofUnits(1_000_000)  // scaled = 1e14
        val price = UnitPrice.ofMajorUnits(1500)             // 1500 元
        oneMillionShares.valueAt(price) shouldBe Money(150_000_000_000L)
    }

    @Test
    fun `真的溢出时抛异常而不是回绕`() {
        assertFailsWith<ArithmeticException> {
            Quantity.ofUnits(Long.MAX_VALUE / Quantity.ONE).valueAt(UnitPrice(Long.MAX_VALUE / 2))
        }
    }

    @Test
    fun `低价代币不会被静默归零`() {
        // 单价 0.00001234 元，持有 100 万个 → 12.34 元。
        // 如果单价用 scale-2 的 Money 存，0.00001234 会变成 0.00，整项资产归零。
        val price = UnitPrice(1234)                    // 0.00001234
        Quantity.ofUnits(1_000_000).valueAt(price) shouldBe Money(12_34)
    }

    @Test
    fun `港股三位小数单价不丢精度`() {
        // 腾讯接口返回的港股价格是 3 位小数，如 462.400
        val price = UnitPrice(462_400_00000L)          // 462.40000
        Quantity.ofUnits(100).valueAt(price) shouldBe Money(46_240_00)
    }

    @Test
    fun `成本均价是总成本除以份额`() {
        // 总成本 105000 分（1050 元）/ 100 份 = 10.50 元/份
        val avg = Money(105_000).unitCostOver(Quantity.ofUnits(100))
        avg shouldBe Money(1050)
    }

    @Test
    fun `份额为零时没有均价可言`() {
        // 不是返回 0，也不是除零崩溃 —— 是"无均价"
        Money(105_000).unitCostOver(Quantity.ZERO).shouldBeNull()
    }
}
