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
 * 在真实 SQLite 上验证 schema —— 光"能编译"证明不了 CHECK 约束真的生效、
 * 或者 seed 真的幂等。
 *
 * 用 JDBC driver 跑在 JVM 上。生产用的是 Android/Native driver，
 * 但 SQL 和约束是同一套。
 */
class DatabaseSchemaTest {

    private lateinit var driver: JdbcSqliteDriver

    private fun freshDb(): BoomssetDatabase {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        BoomssetDatabase.Schema.create(driver)
        return createDatabase(driver)
    }

    /** 绕过 SQLDelight 的类型安全 API 直接写 SQL，用来撞 CHECK 约束。 */
    private fun rawExecute(sql: String) {
        driver.execute(identifier = null, sql = sql, parameters = 0).value
    }

    @Test
    fun `schema 能创建且内置品种被写入`() {
        val db = freshDb()
        val subtypes = db.assetSubtypeQueries.selectAll().executeAsList()

        subtypes shouldHaveSize BUILT_IN_SUBTYPES.size
        subtypes.all { it.is_built_in } shouldBe true
        // 枚举 adapter 双向工作
        subtypes.first { it.name == "A股" }.asset_class shouldBe AssetClass.EQUITY
        subtypes.first { it.name == "A股" }.default_valuation_mode shouldBe ValuationMode.QUOTED
    }

    @Test
    fun `重复 seed 是幂等的`() {
        val db = freshDb()
        db.seedBuiltIns()
        db.seedBuiltIns()

        db.assetSubtypeQueries.selectAll().executeAsList() shouldHaveSize BUILT_IN_SUBTYPES.size
        db.targetAllocationQueries.selectAll().executeAsList() shouldHaveSize BUILT_IN_PRESETS.size
    }

    @Test
    fun `内置预设的比例之和都是一万基点`() {
        val db = freshDb()
        db.targetAllocationQueries.selectAll().executeAsList().forEach { allocation ->
            val sum = db.targetAllocationQueries.sumOfItems(allocation.id).executeAsOne()
            sum shouldBe TargetAllocation.TOTAL_BP.toLong()
        }
    }

    @Test
    fun `只有一套预设是生效的`() {
        val db = freshDb()
        db.targetAllocationQueries.selectAll().executeAsList()
            .count { it.is_active } shouldBe 1
    }

    // ---------- CHECK 约束：让「模式和字段不匹配」在数据库层面写不进去 ----------

    /**
     * 正向对照。没有这个测试，下面两个 assertFails 可能**因为错误的原因通过** ——
     * 比如 rawExecute 自己就是坏的、或者 SQL 写错了列名。
     * 这个测试证明同样路径下合法的 SQL 是能成功的。
     */
    @Test
    fun `对照组 合法的原始SQL能写入`() {
        val db = freshDb()
        val assetId = insertAsset(db)

        rawExecute(
            "INSERT INTO snapshot(asset_id, as_of, mode, value_minor, recorded_at) " +
                "VALUES ($assetId, 1, 'MANUAL', 100, 1)",
        )

        db.snapshotQueries.selectForAsset(assetId).executeAsList() shouldHaveSize 1
    }

    @Test
    fun `QUOTED 快照缺份额时写入失败`() {
        val db = freshDb()
        val assetId = insertAsset(db)

        // 手工构造一条不合法的记录：mode=QUOTED 但没有 quantity
        assertFails {
            rawExecute(
                "INSERT INTO snapshot(asset_id, as_of, mode, value_minor, recorded_at) " +
                    "VALUES ($assetId, 1, 'QUOTED', 100, 1)",
            )
        }
    }

    @Test
    fun `MANUAL 快照带份额时写入失败`() {
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
    fun `合法的两种快照都能写入且成本可选`() {
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
            // QUOTED 也能有成本 —— 这是 domain.md 里被表格写歪过的那条规则
            cost_basis_minor = 10_000_00,
            recorded_at = 2,
        )

        val all = db.snapshotQueries.selectForAsset(assetId).executeAsList()
        all shouldHaveSize 2
        all[0].mode shouldBe ValuationMode.MANUAL
        all[1].mode shouldBe ValuationMode.QUOTED
        all[1].cost_basis_minor shouldBe 10_000_00
    }

    // ---------- 结转查询 ----------

    @Test
    fun `取某时点前最近一条快照`() {
        val db = freshDb()
        val assetId = insertAsset(db)

        listOf(100L to 100_00L, 200L to 200_00L, 300L to 300_00L).forEach { (asOf, value) ->
            db.snapshotQueries.insertManual(assetId, asOf, value, null, asOf)
        }

        // T=250 时应当拿到 asOf=200 那条（结转规则：上次估值继续有效）
        val at250 = db.snapshotQueries.selectLatestAsOfPerAsset(250).executeAsList()
        at250 shouldHaveSize 1
        at250.single().value_minor shouldBe 200_00

        // T=50 时一条都没有 —— 该资产那时还不存在，不是值为 0
        db.snapshotQueries.selectLatestAsOfPerAsset(50).executeAsList() shouldHaveSize 0
    }

    @Test
    fun `同一时点有多条修正记录时取最后录入的那条`() {
        val db = freshDb()
        val assetId = insertAsset(db)

        db.snapshotQueries.insertManual(assetId, 100, 100_00, null, 1)
        // 修正历史：同一个 asOf 追加一条新的，而不是原地改
        db.snapshotQueries.insertManual(assetId, 100, 999_00, null, 2)

        val result = db.snapshotQueries.selectLatestAsOfPerAsset(100).executeAsList()
        result shouldHaveSize 1
        result.single().value_minor shouldBe 999_00
    }

    @Test
    fun `行情按天 upsert 同一天只留一条`() {
        val db = freshDb()

        db.quoteQueries.upsert("600519", "2026-07-28", 150_00, "CNY", 1)
        db.quoteQueries.upsert("600519", "2026-07-28", 151_00, "CNY", 2)
        db.quoteQueries.upsert("600519", "2026-07-29", 152_00, "CNY", 3)

        // 打开 App 刷新十次也只有一条当天记录
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
