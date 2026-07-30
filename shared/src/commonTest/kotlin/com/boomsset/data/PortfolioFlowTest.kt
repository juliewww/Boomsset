package com.boomsset.data

import app.cash.turbine.test
import com.boomsset.domain.Asset
import com.boomsset.domain.AssetClass
import com.boomsset.domain.PortfolioData
import com.boomsset.domain.ValuationMode
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * 用 Turbine 测数据流。
 *
 * 这份测试存在的一个附带目的：**让 Turbine 真正被执行一次**。
 * 它此前是个声明了却没人导入的依赖 —— docs/stack.md 记着一条风险
 * 「Turbine 1.2.1 的 iOS klib 是对着 Kotlin stdlib 2.1.21 编的，我们在 2.4.10」，
 * 但没有任何测试用它，那条风险其实一直没被验证（链接器会丢掉没引用的符号，
 * 所以连"能编译"都说明不了什么）。这份测试跑在 iOS 上就把那条风险清掉了。
 */
class PortfolioFlowTest {

    private fun asset(id: Long, name: String) = Asset(
        id = id,
        name = name,
        assetClass = AssetClass.LIQUID,
        subtypeId = 1,
        currency = "CNY",
        defaultValuationMode = ValuationMode.MANUAL,
    )

    @Test
    fun `数据流按顺序发射每次变化`() = runTest {
        val source = MutableStateFlow(PortfolioData.EMPTY)

        source.test {
            awaitItem().assets.size shouldBe 0

            source.value = PortfolioData(
                assets = listOf(asset(1, "现金")),
                snapshots = emptyList(), quotes = emptyList(), fxRates = emptyList(),
            )
            awaitItem().assets.single().name shouldBe "现金"

            source.value = PortfolioData(
                assets = listOf(asset(1, "现金"), asset(2, "定期")),
                snapshots = emptyList(), quotes = emptyList(), fxRates = emptyList(),
            )
            awaitItem().assets.size shouldBe 2

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `没有新变化时不会有多余发射`() = runTest {
        val source = MutableStateFlow(PortfolioData.EMPTY)

        source.test {
            awaitItem()
            // StateFlow 去重：写入相同值不该再发一次
            source.value = PortfolioData.EMPTY
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }
}
