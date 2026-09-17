package com.boomsset.ui.assets

import com.boomsset.domain.Money
import com.boomsset.domain.Quantity
import com.boomsset.domain.UnitPrice
import com.boomsset.domain.valueAt
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class QuantityParsingTest {

    @Test
    fun `whole-number quantity`() {
        "100".toQuantityOrNull() shouldBe Quantity.ofUnits(100)
        "1".toQuantityOrNull() shouldBe Quantity.ofUnits(1)
    }

    @Test
    fun `fractional quantity is stored fixed-point at scale 8`() {
        "1.5".toQuantityOrNull() shouldBe Quantity(150_000_000)
        "0.12345678".toQuantityOrNull() shouldBe Quantity(12_345_678)
        ".5".toQuantityOrNull() shouldBe Quantity(50_000_000)
    }

    @Test
    fun `parsing never goes through Double, so there is no floating-point error`() {
        // Problems like 0.1 + 0.2 don't exist here, because it's integers all the way through
        "0.1".toQuantityOrNull() shouldBe Quantity(10_000_000)
        "1234.5678".toQuantityOrNull() shouldBe Quantity(123_456_780_000)
    }

    @Test
    fun `more than eight decimal places is judged invalid rather than silently truncated`() {
        // Silent truncation would make the user think a more precise quantity was recorded
        "0.123456789".toQuantityOrNull().shouldBeNull()
    }

    @Test
    fun `quantity cannot be negative`() {
        "-1".toQuantityOrNull().shouldBeNull()
    }

    @Test
    fun `invalid input returns null`() {
        "".toQuantityOrNull().shouldBeNull()
        "abc".toQuantityOrNull().shouldBeNull()
        "1.2.3".toQuantityOrNull().shouldBeNull()
    }

    @Test
    fun `very large quantity does not silently overflow`() {
        "99999999999999999999".toQuantityOrNull().shouldBeNull()
    }

    @Test
    fun `a parsed quantity can be used directly in a market-value calculation`() {
        // End to end: input "10.5" units at a unit price of 20 yuan → 210 yuan
        val q = "10.5".toQuantityOrNull()!!
        q.valueAt(UnitPrice.ofMajorUnits(20)) shouldBe Money(210_00)
    }
}
