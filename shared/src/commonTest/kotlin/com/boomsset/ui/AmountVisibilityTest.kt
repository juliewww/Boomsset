package com.boomsset.ui

import com.boomsset.domain.Money
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlin.test.Test

/**
 * The replacement rule when the eye icon hides the amount.
 *
 * The icon itself is drawn with Canvas and has no testable surface beyond its
 * accessibility node (see [AmountVisibilityToggle]), but **"what's actually left in the
 * string after hiding" is a pure function and can be locked down** — and that is exactly
 * the one place this feature could silently break: dropping a digit, or generating a
 * placeholder whose length depends on the digit count, would make the UI look hidden
 * while the magnitude still leaks through.
 */
class AmountVisibilityTest {

    @Test
    fun `returns the text unchanged when not hidden`() {
        maskAmount(hidden = false, text = "¥133,405.00") shouldBe "¥133,405.00"
    }

    @Test
    fun `hidden text keeps no digits at all`() {
        val masked = maskAmount(hidden = true, text = "¥133,405.00")
        ('0'..'9').forEach { digit -> masked shouldNotContain digit.toString() }
    }

    /**
     * **The placeholder length must be independent of the amount.** Generating it by
     * digit count (`"•".repeat(digits)`) would let a seven-digit and a four-digit amount
     * be told apart at a glance — that leaks "roughly how much money", which is exactly
     * what's supposed to be hidden.
     */
    @Test
    fun `placeholder length does not change with the amount's magnitude`() {
        val small = maskAmount(hidden = true, text = Money(1_00).formatWithCurrency("CNY"))
        val large = maskAmount(hidden = true, text = Money(1_234_567_89).formatWithCurrency("CNY"))
        small shouldBe large
    }

    /** The currency symbol and sign are stripped too — a sign would leak the direction (see the comment on [MASKED_AMOUNT]). */
    @Test
    fun `placeholder carries no currency symbol or sign`() {
        val negative = maskAmount(hidden = true, text = Money(-500_00).formatSigned("USD"))
        negative shouldBe MASKED_AMOUNT
        negative shouldNotContain "-"
        negative shouldNotContain "$"
    }
}
