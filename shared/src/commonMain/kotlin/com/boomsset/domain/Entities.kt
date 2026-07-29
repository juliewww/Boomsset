package com.boomsset.domain

import kotlin.time.Instant

/** 品种（分类第二层）。服务记账归类，可自定义扩展。 */
data class AssetSubtype(
    val id: Long,
    val name: String,
    val assetClass: AssetClass,
    val defaultValuationMode: ValuationMode,
    val isBuiltIn: Boolean,
    val hidden: Boolean = false,
)

/**
 * 一项资产或负债。
 *
 * [defaultValuationMode] / [defaultQuoteSymbol] 只是**新快照的默认值**，
 * 估值一律看 [Snapshot.mode]。
 */
data class Asset(
    val id: Long,
    val name: String,
    val assetClass: AssetClass,
    val subtypeId: Long,
    val currency: String,
    val isLiability: Boolean = false,
    /** 是否计入配置比例。关掉它是排除自住房的低成本手段。 */
    val includeInAllocation: Boolean = true,
    val defaultValuationMode: ValuationMode,
    val defaultQuoteSymbol: String? = null,
    val archivedAt: Instant? = null,
) {
    val isArchived: Boolean get() = archivedAt != null
}

/**
 * 某时点的完整持仓状态。**不可变、只追加**，修正历史是追加新记录。
 *
 * 用 sealed 而不是「一个类带一堆可空字段」，这样「QUOTED 的快照没有份额」
 * 在类型层面就构造不出来 —— 和 schema 里那条 CHECK 约束是同一个意图的两层防护。
 */
sealed interface Snapshot {
    val id: Long
    val assetId: Long
    val asOf: Instant

    /** 自身币种下的**总成本**。null = 用户没填，不参与盈亏统计。均价是派生值，不存。 */
    val costBasisMinor: Money?
    val recordedAt: Instant

    val mode: ValuationMode

    /** 用户直接填市值。 */
    data class Manual(
        override val id: Long,
        override val assetId: Long,
        override val asOf: Instant,
        val value: Money,
        override val costBasisMinor: Money? = null,
        override val recordedAt: Instant,
    ) : Snapshot {
        override val mode: ValuationMode get() = ValuationMode.MANUAL
    }

    /**
     * 按份额记，市值 = 份额 × 行情单价。
     *
     * [quoteSymbol] 记在快照上而不是资产上：股票退市转 MANUAL 后，
     * 这些历史快照仍需知道该用哪个代码查历史行情。顺带也兼容了代码变更。
     */
    data class Quoted(
        override val id: Long,
        override val assetId: Long,
        override val asOf: Instant,
        val quantity: Quantity,
        val quoteSymbol: String,
        override val costBasisMinor: Money? = null,
        override val recordedAt: Instant,
    ) : Snapshot {
        override val mode: ValuationMode get() = ValuationMode.QUOTED
    }
}

/** 市场行情。公开数据，与用户无关。同一 symbol 同一天只有一条。 */
data class Quote(
    val symbol: String,
    val asOfDay: String,
    /** 单价用 scale-8 定点，不用 Money —— 见 [UnitPrice] 里关于低价股和代币的说明。 */
    val price: UnitPrice,
    val currency: String,
    val fetchedAt: Instant,
)

/** 目标配置。允许多套并存，其中一套 [isActive]。 */
data class TargetAllocation(
    val id: Long,
    val name: String,
    val isBuiltIn: Boolean,
    val isActive: Boolean,
    /** 各大类目标比例，单位**基点**（100% = 10000）。 */
    val targetsBp: Map<AssetClass, Int>,
) {
    /** 一套配置的比例之和必须是 10000。UI 保存前要校验。 */
    val sumBp: Int get() = targetsBp.values.sum()
    val isValid: Boolean get() = sumBp == TOTAL_BP

    companion object {
        const val TOTAL_BP: Int = 10_000
    }
}
