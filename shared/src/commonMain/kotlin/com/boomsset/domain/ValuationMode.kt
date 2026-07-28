package com.boomsset.domain

/**
 * 估值模式。
 *
 * ⚠️ **估值一律看 [Snapshot.mode]，不看 [Asset.defaultValuationMode]** ——
 * 后者只是新快照的默认值。一支股票退市转成 MANUAL 后，它历史上那些按份额记的快照
 * 仍然要按 QUOTED 估值；按资产当前模式去判会去读老快照里空的 valueMinor，历史就崩了。
 * 而且这个错**不报错、只是静默算错**，要等到真有资产转换过才炸。
 *
 * 成本（costBasisMinor）**与本枚举无关，两种模式都可填、都显示收益率**。
 * 别把"市值只读"误推成"成本只读"。
 */
enum class ValuationMode {
    /** 市值 = 份额 × 市场单价，只读；用户改份额和成本。参与行情刷新。 */
    QUOTED,

    /** 市值由用户直接填，可改；不参与行情刷新。 */
    MANUAL,
}
