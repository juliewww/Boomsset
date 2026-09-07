package com.boomsset.domain

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * 一条更新记录属于哪一类事件。
 *
 * 三类都是**从快照本身推出来的**，不是另外记的 —— 库里没有「事件表」，
 * `snapshot` 那条不可变、只追加的链本身就是流水（见 Snapshot.sq 的注释）。
 * 多记一份等于多一个会和快照对不上的事实来源。
 */
enum class UpdateKind {
    /** 该资产的第一条快照 —— 建资产时一起写的。 */
    CREATED,

    /** 普通的一次估值更新。 */
    UPDATED,

    /** 归档时追加的那条归零快照。 */
    ARCHIVED,
}

/**
 * 资产页底部「更新记录」里的一行。
 *
 * 同时带着 [snapshot] 和链上**紧邻的前一条** [previous]，所有「从 X 变成 Y」都由这两条
 * 现场算出来 —— 没有任何一个「变化量」被写进库里。变化量存库就得在修正历史时同步维护，
 * 而快照是可以补录的（同一 `asOf` 追加一条更晚录入的记录就是修正），维护漏一处就静默不一致。
 *
 * ⚠️ **QUOTED 的快照里没有市值，这里也不算市值。** 市值 = 份额 × 当时行情，而本项目
 * 已知「Quote 还没有做历史回补」（见 AGENTS.md 开头）—— 历史时点大多取不到价，
 * 硬算要么得出「无法估值」，要么拿今天的价去解释三个月前的那条记录，属于静默算错。
 * 所以 QUOTED 行展示的是**快照上真实存着的份额和成本**，不是推算出来的市值。
 */
data class UpdateRecord(
    val asset: Asset,
    val snapshot: Snapshot,
    /**
     * 链上紧邻的前一条快照。null = 这是第一条。
     *
     * 「紧邻」按 `(asOf, id)` 定义，和结转规则取「该时点前最近的一条」用的是同一个次序 ——
     * 用别的次序会让这里显示的「前值」和净值曲线实际结转的那条对不上。
     */
    val previous: Snapshot?,
    val kind: UpdateKind,
    /** [Snapshot.recordedAt] 落到本地时区的日期，供 UI 直接显示。 */
    val recordedDate: LocalDate,
) {
    val recordedAt: Instant get() = snapshot.recordedAt

    /**
     * 上一条是另一种估值方式（比如退市后 QUOTED 转 MANUAL）。
     *
     * 此时**前值不可比**：一边是份额、一边是市值，减不出变化量。UI 要退回「只显示新值」，
     * 不能把 `null` 当成 0 去算差。
     */
    val modeChanged: Boolean get() = previous != null && previous.mode != snapshot.mode

    /** MANUAL 快照的市值；QUOTED 为 null。 */
    val value: Money? get() = (snapshot as? Snapshot.Manual)?.value

    /** 上一条 MANUAL 快照的市值。上一条不存在或不是 MANUAL 时为 null。 */
    val previousValue: Money? get() = (previous as? Snapshot.Manual)?.value

    /** QUOTED 快照的份额；MANUAL 为 null。 */
    val quantity: Quantity? get() = (snapshot as? Snapshot.Quoted)?.quantity

    val previousQuantity: Quantity? get() = (previous as? Snapshot.Quoted)?.quantity

    /** 总成本。两种模式都可能有，也都可能是 null（用户没填）。 */
    val cost: Money? get() = snapshot.costBasisMinor

    val previousCost: Money? get() = previous?.costBasisMinor

    /** 市值变化。两端都得是 MANUAL 才有值 —— 见 [modeChanged]。 */
    val valueChange: Money?
        get() {
            val now = value ?: return null
            val before = previousValue ?: return null
            return now - before
        }

    /** 份额变化。两端都得是 QUOTED 才有值。 */
    val quantityChange: Quantity?
        get() {
            val now = quantity ?: return null
            val before = previousQuantity ?: return null
            return now - before
        }

    /**
     * 成本变化。两端都填了成本才有值。
     *
     * 单独拿出来是因为它回答的是另一个问题：「这次是加仓/减仓，还是只是市价变了」。
     * 份额没动而成本动了同样有意义（用户在修正自己填错的成本）。
     */
    val costChange: Money?
        get() {
            val now = cost ?: return null
            val before = previousCost ?: return null
            return now - before
        }

    /** 有没有任何可展示的变化量。都没有时 UI 只显示新值，不画一个「→」出来。 */
    val hasChange: Boolean get() = valueChange != null || quantityChange != null
}

