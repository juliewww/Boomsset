package com.boomsset.ui

import com.boomsset.domain.Money
import com.boomsset.domain.Quantity
import com.boomsset.domain.UnitPrice
import com.boomsset.domain.parseUnitPrice
import com.boomsset.ui.assets.toQuantityOrNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * 往返不变量：**预填到输入框的字符串，必须能被我们自己的解析器读回同一个值。**
 *
 * 这组测试是为一个实跑时发现的 bug 加的。原来更新弹窗用带千分位的 `formatAmount()` 预填，
 * 而 `toMinorUnitsOrNull()` 明确拒绝逗号 —— 结果任何 ≥¥1000 的资产打开弹窗后
 * 「保存」永久禁用。编译通过、72 个单测全绿，但 App 的核心循环对真实金额是坏的。
 *
 * 单测没抓到是因为格式化和解析各自都有测试，**没有测试跨过它们之间的接缝**。
 */
class InputRoundTripTest {

    @Test
    fun `金额预填能被解析回原值`() {
        val cases = listOf(
            0L,
            5L,            // 0.05
            100L,          // 1.00
            99_999L,       // 999.99
            100_000L,      // 1,000.00 ← 原来就是这里开始坏的
            123_456_78L,   // 大额
            999_999_999_99L,
        )
        cases.forEach { minor ->
            val money = Money(minor)
            val text = money.formatForInput()
            withClue(minor, text) { text.toMinorUnitsOrNull() shouldBe minor }
        }
    }

    @Test
    fun `负数金额预填也能往返`() {
        // 负债的欠款额
        val money = Money(-1_234_567L)
        money.formatForInput().toMinorUnitsOrNull() shouldBe -1_234_567L
    }

    @Test
    fun `展示用格式带千分位 但不能拿去预填`() {
        // 明确记录两者的差别，防止有人"顺手统一"回去
        Money(100_000).formatAmount() shouldBe "1,000.00"
        Money(100_000).formatForInput() shouldBe "1000.00"
        Money(100_000).formatAmount().toMinorUnitsOrNull() shouldBe null  // 逗号被拒
    }

    @Test
    fun `份额预填能被解析回原值`() {
        val cases = listOf(
            0L,
            1L,                    // 1 satoshi 级 —— Double.toString 会给 1.0E-8
            50_000_000L,           // 0.5
            Quantity.ONE,          // 1
            123_456_780_000L,      // 1234.5678
            100_000_000_000_000L,  // 100 万份
        )
        cases.forEach { scaled ->
            val quantity = Quantity(scaled)
            val text = quantity.formatForInput()
            withClue(scaled, text) { text.toQuantityOrNull() shouldBe quantity }
        }
    }

    @Test
    fun `单价预填能被解析回原值`() {
        // 单价 scale=8，覆盖港股 3 位小数和代币的极小值
        val cases = listOf(
            0L,
            1L,                        // 0.00000001，代币级
            46_240_0000_0L,            // 462.400，港股
            1300_00000000L,            // 1300
            133_405_000_000L,          // 1334.05，实测的茅台价
        )
        cases.forEach { scaled ->
            val price = UnitPrice(scaled)
            val text = price.formatForInput()
            withClue(scaled, text) { parseUnitPrice(text) shouldBe price }
        }
    }

    @Test
    fun `极小份额不会变成科学计数法`() {
        // Double 路线会给出 "1.0E-8"，解析器读不了
        Quantity(1).formatForInput() shouldBe "0.00000001"
    }

    private inline fun withClue(vararg context: Any?, block: () -> Unit) {
        try {
            block()
        } catch (e: AssertionError) {
            throw AssertionError("输入 ${context.joinToString(" → ")} 往返失败: ${e.message}", e)
        }
    }
}
