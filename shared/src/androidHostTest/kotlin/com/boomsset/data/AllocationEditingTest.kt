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
 * 目标配置的编辑走真实 SQLite 验证 —— 光单测证明不了事务、唯一索引和
 * 「只能删自定义」这类约束真的生效。
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
    fun `切换生效配置后只有一套是 active`() = runTest {
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
    fun `保存目标比例后能读回`() = runTest {
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
    fun `不闭合的比例被拒绝写入`() = runTest {
        val (_, r) = repo()
        val allocation = r.observeAllocations().first().first()

        // 加起来只有 50%，存进去会让偏离度全错且不报错 —— 必须拒绝
        assertFails {
            r.saveAllocationTargets(
                allocation.id,
                mapOf(AssetClass.LIQUID to 3000, AssetClass.EQUITY to 2000),
            )
        }
    }

    @Test
    fun `保存时先清旧条目 不残留`() = runTest {
        val (_, r) = repo()
        val allocation = r.observeAllocations().first().first()

        // 只给两个大类，其余为 0
        r.saveAllocationTargets(
            allocation.id,
            mapOf(AssetClass.LIQUID to 5000, AssetClass.EQUITY to 5000),
        )

        val reloaded = r.observeAllocations().first().first { it.id == allocation.id }
        // 旧的 FIXED_INCOME / ALTERNATIVE / PROTECTION 条目必须没了，否则合计会超 100%
        reloaded.sumBp shouldBe TargetAllocation.TOTAL_BP
        reloaded.targetsBp.keys shouldBe setOf(AssetClass.LIQUID, AssetClass.EQUITY)
    }

    @Test
    fun `新建自定义配置`() = runTest {
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
    fun `内置配置删不掉 自定义可以删`() = runTest {
        val (_, r) = repo()
        val builtIn = r.observeAllocations().first().first { it.isBuiltIn }
        val customId = r.createAllocation("临时", mapOf(AssetClass.LIQUID to 10_000))

        r.deleteAllocation(builtIn.id)
        r.deleteAllocation(customId)

        val after = r.observeAllocations().first()
        // 内置还在，自定义没了
        after.any { it.id == builtIn.id } shouldBe true
        after.any { it.id == customId } shouldBe false
    }

    @Test
    fun `编辑目标比例会让数据流重新发射`() = runTest {
        // 回归测试：原来 observeActiveTarget 只监听 allocation 表，
        // 写 items 表不触发发射，配置页看不到改动
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