/**
 * 从原始快照流里还原出「更新记录」。纯函数、无 IO。
 *
 * ## 为什么不做保留期
 *
 * 这里**不截断、不过滤、不删除**任何记录，UI 侧只是分页显示。快照是净值曲线的唯一数据源，
 * 而结转规则取「该时点前最近的一条」—— 删掉「半年前」的记录后，一项半年没更新过的资产
 * 会连**今天**都取不到快照，于是从净值、配置、资产列表里整个消失。那不是丢精度，
 * 是资产凭空蒸发，且不报错（正是 AGENTS.md 反复警告的「静默算错」）。
 * 存储上也没有收益：一行快照约 100 字节，20 项资产按月更新存十年不到 250 KB。
 *
 * 量级上限沿用 [PortfolioData] 那条：几万条快照时全量加载会明显变慢，但不会静默出错。
 */
object UpdateHistory {

    fun build(data: PortfolioData, zone: TimeZone): List<UpdateRecord> {
        val assetsById = data.assets.associateBy { it.id }
        return data.snapshots
            .groupBy { it.assetId }
            .flatMap { (assetId, chain) ->
                // 资产被删掉而快照还在，理论上不该出现（没有删资产的入口），
                // 但这里宁可跳过也不要抛 —— 这是展示用的派生数据，不值得让整页崩掉。
                val asset = assetsById[assetId] ?: return@flatMap emptyList<UpdateRecord>()
                val ordered = chain.sortedWith(compareBy({ it.asOf }, { it.id }))
                ordered.mapIndexed { index, snapshot ->
                    UpdateRecord(
                        asset = asset,
                        snapshot = snapshot,
                        previous = ordered.getOrNull(index - 1),
                        kind = kindOf(asset, snapshot, isFirst = index == 0),
                        recordedDate = snapshot.recordedAt.toLocalDateTime(zone).date,
                    )
                }
            }
            // 倒序，最新的在最上面。按 **recordedAt**（什么时候记的）而不是 asOf
            // （记的是哪个时点的状态）—— 这一栏回答的是「我最近做了什么」。
            // 目前两者恒等（追加快照时用同一个 now），补录历史的入口出现后才会分叉。
            .sortedWith(
                compareByDescending<UpdateRecord> { it.recordedAt }
                    .thenByDescending { it.snapshot.id },
            )
    }

    /**
     * 先判归档、再判首条。
     *
     * 顺序有讲究：一项「建完就归档、只有两条快照」的资产，第二条同时满足不了首条，
     * 但假如将来出现「建资产即归档」的路径，归档这个信息比「这是第一条」更该显示出来。
     *
     * 归档的判据是 `archivedAt == asOf` —— `archiveAsset` 里这两个字段写的是同一个 `now`。
     * 取消归档会清掉 `archivedAt`（那条归零快照留着，见 `unarchiveAsset`），
     * 于是这条记录退回显示成普通「更新（归零）」—— 正确，因为归档确实被撤销了。
     */
    private fun kindOf(asset: Asset, snapshot: Snapshot, isFirst: Boolean): UpdateKind = when {
        asset.archivedAt != null && asset.archivedAt == snapshot.asOf -> UpdateKind.ARCHIVED
        isFirst -> UpdateKind.CREATED
        else -> UpdateKind.UPDATED
    }
}
