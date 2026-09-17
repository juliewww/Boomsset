package com.boomsset.domain

/**
 * The five top-level classes — the first layer of the classification system, serving
 * **asset allocation ratios**.
 *
 * The framework is the four classes of Strategic Asset Allocation (SAA), plus a
 * "protection" class to fit the domestic habit of including annuities/increasing whole
 * life insurance in one's allocation. This layer must stay **small and stable** (the
 * allocation view requires it); the extensible layer is [AssetSubtype].
 *
 * Note: there is no single "supreme authority" in the asset allocation domain. The four
 * SAA classes are the most common top-level split in institutional practice, grounded in
 * Markowitz's Modern Portfolio Theory. The "S&P family asset quadrant chart" that
 * circulates in Chinese material is not an official S&P study — don't cite it in the UI
 * as "S&P research shows...". See docs/domain.md.
 */
enum class AssetClass {
    /** Liquid funds: WeChat Wallet, Alipay, bank current deposits, money market funds, cash */
    LIQUID,

    /** Fixed income: bank time deposits, government bonds, bond funds, bank wealth products, corporate bonds */
    FIXED_INCOME,

    /** Equity: A-shares, Hong Kong stocks, US stocks, equity funds, index funds, options */
    EQUITY,

    /** Alternative/physical: real estate, gold, crypto, vehicles, collectibles */
    ALTERNATIVE,

    /** Protection: annuity insurance, increasing whole life insurance (valued by cash value) */
    PROTECTION,
    ;

    companion object {
        /** Fixed display order in the UI — from most to least liquid. */
        val displayOrder: List<AssetClass> =
            listOf(LIQUID, FIXED_INCOME, EQUITY, ALTERNATIVE, PROTECTION)
    }
}
