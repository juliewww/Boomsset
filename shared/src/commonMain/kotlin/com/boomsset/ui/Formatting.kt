package com.boomsset.ui

import com.boomsset.domain.Money
import com.boomsset.domain.AssetValuation
import com.boomsset.domain.Period
import com.boomsset.domain.Quantity
import com.boomsset.domain.UnitPrice
import com.boomsset.domain.TargetAllocation
import com.boomsset.domain.parseMoneyMinor
import kotlinx.datetime.LocalDate
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

/**
 * 例：Money(123456) → "1,234.56"
 *
 * @param grouped 是否加千分位。**预填到输入框时必须传 false** ——
 *   见 [formatForInput]。
 */
fun Money.formatAmount(showDecimals: Boolean = true, grouped: Boolean = true): String {
    val negative = minorUnits < 0
    val magnitude = abs(minorUnits)
    val yuan = magnitude / 100
    val cents = magnitude % 100

    val yuanText = if (grouped) {
        yuan.toString().reversed().chunked(3).joinToString(",").reversed()
    } else {
        yuan.toString()
    }
    val body = if (showDecimals) {
        "$yuanText.${cents.toString().padStart(2, '0')}"
    } else {
        yuanText
    }
    return if (negative) "-$body" else body
}

/**
 * 预填到可编辑输入框用的格式：**不带千分位**。
 *
 * 存在的理由是一个实跑时才发现的 bug：预填用了带逗号的 [formatAmount]，
 * 而 [toMinorUnitsOrNull] 明确拒绝逗号，于是任何 ≥1000 的资产打开更新弹窗后
 * 「保存」永久禁用 —— 核心循环对真实金额直接是坏的。
 *
 * 不选择"让解析器容忍逗号"是有意的：那样 `"1,23"` 会被当成 123，
 * 而用户很可能想输 1.23 —— 100 倍的静默错误比一个禁用的按钮糟糕得多。
 *
 * 不变量：**本函数的输出必须能被 [toMinorUnitsOrNull] 解析回原值。** 有测试锁着。
 */
fun Money.formatForInput(): String = formatAmount(showDecimals = true, grouped = false)

/**
 * 例：Money(123456) + "CNY" → "¥1,234.56"，Money(-123456) → "-¥1,234.56"
 *
 * 负号在**币种符号外面**。原来是 `symbol + formatAmount()`，负数会排成 "¥-1,234.56" ——
 * 负号被塞进了数字内部，中英文习惯都不这么写；[formatCompact] 一直是对的，这里对齐它。
 */
fun Money.formatWithCurrency(currency: String, showDecimals: Boolean = true): String =
    signPrefix(withPlus = false) + currencySymbol(currency) + magnitude().formatAmount(showDecimals)

/**
 * 带**显式正号**的金额。例：+¥564.00 / -¥564.00 / ¥0.00
 *
 * 变化量必须一眼看出涨还是跌，不能靠"有没有减号"去反推
 * （配置页的净敞口是同一条理由，见 AllocationScreen）。
 * 零不加号：`+¥0.00` 读起来像"涨了 0"，而事实是"没有变化"。
 *
 * @param showDecimals 规划用的量级（配置页「距目标」的调整额）不显示分 ——
 *   那个数是"大概该挪多少钱"，两位小数是假精度，还把整行挤长。
 */
fun Money.formatSigned(currency: String, showDecimals: Boolean = true): String =
    signPrefix(withPlus = true) + currencySymbol(currency) + magnitude().formatAmount(showDecimals)

private fun Money.signPrefix(withPlus: Boolean): String = when {
    minorUnits < 0 -> "-"
    withPlus && minorUnits > 0 -> "+"
    else -> ""
}

private fun Money.magnitude(): Money = Money(abs(minorUnits))

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
 * 份额预填到输入框用的格式。
 *
 * **不能用 `toDisplayDouble().toString()`** —— 那对小份额会产出科学计数法
 * （`Quantity(1)` → `"1.0E-8"`），而 `toQuantityOrNull` 解析不了，
 * 于是「保存」被禁用。和金额那个 bug 是同一类。
 *
 * 不变量：本函数的输出必须能被 [com.boomsset.ui.assets.toQuantityOrNull] 解析回原值。
 */
fun Quantity.formatForInput(): String {
    val whole = scaled / Quantity.ONE
    val frac = scaled % Quantity.ONE
    if (frac == 0L) return whole.toString()
    val fracText = frac.toString().padStart(Quantity.SCALE, '0').trimEnd('0')
    return "$whole.$fracText"
}

/**
 * 份额展示。规则和 [Quantity.formatForInput] 完全一样，只是叫法不同 ——
 * 份额没有千分位，也不补固定小数位，「预填给输入框」和「显示给人看」在这里恰好同一个格式。
 *
 * 单独起个名字是为了让展示代码不出现 `formatForInput()` 这种读起来像写错了的调用；
 * 真要分叉（比如展示侧加千分位）时改这一个函数就行，不会连带破坏输入框那条
 * 「预填必须能被自己的解析器读回原值」的不变量。
 */
fun Quantity.formatDisplay(): String = formatForInput()

/**
 * 更新记录里的日期。同年只写月日，跨年补上年份。
 *
 * 这个列表会一路翻到几年前，全都写「9月7日」分不出是哪一年；但每行都带上年份又太啰嗦
 * （绝大多数记录都是今年的）。和 [periodLabel] 那条"年份不能省"不冲突 ——
 * 那里最多只有 12 个标签、跨年是常态，这里是一条按时间倒序、绝大部分集中在近期的流水。
 */
