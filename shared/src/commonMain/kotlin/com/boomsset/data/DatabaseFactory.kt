package com.boomsset.data

import app.cash.sqldelight.EnumColumnAdapter
import com.boomsset.db.Asset
import com.boomsset.db.Asset_subtype
import com.boomsset.db.BoomssetDatabase
import com.boomsset.db.Snapshot
import com.boomsset.db.Target_allocation_item
import com.boomsset.domain.TargetAllocation
import kotlin.time.Clock

/**
 * 组装 [BoomssetDatabase]。
 *
 * 枚举列用 [EnumColumnAdapter] 按名字存 TEXT —— 可读、且加新枚举值不需要迁移。
 * 代价是重命名枚举值会读不出旧数据，所以**不要重命名 [com.boomsset.domain.AssetClass]
 * 和 [com.boomsset.domain.ValuationMode] 的成员名**，要改就得写 migration。
 *
 * `INTEGER AS Boolean` 是 SQLDelight 内建支持的，不需要 adapter。
 */
fun createDatabase(driverFactory: DatabaseDriverFactory): BoomssetDatabase =
    createDatabase(driverFactory.create())

/**
 * 给定 driver 组装数据库。测试用 JDBC driver 走这条，生产走上面那条。
 */
internal fun createDatabase(driver: app.cash.sqldelight.db.SqlDriver): BoomssetDatabase {
    val database = BoomssetDatabase(
        driver = driver,
        assetAdapter = Asset.Adapter(
            asset_classAdapter = EnumColumnAdapter(),
            default_valuation_modeAdapter = EnumColumnAdapter(),
        ),
        asset_subtypeAdapter = Asset_subtype.Adapter(
            asset_classAdapter = EnumColumnAdapter(),
            default_valuation_modeAdapter = EnumColumnAdapter(),
        ),
        snapshotAdapter = Snapshot.Adapter(
            modeAdapter = EnumColumnAdapter(),
        ),
        target_allocation_itemAdapter = Target_allocation_item.Adapter(
            asset_classAdapter = EnumColumnAdapter(),
        ),
    )
    database.seedBuiltIns()
    return database
}

/**
 * 写入内置品种和内置目标配置预设。
 *
 * **幂等**：品种用 `INSERT OR IGNORE` + 唯一索引，预设先查名字。所以每次启动调用都安全，
 * 不需要"是否首次启动"这种状态。
 */
internal fun BoomssetDatabase.seedBuiltIns() {
    transaction {
        BUILT_IN_SUBTYPES.forEach { subtype ->
            assetSubtypeQueries.insertBuiltIn(
                name = subtype.name,
                asset_class = subtype.assetClass,
                default_valuation_mode = subtype.defaultValuationMode,
            )
        }

        val existing = targetAllocationQueries.selectAll().executeAsList()
            .filter { it.is_built_in }
            .map { it.name }
            .toSet()

        val now = Clock.System.now().toEpochMilliseconds()

        BUILT_IN_PRESETS.forEachIndexed { index, preset ->
            if (preset.name in existing) return@forEachIndexed

            targetAllocationQueries.insertAllocation(
                name = preset.name,
                is_built_in = true,
                // 首次 seed 时把「平衡」设为生效，用户之后可切换
                is_active = existing.isEmpty() && preset.name == DEFAULT_ACTIVE_PRESET,
                created_at = now,
            )
            val allocationId = targetAllocationQueries.lastInsertedId().executeAsOne()

            preset.targetsBp.forEach { (assetClass, bp) ->
                targetAllocationQueries.upsertItem(
                    allocation_id = allocationId,
                    asset_class = assetClass,
                    target_percent_bp = bp.toLong(),
                )
            }

            val sum = targetAllocationQueries.sumOfItems(allocationId).executeAsOne()
            check(sum == TargetAllocation.TOTAL_BP.toLong()) {
                "预设「${preset.name}」写入后比例之和是 $sum，应为 ${TargetAllocation.TOTAL_BP}"
            }
        }
    }
}

private const val DEFAULT_ACTIVE_PRESET = "平衡"
