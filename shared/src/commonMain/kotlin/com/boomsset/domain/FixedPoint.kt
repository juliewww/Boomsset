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
