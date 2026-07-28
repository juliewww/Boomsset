package com.boomsset.domain

import kotlin.jvm.JvmInline

/**
 * 持有份额，定点整数表示，scale = 8。
 *
 * scale 取 8 是为了覆盖加密货币的最小单位（BTC 的 satoshi 是 1e-8）；
 * 基金份额一般 4 位小数，股票是整数，都在范围内。
 *
 * **不用 Double** —— 份额要参与「份额 × 单价」算市值，浮点误差会进到净值里。
 */
@JvmInline
value class Quantity(val scaled: Long) : Comparable<Quantity> {

    operator fun plus(other: Quantity) = Quantity(scaled + other.scaled)
    operator fun minus(other: Quantity) = Quantity(scaled - other.scaled)

    override fun compareTo(other: Quantity): Int = scaled.compareTo(other.scaled)

    val isZero: Boolean get() = scaled == 0L

    /** 仅用于展示。会丢精度，**不要**拿返回值再去算钱。 */
    fun toDisplayDouble(): Double = scaled.toDouble() / ONE

    companion object {
        const val SCALE: Int = 8
        const val ONE: Long = 100_000_000L

        val ZERO = Quantity(0)

        fun ofUnits(units: Long): Quantity = Quantity(units * ONE)
    }
}

/**
 * 市值 = 份额 × 单价。
 *
 * 单价是 [Money]（该资产币种下的最小单位），结果也是同币种的 [Money]。
 * 溢出会抛 [ArithmeticException] 而不是静默回绕 —— 见 [FixedPoint]。
 */
fun Quantity.valueAt(unitPrice: Money): Money =
    Money(FixedPoint.multiply(unitPrice.minorUnits, scaled, Quantity.ONE))

/**
 * 成本均价 = 总成本 / 份额。**这是派生显示值，不存库。**
 *
 * 存总成本而不是存均价，是因为存均价会在加仓时静默算错：用户把份额从 100 改成 200
 * 却没更新均价，总成本会自动变成「均价 × 200」—— 一个他从没付过的价格。
 * 见 docs/domain.md「录入形式」。
 *
 * @return 份额为 0 时返回 null（没有均价可言，而不是除零或返回 0）
 */
fun Money.unitCostOver(quantity: Quantity): Money? {
    if (quantity.isZero) return null
    // 总成本 / 份额 = 总成本 * ONE / scaled
    return Money(FixedPoint.multiply(minorUnits, Quantity.ONE, quantity.scaled))
}
