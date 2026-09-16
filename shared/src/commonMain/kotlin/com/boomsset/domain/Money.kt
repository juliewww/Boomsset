package com.boomsset.domain

import kotlin.jvm.JvmInline

/**
 * An amount, stored as an integer in the smallest currency unit (fen).
 *
 * Exists so that the mistake of "amounts using Double" is impossible to write at the
 * type level — see AGENTS.md constraint 4. Net worth is the sum of a large number of
 * values, so floating-point error would accumulate and be amplified, and this app's
 * entire value proposition rests on the trustworthiness of that total.
 *
 * Note this type **carries no currency**. Currency lives on
 * [com.boomsset.domain.Asset.currency]; adding Money values across different currencies
 * is a business-logic error handled by the conversion layer, not guarded against by
 * this type.
 */
@JvmInline
value class Money(val minorUnits: Long) : Comparable<Money> {

    operator fun plus(other: Money) = Money(minorUnits + other.minorUnits)
    operator fun minus(other: Money) = Money(minorUnits - other.minorUnits)
    operator fun unaryMinus() = Money(-minorUnits)

    override fun compareTo(other: Money): Int = minorUnits.compareTo(other.minorUnits)

    val isZero: Boolean get() = minorUnits == 0L

    companion object {
        val ZERO = Money(0)
    }
}

fun Iterable<Money>.sum(): Money = Money(sumOf { it.minorUnits })
