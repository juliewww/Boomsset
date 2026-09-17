package com.boomsset.domain

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.test.assertFailsWith

class QuantityTest {

    @Test
    fun `integer share count times unit price`() {
        // 100 shares x 15.00 yuan = 1500.00 yuan
        Quantity.ofUnits(100).valueAt(UnitPrice.ofMajorUnits(15)) shouldBe Money(150_000)
    }

    @Test
    fun `fractional share count times unit price rounds`() {
        // 1.5 shares x 3.33 yuan = 4.995 yuan -> 5.00 yuan (rounded to the cent)
        val oneAndHalf = Quantity(Quantity.ONE + Quantity.ONE / 2)
        oneAndHalf.valueAt(UnitPrice(3_33_000000L)) shouldBe Money(500)
    }

    @Test
    fun `bitcoin-level fractional precision is not lost`() {
        // 0.00000001 BTC (1 satoshi) x 500000.00 yuan/BTC = 0.005 yuan -> 0.01 yuan
        Quantity(1).valueAt(UnitPrice.ofMajorUnits(500_000)) shouldBe Money(1)
    }

    @Test
    fun `large amounts do not silently overflow and wrap`() {
        // The naive scaled * price would exceed Long.MAX_VALUE and wrap around to negative.
        // Splitting into integer and fractional parts computes this magnitude correctly.
        val oneMillionShares = Quantity.ofUnits(1_000_000)  // scaled = 1e14
        val price = UnitPrice.ofMajorUnits(1500)             // 1500 yuan
        oneMillionShares.valueAt(price) shouldBe Money(150_000_000_000L)
    }

    @Test
    fun `a genuine overflow throws instead of wrapping`() {
        assertFailsWith<ArithmeticException> {
            Quantity.ofUnits(Long.MAX_VALUE / Quantity.ONE).valueAt(UnitPrice(Long.MAX_VALUE / 2))
        }
    }

    @Test
    fun `low-priced tokens are not silently zeroed out`() {
        // Unit price 0.00001234 yuan, holding 1,000,000 units -> 12.34 yuan.
        // If the unit price were stored as scale-2 Money, 0.00001234 would become 0.00,
        // zeroing out the entire position.
        val price = UnitPrice(1234)                    // 0.00001234
        Quantity.ofUnits(1_000_000).valueAt(price) shouldBe Money(12_34)
    }

    @Test
    fun `Hong Kong stock prices with 3 decimal places keep full precision`() {
        // The Tencent API returns HK stock prices with 3 decimal places, e.g. 462.400
        val price = UnitPrice(462_400_00000L)          // 462.40000
        Quantity.ofUnits(100).valueAt(price) shouldBe Money(46_240_00)
    }

    @Test
    fun `average cost is total cost divided by share count`() {
        // Total cost 105000 minor units (1050 yuan) / 100 shares = 10.50 yuan/share
        val avg = Money(105_000).unitCostOver(Quantity.ofUnits(100))
        avg shouldBe Money(1050)
    }

    @Test
    fun `zero shares means there is no average cost`() {
        // Not 0, and not a divide-by-zero crash -- it's "no average cost"
        Money(105_000).unitCostOver(Quantity.ZERO).shouldBeNull()
    }
}
