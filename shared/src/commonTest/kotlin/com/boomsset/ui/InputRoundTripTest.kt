package com.boomsset.ui

import com.boomsset.domain.Money
import com.boomsset.domain.Quantity
import com.boomsset.domain.UnitPrice
import com.boomsset.domain.parseUnitPrice
import com.boomsset.ui.assets.toQuantityOrNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * Round-trip invariant: **a string prefilled into an input field must be readable back
 * to the same value by our own parser.**
 *
 * This group of tests was added for a bug found during a real run. The update dialog
 * used to prefill with `formatAmount()`, which includes thousands separators, while
 * `toMinorUnitsOrNull()` explicitly rejects commas — the result was that "Save" stayed
 * permanently disabled on any asset opened for editing whose amount was ≥¥1000. It
 * compiled and all 72 unit tests were green, but the app's core loop was broken for
 * real amounts.
 *
 * Unit tests missed it because formatting and parsing each had their own tests,
 * **but nothing tested the seam between them.**
 */
class InputRoundTripTest {

    @Test
    fun `prefilled amount can be parsed back to the original value`() {
        val cases = listOf(
            0L,
            5L,            // 0.05
            100L,          // 1.00
            99_999L,       // 999.99
            100_000L,      // 1,000.00 ← this is exactly where it used to break
            123_456_78L,   // large amount
            999_999_999_99L,
        )
        cases.forEach { minor ->
            val money = Money(minor)
            val text = money.formatForInput()
            withClue(minor, text) { text.toMinorUnitsOrNull() shouldBe minor }
        }
    }

    @Test
    fun `negative amount also round-trips when prefilled`() {
        // A liability's owed amount
        val money = Money(-1_234_567L)
        money.formatForInput().toMinorUnitsOrNull() shouldBe -1_234_567L
    }

    @Test
    fun `display format has thousands separators but must not be used for prefilling`() {
        // Explicitly documents the difference between the two, to stop someone from
        // "helpfully" unifying them back together
        Money(100_000).formatAmount() shouldBe "1,000.00"
        Money(100_000).formatForInput() shouldBe "1000.00"
        Money(100_000).formatAmount().toMinorUnitsOrNull() shouldBe null  // comma rejected
    }

    @Test
    fun `prefilled quantity can be parsed back to the original value`() {
        val cases = listOf(
            0L,
            1L,                    // satoshi-level — Double.toString would give "1.0E-8"
            50_000_000L,           // 0.5
            Quantity.ONE,          // 1
            123_456_780_000L,      // 1234.5678
            100_000_000_000_000L,  // 1,000,000 units
        )
        cases.forEach { scaled ->
            val quantity = Quantity(scaled)
            val text = quantity.formatForInput()
            withClue(scaled, text) { text.toQuantityOrNull() shouldBe quantity }
        }
    }

    @Test
    fun `prefilled unit price can be parsed back to the original value`() {
        // Unit price uses scale=8, covering HK stocks' 3 decimal places and tokens' tiny values
        val cases = listOf(
            0L,
            1L,                        // 0.00000001, token-level
            46_240_0000_0L,            // 462.400, HK stock
            1300_00000000L,            // 1300
            133_405_000_000L,          // 1334.05, the Moutai price used in real testing
        )
        cases.forEach { scaled ->
            val price = UnitPrice(scaled)
            val text = price.formatForInput()
            withClue(scaled, text) { parseUnitPrice(text) shouldBe price }
        }
    }

    @Test
    fun `very small quantity does not turn into scientific notation`() {
        // The Double route would give "1.0E-8", which the parser can't read
        Quantity(1).formatForInput() shouldBe "0.00000001"
    }

    private inline fun withClue(vararg context: Any?, block: () -> Unit) {
        try {
            block()
        } catch (e: AssertionError) {
            throw AssertionError("round-trip failed for input ${context.joinToString(" → ")}: ${e.message}", e)
        }
    }
}
