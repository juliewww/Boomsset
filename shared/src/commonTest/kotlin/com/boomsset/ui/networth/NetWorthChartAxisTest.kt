package com.boomsset.ui.networth

import com.boomsset.domain.Money
import com.boomsset.domain.NetWorthPoint
import com.boomsset.domain.NetWorthSeries
import com.boomsset.domain.Period
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.time.Instant

/**
 * 净值图 x 轴标签的规则。
 *
 * 这些是纯函数，但它们守的是 **Vico 的两条硬约束**：
 * 1. 轴标签**不能是空白串** —— Vico 对每个标签都做 `check(isNotBlank())`，返回空串直接抛异常，
 *    表现是 App 闪退（实机上「按月切按季/按年」就是这么崩的）
 * 2. `ItemPlacer.aligned()` 要求 `spacing > 0`、`offset >= 0`，否则 `require` 失败
 *
 * 单测测不到 Vico 画出来什么样，但能锁住"喂给 Vico 的值永远合法"这一半。
 */
class NetWorthChartAxisTest {

    private fun series(period: Period, dateCount: Int): NetWorthSeries {
        val t = Instant.fromEpochMilliseconds(0)
        val dates = List(dateCount) { LocalDate(2020 + it / 12, it % 12 + 1, 28) }
        return NetWorthSeries(
            period = period,
            baseCurrency = "CNY",
            dates = dates,
            points = dates.map { NetWorthPoint(t, "CNY", Money.ZERO, Money.ZERO) },
        )
    }

    @Test
    fun `每个周期粒度的标签都非空`() {
        Period.entries.forEach { period ->
            val labels = axisLabels(series(period, 12))
            labels.size shouldBe 12
            labels.forEach { it.isNotBlank() shouldBe true }
        }
    }

    /**
     * 回归测试：**按月切到按季/按年会闪退。**
     *
     * `CartesianChartModelProducer` 跨 period 切换一直活着，而模型更新是 suspend transaction，
     * 所以切换的那一帧 Vico 手里还是旧模型（按月 12 个点），却已经在用新的标签表（按季 2 个）。
     * 原来的 formatter 直接 `dates.getOrNull(x) ?: ""`，x=2..11 时返回空串 → Vico 抛异常。
     *
     * 现在标签随 model 走 ExtraStore，正常不会出现这种错配；这条测试锁的是**兜底行为**：
     * 就算真的问到越界的 x，也只能给占位符，绝不能给空白串。
     */
    @Test
    fun `标签表比 x 短时给占位符而不是空串`() {
        val quarterly = axisLabels(series(Period.QUARTER, 2))
        (0..11).forEach { x ->
            axisLabelAt(quarterly, x.toDouble()).isNotBlank() shouldBe true
        }
        axisLabelAt(quarterly, 11.0) shouldBe MISSING_AXIS_LABEL
        axisLabelAt(quarterly, 1.0) shouldBe quarterly[1]
    }

    @Test
    fun `没有标签表时也不返回空串`() {
        axisLabelAt(null, 0.0).isNotBlank() shouldBe true
        axisLabelAt(emptyList(), 0.0).isNotBlank() shouldBe true
        // 负的 x（Vico 会为坐标轴留白测量越界的位置）同样不能返回空串
        axisLabelAt(axisLabels(series(Period.MONTH, 3)), -1.0).isNotBlank() shouldBe true
    }

    @Test
    fun `spacing 和 offset 对任意点数都是合法值`() {
        (0..40).forEach { count ->
            val spacing = axisLabelSpacing(count)
            val offset = axisLabelOffset(count)
            spacing shouldBeGreaterThan 0
            offset shouldBeGreaterThanOrEqualTo 0
            offset shouldBeLessThan spacing
        }
    }

    @Test
    fun `点数多时稀疏标注 但最后一个点一定被标到`() {
        val count = 12
        val spacing = axisLabelSpacing(count)
        spacing shouldBeGreaterThan 1
        // aligned 标的是 offset, offset+spacing, offset+2*spacing… 最后一个下标必须落在其中，
        // 否则用户最关心的"现在"那个点没有标签
        ((count - 1 - axisLabelOffset(count)) % spacing) shouldBe 0
    }

    @Test
    fun `点数少时每个点都标`() {
        axisLabelSpacing(1) shouldBe 1
        axisLabelSpacing(6) shouldBe 1
        axisLabelOffset(1) shouldBe 0
        axisLabelOffset(6) shouldBe 0
    }
}
