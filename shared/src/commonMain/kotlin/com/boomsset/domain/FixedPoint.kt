package com.boomsset.domain

/**
 * Shared implementation for fixed-point multiplication.
 *
 * Why this exists: `quantity × unit price` multiplies two large integers and then
 * divides by scale — **the naive approach overflows Long**. For example at scale=8, a
 * quantity of 1,000,000 shares has a scaled value of 1e14; a unit price of 1500 yuan is
 * 150000 fen; multiplying them directly gives 1.5e19 — beyond Long.MAX_VALUE
 * (about 9.2e18), which silently wraps around into a negative number.
 *
 * Approach: split the scaled value into an integer part and a fractional part and
 * multiply each separately, so both intermediate results are much smaller. And **throw
 * on overflow instead of wrapping** — silently miscalculating an amount is exactly the
 * failure mode this project can least afford.
 */
internal object FixedPoint {

    /**
     * Parses a decimal string into an integer scaled up by 10^[scale]. **Never goes
     * through Double.**
     *
     * Used for the unified parsing of amounts (scale=2), quantities (scale=8), and
     * exchange rates (scale=8) — none of the three can tolerate floating-point error.
     * `"1.15"` via `toDouble() * 100` yields 114.999…, which truncates to one fen short.
     *
     * Strict rules (all intentional):
     * - More decimal digits than [scale] → returns null, **never silently truncates**.
     *   The user would believe they recorded a more precise number than was actually
     *   stored.
     * - Thousands-separator commas → returns null. Tolerating them would turn
     *   `"1,23"` into 123, when the user may have meant to type 1.23.
     * - Overflow → returns null, never wraps.
     *
     * @param allowNegative exchange rates and quantities don't accept negative values; amounts (liabilities) do
     */
    fun parseDecimal(text: String, scale: Int, allowNegative: Boolean = true): Long? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null

        val negative = trimmed.startsWith('-')
        if (negative && !allowNegative) return null

        val body = trimmed.removePrefix("-").removePrefix("+")
        if (body.isEmpty()) return null

        val parts = body.split('.')
        if (parts.size > 2) return null

        val wholeText = parts[0].ifEmpty { "0" }
        if (!wholeText.all { it.isDigit() }) return null

        val fracText = parts.getOrNull(1) ?: ""
        if (!fracText.all { it.isDigit() } || fracText.length > scale) return null

        val whole = wholeText.toLongOrNull() ?: return null
        val frac = fracText.padEnd(scale, '0').toLongOrNull() ?: return null

        val multiplier = pow10(scale)
        if (whole > (Long.MAX_VALUE - frac) / multiplier) return null

        val magnitude = whole * multiplier + frac
        return if (negative) -magnitude else magnitude
    }

    fun pow10(scale: Int): Long {
        var result = 1L
        repeat(scale) { result *= 10 }
        return result
    }

    /**
     * Computes `amount × (scaled / one)`, rounded to an integer.
     *
     * @param amount the multiplicand (e.g. an amount in minor units)
     * @param scaled the fixed-point multiplier
     * @param one    the value representing 1 at this fixed-point scale (e.g. 100_000_000 at scale=8)
     */
    fun multiply(amount: Long, scaled: Long, one: Long): Long {
        if (amount == 0L || scaled == 0L) return 0L

        val negative = (amount < 0) != (scaled < 0)
        val a = abs(amount)
        val s = abs(scaled)

        val intPart = s / one
        val fracPart = s % one

        // a * intPart is the term most likely to overflow, so check it first
        if (intPart != 0L && a > Long.MAX_VALUE / intPart) {
            overflow(amount, scaled, one)
        }
        val whole = a * intPart

        // fracPart < one, so a * fracPart is of a much smaller magnitude than above, but extreme inputs can still overflow
        if (fracPart != 0L && a > (Long.MAX_VALUE - one / 2) / fracPart) {
            overflow(amount, scaled, one)
        }
        // Round to nearest: add half of one, then integer-divide
        val frac = (a * fracPart + one / 2) / one

        if (whole > Long.MAX_VALUE - frac) overflow(amount, scaled, one)
        val magnitude = whole + frac

        return if (negative) -magnitude else magnitude
    }

    private fun abs(v: Long): Long =
        if (v < 0) {
            // Taking the absolute value of Long.MIN_VALUE overflows, so guard against it separately
            require(v != Long.MIN_VALUE) { "Fixed-point arithmetic doesn't support Long.MIN_VALUE" }
            -v
        } else {
            v
        }

    private fun overflow(amount: Long, scaled: Long, one: Long): Nothing =
        throw ArithmeticException(
            "Fixed-point multiplication overflowed: amount=$amount, scaled=$scaled, one=$one. " +
                "This magnitude exceeds what a Long can represent — don't let it silently wrap around.",
        )
}

/** "Yuan" string → fen. Negative values allowed (for liabilities). */
fun parseMoneyMinor(text: String): Long? =
    FixedPoint.parseDecimal(text, scale = 2, allowNegative = true)

/** Quantity string → fixed-point integer. Quantity cannot be negative. */
fun parseQuantity(text: String): Quantity? =
    FixedPoint.parseDecimal(text, scale = Quantity.SCALE, allowNegative = false)
        ?.let { Quantity(it) }

/** Exchange rate string → fixed-point integer. Exchange rate cannot be negative. */
fun parseExchangeRate(text: String): ExchangeRate? =
    FixedPoint.parseDecimal(text, scale = ExchangeRate.SCALE, allowNegative = false)
        ?.let { ExchangeRate(it) }
