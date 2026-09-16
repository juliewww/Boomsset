package com.boomsset.domain

import kotlin.jvm.JvmInline

/**
 * Unit price, as a fixed-point integer, scale = 8.
 *
 * **Why not use [Money] (scale 2)**: unit prices need far more precision than amounts do.
 * - Low-priced Hong Kong stocks are quoted to 3 decimal places (the Tencent API returns `462.400`)
 * - Crypto tokens can be `0.00001234`
 *
 * Storing at scale 2 would turn `0.00001234` into `0.00`, silently zeroing out the
 * entire asset — exactly the failure mode this project cannot accept. Scale 8 matches
 * [Quantity] and covers both cases above.
 */
@JvmInline
value class UnitPrice(val scaled: Long) : Comparable<UnitPrice> {

    override fun compareTo(other: UnitPrice): Int = scaled.compareTo(other.scaled)

    val isZero: Boolean get() = scaled == 0L

    companion object {
        const val SCALE: Int = 8
        const val ONE: Long = 100_000_000L

        val ZERO = UnitPrice(0)

        /** Constructed from "yuan", used only in tests and constants. */
        fun ofMajorUnits(units: Long): UnitPrice = UnitPrice(units * ONE)
    }
}

/** Unit price string → fixed-point integer. Cannot be negative. */
fun parseUnitPrice(text: String): UnitPrice? =
    FixedPoint.parseDecimal(text, scale = UnitPrice.SCALE, allowNegative = false)
        ?.let { UnitPrice(it) }

/**
 * Market value = quantity × unit price. The result is [Money] (fen) in the asset's currency.
 *
 * Multiplying two scale=8 fixed-point numbers and converting to scale=2 — the naive
 * approach `q * p / 1e14` is guaranteed to overflow Long (quantity 1,000,000 × unit
 * price 1000 yuan → 1e14 × 1e11 = 1e25). Done in two steps instead:
 *
 * 1. `multiply(p, q, ONE)` gives "market value × 1e8" (yuan in scale-8 representation)
 * 2. divide by 1e6 to convert to fen (since 1e8 / 100 = 1e6), rounding to nearest
 *
 * Throws [ArithmeticException] on overflow instead of wrapping — see [FixedPoint].
 */
fun Quantity.valueAt(unitPrice: UnitPrice): Money {
    if (isZero || unitPrice.isZero) return Money.ZERO
    // Step 1: the result is yuan in scale-8 representation
    val yuanScaled = FixedPoint.multiply(unitPrice.scaled, scaled, Quantity.ONE)
    // Step 2: scale-8 yuan → scale-2 fen, i.e. divide by 1e6, rounding to nearest
    val divisor = 1_000_000L
    val negative = yuanScaled < 0
    val magnitude = if (negative) -yuanScaled else yuanScaled
    val fen = (magnitude + divisor / 2) / divisor
    return Money(if (negative) -fen else fen)
}
