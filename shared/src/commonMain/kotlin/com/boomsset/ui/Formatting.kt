package com.boomsset.ui

import com.boomsset.domain.Money
import com.boomsset.domain.TargetAllocation
import kotlin.math.abs

/**
 * 展示层的格式化。手写而不是用平台 NumberFormat —— commonMain 里没有它，
 * 而且这里的需求很窄（人民币金额、基点转百分比）。
 */

private val currencySymbols = mapOf(
    "CNY" to "¥",
    "USD" to "$",
    "HKD" to "HK$",
    "EUR" to "€",
    "JPY" to "¥",
    "GBP" to "£",
)

fun currencySymbol(code: String): String = currencySymbols[code] ?: "$code "

/** 例：Money(123456) → "1,234.56" */
fun Money.formatAmount(showDecimals: Boolean = true): String {
    val negative = minorUnits < 0
    val magnitude = abs(minorUnits)
    val yuan = magnitude / 100
    val cents = magnitude % 100

    val grouped = yuan.toString().reversed().chunked(3).joinToString(",").reversed()
    val body = if (showDecimals) {
        "$grouped.${cents.toString().padStart(2, '0')}"
    } else {
        grouped
    }
    return if (negative) "-$body" else body
}

/** 例：Money(123456) + "CNY" → "¥1,234.56" */
fun Money.formatWithCurrency(currency: String, showDecimals: Boolean = true): String =
    currencySymbol(currency) + formatAmount(showDecimals)

/**
 * 大额缩写，用于概览卡片。例：¥12,345,678.00 → "¥1234.6万"
 *
 * 中文语境用「万 / 亿」而不是 K/M —— 后者对中文用户要多算一步。
 */
fun Money.formatCompact(currency: String): String {
    val symbol = currencySymbol(currency)
    val negative = minorUnits < 0
    val yuan = abs(minorUnits) / 100
    val body = when {
        yuan >= 100_000_000L -> "${(yuan / 10_000_000L).toDecimalString(1)}亿"
        yuan >= 10_000L -> "${(yuan / 1_000L).toDecimalString(1)}万"
        else -> yuan.toString()
    }
    return (if (negative) "-" else "") + symbol + body
}

/** 把「放大了 10^scale 倍的整数」还原成小数字符串，避免用 Double。 */
private fun Long.toDecimalString(scale: Int): String {
    val divisor = generateSequence(1L) { it * 10 }.take(scale + 1).last()
    val whole = this / divisor
    val frac = abs(this % divisor)
    return if (frac == 0L) whole.toString()
    else "$whole.${frac.toString().padStart(scale, '0').trimEnd('0').ifEmpty { "0" }}"
}

/**
 * 基点 → 百分比字符串。例：1234 → "12.34%"，-500 → "-5.00%"
 */
fun Int.bpToPercent(decimals: Int = 2, withSign: Boolean = false): String {
    val negative = this < 0
    val magnitude = abs(this)
    // 基点：10000 = 100%，所以 1% = 100bp
    val whole = magnitude / 100
    val frac = magnitude % 100

    val body = when (decimals) {
        0 -> whole.toString()
        1 -> "$whole.${(frac / 10)}"
        else -> "$whole.${frac.toString().padStart(2, '0')}"
    }
    val sign = when {
        negative -> "-"
        withSign -> "+"
        else -> ""
    }
    return "$sign$body%"
}

/** 目标配置比例之和的可读描述，用于校验提示。 */
fun TargetAllocation.sumDescription(): String =
    "${sumBp.bpToPercent(decimals = 0)} / 100%"
