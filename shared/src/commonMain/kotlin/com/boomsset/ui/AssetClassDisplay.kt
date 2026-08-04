package com.boomsset.ui

import com.boomsset.domain.AssetClass

/**
 * 大类的展示文案。**唯一事实来源** —— 之前 `AddAssetDialog` 和 `AllocationScreen`
 * 各写了一份 `label()`，改一处忘另一处只是时间问题。
 */
fun AssetClass.label(): String = when (this) {
    AssetClass.LIQUID -> "流动资金"
    AssetClass.FIXED_INCOME -> "固定收益"
    AssetClass.EQUITY -> "权益类"
    AssetClass.ALTERNATIVE -> "另类实物"
    AssetClass.PROTECTION -> "保障类"
}

/**
 * 一句话说明这类里放什么。
 *
 * 存在的理由：用户**不知道自己要加的东西属于哪一类**，而分类术语（"另类实物"）
 * 对非专业用户没有信息量。添加资产时按品种选（支付宝、房贷…），这句话只是辅助解释，
 * 不要求用户理解它也能完成操作。
 */
fun AssetClass.hint(): String = when (this) {
    AssetClass.LIQUID -> "随时能取用的钱"
    AssetClass.FIXED_INCOME -> "到期还本付息，波动小"
    AssetClass.EQUITY -> "股票和股票型基金，波动大"
    AssetClass.ALTERNATIVE -> "房产、黄金、加密货币这类"
    AssetClass.PROTECTION -> "保险的现金价值"
}
