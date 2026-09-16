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
 * Assembles [BoomssetDatabase].
 *
 * Enum columns are stored as TEXT by name via [EnumColumnAdapter] — readable, and adding a
 * new enum value needs no migration. The trade-off is that renaming an enum value makes old
 * data unreadable, so **do not rename the member names of [com.boomsset.domain.AssetClass]
 * or [com.boomsset.domain.ValuationMode]** — if you must, write a migration.
 *
 * `INTEGER AS Boolean` is natively supported by SQLDelight and needs no adapter.
 */
fun createDatabase(driverFactory: DatabaseDriverFactory): BoomssetDatabase =
    createDatabase(driverFactory.create())

/**
 * Assembles the database given a driver. Tests use the JDBC driver via this path; production
 * uses the one above.
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
 * Writes the built-in subtypes and built-in target allocation presets.
 *
 * **Idempotent**: subtypes use `INSERT OR IGNORE` + a unique index, and presets are looked
 * up by name first. So it's safe to call on every startup — no "is this the first launch"
 * state is needed.
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
                // On the first seed, mark "平衡" (Balanced) as active; the user can switch later
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
