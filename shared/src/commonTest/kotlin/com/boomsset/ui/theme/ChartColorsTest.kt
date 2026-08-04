package com.boomsset.ui.theme

import androidx.compose.ui.graphics.Color
import com.boomsset.domain.AssetClass
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.test.Test

/**
 * 锁住图表配色。
 *
 * 单测跑不了色盲模拟和对比度计算 —— 那些是用 dataviz 的验证器离线跑的。
 * 这里的作用是**防止有人顺手改掉已验证过的色值而没有重新验证**：
 * 色值一变这些断言就挂，挂了就得回去重跑验证器。
 *
 * 重跑方式（两个模式都要，底色用**图形实际渲染的那一层**，即 surfaceContainer）：
 * ```
 * node scripts/validate_palette.js "<五个浅色 hex>" --mode light --surface "#F7EEE1"
 * node scripts/validate_palette.js "<五个深色 hex>" --mode dark  --surface "#241F17"
 * ```
 */
class ChartColorsTest {

    @Test
    fun `每个大类都有颜色 且互不相同`() {
        listOf(chartColorsFor(darkTheme = false), chartColorsFor(darkTheme = true)).forEach { c ->
            c.assetClassColors shouldHaveSize AssetClass.displayOrder.size
            // 五个颜色必须互不相同 —— 重复会让两个大类在图上无法区分
            c.assetClassColors.toSet() shouldHaveSize AssetClass.displayOrder.size
            AssetClass.displayOrder.forEach { assetClass ->
                c.of(assetClass) shouldNotBe c.track
            }
        }
    }

    /**
     * `of()` 必须按 [AssetClass.displayOrder] 的下标取色，**不是 enum 的 ordinal**。
     *
     * 展示顺序是产品决定的；如果按 ordinal 取，将来重排 enum 声明会把全部大类的颜色
     * 悄悄换一遍 —— 而颜色顺序正是色盲安全性的保证机制，换了就不再是验证过的那组。
     */
    @Test
    fun `按展示顺序取色而不是 enum 声明顺序`() {
        val c = chartColorsFor(darkTheme = false)
        AssetClass.displayOrder.forEachIndexed { index, assetClass ->
            c.of(assetClass) shouldBe c.assetClassColors[index]
        }
    }

    /**
     * 已验证的色值。改动任何一个都要重新跑验证器 —— 见类注释。
     *
     * 实测结果（OKLab ΔE ×100）：
     * 浅色 最差相邻对 9.1 / 正常视力 19.6；深色 8.4 / 19.3。门槛是 8 / 15。
     */
    @Test
    fun `色值就是验证过的那组`() {
        chartColorsFor(darkTheme = false).assetClassColors shouldBe listOf(
            Color(0xFF2A78D6), Color(0xFFEB6834), Color(0xFF1BAF7A),
            Color(0xFFEDA100), Color(0xFFE87BA4),
        )
        chartColorsFor(darkTheme = true).assetClassColors shouldBe listOf(
            Color(0xFF3987E5), Color(0xFFD95926), Color(0xFF199E70),
            Color(0xFFC98500), Color(0xFFD55181),
        )
    }

    /**
     * 偏离度是**分歧配色**：两个对立色相 + 中性中点。
     *
     * 中点必须是中性色 —— 如果"已达标"也是一个色相，读者会把"没有偏离"
     * 也当成一种需要处理的状态。
     */
    @Test
    fun `偏离度三态互不相同 且超配不复用错误色`() {
        listOf(chartColorsFor(false), chartColorsFor(true)).forEach { c ->
            setOf(c.over, c.under, c.onTarget) shouldHaveSize 3
            // 超配不是错误，不能复用 error。浅色主题的 error 是 #B3261E
            c.over shouldNotBe Color(0xFFB3261E)
        }
    }

    /**
     * 大类色里不能出现偏离度用的红蓝 —— 否则"蓝色"在同一屏上既表示某个大类
     * 又表示低配。
     */
    @Test
    fun `大类色和偏离度色不重叠`() {
        listOf(chartColorsFor(false), chartColorsFor(true)).forEach { c ->
            val deviation = setOf(c.over, c.under, c.onTarget)
            c.assetClassColors.forEach { it shouldNotBe null }
            (c.assetClassColors.toSet() intersect deviation) shouldBe emptySet()
        }
    }

    /** 大类数量增加但没给颜色时要退回中性色，不能崩、也不能"生成"一个未验证的颜色。 */
    @Test
    fun `没有配色的大类退回轨道色`() {
        val short = chartColorsFor(false).copy(assetClassColors = listOf(Color(0xFF2A78D6)))
        short.of(AssetClass.displayOrder.first()) shouldBe Color(0xFF2A78D6)
        short.of(AssetClass.displayOrder.last()) shouldBe short.track
    }
}
