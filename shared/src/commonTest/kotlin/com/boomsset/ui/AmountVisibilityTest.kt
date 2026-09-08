package com.boomsset.ui

import com.boomsset.domain.Money
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlin.test.Test

/**
 * 眼睛图标藏起金额时的替换规则。
 *
 * 图标本身是 Canvas 画的、没有无障碍节点之外的可测面（见 [AmountVisibilityToggle]），
 * 但**"藏了之后字符串里到底还剩什么"是纯函数，可以锁死** ——
 * 而这正是这个功能唯一会静默出错的地方：漏一位数字或者按位数生成占位符，
 * 界面看起来是藏好了，量级却还在。
 */
class AmountVisibilityTest {

    @Test
    fun `不隐藏时原样返回`() {
        maskAmount(hidden = false, text = "¥133,405.00") shouldBe "¥133,405.00"
    }

    @Test
    fun `隐藏时不留任何数字`() {
        val masked = maskAmount(hidden = true, text = "¥133,405.00")
        ('0'..'9').forEach { digit -> masked shouldNotContain digit.toString() }
    }

    /**
     * **占位符的长度必须和金额无关。** 按位数生成（`"•".repeat(digits)`）会让
     * 七位数和四位数一眼分得开 —— 那等于把"大概多少钱"漏出去，而那就是要藏的东西。
     */
    @Test
    fun `占位符长度不随金额量级变化`() {
        val small = maskAmount(hidden = true, text = Money(1_00).formatWithCurrency("CNY"))
        val large = maskAmount(hidden = true, text = Money(1_234_567_89).formatWithCurrency("CNY"))
        small shouldBe large
    }

    /** 币种符号和正负号也不留 —— 符号会把方向漏出来（见 [MASKED_AMOUNT] 的注释）。 */
    @Test
    fun `占位符不带币种符号和正负号`() {
        val negative = maskAmount(hidden = true, text = Money(-500_00).formatSigned("USD"))
        negative shouldBe MASKED_AMOUNT
        negative shouldNotContain "-"
        negative shouldNotContain "$"
    }
}
