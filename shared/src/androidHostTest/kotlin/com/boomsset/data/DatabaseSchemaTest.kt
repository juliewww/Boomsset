package com.boomsset.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.boomsset.db.BoomssetDatabase
import com.boomsset.domain.AssetClass
import com.boomsset.domain.TargetAllocation
import com.boomsset.domain.ValuationMode
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.test.assertFails

/**
 * Verifies the schema against real SQLite — merely "compiling" can't prove that CHECK
 * constraints actually take effect, or that seeding is truly idempotent.
 *
 * Runs on the JVM using the JDBC driver. Production uses the Android/Native driver,
 * but the SQL and constraints are the same set.
 */
class DatabaseSchemaTest {

    private lateinit var driver: JdbcSqliteDriver

    private fun freshDb(): BoomssetDatabase {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        BoomssetDatabase.Schema.create(driver)
        return createDatabase(driver)
    }

    /** Bypasses SQLDelight's type-safe API to write raw SQL directly, used to trigger CHECK constraints. */
    private fun rawExecute(sql: String) {
        driver.execute(identifier = null, sql = sql, parameters = 0).value
    }

    @Test
    fun `schema can be created and built-in subtypes are written`() {
        val db = freshDb()
        val subtypes = db.assetSubtypeQueries.selectAll().executeAsList()

        subtypes shouldHaveSize BUILT_IN_SUBTYPES.size
        subtypes.all { it.is_built_in } shouldBe true
        // The enum adapter works in both directions
        subtypes.first { it.name == "A股" }.asset_class shouldBe AssetClass.EQUITY
        subtypes.first { it.name == "A股" }.default_valuation_mode shouldBe ValuationMode.QUOTED
    }

    @Test
    fun `repeated seeding is idempotent`() {
        val db = freshDb()
        db.seedBuiltIns()
        db.seedBuiltIns()

        db.assetSubtypeQueries.selectAll().executeAsList() shouldHaveSize BUILT_IN_SUBTYPES.size
        db.targetAllocationQueries.selectAll().executeAsList() shouldHaveSize BUILT_IN_PRESETS.size
    }

    @Test
    fun `every built-in preset's ratios sum to ten thousand basis points`() {
        val db = freshDb()
        db.targetAllocationQueries.selectAll().executeAsList().forEach { allocation ->
            val sum = db.targetAllocationQueries.sumOfItems(allocation.id).executeAsOne()
            sum shouldBe TargetAllocation.TOTAL_BP.toLong()
        }
    }

    @Test
    fun `only one preset is active`() {
        val db = freshDb()
        db.targetAllocationQueries.selectAll().executeAsList()
            .count { it.is_active } shouldBe 1
    }

    // ---------- CHECK constraints: prevent "mode and fields mismatched" from being writable at the database level ----------

    /**
     * Positive control. Without this test, the two `assertFails` below could **pass for
     * the wrong reason** — e.g. `rawExecute` itself being broken, or the SQL having a
     * wrong column name. This test proves that legal SQL on the same path succeeds.
     */
    @Test
    fun `control group - legal raw SQL can be written`() {
        val db = freshDb()
        val assetId = insertAsset(db)

        rawExecute(
            "INSERT INTO snapshot(asset_id, as_of, mode, value_minor, recorded_at) " +
                "VALUES ($assetId, 1, 'MANUAL', 100, 1)",
        )

        db.snapshotQueries.selectForAsset(assetId).executeAsList() shouldHaveSize 1
    }

    @Test
    fun `writing fails for a QUOTED snapshot missing quantity`() {
        val db = freshDb()
        val assetId = insertAsset(db)

        // Hand-craft an illegal record: mode=QUOTED but no quantity
        assertFails {
            rawExecute(
                "INSERT INTO snapshot(asset_id, as_of, mode, value_minor, recorded_at) " +
                    "VALUES ($assetId, 1, 'QUOTED', 100, 1)",
            )
        }
    }

