package com.boomsset.domain

import kotlin.jvm.JvmInline

/**
 * Quantity held, represented as a fixed-point integer, scale = 8.
 *
 * Scale 8 is chosen to cover crypto's smallest unit (a BTC satoshi is 1e-8); fund shares
 * usually have 4 decimal places and stocks are whole numbers, both well within range.
 *
 * **Doesn't use Double** — quantity participates in "quantity × unit price" to compute
 * market value, and floating-point error would leak into net worth.
 */
@JvmInline
value class Quantity(val scaled: Long) : Comparable<Quantity> {

    operator fun plus(other: Quantity) = Quantity(scaled + other.scaled)
    operator fun minus(other: Quantity) = Quantity(scaled - other.scaled)

    override fun compareTo(other: Quantity): Int = scaled.compareTo(other.scaled)

    val isZero: Boolean get() = scaled == 0L

    /** Display only. Loses precision — **never** use the return value for money calculations. */
    fun toDisplayDouble(): Double = scaled.toDouble() / ONE

    companion object {
        const val SCALE: Int = 8
        const val ONE: Long = 100_000_000L

        val ZERO = Quantity(0)

        fun ofUnits(units: Long): Quantity = Quantity(units * ONE)
    }
}

/**
 * Average cost per unit = total cost / quantity. **This is a derived display value,
 * never stored.**
 *
 * Total cost is stored instead of average cost because storing average cost would
 * silently miscalculate on adding to a position: if the user changes quantity from 100
 * to 200 without updating the average cost, total cost would automatically become
 * "average cost × 200" — a price they never actually paid. See docs/domain.md,
 * "input form".
 *
 * @return returns null when quantity is 0 (there is no meaningful average — not a
 *   divide-by-zero, not 0 either)
 */
fun Money.unitCostOver(quantity: Quantity): Money? {
    if (quantity.isZero) return null
    // total cost / quantity = total cost * ONE / scaled
    return Money(FixedPoint.multiply(minorUnits, Quantity.ONE, quantity.scaled))
}
