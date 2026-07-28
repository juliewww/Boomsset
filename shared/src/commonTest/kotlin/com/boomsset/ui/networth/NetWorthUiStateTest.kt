package com.boomsset.ui.networth

import com.boomsset.domain.Money
import com.boomsset.domain.NetWorthPoint
import com.boomsset.domain.NetWorthSeries
import com.boomsset.domain.Period
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.time.Instant

class NetWorthUiStateTest {

    private fun seriesWithZeroPoints(): NetWorthSeries {
        val t = Instant.fromEpochMilliseconds(0)
        return NetWorthSeries(
            period = Period.MONTH,
            baseCurrency = "CNY",
            dates = listOf(LocalDate(2026, 6, 30), LocalDate(2026, 7, 28)),
            points = listOf(
                NetWorthPoint(t, "CNY", Money.ZERO, Money.ZERO),
                NetWorthPoint(t, "CNY", Money.ZERO, Money.ZERO),
            ),
        )
    }

    /**
     * 回归测试。这个 bug 是在模拟器上实跑时发现的：
     * 原来 isEmpty 判的是 `series.latest == null`，但 buildSeries 即使零资产也会生成
     * 一整串净值为 0 的点（取样日期按周期算，与有没有数据无关），latest 永远非空，
     * 于是空状态永不出现 —— 新用户看到的是 ¥0.00 和一条平坦的零线，而不是引导文案。
     */
    @Test
    fun `零资产时是空状态 即使序列有一堆零值点`() {
        val state = NetWorthUiState(
            loading = false,
            series = seriesWithZeroPoints(),
            hasAssets = false,
        )
        state.isEmpty shouldBe true
    }

    @Test
    fun `有资产就不是空状态 即使当前净值恰好为零`() {
        // 资产存在但净值为 0（比如全部已还清的负债对冲掉了）——
        // 这时候该显示正常界面，不是"还没有资产"的引导
        val state = NetWorthUiState(
            loading = false,
            series = seriesWithZeroPoints(),
            hasAssets = true,
        )
        state.isEmpty shouldBe false
    }

    @Test
    fun `加载中不算空状态`() {
        NetWorthUiState(loading = true, hasAssets = false).isEmpty shouldBe false
    }
}
