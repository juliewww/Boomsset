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
 * 在**真实 iOS 模拟器**上验证 SQLDelight 的 NativeSqliteDriver。
 *
 * 为什么必须单独写一份：`DatabaseSchemaTest` 等三个数据库测试都在 `androidHostTest`，
 * 用的是 JVM 的 JDBC driver。它们证明了 SQL 和约束在 SQLite 上成立，但**完全没有触碰
 * iOS 的 driver**。docs/stack.md 一直把「SQLDelight 在真实 iOS 上的运行」标为未验证 ——
 * 这份测试就是来清掉那一项的。
 *
 * 尤其值得验的是 **CHECK 约束**：Android 和 iOS 用的是不同的 SQLite 构建，
 * 约束能不能拦住写入是运行时行为，不是编译期能保证的事。
 */
class NativeDatabaseTest {

    private fun freshDriver(): SqlDriver = NativeSqliteDriver(
        schema = BoomssetDatabase.Schema,
        name = "boomsset-test.db",
        onConfiguration = { it.copy(inMemory = true) },
    )

    @Test
    fun `native driver 能创建 schema 并写入内置数据`() {
        val db = createDatabase(freshDriver())

        db.assetSubtypeQueries.selectAll().executeAsList() shouldHaveSize BUILT_IN_SUBTYPES.size
        db.targetAllocationQueries.selectAll().executeAsList() shouldHaveSize BUILT_IN_PRESETS.size
    }

    @Test
    fun `枚举 adapter 在 native 上双向工作`() {
        // EnumColumnAdapter 按名字存 TEXT。Native 没有反射，这条是真的要验。
        val db = createDatabase(freshDriver())
        val aShare = db.assetSubtypeQueries.selectAll().executeAsList().first { it.name == "A股" }

        aShare.asset_class shouldBe AssetClass.EQUITY
        aShare.default_valuation_mode shouldBe ValuationMode.QUOTED
    }

    @Test
    fun `CHECK 约束在 iOS 的 SQLite 上同样生效`() {
        // Android 和 iOS 是不同的 SQLite 构建，约束是否拦得住属于运行时行为
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

        // 对照组：合法的原始 SQL 能写入，证明这条路径本身是通的
        driver.execute(
            null,
            "INSERT INTO snapshot(asset_id, as_of, mode, value_minor, recorded_at) " +
                "VALUES ($assetId, 1, 'MANUAL', 100, 1)",
            0,
        ).value
        db.snapshotQueries.selectForAsset(assetId).executeAsList() shouldHaveSize 1

        // mode=QUOTED 但没有 quantity —— 必须被 CHECK 拦掉
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
    fun `事务和读写在 native 上正常`() {
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
    fun `按天 upsert 在 native 上同样只留一条`() {
        val db = createDatabase(freshDriver())

        db.quoteQueries.upsert("sh600519", "2026-07-29", 133_405_000_000, "CNY", 1)
        db.quoteQueries.upsert("sh600519", "2026-07-29", 133_500_000_000, "CNY", 2)

        db.quoteQueries.selectAllQuotes().executeAsList() shouldHaveSize 1
        db.quoteQueries.selectLatestOnOrBefore("sh600519", "2026-07-29")
            .executeAsOne().price_scaled shouldBe 133_500_000_000
    }
}
