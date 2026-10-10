package com.boomsset.ui

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import com.boomsset.domain.Money
import kotlin.math.abs

/**
 * How a typed amount reads in Chinese units, e.g. `72.1613万 · 十万级`.
 *
 * What this is for: **catching a mistyped zero.** `7216130` and `721613` look nearly identical in
 * a text field; "72.1613万" and "721.613万" don't. The thousands-grouped form the rest of the app
 * uses can't be shown *while typing* (the parser rejects commas, see
 * [formatForInput]), so the reading goes underneath the field instead.
 *
 * Rules, each of which is a decision rather than a default:
 * - **Only whole yuan are read.** Cents don't change the magnitude, and including them would turn
 *   `72.1613万` into `72.161345万`, which is longer than the number being checked.
 * - **No rounding anywhere.** Digits below the unit go after the decimal point with trailing
 *   zeros trimmed (12万 整, not 12.0000万). Rounding would be able to swallow exactly the digit the
 *   user is double-checking.
 * - **Nothing below 1,000.** Those read at a glance; a hint there is noise.
 * - **One unit, plus a tier word.** The first prototype split into groups ("2万0045"), which read
 *   as a thousands separator in Chinese clothes. The tier word (千 / 万 / 十万 / 百万 / 千万 / 亿 …)
 *   is what the "how big is this" check actually needs.
 */
internal data class MagnitudeReading(val value: String, val tier: String) {
    /** "72.1613万 · 十万级" */
    val text: String get() = "$value · ${tier}级"
}

private data class MagnitudeStep(val threshold: Long, val divisor: Long, val unit: String, val tier: String)

/** Ascending. Each step covers `[threshold, next threshold)`; the last has no upper bound. */
private val MAGNITUDE_STEPS = listOf(
    MagnitudeStep(1_000L, 1_000L, "千", "千"),
    MagnitudeStep(10_000L, 10_000L, "万", "万"),
    MagnitudeStep(100_000L, 10_000L, "万", "十万"),
    MagnitudeStep(1_000_000L, 10_000L, "万", "百万"),
    MagnitudeStep(10_000_000L, 10_000L, "万", "千万"),
    MagnitudeStep(100_000_000L, 100_000_000L, "亿", "亿"),
    MagnitudeStep(1_000_000_000L, 100_000_000L, "亿", "十亿"),
    MagnitudeStep(10_000_000_000L, 100_000_000L, "亿", "百亿"),
    MagnitudeStep(100_000_000_000L, 100_000_000L, "亿", "千亿"),
    MagnitudeStep(1_000_000_000_000L, 1_000_000_000_000L, "万亿", "万亿"),
)

/** @return null below 1,000 yuan, where a reading adds nothing. */
internal fun Money.magnitudeReading(): MagnitudeReading? {
    val yuan = abs(minorUnits / 100)
    val step = MAGNITUDE_STEPS.lastOrNull { yuan >= it.threshold } ?: return null
    val whole = yuan / step.divisor
    val remainder = yuan % step.divisor
    val digits = step.divisor.toString().length - 1
    val fraction = if (remainder == 0L) "" else "." + remainder.toString().padStart(digits, '0').trimEnd('0')
    val sign = if (minorUnits < 0) "-" else ""
    return MagnitudeReading("$sign$whole$fraction${step.unit}", step.tier)
}

/**
 * A numeric input that shows what's there **now** as a hint and what you typed **as a reading**.
 *
 * One component for every "type a number into a field that already has a value" case — the
 * update dialog's market value / cost / quantity, and the add-asset page's amount / cost. They
 * used to be separate [OutlinedTextField]s that each prefilled their own text and had to be
 * remembered one by one; one of those prefills was the root of AGENTS.md lesson 2 (a thousands
 * separator that the parser rejected, leaving "Save" permanently disabled).
 *
 * **The current value is a hint, not prefilled text.** To change a prefilled field you first have
 * to delete it, and for a number the user is about to replace that is pure friction. Here the
 * field starts empty, the hint stays visible, and **empty means "keep the current value"** — the
 * caller decides what that means for its own field. A side effect worth having: nothing prefilled
 * means nothing can be formatted in a way its own parser rejects.
 *
 * @param current the existing value, already formatted for display; null when there isn't one
 *   (a new asset). Shown as the placeholder (visible while focused) **and** in the supporting
 *   line while the field is empty — Material hides a placeholder until focus, and the line has to
 *   say what "leave it empty" does before anyone taps in.
 * @param reading the typed value's reading, null when there's nothing to show
 * @param note a fixed explanation appended to the supporting line, for fields that had one
 */
@Composable
internal fun HintedField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    current: String?,
    reading: String?,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    note: String? = null,
) {
    val supporting = listOfNotNull(
        when {
            value.isBlank() && current != null -> "留空沿用当前 $current"
            value.isNotBlank() && reading != null -> "= $reading"
            else -> null
        },
        note,
    ).joinToString(" · ").takeIf { it.isNotEmpty() }

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = current?.let { { Text(it) } },
        supportingText = supporting?.let { { Text(it) } },
        singleLine = true,
        isError = isError,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
    )
}

/**
 * [HintedField] for money. Parses [value] itself so every caller reads the same way and a typed
 * string that doesn't parse shows no reading (and is flagged by [isError], which callers compute
 * from the same parse).
 *
 * @param current the existing amount; null for a new asset
 */
@Composable
internal fun AmountField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    currency: String,
    current: Money?,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    note: String? = null,
) {
    HintedField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        current = current?.formatWithCurrency(currency),
        reading = value.toMinorUnitsOrNull()?.let { Money(it).magnitudeReading()?.text },
        modifier = modifier,
        isError = isError,
        note = note,
    )
}
