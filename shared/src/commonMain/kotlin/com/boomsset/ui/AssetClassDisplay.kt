package com.boomsset.ui

import com.boomsset.domain.AssetClass

/**
 * Display copy for asset classes. **The single source of truth** — `AddAssetDialog` and
 * `AllocationScreen` used to each write their own `label()`, and it was only a matter of
 * time before one got updated and the other was forgotten.
 */
fun AssetClass.label(): String = when (this) {
    AssetClass.LIQUID -> "流动资金"
    AssetClass.FIXED_INCOME -> "固定收益"
    AssetClass.EQUITY -> "权益类"
    AssetClass.ALTERNATIVE -> "另类实物"
    AssetClass.PROTECTION -> "保障类"
}

/**
 * A one-line explanation of what belongs in this class.
 *
 * Reason for existing: users **don't know which class the thing they're adding belongs
 * to**, and classification jargon ("alternative/physical assets") carries no information
 * for non-expert users. When adding an asset, selection happens by subtype (Alipay,
 * mortgage, ...) — this sentence is only supplementary explanation and doesn't require the
 * user to understand it to complete the action.
 */
fun AssetClass.hint(): String = when (this) {
    AssetClass.LIQUID -> "随时能取用的钱"
    AssetClass.FIXED_INCOME -> "到期还本付息，波动小"
    AssetClass.EQUITY -> "股票和股票型基金，波动大"
    AssetClass.ALTERNATIVE -> "房产、黄金、加密货币这类"
    AssetClass.PROTECTION -> "保险的现金价值"
}
