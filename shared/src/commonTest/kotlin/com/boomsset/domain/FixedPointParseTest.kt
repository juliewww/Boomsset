package com.boomsset.domain

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * Three kinds of fixed-point values (amounts scale=2, quantities scale=8, exchange rates
 * scale=8) share a single parser. These tests lock in that each one's scale and sign rules
 * still hold correctly after being unified.
 */
class FixedPointParseTest {

    @Test
    fun `amounts use two decimal places`() {
        parseMoneyMinor("1234.56") shouldBe 123_456
        parseMoneyMinor("0.05") shouldBe 5
        parseMoneyMinor("100") shouldBe 10_000
    }

    @Test
    fun `amounts allow negative values for liabilities`() {
        parseMoneyMinor("-1234.56") shouldBe -123_456
    }

    @Test
    fun `quantities use eight decimal places and disallow negative values`() {
        parseQuantity("1.5") shouldBe Quantity(150_000_000)
        parseQuantity("0.00000001") shouldBe Quantity(1)
        parseQuantity("-1").shouldBeNull()
    }

    @Test
    fun `exchange rates use eight decimal places and disallow negative values`() {
        parseExchangeRate("6.7855") shouldBe ExchangeRate(678_550_000)
        parseExchangeRate("1") shouldBe ExchangeRate.IDENTITY
        parseExchangeRate("-6.78").shouldBeNull()
    }

    @Test
    fun `decimal places beyond each type's scale are always rejected`() {
        parseMoneyMinor("1.234").shouldBeNull()           // amounts only go to the cent
        parseQuantity("0.123456789").shouldBeNull()       // quantities only go to 1e-8
        parseExchangeRate("6.123456789").shouldBeNull()   // same for exchange rates
    }

    @Test
    fun `thousands-separator commas are always rejected`() {
        // Tolerating it would turn "1,23" into 123, when the user probably meant 1.23 --
        // a silent 100x error
        parseMoneyMinor("1,234").shouldBeNull()
        parseQuantity("1,234").shouldBeNull()
        parseExchangeRate("1,23").shouldBeNull()
    }

    @Test
    fun `overflow returns null instead of wrapping`() {
        parseMoneyMinor("99999999999999999999").shouldBeNull()
        parseQuantity("99999999999999999999").shouldBeNull()
    }

    @Test
    fun `classic floating point traps`() {
        // "1.15" * 100 as a Double is 114.99999999999999
        parseMoneyMinor("1.15") shouldBe 115
        // 7.12345678 is likewise imprecise as a Double
        parseExchangeRate("7.12345678") shouldBe ExchangeRate(712_345_678)
    }
}
