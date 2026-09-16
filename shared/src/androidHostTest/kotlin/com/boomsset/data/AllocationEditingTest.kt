package com.boomsset.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.boomsset.db.BoomssetDatabase
import com.boomsset.domain.AssetClass
import com.boomsset.domain.TargetAllocation
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFails
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Editing a target allocation is verified against real SQLite — a plain unit test
 * can't prove that transactions, unique indexes, and constraints like "only custom
 * ones can be deleted" actually take effect.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AllocationEditingTest {

    private fun repo(): Pair<BoomssetDatabase, SqlDelightPortfolioRepository> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        BoomssetDatabase.Schema.create(driver)
        val db = createDatabase(driver)
        return db to SqlDelightPortfolioRepository(
            db = db,
            dispatcher = UnconfinedTestDispatcher(),
            clock = object : Clock {
                override fun now() = Instant.fromEpochMilliseconds(0)
            },
        )
    }

    @Test
    fun `only one allocation is active after switching the active one`() = runTest {
        val (_, r) = repo()
        val all = r.observeAllocations().first()
        all shouldHaveSize BUILT_IN_PRESETS.size

        val target = all.first { !it.isActive }
        r.setActiveAllocation(target.id)

        val after = r.observeAllocations().first()
        after.count { it.isActive } shouldBe 1
        after.first { it.isActive }.id shouldBe target.id
    }

    @Test
    fun `saved target ratios can be read back`() = runTest {
        val (_, r) = repo()
        val allocation = r.observeAllocations().first().first()

        val targets = mapOf(
            AssetClass.LIQUID to 2000,
            AssetClass.FIXED_INCOME to 2000,
            AssetClass.EQUITY to 4000,
            AssetClass.ALTERNATIVE to 1000,
            AssetClass.PROTECTION to 1000,
        )
        r.saveAllocationTargets(allocation.id, targets)

        val reloaded = r.observeAllocations().first().first { it.id == allocation.id }
        reloaded.targetsBp shouldBe targets
        reloaded.isValid shouldBe true
    }

    @Test
    fun `ratios that do not sum to 100 percent are rejected on write`() = runTest {
        val (_, r) = repo()
        val allocation = r.observeAllocations().first().first()

        // Only sums to 50% — writing it in would make every deviation wrong without any error — must be rejected
        assertFails {
            r.saveAllocationTargets(
                allocation.id,
                mapOf(AssetClass.LIQUID to 3000, AssetClass.EQUITY to 2000),
            )
        }
    }

    @Test
    fun `old entries are cleared before saving, leaving no leftovers`() = runTest {
        val (_, r) = repo()
        val allocation = r.observeAllocations().first().first()

        // Only two asset classes given, the rest should be 0
        r.saveAllocationTargets(
            allocation.id,
            mapOf(AssetClass.LIQUID to 5000, AssetClass.EQUITY to 5000),
        )

        val reloaded = r.observeAllocations().first().first { it.id == allocation.id }
        // The old FIXED_INCOME / ALTERNATIVE / PROTECTION entries must be gone, otherwise the sum would exceed 100%
        reloaded.sumBp shouldBe TargetAllocation.TOTAL_BP
        reloaded.targetsBp.keys shouldBe setOf(AssetClass.LIQUID, AssetClass.EQUITY)
    }

    @Test
    fun `create a custom allocation`() = runTest {
        val (_, r) = repo()
        val id = r.createAllocation(
            "我的",
            mapOf(
                AssetClass.LIQUID to 1000,
                AssetClass.EQUITY to 9000,
            ),
        )

        val created = r.observeAllocations().first().first { it.id == id }
        created.name shouldBe "我的"
        created.isBuiltIn shouldBe false
        created.sumBp shouldBe TargetAllocation.TOTAL_BP
    }

    @Test
    fun `built-in allocations cannot be deleted, custom ones can`() = runTest {
        val (_, r) = repo()
        val builtIn = r.observeAllocations().first().first { it.isBuiltIn }
        val customId = r.createAllocation("临时", mapOf(AssetClass.LIQUID to 10_000))

        r.deleteAllocation(builtIn.id)
        r.deleteAllocation(customId)

        val after = r.observeAllocations().first()
        // Built-in is still there, custom is gone
        after.any { it.id == builtIn.id } shouldBe true
        after.any { it.id == customId } shouldBe false
    }

    @Test
    fun `editing target ratios makes the data flow re-emit`() = runTest {
        // Regression test: observeActiveTarget used to only listen to the allocation
        // table; writing to the items table didn't trigger an emission, so the
        // allocation page couldn't see the change
        val (_, r) = repo()
        val before = r.observeActiveTarget().first()!!

        r.saveAllocationTargets(
            before.id,
            mapOf(AssetClass.LIQUID to 10_000),
        )

        val after = r.observeActiveTarget().first()!!
        after.targetsBp shouldBe mapOf(AssetClass.LIQUID to 10_000)
    }
}
