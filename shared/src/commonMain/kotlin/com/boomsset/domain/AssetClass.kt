package com.boomsset.domain

/**
 * 五大类 —— 分类体系的第一层，服务于**资产配置比例**。
 *
 * 框架是战略资产配置（SAA）的四大类，加一个「保障类」适配国内配置年金险/增额寿的习惯。
 * 这一层必须**少而稳定**（比例视图要求如此）；可扩展的那层是 [AssetSubtype]。
 *
 * 注意：资产配置领域没有单一"最高权威"。SAA 四大类是机构界最通用的顶层划分，
 * 理论基础是 Markowitz 的现代投资组合理论。中文流传的「标准普尔家庭资产象限图」
 * 并非标普官方研究 —— UI 上引用来源时别写成"标普研究表明"。见 docs/domain.md。
 */
enum class AssetClass {
    /** 流动资金：微信钱包、支付宝、银行活期、货币基金、现金 */
    LIQUID,

    /** 固定收益：银行定期、国债、债券基金、银行理财、企业债 */
    FIXED_INCOME,

    /** 权益类：A股、港股、美股、股票基金、指数基金、期权 */
    EQUITY,

    /** 另类实物：房产、黄金、加密货币、车辆、收藏品 */
    ALTERNATIVE,

    /** 保障类：年金险、增额终身寿（按现金价值计值） */
    PROTECTION,
    ;

    companion object {
        /** UI 上的固定展示顺序 —— 从流动性最高到最低。 */
        val displayOrder: List<AssetClass> =
            listOf(LIQUID, FIXED_INCOME, EQUITY, ALTERNATIVE, PROTECTION)
    }
}