fun LocalDate.historyDateLabel(today: LocalDate): String =
    if (year == today.year) monthDayLabel() else "${year}年${monthDayLabel()}"

/** 单价预填到输入框：scale-8 定点 → 不带多余 0 的小数串。 */
fun UnitPrice.formatForInput(): String {
    val whole = scaled / UnitPrice.ONE
    val frac = scaled % UnitPrice.ONE
    if (frac == 0L) return whole.toString()
    return "$whole.${frac.toString().padStart(UnitPrice.SCALE, '0').trimEnd('0')}"
}

/** 单价展示：默认两位小数，但小数位有效就多显示（低价股/代币需要）。 */
fun UnitPrice.formatDisplay(currency: String): String {
    val whole = scaled / UnitPrice.ONE
    val frac = scaled % UnitPrice.ONE
    val fracText = frac.toString().padStart(UnitPrice.SCALE, '0').trimEnd('0')
    val body = when {
        fracText.isEmpty() -> "$whole.00"
        fracText.length < 2 -> "$whole.${fracText}0"
        else -> "$whole.$fracText"
    }
    return currencySymbol(currency) + body
}

/**
 * 行情的日期与新鲜度描述。
 *
 * domain.md 要求「UI 上要能看出来这个价格是 3 天前的」——
 * 取价失败时会退回 stale 价格，用户必须知道自己看的不是当前市价。
 */
fun AssetValuation.priceDescription(): String {
    val q = quote ?: return "还没有这个代码的行情。可以手填一个单价先用着。"
    val price = q.price.formatDisplay(q.currency)
    return when (val age = priceAgeDays) {
        null -> "单价 $price（日期 ${q.asOfDay}）"
        0 -> "单价 $price · 今天的行情"
        1 -> "单价 $price · 昨天的行情"
        else -> {
            val tail = if (isPriceStale) " ⚠️ 可能已过期，必要时手填覆盖" else ""
            "单价 $price · $age 天前的行情$tail"
        }
    }
}

/** 例：LocalDate(2026, 8, 4) → "8月4日"。年份留给 [periodLabel]，日常场景不需要。 */
fun LocalDate.monthDayLabel(): String = "${month.ordinal + 1}月${day}日"

/**
 * 取样点所在周期的名字，**带年份**。
 *
 * 年份不能省：净值页最多回看 12 个周期，"相比 9月"在按月下跨年就有歧义
 * （去年 9 月还是今年 9 月？），而这个标签的全部作用就是把基准说清楚。
 */
fun LocalDate.periodLabel(period: Period): String = when (period) {
    Period.MONTH -> "${year}年${month.ordinal + 1}月"
    Period.QUARTER -> "${year}年Q${month.ordinal / 3 + 1}"
    Period.YEAR -> "${year}年"
}

/**
 * 最近一次记快照是什么时候。
 *
 * 「记快照不记流水」的直接后果：净值这个数字**不会自己更新**，三个月前的记录
 * 和今天的记录在界面上长得一样。所以日期和"多久以前"都要说，
 * 让用户自己判断顶上那个数还算不算数 —— 这里不替他下"该更新了"的结论，
 * 更新节奏因人而异（月度记账的人和季度记账的人对"旧"的容忍度差一个数量级）。
 *
 * @return 一条快照都没有时返回 null，调用方不显示这一行。
 */
fun lastRecordDescription(date: LocalDate?, ageDays: Int?): String? {
    if (date == null) return null
    val head = "最近记录 ${date.monthDayLabel()}"
    return when (ageDays) {
        null -> head
        0 -> "$head · 今天"
        1 -> "$head · 昨天"
        else -> "$head · $ageDays 天前"
    }
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

/**
 * 变化量的百分比：正数带 `+`、负数带 `-`、**零不带号**。
 *
 * `bpToPercent(withSign = true)` 会把 0 排成 "+0.00%" —— 读起来像"涨了 0"，
 * 和金额那边 [Money.formatSigned] 的处理保持一致。
 */
fun Int.bpToSignedPercent(): String = bpToPercent(withSign = this > 0)

/**
 * 基点预填到百分比输入框用的格式：**不带 % 号、不带多余的 0**。
 * 3000 → "30"，1250 → "12.5"
 *
 * 不变量：本函数的输出必须能被 [parsePercentToBp] 解析回原值。有测试锁着 ——
 * 这是从「预填带千分位导致保存永久禁用」那个 bug 学到的教训。
 */
fun Int.bpToInputPercent(): String {
    val whole = this / 100
    val frac = this % 100
    if (frac == 0) return whole.toString()
    return "$whole.${frac.toString().padStart(2, '0').trimEnd('0')}"
}

/**
 * 百分比字符串 → 基点。`"30"` → 3000，`"12.5"` → 1250。
 *
 * 复用统一的定点解析器：百分比保留两位小数正好等于基点（1% = 100bp），
 * 所以 scale 就是 2 —— 和金额用同一条代码路径，不走 Double。
 */
fun parsePercentToBp(text: String): Int? =
    parseMoneyMinor(text)?.takeIf { it in 0..TargetAllocation.TOTAL_BP }?.toInt()

/** 目标配置比例之和的可读描述，用于校验提示。 */
fun TargetAllocation.sumDescription(): String =
    "${sumBp.bpToPercent(decimals = 0)} / 100%"
