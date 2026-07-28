package com.boomsset.domain

import io.kotest.matchers.shouldBe
import kotlin.test.Test

class MoneyTest {

    @Test
    fun `相加不产生浮点误差`() {
        // 这个数列用 Double 累加会得到 0.9999999999999999
        val tenCents = List(10) { Money(10) }
        tenCents.sum() shouldBe Money(100)
    }

    @Test
    fun `负债用负数表示并正确抵扣`() {
        val asset = Money(500_00)
        val liability = Money(120_00)
        (asset - liability) shouldBe Money(380_00)
    }

    @Test
    fun `可比较`() {
        (Money(1) > Money.ZERO) shouldBe true
        Money.ZERO.isZero shouldBe true
    }
}
