package com.boomsset.data

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import com.boomsset.db.BoomssetDatabase
import com.boomsset.domain.AssetClass
import com.boomsset.domain.TargetAllocation
import com.boomsset.domain.ValuationMode
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.test.assertFails

/**
 * Verifies SQLDelight's NativeSqliteDriver on a **real iOS simulator**.
 *
 * Why this must be written separately: `DatabaseSchemaTest` and the other two database
 * tests all live in `androidHostTest`, using the JVM's JDBC driver. They prove that the
 * SQL and constraints hold on SQLite, but **never touch the iOS driver at all**.
 * docs/stack.md had always flagged "SQLDelight running on real iOS" as unverified —
 * this test file is here to clear that item off the list.
 *
 * The **CHECK constraints** are especially worth verifying: Android and iOS use
 * different SQLite builds, and whether a constraint actually blocks a write is
 * runtime behavior, not something compile time can guarantee.
 */
class NativeDatabaseTest {

    private fun freshDriver(): SqlDriver = NativeSqliteDriver(
        schema = BoomssetDatabase.Schema,
        name = "boomsset-test.db",
        onConfiguration = { it.copy(inMemory = true) },
    )

    @Test
    fun `native driver can create the schema and write built-in data`() {
        val db = createDatabase(freshDriver())

        db.assetSubtypeQueries.selectAll().executeAsList() shouldHaveSize BUILT_IN_SUBTYPES.size
        db.targetAllocationQueries.selectAll().executeAsList() shouldHaveSize BUILT_IN_PRESETS.size
    }

    @Test
    fun `enum adapter works in both directions on native`() {
        // EnumColumnAdapter stores as TEXT by name. Native has no reflection, so this genuinely needs verifying.
        val db = createDatabase(freshDriver())
        val aShare = db.assetSubtypeQueries.selectAll().executeAsList().first { it.name == "A股" }

        aShare.asset_class shouldBe AssetClass.EQUITY
        aShare.default_valuation_mode shouldBe ValuationMode.QUOTED
    }

    @Test
    fun `CHECK constraints take effect on iOS's SQLite the same way`() {
        // Android and iOS are different SQLite builds; whether a constraint blocks a write is runtime behavior
        val driver = freshDriver()
        val db = createDatabase(driver)
        val subtypeId = db.assetSubtypeQueries.selectAll().executeAsList().first().id
        db.assetQueries.insert(
            name = "测试", asset_class = AssetClass.EQUITY, subtype_id = subtypeId,
            currency = "CNY", is_liability = false, include_in_allocation = true,
            default_valuation_mode = ValuationMode.MANUAL, default_quote_symbol = null,
            created_at = 1,
        )
        val assetId = db.assetQueries.lastInsertedId().executeAsOne()

        // Control group: legal raw SQL can be written, proving this path itself works
        driver.execute(
            null,
            "INSERT INTO snapshot(asset_id, as_of, mode, value_minor, recorded_at) " +
                "VALUES ($assetId, 1, 'MANUAL', 100, 1)",
            0,
        ).value
        db.snapshotQueries.selectForAsset(assetId).executeAsList() shouldHaveSize 1

        // mode=QUOTED but no quantity — must be blocked by the CHECK constraint
        assertFails {
            driver.execute(
                null,
                "INSERT INTO snapshot(asset_id, as_of, mode, value_minor, recorded_at) " +
                    "VALUES ($assetId, 2, 'QUOTED', 100, 2)",
                0,
            ).value
        }
    }

    @Test
    fun `transactions and reads-writes work normally on native`() {
        val db = createDatabase(freshDriver())
        val allocation = db.targetAllocationQueries.selectAll().executeAsList().first()

        db.transaction {
            db.targetAllocationQueries.deleteItems(allocation.id)
            db.targetAllocationQueries.upsertItem(allocation.id, AssetClass.LIQUID, 10_000)
        }

        db.targetAllocationQueries.sumOfItems(allocation.id).executeAsOne() shouldBe
            TargetAllocation.TOTAL_BP.toLong()
    }

    @Test
    fun `upsert by day likewise keeps only one entry on native`() {
        val db = createDatabase(freshDriver())

        db.quoteQueries.upsert("sh600519", "2026-07-29", 133_405_000_000, "CNY", 1)
        db.quoteQueries.upsert("sh600519", "2026-07-29", 133_500_000_000, "CNY", 2)

        db.quoteQueries.selectAllQuotes().executeAsList() shouldHaveSize 1
        db.quoteQueries.selectLatestOnOrBefore("sh600519", "2026-07-29")
            .executeAsOne().price_scaled shouldBe 133_500_000_000
    }
}
