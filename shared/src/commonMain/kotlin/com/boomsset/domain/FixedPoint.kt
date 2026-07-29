package com.boomsset.domain

/**
 * 定点数乘法的公共实现。
 *
 * 存在的理由：`份额 × 单价` 是两个大整数相乘再除以 scale，**朴素写法会溢出 Long**。
 * 举例 scale=8 时，份额 100 万股的 scaled 值是 1e14，单价 1500 元 = 150000 分，
 * 直接相乘是 1.5e19 —— 超过 Long.MAX_VALUE（约 9.2e18），会静默回绕成负数。
 *
 * 做法：把 scaled 拆成整数部分和小数部分分别乘，两边的中间结果都小得多。
 * 并且**溢出时抛异常而不是回绕** —— 金额静默算错正是这个项目最不能接受的失败模式。
 */
internal object FixedPoint {

    /**
     * 把十进制字符串解析成放大 10^[scale] 倍的整数。**不经过 Double。**
     *
     * 用于金额（scale=2）、份额（scale=8）、汇率（scale=8）的统一解析 ——
     * 三处都不能有浮点误差。`"1.15"` 走 `toDouble() * 100` 会得到 114.999…，
     * 截断后少一分钱。
     *
     * 严格规则（都是有意的）：
     * - 小数位超过 [scale] → 返回 null，**不静默截断**。用户以为记住了更精确的数。
     * - 千分位逗号 → 返回 null。容忍它会让 `"1,23"` 变成 123，而用户可能想输 1.23。
     * - 溢出 → 返回 null，不回绕。
     *
     * @param allowNegative 汇率和份额不接受负数，金额（负债）接受
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
     * 计算 `amount × (scaled / one)`，四舍五入到整数。
     *
     * @param amount 被乘数（如金额的最小单位）
     * @param scaled 定点乘数
     * @param one    该定点表示的 1（如 scale=8 时是 100_000_000）
     */
    fun multiply(amount: Long, scaled: Long, one: Long): Long {
        if (amount == 0L || scaled == 0L) return 0L

        val negative = (amount < 0) != (scaled < 0)
        val a = abs(amount)
        val s = abs(scaled)

        val intPart = s / one
        val fracPart = s % one

        // a * intPart 是最容易溢出的一项，先检查
        if (intPart != 0L && a > Long.MAX_VALUE / intPart) {
            overflow(amount, scaled, one)
        }
        val whole = a * intPart

        // fracPart < one，所以 a * fracPart 的量级比上面小得多，但极端输入仍可能溢出
        if (fracPart != 0L && a > (Long.MAX_VALUE - one / 2) / fracPart) {
            overflow(amount, scaled, one)
        }
        // 四舍五入：加上半个 one 再整除
        val frac = (a * fracPart + one / 2) / one

        if (whole > Long.MAX_VALUE - frac) overflow(amount, scaled, one)
        val magnitude = whole + frac

        return if (negative) -magnitude else magnitude
    }

    private fun abs(v: Long): Long =
        if (v < 0) {
            // Long.MIN_VALUE 取绝对值会溢出，单独挡掉
            require(v != Long.MIN_VALUE) { "定点运算不支持 Long.MIN_VALUE" }
            -v
        } else {
            v
        }

    private fun overflow(amount: Long, scaled: Long, one: Long): Nothing =
        throw ArithmeticException(
            "定点乘法溢出：amount=$amount, scaled=$scaled, one=$one。" +
                "这个数量级超出了 Long 能表示的范围，不要让它静默回绕。",
        )
}

/** 「元」字符串 → 分。负数允许（负债）。 */
fun parseMoneyMinor(text: String): Long? =
    FixedPoint.parseDecimal(text, scale = 2, allowNegative = true)

/** 份额字符串 → 定点整数。份额不能为负。 */
fun parseQuantity(text: String): Quantity? =
    FixedPoint.parseDecimal(text, scale = Quantity.SCALE, allowNegative = false)
        ?.let { Quantity(it) }

/** 汇率字符串 → 定点整数。汇率不能为负。 */
fun parseExchangeRate(text: String): ExchangeRate? =
    FixedPoint.parseDecimal(text, scale = ExchangeRate.SCALE, allowNegative = false)
        ?.let { ExchangeRate(it) }
