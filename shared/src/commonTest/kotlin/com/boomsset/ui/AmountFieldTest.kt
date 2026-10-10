package com.boomsset.ui

import com.boomsset.domain.Money
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * The reading under an amount field. Its job is to expose a mistyped zero, so every case here is
 * about not hiding digits.
 */
class AmountFieldTest {

    private fun reading(yuan: Long) = Money(yuan * 100).magnitudeReading()?.text

    @Test
    fun `small amounts get no reading`() {
        reading(0).shouldBeNull()
        reading(999).shouldBeNull()
    }

    @Test
    fun `each order of magnitude gets its own tier word`() {
        reading(1_000) shouldBe "1千 · 千级"
        reading(7_500) shouldBe "7.5千 · 千级"
        reading(10_000) shouldBe "1万 · 万级"
        reading(100_000) shouldBe "10万 · 十万级"
        reading(1_000_000) shouldBe "100万 · 百万级"
        reading(10_000_000) shouldBe "1000万 · 千万级"
        reading(100_000_000) shouldBe "1亿 · 亿级"
        reading(1_000_000_000) shouldBe "10亿 · 十亿级"
    }

    /**
     * The case that motivated the feature: 721613 and 7216130 differ by one keystroke, and the
     * reading has to make that obvious.
     */
    @Test
    fun `a mistyped extra zero is visible in the reading`() {
        reading(721_613) shouldBe "72.1613万 · 十万级"
        reading(7_216_130) shouldBe "721.613万 · 百万级"
    }

    /** Digits below the unit are kept, never rounded — rounding could swallow the digit being checked. */
    @Test
    fun `digits below the unit are kept, with trailing zeros trimmed`() {
        reading(20_045) shouldBe "2.0045万 · 万级"
        reading(120_000) shouldBe "12万 · 十万级"
        reading(123_456_789) shouldBe "1.23456789亿 · 亿级"
    }

    @Test
    fun `cents do not change the reading`() {
        Money(721_613_99).magnitudeReading()?.text shouldBe "72.1613万 · 十万级"
    }

    @Test
    fun `a negative amount keeps its sign`() {
        reading(-721_613) shouldBe "-72.1613万 · 十万级"
    }

    @Test
    fun `the largest representable amounts do not overflow`() {
        // Long.MAX / 100 yuan ≈ 9.2e16 — must land in the top tier, not wrap or throw
        Money(Long.MAX_VALUE).magnitudeReading()?.tier shouldBe "万亿"
    }
}
