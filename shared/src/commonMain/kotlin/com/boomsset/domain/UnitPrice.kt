package com.boomsset.domain

import kotlin.jvm.JvmInline

/**
 * 单位价格，定点整数，scale = 8。
 *
 * **为什么不用 [Money]（scale 2）**：单价的精度需求比金额高得多。
 * - 港股低价股报到 3 位小数（腾讯接口返回 `462.400`）
 * - 加密货币代币可能是 `0.00001234`
 *
 * 用 scale 2 存的话，`0.00001234` 会变成 `0.00`，整项资产静默归零 ——
 * 这正是本项目最不能接受的失败模式。scale 8 和 [Quantity] 对齐，够覆盖上面两种情况。
 */
@JvmInline
value class UnitPrice(val scaled: Long) : Comparable<UnitPrice> {

    override fun compareTo(other: UnitPrice): Int = scaled.compareTo(other.scaled)

    val isZero: Boolean get() = scaled == 0L

    companion object {
        const val SCALE: Int = 8
        const val ONE: Long = 100_000_000L

        val ZERO = UnitPrice(0)

        /** 从「元」构造，仅用于测试和常量。 */
        fun ofMajorUnits(units: Long): UnitPrice = UnitPrice(units * ONE)
    }
}

/** 单价字符串 → 定点整数。不能为负。 */
fun parseUnitPrice(text: String): UnitPrice? =
    FixedPoint.parseDecimal(text, scale = UnitPrice.SCALE, allowNegative = false)
        ?.let { UnitPrice(it) }

/**
 * 市值 = 份额 × 单价。结果是该资产币种下的 [Money]（分）。
 *
 * 两个 scale=8 的定点数相乘再转成 scale=2，朴素写法 `q * p / 1e14` 必定溢出 Long
 * （份额 100 万 × 单价 1000 元 → 1e14 × 1e11 = 1e25）。分两步做：
 *
 * 1. `multiply(p, q, ONE)` 得到「市值 × 1e8」（元的 scale-8 表示）
 * 2. 再除以 1e6 转成分（因为 1e8 / 100 = 1e6），四舍五入
 *
 * 溢出时抛 [ArithmeticException] 而不是回绕 —— 见 [FixedPoint]。
 */
fun Quantity.valueAt(unitPrice: UnitPrice): Money {
    if (isZero || unitPrice.isZero) return Money.ZERO
    // 第一步：结果是「元」的 scale-8 表示
    val yuanScaled = FixedPoint.multiply(unitPrice.scaled, scaled, Quantity.ONE)
    // 第二步：scale-8 的元 → scale-2 的分，即除以 1e6，四舍五入
    val divisor = 1_000_000L
    val negative = yuanScaled < 0
    val magnitude = if (negative) -yuanScaled else yuanScaled
    val fen = (magnitude + divisor / 2) / divisor
    return Money(if (negative) -fen else fen)
}
