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
 * 重跑方式（两个模式都要，底色用**图形实际渲染的那一层**）：
 * ```
 * node scripts/validate_palette.js "<五个浅色 hex>" --mode light --surface "#FEF9F4"
 * node scripts/validate_palette.js "<五个深色 hex>" --mode dark  --surface "#211C17"
 * ```
 * ⚠️ **必须跑 `--mode dark` 那一条，不能只跑浅色那条然后套用。**
 * 验证器给两个模式的**亮度带是不同的**：浅色 [0.43, 0.77]，**深色只有 [0.48, 0.67]**。
 * 拿浅色的带去判深色，会放行一组实际越界的值（真踩过）。
 *
 * ⚠️ 验证器还会报一个 **tritan（蓝黄色盲）分离度**，它是**报告项、不是门槛**，
 * 因此很容易被忽略——但它是真实退化。本项目里**蓝↔绿的 tritan 区分完全靠两者的亮度差**，
 * 所以任何"把五个色拉到同一亮度"的想法都会让它崩掉（实测 9.6 → 3.3）。
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
     * 浅色 最差相邻对 11.5 / 正常视力 20.4；深色 10.8 / 正常视力 19.4。门槛是 8 / 15。
     *
     * 前四个色相取自有知有行，但经过 snap-to-passing（色相角不动，挪亮度和彩度到合规）。
     *
     * ⚠️ **第五格「保障类」是后来换掉的**：原本是紫 `#585CA2`，为了给品牌紫腾位置
     * 挪到了金黄。换的方向不是随便挑的 —— 详见 ChartColors 的类注释。
     * 换完之后两套配色都重跑过 dataviz 验证器，六项全过。
     */
    @Test
    fun `色值就是验证过的那组`() {
        // 浅色是「整体上移 0.05、梯度略收窄、彩度不动」的调亮版（反馈"颜色不要那么深"）；
        // 深色**放不下**（验证器给深色的亮度带只有 [0.48,0.67]，现值已经贴顶），保持原值。
        // ⚠️ 两套都跑过**官方验证器**（不是我写的镜像 —— 镜像漏算 tritan、又把浅色的
        // 亮度带错套到深色上，两次都差点放行错误的值）：
        //   node scripts/validate_palette.js "<五个浅色 hex>" --mode light --surface "#FEF9F4"
        //   node scripts/validate_palette.js "<五个深色 hex>" --mode dark  --surface "#211C17"
        chartColorsFor(darkTheme = false).assetClassColors shouldBe listOf(
            Color(0xFF4D95E0), Color(0xFF40C596), Color(0xFFF29637),
            Color(0xFF4EBFDE), Color(0xFFA68D21),
        )
        chartColorsFor(darkTheme = true).assetClassColors shouldBe listOf(
            Color(0xFF4186CE), Color(0xFF00AB79), Color(0xFFCF7600),
            Color(0xFF219FBC), Color(0xFF9B8100),
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
