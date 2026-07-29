package com.boomsset.ui

import com.boomsset.domain.TargetAllocation
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class PercentInputTest {

    @Test
    fun `百分比字符串转基点`() {
        parsePercentToBp("30") shouldBe 3000
        parsePercentToBp("12.5") shouldBe 1250
        parsePercentToBp("0") shouldBe 0
        parsePercentToBp("100") shouldBe TargetAllocation.TOTAL_BP
        parsePercentToBp("0.01") shouldBe 1
    }

    @Test
    fun `超出零到一百的范围判非法`() {
        // 单个大类不可能超过 100%，也不可能是负数
        parsePercentToBp("101").shouldBeNull()
        parsePercentToBp("-5").shouldBeNull()
    }

    @Test
    fun `非法输入返回null`() {
        parsePercentToBp("").shouldBeNull()
        parsePercentToBp("abc").shouldBeNull()
        parsePercentToBp("30%").shouldBeNull()   // 带 % 号要用户去掉，不猜
        parsePercentToBp("12.345").shouldBeNull()  // 超过两位小数
    }

    @Test
    fun `基点预填格式不带百分号和多余的零`() {
        3000.bpToInputPercent() shouldBe "30"
        1250.bpToInputPercent() shouldBe "12.5"
        0.bpToInputPercent() shouldBe "0"
        TargetAllocation.TOTAL_BP.bpToInputPercent() shouldBe "100"
        1.bpToInputPercent() shouldBe "0.01"
    }

    /**
     * 往返不变量。和金额/份额那两处同一类 ——
     * **预填的字符串必须能被自己的解析器读回原值**，否则「保存」会莫名禁用。
     */
    @Test
    fun `预填的百分比能被解析回原值`() {
        val cases = listOf(0, 1, 50, 500, 1250, 3000, 3500, 6500, 10_000)
        cases.forEach { bp ->
            val text = bp.bpToInputPercent()
            val parsed = parsePercentToBp(text)
            if (parsed != bp) {
                throw AssertionError("基点 $bp 预填成 \"$text\"，解析回 $parsed，往返失败")
            }
        }
    }

    @Test
    fun `内置预设的每个值都能往返`() {
        // 直接拿真实预设值验，避免"测试用的数刚好能过"
        com.boomsset.data.BUILT_IN_PRESETS.forEach { preset ->
            preset.targetsBp.forEach { (assetClass, bp) ->
                val text = bp.bpToInputPercent()
                if (parsePercentToBp(text) != bp) {
                    throw AssertionError("预设「${preset.name}」的 $assetClass = $bp 往返失败（\"$text\"）")
                }
            }
        }
    }
}
