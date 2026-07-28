package com.boomsset.domain

import kotlin.jvm.JvmInline

/**
 * 汇率，定点整数，scale = 8。同样不用 Double —— 它会参与净值累加。
 */
@JvmInline
value class ExchangeRate(val scaled: Long) {

    /** 把 [amount] 从 base 币种折算到 quote 币种。 */
    fun convert(amount: Money): Money =
        Money(FixedPoint.multiply(amount.minorUnits, scaled, ONE))

    companion object {
        const val SCALE: Int = 8
        const val ONE: Long = 100_000_000L

        /** 同币种，1:1。 */
        val IDENTITY = ExchangeRate(ONE)
    }
}

/**
 * 某一天的汇率。
 *
 * **折算历史净值必须用当时的汇率**，不是今天的 —— 否则汇率波动会污染历史曲线，
 * 让用户看到自己从没经历过的涨跌。
 */
data class FxRate(
    val base: String,
    val quote: String,
    val asOfDay: String,
    val rate: ExchangeRate,
)
