package com.boomsset.ui.networth

import com.boomsset.domain.Money
import com.boomsset.domain.NetWorthPoint
import com.boomsset.domain.NetWorthSeries
import com.boomsset.domain.Period
import io.kotest.matchers.collections.shouldHaveSize
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

    // ---------- 增长率标签带 ----------

    private fun money(vararg yuan: Long) = yuan.map { Money(it * 100) }

    @Test
    fun `增长率标签数等于柱子数 第一根没有可比的前一根`() {
        val labels = growthLabels(money(100, 110, 99))

        labels shouldHaveSize 3
        labels[0] shouldBe GrowthLabel.MISSING
        labels[1] shouldBe GrowthLabel("+10%", 1)
        labels[2] shouldBe GrowthLabel("-10%", -1)
    }

    /**
     * 期初 ≤ 0 时算不出百分比（分母无意义），必须是「—」而不是某个凭空算出来的数。
     *
     * 这条和顶部卡片那个"整段区间增长"共用 [PortfolioCalculator.growthBp] 的判据 ——
     * 两处各写一遍除法，迟早会一处显示「—」、另一处显示 +∞ 之类的东西。
     */
    @Test
    fun `期初不是正数时算不出增长率`() {
        growthLabels(money(0, 100))[1] shouldBe GrowthLabel.MISSING
        growthLabels(money(-50, 100))[1] shouldBe GrowthLabel.MISSING
        // 期末为负是可以算的（净值真的跌成负数），方向是跌
        growthLabels(money(100, -50))[1].direction shouldBe -1
    }

    /**
     * Vico 对**每一个**轴标签都做 `check(isNotBlank())`，空白串直接抛异常。
     * 增长率这条是坐标轴（不是 dataLabel），所以同一条约束在这里一样成立。
     */
    @Test
    fun `增长率标签永远不是空白串`() {
        val labels = growthLabels(money(0, 100, 100, -20, 0))
        labels.forEach { it.text.isNotBlank() shouldBe true }

        // 越界、空表、null 表（模型还没落地的那一帧）都只能给占位符
        (-2..9).forEach { x ->
            growthLabelAt(labels, x.toDouble()).text.isNotBlank() shouldBe true
            growthLabelAt(null, x.toDouble()) shouldBe GrowthLabel.MISSING
            growthLabelAt(emptyList(), x.toDouble()) shouldBe GrowthLabel.MISSING
        }
        growthLabelAt(labels, 1.0) shouldBe labels[1]
    }

    /**
     * 整数百分比，**四舍五入不是截断**。
     *
     * 十几根柱子并排时每根只分到二十几 dp，带小数的百分比会被截断成 "+12…" ——
     * 一个被截断的数字比没有更糟。而截断到整数会把 +0.9% 显示成 "+0%"，
     * 读起来像"没动"，其实涨了。
     */
    @Test
    fun `增长率四舍五入到整数`() {
        formatGrowthPercent(1234) shouldBe "+12%"
        formatGrowthPercent(1250) shouldBe "+13%"
        formatGrowthPercent(-1250) shouldBe "-13%"
        formatGrowthPercent(-149) shouldBe "-1%"
        formatGrowthPercent(-150) shouldBe "-2%"
        // 四舍五入到 0 时不带正负号：+0% 会让人以为"涨了一点点但显示不出来"
        formatGrowthPercent(49) shouldBe "0%"
        formatGrowthPercent(-49) shouldBe "0%"
        formatGrowthPercent(50) shouldBe "+1%"
        formatGrowthPercent(0) shouldBe "0%"
    }

    /**
     * 四舍五入到 0 时颜色也要中性。
     *
     * 实机截图抓到的：+0.19% 的那根柱子标着 "0%"，却涂成了"涨"的红色 ——
     * 字说没动、颜色说涨了，自相矛盾。方向必须按**显示出来的那个数**判。
     */
    @Test
    fun `四舍五入成 0 时不涂涨跌色`() {
        // 10.58 万 → 10.60 万，+0.19%
        val labels = growthLabels(listOf(Money(10_580_000), Money(10_600_000)))
        labels[1] shouldBe GrowthLabel("0%", 0)

        growthLabels(money(1000, 996))[1] shouldBe GrowthLabel("0%", 0)
    }

    /**
     * 增长率带和底部的周期轴**必须用同一套 spacing/offset**，否则最新那根柱子会漏标。
     *
     * 实机（12 根柱子）抓到的：增长率带原来用 `aligned()` 的默认值（spacing=1、offset=0），
     * 而 `aligned()` 默认 `addExtremeLabelPadding = true`，Vico 会把 spacing 再乘上
     * `ceil(maxLabelWidth / xSpacing)` 来防重叠 —— 实际间隔变成 2 而 offset 还是 0，
     * 于是标到 0/2/…/10，**最右边（最新那期）头上是空的**。
     * 两条轴的标签数天然相同（都等于取样点数），所以共用同一个算法就对齐了。
     */
    @Test
    fun `增长率带和周期轴的标签数一致 稀疏时也标到最后一根`() {
        val s = series(Period.MONTH, 12)
        val growth = growthLabels(s.points.map { it.netWorth })

        growth shouldHaveSize axisLabels(s).size

        val spacing = axisLabelSpacing(growth.size)
        ((growth.size - 1 - axisLabelOffset(growth.size)) % spacing) shouldBe 0
    }

    // ---------- 趋势图 ----------

    /**
     * 教训 10 的回归：只有 1 个点时折线画不出线段，图表区里只有坐标轴。
     * 趋势图现在是用户主动选的，点数不够必须**明说**而不是给一张空图。
     */
    @Test
    fun `趋势图至少要两个点`() {
        canDrawTrend(0) shouldBe false
        canDrawTrend(1) shouldBe false
        canDrawTrend(2) shouldBe true
    }

    /**
     * 趋势图下面那两个日期是**自己画的**（Vico 的底部轴画不出末尾那个，见
     * [TrendChartFrame] 的注释），所以这里只需要锁住"首末各取一个、都是完整日期"。
     */
    @Test
    fun `日期标签是完整日期且首末取到两端`() {
        val labels = dateLabels(
            listOf(LocalDate(2026, 1, 31), LocalDate(2026, 4, 30), LocalDate(2026, 7, 28)),
        )
        labels shouldBe listOf("2026-01-31", "2026-04-30", "2026-07-28")
        labels.first() shouldBe "2026-01-31"
        labels.last() shouldBe "2026-07-28"
    }

    /**
     * 堆叠面积是靠"累计边界 + 后画的盖前画的"拼出来的（Vico 没有原生堆叠面积图）。
     * 累计必须单调递增，否则边界互相穿插、分层是错的 —— 前提是每段非负，
     * 由 `AllocationSeries.hasNegativeExposure` 在调用前把关。
     */
    @Test
    fun `累计边界逐层递增且最上层等于合计`() {
        val bands = stackedBands(
            listOf(
                listOf(10L, 20L),
                listOf(5L, 0L),
                listOf(1L, 3L),
            ),
        )

        bands shouldBe listOf(
            listOf(10L, 20L),
            listOf(15L, 20L),
            listOf(16L, 23L),
        )
        bands.last() shouldBe listOf(16L, 23L)
    }

    @Test
    fun `累计边界处理空输入`() {
        stackedBands(emptyList()) shouldBe emptyList()
        stackedBands(listOf(emptyList())) shouldBe listOf(emptyList())
        stackedBands(listOf(listOf(7L))) shouldBe listOf(listOf(7L))
    }

    // ---------- 纵轴金额 ----------

    /** 纵轴同样是 Vico 的轴标签，同样不能空白。 */
    @Test
    fun `金额缩写成万和亿且永不为空`() {
        compactAmountLabel(0.0) shouldBe "0"
        compactAmountLabel(9999.0) shouldBe "9999"
        compactAmountLabel(10_000.0) shouldBe "1万"
        compactAmountLabel(12_500.0) shouldBe "1.3万"
        compactAmountLabel(1_234_567.0) shouldBe "123.5万"
        compactAmountLabel(100_000_000.0) shouldBe "1亿"
        compactAmountLabel(-25_000.0) shouldBe "-2.5万"
        // 负的极小值四舍五入到 0 时不带负号 —— "-0" 不是一个数
        compactAmountLabel(-0.4) shouldBe "0"

        listOf(-1e12, -1.0, 0.0, 0.5, 9_999.4, 1e12).forEach {
            compactAmountLabel(it).isNotBlank() shouldBe true
        }
    }
}
