package com.boomsset.domain

import kotlin.jvm.JvmInline

/**
 * An exchange rate, as a fixed-point integer, scale = 8. Also doesn't use Double — it
 * participates in net worth accumulation.
 */
@JvmInline
value class ExchangeRate(val scaled: Long) {

    /** Converts [amount] from the base currency to the quote currency. */
    fun convert(amount: Money): Money =
        Money(FixedPoint.multiply(amount.minorUnits, scaled, ONE))

    companion object {
        const val SCALE: Int = 8
        const val ONE: Long = 100_000_000L

        /** Same currency, 1:1. */
        val IDENTITY = ExchangeRate(ONE)
    }
}

/**
 * The exchange rate on a given day.
 *
 * **Converting a historical net worth value must use the rate from that day**, not
 * today's rate — otherwise exchange-rate fluctuations would pollute the historical
 * curve, showing the user swings they never actually experienced.
 */
data class FxRate(
    val base: String,
    val quote: String,
    val asOfDay: String,
    val rate: ExchangeRate,
)
