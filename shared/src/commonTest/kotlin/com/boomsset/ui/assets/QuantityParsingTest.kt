package com.boomsset.ui.assets

import com.boomsset.domain.Money
import com.boomsset.domain.Quantity
import com.boomsset.domain.UnitPrice
import com.boomsset.domain.valueAt
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class QuantityParsingTest {

    @Test
    fun `整数份额`() {
        "100".toQuantityOrNull() shouldBe Quantity.ofUnits(100)
        "1".toQuantityOrNull() shouldBe Quantity.ofUnits(1)
    }

    @Test
    fun `小数份额按 scale 8 定点存储`() {
        "1.5".toQuantityOrNull() shouldBe Quantity(150_000_000)
        "0.12345678".toQuantityOrNull() shouldBe Quantity(12_345_678)
        ".5".toQuantityOrNull() shouldBe Quantity(50_000_000)
    }

    @Test
    fun `解析不经过 Double 所以没有浮点误差`() {
        // 0.1 + 0.2 类的问题在这里不存在，因为全程整数
        "0.1".toQuantityOrNull() shouldBe Quantity(10_000_000)
        "1234.5678".toQuantityOrNull() shouldBe Quantity(123_456_780_000)
    }

    @Test
    fun `超过八位小数判为非法而不静默截断`() {
        // 静默截断会让用户以为记住了一个更精确的份额
        "0.123456789".toQuantityOrNull().shouldBeNull()
    }

    @Test
    fun `份额不能为负`() {
        "-1".toQuantityOrNull().shouldBeNull()
    }

    @Test
    fun `非法输入返回null`() {
        "".toQuantityOrNull().shouldBeNull()
        "abc".toQuantityOrNull().shouldBeNull()
        "1.2.3".toQuantityOrNull().shouldBeNull()
    }

    @Test
    fun `超大份额不静默溢出`() {
        "99999999999999999999".toQuantityOrNull().shouldBeNull()
    }

    @Test
    fun `解析出的份额能直接参与市值计算`() {
        // 端到端：输入 "10.5" 份，单价 20 元 → 210 元
        val q = "10.5".toQuantityOrNull()!!
        q.valueAt(UnitPrice.ofMajorUnits(20)) shouldBe Money(210_00)
    }
}
