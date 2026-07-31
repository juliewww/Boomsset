package com.boomsset.ui.allocation

import com.boomsset.data.BUILT_IN_PRESETS
import com.boomsset.domain.AssetClass
import com.boomsset.domain.PortfolioData
import com.boomsset.domain.PortfolioSeriesCalculator
import com.boomsset.domain.TargetAllocation
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test

/**
 * 零资产时「配置」页的状态。
 *
 * **这一页在没有任何资产的时候也必须是有用的。** 它回答的是"我打算怎么配"，
 * 这个问题不依赖持仓 —— 而且目标配置恰恰是用户录第一笔资产**之前**就想设的东西。
 */
class AllocationUiStateTest {

    private val zone = TimeZone.UTC
    private val today = LocalDate(2026, 7, 31)
    private val empty = PortfolioData(
        assets = emptyList(),
        snapshots = emptyList(),
        quotes = emptyList(),
        fxRates = emptyList(),
    )

    private fun allocations() = BUILT_IN_PRESETS.mapIndexed { i, preset ->
        TargetAllocation(
            id = i + 1L,
            name = preset.name,
            targetsBp = preset.targetsBp,
            isBuiltIn = true,
            isActive = preset.name == "平衡",
        )
    }

    private fun stateWithNoAssets(
        allocations: List<TargetAllocation> = allocations(),
    ) = AllocationUiState(
        loading = false,
        view = PortfolioSeriesCalculator.currentAllocation(
            data = empty,
            baseCurrency = "CNY",
            today = today,
            zone = zone,
            target = allocations.firstOrNull { it.isActive },
        ),
        allocations = allocations,
    )

    /**
     * 回归测试。实跑时发现：零资产时 `AllocationScreen` 走 `state.isEmpty` 分支，
     * 只渲染一行"还没有资产，先去净值页添加"，而 `AllocationPicker` ——
     * **切换/编辑/新建目标配置的唯一入口** —— 写在 `else` 分支里，整块被跳过。
     * 结果新用户根本够不到目标配置。
     *
     * 数据层一直是对的：预设来自 `observeAllocations()`，和持仓无关，零资产时也在。
     * 纯粹是 UI 路径缺失 —— 和「全部归档后取消不了归档」是同一类 bug，第三次。
     *
     * 这条测试锁住数据契约（入口需要的东西都在）；**UI 上真的够得到**由
     * `iosAppUITests/AssetFlowUITest` 的 `testAllocationTargetsReachableWithNoAssets` 兜。
     */
    @Test
    fun `零资产也是空状态 但目标配置的数据全在`() {
        val state = stateWithNoAssets()

        state.isEmpty.shouldBeTrue()

        // 切换用的全部预设
        state.allocations.shouldNotBeEmpty()
        state.allocations.map { it.name } shouldBe listOf("稳健", "平衡", "激进")

        // 当前对比的那一套，以及它的比例 —— 空状态要拿它渲染目标预览
        val active = state.allocations.single { it.isActive }
        active.name shouldBe "平衡"
        active.targetsBp.values.sum() shouldBe TargetAllocation.TOTAL_BP
        AssetClass.displayOrder.forEach { active.targetsBp[it].shouldNotBeNull() }
    }

    /**
     * 一套目标都没有时（理论上不该发生，内置预设是 seed 的）也不能白屏 ——
     * UI 要给出「＋ 新建」的出路。这里锁住状态本身不会崩。
     */
    @Test
    fun `没有任何目标配置时状态仍然可用`() {
        val state = stateWithNoAssets(allocations = emptyList())

        state.isEmpty.shouldBeTrue()
        state.allocations shouldBe emptyList()
        // view 仍然算得出来（净资产为 0），不是 null —— UI 不用处理"读不到数据"
        state.view.shouldNotBeNull()
        state.view.target shouldBe null
    }

    @Test
    fun `有资产时不是空状态`() {
        // 空状态的判据是"每个大类的资产都为零"，加一笔就应当翻转
        val state = stateWithNoAssets()
        state.isEmpty.shouldBeTrue()

        val view = state.view
        view.shouldNotBeNull()
        val withAsset = state.copy(
            view = view.copy(
                exposures = view.exposures.mapValues { (assetClass, exposure) ->
                    if (assetClass == AssetClass.LIQUID) {
                        exposure.copy(assets = com.boomsset.domain.Money(100_00))
                    } else {
                        exposure
                    }
                },
            ),
        )
        withAsset.isEmpty shouldBe false
    }
}
