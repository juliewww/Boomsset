package com.boomsset.data

import com.boomsset.domain.AssetClass
import com.boomsset.domain.AssetClass.ALTERNATIVE
import com.boomsset.domain.AssetClass.EQUITY
import com.boomsset.domain.AssetClass.FIXED_INCOME
import com.boomsset.domain.AssetClass.LIQUID
import com.boomsset.domain.AssetClass.PROTECTION
import com.boomsset.domain.TargetAllocation
import com.boomsset.domain.ValuationMode
import com.boomsset.domain.ValuationMode.MANUAL
import com.boomsset.domain.ValuationMode.QUOTED

/** Definition of a built-in subtype. Seeding uses INSERT OR IGNORE, so it can run repeatedly. */
data class BuiltInSubtype(
    val name: String,
    val assetClass: AssetClass,
    val defaultValuationMode: ValuationMode,
)

/**
 * List of built-in subtypes. Users can additionally add their own custom ones.
 *
 * Built-in entries **cannot be deleted** (historical assets would end up pointing at a
 * nonexistent subtype) — they can only be hidden.
 */
val BUILT_IN_SUBTYPES: List<BuiltInSubtype> = listOf(
    // Liquid funds
    BuiltInSubtype("现金", LIQUID, MANUAL),
    BuiltInSubtype("微信钱包", LIQUID, MANUAL),
    BuiltInSubtype("支付宝", LIQUID, MANUAL),
    BuiltInSubtype("银行活期", LIQUID, MANUAL),
    BuiltInSubtype("货币基金", LIQUID, MANUAL),

    // Fixed income
    BuiltInSubtype("银行定期", FIXED_INCOME, MANUAL),
    BuiltInSubtype("银行理财", FIXED_INCOME, MANUAL),
    BuiltInSubtype("国债", FIXED_INCOME, MANUAL),
    BuiltInSubtype("债券基金", FIXED_INCOME, QUOTED),

    // Equity
    BuiltInSubtype("A股", EQUITY, QUOTED),
    BuiltInSubtype("港股", EQUITY, QUOTED),
    BuiltInSubtype("美股", EQUITY, QUOTED),
    BuiltInSubtype("股票基金", EQUITY, QUOTED),
    BuiltInSubtype("指数基金", EQUITY, QUOTED),
    BuiltInSubtype("公司期权", EQUITY, MANUAL),

    // Alternative / physical assets
    BuiltInSubtype("房产", ALTERNATIVE, MANUAL),
    BuiltInSubtype("黄金", ALTERNATIVE, QUOTED),
    BuiltInSubtype("加密货币", ALTERNATIVE, QUOTED),
    BuiltInSubtype("车辆", ALTERNATIVE, MANUAL),

    // Protection (valued at cash value)
    BuiltInSubtype("年金险", PROTECTION, MANUAL),
    BuiltInSubtype("增额终身寿", PROTECTION, MANUAL),

    // Liability side. Every liability must have an assetClass for attribution/offsetting —
    // unsecured debt is attributed to the class you'd use to pay it off.
    BuiltInSubtype("房贷", ALTERNATIVE, MANUAL),
    BuiltInSubtype("车贷", ALTERNATIVE, MANUAL),
    BuiltInSubtype("信用卡", LIQUID, MANUAL),
    BuiltInSubtype("消费贷", LIQUID, MANUAL),
)

/**
 * Which built-in subtypes are liabilities.
 *
 * **Deliberately not persisted to the database.** The `subtype` table has no such column,
 * and adding one would require a migration — there's already real data on real devices,
 * and changing the schema just for a "checkbox default value" isn't worth it.
 * This is only used to **pre-set the initial value** of the liability toggle when adding
 * an asset; the user can change it at any time, so the worst consequence of getting it
 * wrong is one extra tap.
 *
 * Matched by name. If a user creates their own subtype also named "房贷" (mortgage), it
 * will likewise be pre-set as a liability — which happens to be correct too.
 */
val LIABILITY_SUBTYPE_NAMES: Set<String> = setOf("房贷", "车贷", "信用卡", "消费贷")

/**
 * Built-in target allocation presets.
 *
 * ⚠️ **These figures are common industry starting points, not authoritative prescriptions**,
 * and users must be able to edit them. In the UI, present them as **generic templates**
 * rather than recommendations tailored to the user — an app telling a user "you should
 * allocate 30% to equities" could be construed as investment advice in some jurisdictions.
 * Avoid phrasing like "we recommend...".
 *
 * Also note: since the allocation denominator is total net assets (including a primary
 * residence), users who own a home will see ALTERNATIVE far exceed the target value here.
 * That's not a bug — see docs/domain.md.
 */
data class AllocationPreset(val name: String, val targetsBp: Map<AssetClass, Int>)

val BUILT_IN_PRESETS: List<AllocationPreset> = listOf(
    AllocationPreset(
        "稳健",
        mapOf(LIQUID to 1000, FIXED_INCOME to 5500, EQUITY to 2000, ALTERNATIVE to 500, PROTECTION to 1000),
    ),
    AllocationPreset(
        "平衡",
        mapOf(LIQUID to 1000, FIXED_INCOME to 3500, EQUITY to 4000, ALTERNATIVE to 500, PROTECTION to 1000),
    ),
    AllocationPreset(
        "激进",
        mapOf(LIQUID to 500, FIXED_INCOME to 1500, EQUITY to 6500, ALTERNATIVE to 1000, PROTECTION to 500),
    ),
).also { presets ->
    // Each preset's percentages must sum to 10000; a mistake here should blow up immediately,
    // not surface later as an unclosed allocation in the UI
    presets.forEach { preset ->
        val sum = preset.targetsBp.values.sum()
        require(sum == TargetAllocation.TOTAL_BP) {
            "预设「${preset.name}」的比例之和是 $sum，应为 ${TargetAllocation.TOTAL_BP}"
        }
    }
}
