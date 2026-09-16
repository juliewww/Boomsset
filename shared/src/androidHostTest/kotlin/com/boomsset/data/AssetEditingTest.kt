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
    fun `renaming and reclassifying persist to the database`() = runTest {
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
    fun `editing metadata does not touch any snapshot`() = runTest {
        // This is the core guarantee of this feature: reclassifying does not change amounts
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

    // ---------- Edit policy ----------

    @Test
    fun `currency can be changed when there is only one snapshot`() {
        AssetEditPolicy.canChangeCurrencyAndLiability(1) shouldBe true
        AssetEditPolicy.canChangeCurrencyAndLiability(0) shouldBe true
    }

    @Test
    fun `currency cannot be changed once there is history`() {
        // Changing currency would reinterpret every historical amount as a different currency — the numbers stay the same but the meaning changes entirely
        AssetEditPolicy.canChangeCurrencyAndLiability(2) shouldBe false
        AssetEditPolicy.canChangeCurrencyAndLiability(50) shouldBe false
    }

    @Test
    fun `gives a readable reason when locked`() {
        val reason = AssetEditPolicy.lockedReason(3)
        (reason.contains("3 条") && reason.contains("重新解读")) shouldBe true
    }

    // ---------- Archive / unarchive ----------

    @Test
    fun `unarchiving only clears the flag, does not delete the zeroing snapshot`() = runTest {
        val r = repo()
        val id = r.newAsset()
        nowMs = 2_000
        r.archiveAsset(id)

        val afterArchive = r.observePortfolio().first()
        afterArchive.assets.first { it.id == id }.isArchived shouldBe true
        // Archiving appended a zero-value snapshot
        afterArchive.snapshots.count { it.assetId == id } shouldBe 2

        nowMs = 3_000
        r.unarchiveAsset(id)

        val afterUnarchive = r.observePortfolio().first()
        afterUnarchive.assets.first { it.id == id }.isArchived shouldBe false
        // That zero-value snapshot is still there — it's a real record and shouldn't be
        // undone. So after unarchiving, the asset shows 0 until the user records a new
        // valuation.
        afterUnarchive.snapshots.count { it.assetId == id } shouldBe 2
    }

    // ---------- Custom subtypes ----------

    @Test
    fun `add a custom subtype`() = runTest {
        val r = repo()
        val before = r.observeSubtypes().first().size

        val id = r.createSubtype("私募基金", AssetClass.EQUITY, ValuationMode.MANUAL)

        val after = r.observeSubtypes().first()
        after.size shouldBe before + 1
        val created = after.first { it.id == id }
        created.name shouldBe "私募基金"
        created.assetClass shouldBe AssetClass.EQUITY
        // Custom is not built-in — built-in can't be deleted, custom can
        created.isBuiltIn shouldBe false
    }
}
