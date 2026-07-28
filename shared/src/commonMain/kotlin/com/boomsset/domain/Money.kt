package com.boomsset.domain

import kotlin.jvm.JvmInline

/**
 * 金额，以最小货币单位（分）存储的整数。
 *
 * 存在的目的就是让「金额用 Double」这个错误在类型层面写不出来 —— 见 AGENTS.md 约束 4。
 * 净值是大量数字连加，浮点误差会累积并被放大，而这个 App 的全部价值就在那个总数的可信度上。
 *
 * 注意本类型**不带币种**。币种在 [com.boomsset.domain.Asset.currency] 上，
 * 不同币种的 Money 相加是业务错误，由折算层负责，不由本类型防御。
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
