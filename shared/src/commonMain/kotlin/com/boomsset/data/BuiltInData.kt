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

/** 内置品种的定义。seed 用 INSERT OR IGNORE，可以反复执行。 */
data class BuiltInSubtype(
    val name: String,
    val assetClass: AssetClass,
    val defaultValuationMode: ValuationMode,
)

/**
 * 内置品种清单。用户可以另外自定义添加。
 *
 * 内置项**不允许删除**（历史资产会指向空品种），只能隐藏。
 */
val BUILT_IN_SUBTYPES: List<BuiltInSubtype> = listOf(
    // 流动资金
    BuiltInSubtype("现金", LIQUID, MANUAL),
    BuiltInSubtype("微信钱包", LIQUID, MANUAL),
    BuiltInSubtype("支付宝", LIQUID, MANUAL),
    BuiltInSubtype("银行活期", LIQUID, MANUAL),
    BuiltInSubtype("货币基金", LIQUID, MANUAL),

    // 固定收益
    BuiltInSubtype("银行定期", FIXED_INCOME, MANUAL),
    BuiltInSubtype("银行理财", FIXED_INCOME, MANUAL),
    BuiltInSubtype("国债", FIXED_INCOME, MANUAL),
    BuiltInSubtype("债券基金", FIXED_INCOME, QUOTED),

    // 权益类
    BuiltInSubtype("A股", EQUITY, QUOTED),
    BuiltInSubtype("港股", EQUITY, QUOTED),
    BuiltInSubtype("美股", EQUITY, QUOTED),
    BuiltInSubtype("股票基金", EQUITY, QUOTED),
    BuiltInSubtype("指数基金", EQUITY, QUOTED),
    BuiltInSubtype("公司期权", EQUITY, MANUAL),

    // 另类实物
    BuiltInSubtype("房产", ALTERNATIVE, MANUAL),
    BuiltInSubtype("黄金", ALTERNATIVE, QUOTED),
    BuiltInSubtype("加密货币", ALTERNATIVE, QUOTED),
    BuiltInSubtype("车辆", ALTERNATIVE, MANUAL),

    // 保障类（按现金价值计值）
    BuiltInSubtype("年金险", PROTECTION, MANUAL),
    BuiltInSubtype("增额终身寿", PROTECTION, MANUAL),

    // 负债侧。每条负债都必须有 assetClass 用于归属抵扣 ——
    // 无抵押债务归到你会用来偿还它的那类。
    BuiltInSubtype("房贷", ALTERNATIVE, MANUAL),
    BuiltInSubtype("车贷", ALTERNATIVE, MANUAL),
    BuiltInSubtype("信用卡", LIQUID, MANUAL),
    BuiltInSubtype("消费贷", LIQUID, MANUAL),
)

/**
 * 哪些内置品种是负债。
 *
 * **刻意不落到数据库里。** `subtype` 表没有这一列，而加列需要 migration ——
 * 现在已经有真机上的真实数据，为了一个"复选框默认值"去改 schema 不值得。
 * 这里只用来给添加资产时的负债开关**预设初值**，用户随时能改，
 * 判断错了最坏的后果是多点一下。
 *
 * 按名字匹配。用户自建的品种如果也叫「房贷」会被一并预设为负债 ——
 * 那恰好也是对的。
 */
val LIABILITY_SUBTYPE_NAMES: Set<String> = setOf("房贷", "车贷", "信用卡", "消费贷")

/**
 * 内置目标配置预设。
 *
 * ⚠️ **这些数值是行业常见的起点，不是权威处方**，必须允许用户编辑。
 * UI 上请把它们呈现为**通用模板**而非针对该用户的推荐 —— 一个 App 告诉用户
 * "你应该配置 30% 权益"在部分司法辖区可能被认定为投资建议。避免"我们建议你…"的措辞。
 *
 * 另外注意：因为配置分母是全部净资产（含自住房），有房的用户会看到 ALTERNATIVE
 * 远超这里的目标值。那不是 bug，见 docs/domain.md。
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
    // 每套预设的比例之和必须是 10000，写错了在这里就炸，不要等到 UI 上比例不闭合
    presets.forEach { preset ->
        val sum = preset.targetsBp.values.sum()
        require(sum == TargetAllocation.TOTAL_BP) {
            "预设「${preset.name}」的比例之和是 $sum，应为 ${TargetAllocation.TOTAL_BP}"
        }
    }
}
