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
 * Tests the data flow using Turbine.
 *
 * This test suite has a secondary purpose: **making Turbine actually get executed once**.
 * It used to be a declared-but-never-imported dependency — docs/stack.md notes a risk
 * that "Turbine 1.2.1's iOS klib was built against Kotlin stdlib 2.1.21, but we're on 2.4.10",
 * yet no test used it, so that risk was never actually verified (the linker drops unreferenced
 * symbols, so even "it compiles" proves nothing). Running this test on iOS clears that risk.
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
    fun `the flow emits each change in order`() = runTest {
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
    fun `no extra emission happens without an actual change`() = runTest {
        val source = MutableStateFlow(PortfolioData.EMPTY)

        source.test {
            awaitItem()
            // StateFlow deduplicates: writing the same value again shouldn't emit again
            source.value = PortfolioData.EMPTY
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }
}
