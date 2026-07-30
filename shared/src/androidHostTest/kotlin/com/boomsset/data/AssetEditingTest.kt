package com.boomsset.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.boomsset.db.BoomssetDatabase
import com.boomsset.domain.AssetClass
import com.boomsset.domain.AssetEditPolicy
import com.boomsset.domain.Money
import com.boomsset.domain.ValuationMode
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Clock
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class AssetEditingTest {

    private var nowMs = 1_000L

    private fun repo(): SqlDelightPortfolioRepository {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        BoomssetDatabase.Schema.create(driver)
        return SqlDelightPortfolioRepository(
            db = createDatabase(driver),
            dispatcher = UnconfinedTestDispatcher(),
            clock = object : Clock {
                override fun now() = Instant.fromEpochMilliseconds(nowMs)
            },
        )
    }

    private suspend fun SqlDelightPortfolioRepository.newAsset(): Long {
        val subtypeId = observeSubtypes().first().first { it.assetClass == AssetClass.LIQUID }.id
        return createAsset(
            name = "原名",
            assetClass = AssetClass.LIQUID,
            subtypeId = subtypeId,
            currency = "CNY",
            isLiability = false,
            includeInAllocation = true,
            mode = ValuationMode.MANUAL,
            quoteSymbol = null,
            initialValue = Money(100_00),
            initialQuantity = null,
            costBasis = null,
        )
    }

    @Test
    fun `改名和改大类会落库`() = runTest {
        val r = repo()
        val id = r.newAsset()
        val subtypeId = r.observeSubtypes().first().first { it.assetClass == AssetClass.EQUITY }.id

        r.updateAssetMeta(
            assetId = id,
            name = "新名字",
            assetClass = AssetClass.EQUITY,
            subtypeId = subtypeId,
            currency = "CNY",
            includeInAllocation = false,
            defaultValuationMode = ValuationMode.MANUAL,
            defaultQuoteSymbol = null,
        )

        val asset = r.observePortfolio().first().assets.first { it.id == id }
        asset.name shouldBe "新名字"
        asset.assetClass shouldBe AssetClass.EQUITY
        asset.includeInAllocation shouldBe false
    }

    @Test
    fun `改元信息不动任何快照`() = runTest {
        // 这是这个功能的核心保证：改归类不改金额
        val r = repo()
        val id = r.newAsset()
        val before = r.observePortfolio().first().snapshots

        r.updateAssetMeta(
            assetId = id,
            name = "改了",
            assetClass = AssetClass.EQUITY,
            subtypeId = r.observeSubtypes().first().first { it.assetClass == AssetClass.EQUITY }.id,
            currency = "CNY",
            includeInAllocation = true,
            defaultValuationMode = ValuationMode.MANUAL,
            defaultQuoteSymbol = null,
        )

        r.observePortfolio().first().snapshots shouldBe before
    }

    // ---------- 编辑策略 ----------

    @Test
    fun `只有一条快照时币种可改`() {
        AssetEditPolicy.canChangeCurrencyAndLiability(1) shouldBe true
        AssetEditPolicy.canChangeCurrencyAndLiability(0) shouldBe true
    }

    @Test
    fun `有历史后币种不可改`() {
        // 改币种会让全部历史金额被当成另一种货币重新折算 —— 数字不变含义全变
        AssetEditPolicy.canChangeCurrencyAndLiability(2) shouldBe false
        AssetEditPolicy.canChangeCurrencyAndLiability(50) shouldBe false
    }

    @Test
    fun `锁住时给出可读的原因`() {
        val reason = AssetEditPolicy.lockedReason(3)
        (reason.contains("3 条") && reason.contains("重新解读")) shouldBe true
    }

    // ---------- 归档 / 取消归档 ----------

    @Test
    fun `取消归档只清标记 不删归零快照`() = runTest {
        val r = repo()
        val id = r.newAsset()
        nowMs = 2_000
        r.archiveAsset(id)

        val afterArchive = r.observePortfolio().first()
        afterArchive.assets.first { it.id == id }.isArchived shouldBe true
        // 归档追加了一条 0 值快照
        afterArchive.snapshots.count { it.assetId == id } shouldBe 2

        nowMs = 3_000
        r.unarchiveAsset(id)

        val afterUnarchive = r.observePortfolio().first()
        afterUnarchive.assets.first { it.id == id }.isArchived shouldBe false
        // 那条 0 值快照仍在 —— 它是真实记录，不该被撤销。
        // 所以取消归档后资产会显示 0，需要用户再更新一次估值。
        afterUnarchive.snapshots.count { it.assetId == id } shouldBe 2
    }

    // ---------- 自定义品种 ----------

    @Test
    fun `新增自定义品种`() = runTest {
        val r = repo()
        val before = r.observeSubtypes().first().size

        val id = r.createSubtype("私募基金", AssetClass.EQUITY, ValuationMode.MANUAL)

        val after = r.observeSubtypes().first()
        after.size shouldBe before + 1
        val created = after.first { it.id == id }
        created.name shouldBe "私募基金"
        created.assetClass shouldBe AssetClass.EQUITY
        // 自定义的不是内置 —— 内置的删不掉，自定义的可以
        created.isBuiltIn shouldBe false
    }
}
