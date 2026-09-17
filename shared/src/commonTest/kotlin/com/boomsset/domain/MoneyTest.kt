package com.boomsset.domain

import io.kotest.matchers.shouldBe
import kotlin.test.Test

class MoneyTest {

    @Test
    fun `addition does not introduce floating point error`() {
        // Summing this sequence with Double accumulation would yield 0.9999999999999999
        val tenCents = List(10) { Money(10) }
        tenCents.sum() shouldBe Money(100)
    }

    @Test
    fun `a liability is represented as a negative number and nets out correctly`() {
        val asset = Money(500_00)
        val liability = Money(120_00)
        (asset - liability) shouldBe Money(380_00)
    }

    @Test
    fun `is comparable`() {
        (Money(1) > Money.ZERO) shouldBe true
        Money.ZERO.isZero shouldBe true
    }
}