    @Test
    fun `writing fails for a MANUAL snapshot carrying a quantity`() {
        val db = freshDb()
        val assetId = insertAsset(db)

        assertFails {
            rawExecute(
                "INSERT INTO snapshot(asset_id, as_of, mode, value_minor, quantity_scaled, quote_symbol, recorded_at) " +
                    "VALUES ($assetId, 1, 'MANUAL', 100, 100000000, 'X', 1)",
            )
        }
    }

    @Test
    fun `both legal snapshot kinds can be written and cost basis is optional`() {
        val db = freshDb()
        val assetId = insertAsset(db)

        db.snapshotQueries.insertManual(
            asset_id = assetId,
            as_of = 1,
            value_minor = 500_00,
            cost_basis_minor = null,
            recorded_at = 1,
        )
        db.snapshotQueries.insertQuoted(
            asset_id = assetId,
            as_of = 2,
            quantity_scaled = 100_000_000,
            quote_symbol = "600519",
            // QUOTED can also have a cost basis — this is the rule that got mis-tabulated in domain.md
            cost_basis_minor = 10_000_00,
            recorded_at = 2,
        )

        val all = db.snapshotQueries.selectForAsset(assetId).executeAsList()
        all shouldHaveSize 2
        all[0].mode shouldBe ValuationMode.MANUAL
        all[1].mode shouldBe ValuationMode.QUOTED
        all[1].cost_basis_minor shouldBe 10_000_00
    }

    // ---------- Carry-forward query ----------

    @Test
    fun `takes the most recent snapshot before a given point in time`() {
        val db = freshDb()
        val assetId = insertAsset(db)

        listOf(100L to 100_00L, 200L to 200_00L, 300L to 300_00L).forEach { (asOf, value) ->
            db.snapshotQueries.insertManual(assetId, asOf, value, null, asOf)
        }

        // At T=250 it should get the asOf=200 entry (carry-forward rule: the last valuation stays in effect)
        val at250 = db.snapshotQueries.selectLatestAsOfPerAsset(250).executeAsList()
        at250 shouldHaveSize 1
        at250.single().value_minor shouldBe 200_00

        // At T=50 there are none at all — the asset didn't exist yet at that time, it's not a value of 0
        db.snapshotQueries.selectLatestAsOfPerAsset(50).executeAsList() shouldHaveSize 0
    }

    @Test
    fun `when the same point in time has multiple corrections, takes the last one recorded`() {
        val db = freshDb()
        val assetId = insertAsset(db)

        db.snapshotQueries.insertManual(assetId, 100, 100_00, null, 1)
        // Correcting history: append a new entry at the same asOf, instead of editing in place
        db.snapshotQueries.insertManual(assetId, 100, 999_00, null, 2)

        val result = db.snapshotQueries.selectLatestAsOfPerAsset(100).executeAsList()
        result shouldHaveSize 1
        result.single().value_minor shouldBe 999_00
    }

    @Test
    fun `quote upsert by day keeps only one entry per day`() {
        val db = freshDb()

        db.quoteQueries.upsert("600519", "2026-07-28", 150_00, "CNY", 1)
        db.quoteQueries.upsert("600519", "2026-07-28", 151_00, "CNY", 2)
        db.quoteQueries.upsert("600519", "2026-07-29", 152_00, "CNY", 3)

        // Refreshing the app ten times still leaves only one entry for that day
        db.quoteQueries.selectLatestOnOrBefore("600519", "2026-07-28")
            .executeAsOne().price_scaled shouldBe 151_00
        db.quoteQueries.selectLatestOnOrBefore("600519", "2026-07-30")
            .executeAsOne().price_scaled shouldBe 152_00
    }

    private fun insertAsset(db: BoomssetDatabase): Long {
        val subtypeId = db.assetSubtypeQueries.selectAll().executeAsList().first().id
        db.assetQueries.insert(
            name = "测试资产",
            asset_class = AssetClass.EQUITY,
            subtype_id = subtypeId,
            currency = "CNY",
            is_liability = false,
            include_in_allocation = true,
            default_valuation_mode = ValuationMode.MANUAL,
            default_quote_symbol = null,
            created_at = 1,
        )
        return db.assetQueries.lastInsertedId().executeAsOne()
    }
}
