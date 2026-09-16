package com.boomsset.ui

import com.boomsset.domain.TargetAllocation
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class PercentInputTest {

    @Test
    fun `percent string to basis points`() {
        parsePercentToBp("30") shouldBe 3000
        parsePercentToBp("12.5") shouldBe 1250
        parsePercentToBp("0") shouldBe 0
        parsePercentToBp("100") shouldBe TargetAllocation.TOTAL_BP
        parsePercentToBp("0.01") shouldBe 1
    }

    @Test
    fun `out of the zero-to-one-hundred range is judged invalid`() {
        // A single asset class can never exceed 100%, nor be negative
        parsePercentToBp("101").shouldBeNull()
        parsePercentToBp("-5").shouldBeNull()
    }

    @Test
    fun `invalid input returns null`() {
        parsePercentToBp("").shouldBeNull()
        parsePercentToBp("abc").shouldBeNull()
        parsePercentToBp("30%").shouldBeNull()   // the % sign must be removed by the user, not guessed
        parsePercentToBp("12.345").shouldBeNull()  // more than two decimal places
    }

    @Test
    fun `basis-point prefill format carries no percent sign or extra zeros`() {
        3000.bpToInputPercent() shouldBe "30"
        1250.bpToInputPercent() shouldBe "12.5"
        0.bpToInputPercent() shouldBe "0"
        TargetAllocation.TOTAL_BP.bpToInputPercent() shouldBe "100"
        1.bpToInputPercent() shouldBe "0.01"
    }

    /**
     * Round-trip invariant. Same category as the amount/quantity ones —
     * **the prefilled string must be readable back to the original value by its own
     * parser**, otherwise "Save" ends up mysteriously disabled.
     */
    @Test
    fun `prefilled percent can be parsed back to the original value`() {
        val cases = listOf(0, 1, 50, 500, 1250, 3000, 3500, 6500, 10_000)
        cases.forEach { bp ->
            val text = bp.bpToInputPercent()
            val parsed = parsePercentToBp(text)
            if (parsed != bp) {
                throw AssertionError("basis points $bp prefilled as \"$text\", parsed back as $parsed, round trip failed")
            }
        }
    }

    @Test
    fun `every value in the built-in presets round-trips`() {
        // Verify directly against the real preset values, to avoid "the test numbers just happen to pass"
        com.boomsset.data.BUILT_IN_PRESETS.forEach { preset ->
            preset.targetsBp.forEach { (assetClass, bp) ->
                val text = bp.bpToInputPercent()
                if (parsePercentToBp(text) != bp) {
                    throw AssertionError("preset \"${preset.name}\" $assetClass = $bp round trip failed (\"$text\")")
                }
            }
        }
    }
}
