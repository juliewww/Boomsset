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
 * Presentation-layer formatting. Hand-written rather than using platform NumberFormat —
 * commonMain doesn't have it, and the needs here are narrow anyway (RMB amounts,
 * basis-point-to-percentage conversion).
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
 * Example: Money(123456) → "1,234.56"
 *
 * @param grouped whether to add thousands separators. **Must pass false when pre-filling
 *   an input field** — see [formatForInput].
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
 * Format used to pre-fill an editable input field: **no thousands separators**.
 *
 * Reason for existing: a bug only discovered by running on a real device — pre-filling
 * used the comma-formatted [formatAmount], while [toMinorUnitsOrNull] explicitly rejects
 * commas, so opening the update dialog for any asset ≥1000 would leave "Save" permanently
 * disabled — the core loop was flatly broken for real-world amounts.
 *
 * Deliberately not choosing "make the parser tolerate commas": that would make `"1,23"`
 * parse as 123, when the user most likely meant to type 1.23 — a silent 100x error is far
 * worse than a disabled button.
 *
 * Invariant: **this function's output must be parseable back to the original value by
 * [toMinorUnitsOrNull].** Enforced by a test.
 */
fun Money.formatForInput(): String = formatAmount(showDecimals = true, grouped = false)

/**
 * Example: Money(123456) + "CNY" → "¥1,234.56", Money(-123456) → "-¥1,234.56"
 *
 * The minus sign sits **outside the currency symbol**. It used to be
 * `symbol + formatAmount()`, which laid negatives out as "¥-1,234.56" — the sign ends up
 * stuffed inside the number, which neither Chinese nor English convention does;
 * [formatCompact] always had this right, so this is aligned to match it.
 */
fun Money.formatWithCurrency(currency: String, showDecimals: Boolean = true): String =
    signPrefix(withPlus = false) + currencySymbol(currency) + magnitude().formatAmount(showDecimals)

/**
 * Amount with an **explicit plus sign**. Example: +¥564.00 / -¥564.00 / ¥0.00
 *
 * A change amount must show whether it rose or fell at a glance, without the reader having
 * to infer it from "is there a minus sign" (the allocation page's net exposure follows the
 * same reasoning — see AllocationScreen). Zero gets no sign: `+¥0.00` reads like "rose by 0,"
 * when the fact is "no change."
 *
 * @param showDecimals planning-scale figures (the allocation page's "distance to target"
 *   adjustment amount) don't show cents — that number is "roughly how much to move," and
 *   two decimal places would be false precision while also lengthening the whole line.
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
 * Abbreviation for large amounts, used on overview cards. Example: ¥12,345,678.00 →
 * "¥1234.6万"
 *
 * Chinese-context uses "万/亿" rather than K/M — the latter requires Chinese-speaking users
 * to do an extra mental conversion step.
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

/** Restores an "integer scaled up by 10^scale" back into a decimal string, avoiding Double. */
private fun Long.toDecimalString(scale: Int): String {
    val divisor = generateSequence(1L) { it * 10 }.take(scale + 1).last()
    val whole = this / divisor
    val frac = abs(this % divisor)
    return if (frac == 0L) whole.toString()
    else "$whole.${frac.toString().padStart(scale, '0').trimEnd('0').ifEmpty { "0" }}"
}

/**
 * Format used to pre-fill quantities into an input field.
 *
 * **Cannot use `toDisplayDouble().toString()`** — for small quantities it produces
 * scientific notation (`Quantity(1)` → `"1.0E-8"`), which `toQuantityOrNull` can't parse,
 * leaving "Save" disabled. Same class of bug as the one with amounts.
 *
 * Invariant: this function's output must be parseable back to the original value by
 * [com.boomsset.ui.assets.toQuantityOrNull].
 */
fun Quantity.formatForInput(): String {
    val whole = scaled / Quantity.ONE
    val frac = scaled % Quantity.ONE
    if (frac == 0L) return whole.toString()
    val fracText = frac.toString().padStart(Quantity.SCALE, '0').trimEnd('0')
    return "$whole.$fracText"
}

/**
 * Quantity display. The rule is exactly the same as [Quantity.formatForInput], just a
 * different name — quantities have no thousands separators and no padded decimal places,
 * so "pre-fill into input field" and "display for humans to read" happen to be the same
 * format here.
 *
 * A separate name exists so display code doesn't call something that reads like a typo,
 * `formatForInput()`; if it ever needs to diverge (e.g. adding thousands separators on the
 * display side), only this function needs to change, without dragging along the input
 * field's "pre-fill must be parseable back by its own parser" invariant.
 */
fun Quantity.formatDisplay(): String = formatForInput()

/**
 * Date shown in the update history. Same-year entries write only month/day; entries from a
 * different year get the year prepended.
 *
 * This list can scroll all the way back several years, and writing "Sep 7" for all of them
 * wouldn't tell which year; but putting the year on every line would be too verbose (the
 * vast majority of entries are from this year). This doesn't conflict with [periodLabel]'s
 * "the year can't be omitted" rule — that one has at most 12 labels where crossing years is
 * the norm, while this one is a reverse-chronological log mostly concentrated in the recent
 * past.
 */
fun LocalDate.historyDateLabel(today: LocalDate): String =
    if (year == today.year) monthDayLabel() else "${year}年${monthDayLabel()}"

/** Unit price pre-filled into an input field: scale-8 fixed-point → decimal string with no
 * trailing zeros. */
fun UnitPrice.formatForInput(): String {
    val whole = scaled / UnitPrice.ONE
    val frac = scaled % UnitPrice.ONE
    if (frac == 0L) return whole.toString()
    return "$whole.${frac.toString().padStart(UnitPrice.SCALE, '0').trimEnd('0')}"
}

/** Unit price display: two decimal places by default, but shows more when the extra
 * digits are significant (needed for low-priced stocks/tokens). */
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
 * Description of a quote's date and freshness.
 *
 * domain.md requires "the UI must be able to show that this price is from 3 days ago" —
 * when fetching the price fails, it falls back to the stale price, and the user must know
 * they're not looking at the current market price.
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

/** Example: LocalDate(2026, 8, 4) → "8月4日". The year is left to [periodLabel] — not
 * needed in everyday contexts. */
fun LocalDate.monthDayLabel(): String = "${month.ordinal + 1}月${day}日"

/**
 * The name of the period a sample point falls in, **with the year included**.
 *
 * The year can't be omitted: the net worth page looks back at most 12 periods, and "vs.
 * September" would be ambiguous under the by-month view once years are crossed (last
 * September, or this one?) — and this label's entire job is to state the baseline clearly.
 */
fun LocalDate.periodLabel(period: Period): String = when (period) {
    Period.MONTH -> "${year}年${month.ordinal + 1}月"
    Period.QUARTER -> "${year}年Q${month.ordinal / 3 + 1}"
    Period.YEAR -> "${year}年"
}

/**
 * When the most recent snapshot was recorded.
 *
 * A direct consequence of "record snapshots, not transactions": the net worth figure
 * **doesn't update itself** — a record from three months ago looks identical on screen to
 * one from today. So both the date and "how long ago" must be stated, letting the user
 * judge for themselves whether the number at the top still counts — this doesn't hand down
 * a conclusion of "you should update now"; update cadence varies by person (someone who
 * records monthly and someone who records quarterly have tolerances for "stale" that differ
 * by an order of magnitude).
 *
 * @return null when there isn't a single snapshot yet; the caller doesn't show this line.
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
 * Basis points → percentage string. Example: 1234 → "12.34%", -500 → "-5.00%"
 */
fun Int.bpToPercent(decimals: Int = 2, withSign: Boolean = false): String {
    val negative = this < 0
    val magnitude = abs(this)
    // Basis points: 10000 = 100%, so 1% = 100bp
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
 * Percentage for a change amount: positive gets `+`, negative gets `-`, **zero gets no sign**.
 *
 * `bpToPercent(withSign = true)` would lay 0 out as "+0.00%" — reads like "rose by 0" —
 * kept consistent with how [Money.formatSigned] handles amounts.
 */
fun Int.bpToSignedPercent(): String = bpToPercent(withSign = this > 0)

/**
 * Format used to pre-fill basis points into a percentage input field: **no `%` sign, no
 * trailing zeros**. 3000 → "30", 1250 → "12.5"
 *
 * Invariant: this function's output must be parseable back to the original value by
 * [parsePercentToBp]. Enforced by a test — this is the lesson learned from the
 * "pre-filling with thousands separators permanently disables Save" bug.
 */
fun Int.bpToInputPercent(): String {
    val whole = this / 100
    val frac = this % 100
    if (frac == 0) return whole.toString()
    return "$whole.${frac.toString().padStart(2, '0').trimEnd('0')}"
}

/**
 * Percentage string → basis points. `"30"` → 3000, `"12.5"` → 1250.
 *
 * Reuses the unified fixed-point parser: keeping percentages to two decimal places maps
 * exactly onto basis points (1% = 100bp), so scale is just 2 — the same code path as
 * amounts, no Double involved.
 */
fun parsePercentToBp(text: String): Int? =
    parseMoneyMinor(text)?.takeIf { it in 0..TargetAllocation.TOTAL_BP }?.toInt()

/** A human-readable description of the target allocation percentages' sum, used for
 * validation hints. */
fun TargetAllocation.sumDescription(): String =
    "${sumBp.bpToPercent(decimals = 0)} / 100%"
