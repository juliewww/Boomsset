package com.boomsset.ui.networth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.boomsset.data.SUPPORTED_CURRENCIES
import com.boomsset.security.AppLockUiState
import com.boomsset.security.AuthCapability
import com.boomsset.domain.AssetClass
import com.boomsset.domain.Money
import com.boomsset.domain.Period
import com.boomsset.ui.AmountVisibilityToggle
import com.boomsset.ui.InfoTooltip
import com.boomsset.ui.bpToPercent
import com.boomsset.ui.bpToSignedPercent
import com.boomsset.ui.fallColor
import com.boomsset.ui.formatSigned
import com.boomsset.ui.formatWithCurrency
import com.boomsset.ui.label
import com.boomsset.ui.lastRecordDescription
import com.boomsset.ui.maskAmount
import com.boomsset.ui.periodLabel
import com.boomsset.ui.riseColor
import com.boomsset.ui.theme.chartColors

@Composable
fun NetWorthScreen(
    state: NetWorthUiState,
    onSelectPeriod: (Period) -> Unit,
    onSelectBaseCurrency: (String) -> Unit,
    onSelectChartMode: (ChartMode) -> Unit,
    onSelectChartStyle: (ChartStyle) -> Unit,
    onToggleClass: (AssetClass) -> Unit,
    onToggleAmountsHidden: (Boolean) -> Unit,
    onRetryPricing: () -> Unit,
    lockState: AppLockUiState,
    onToggleLock: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        when {
            state.loading -> Text("加载中…", style = MaterialTheme.typography.bodyMedium)

            state.isEmpty -> {
                EmptyHint()
                // The app lock must still be reachable in the empty state — an early return
                // would cut off this entry point; this is the lesson from AGENTS.md about "don't
                // use an early return for an empty state".
                // ⚠️ The eye icon is **intentionally** not in this branch: it hides amounts, and
                // the empty state has no amounts at all, so a "hide" button would have nothing
                // to hide. This doesn't conflict with the lesson above — that one is about
                // "a feature entry point unreachable in the empty state", whereas this toggle is
                // inherently tied to amounts, and it's always present whenever amounts exist
                // (the branch below).
                AppLockToggle(lockState, onToggleLock)
            }

            else -> {
                SummaryCard(state, onToggleAmountsHidden)
                BaseCurrencySelector(state.baseCurrency, onSelectBaseCurrency)
                ChartSection(
                    state = state,
                    onSelectPeriod = onSelectPeriod,
                    onSelectChartMode = onSelectChartMode,
                    onSelectChartStyle = onSelectChartStyle,
                    onToggleClass = onToggleClass,
                )
                if (state.unpricedCount > 0) UnpricedWarning(state.unpricedCount, onRetryPricing)
                AppLockToggle(lockState, onToggleLock)
            }
        }
    }
}

/**
 * The top total-assets card.
 *
 * It used to be **four or five sentences** ("Net worth growth +2.10% (including new
 * contributions)", "Unrealized gain/loss …", "Only covers the 3 assets with cost basis
 * entered"), where each line required reading the whole sentence to know what that number even
 * was, and worse:
 * - **It didn't say what day the data was from** — we record snapshots, not transactions, so net
 *   worth doesn't update on its own, and a record from three months ago looks identical to one
 *   from today; this is exactly the number this kind of app should show most, and we weren't
 *   showing it
 * - **It didn't say what "growth" was measured against** — the same +2% has a completely
 *   different starting point depending on monthly/quarterly/yearly view
 * - **Only a percentage, no amount** — "+2%" isn't memorable, "+¥12,345" is
 * - **Total assets and total liabilities weren't visible at all**, nor was the liability ratio
 *
 * Now split into three sections (modeled on the top card of household budgeting apps):
 * 1. Net worth itself + what day the data was recorded
 * 2. Total assets / total liabilities as separate cells (label on top, value below) — the value
 *    is what your eye lands on first
 * 3. Net worth growth / unrealized gain-loss as full-width rows (label on the left, value on the
 *    right), with the detailed explanation tucked into an (i)
 *
 * The last two sections use **different layouts**, and that's not arbitrary: short label + short
 * value suits a grid of cells, while a label a dozen-odd Chinese characters long would force both
 * the label and the value to wrap if placed side by side (see the comment on [MetricRow]).
 *
 * **The liabilities section only appears when there actually are liabilities** — a debt-free
 * user seeing "Total liabilities ¥0.00 / Liability ratio 0.00%" would see pure noise, and at that
 * point total assets always equals net worth, so writing it again would be redundant too.
 *
 * The eye icon in the top-right corner replaces **every amount on this card** with a placeholder
 * (the chart's y-axis is hidden along with it, see [ChartSection]). Amounts are hidden while
 * percentages remain; the rule is in [NetWorthUiState.amountsHidden].
 */
@Composable
private fun SummaryCard(state: NetWorthUiState, onToggleAmountsHidden: (Boolean) -> Unit) {
    val point = state.series?.latest
    val net = point?.netWorth ?: Money.ZERO
    val currency = state.baseCurrency
    val hidden = state.amountsHidden
    val onContainer = MaterialTheme.colorScheme.onPrimaryContainer

    // The card is grounded on the light brand-color container — this is the only place on the
    // whole page that uses a container accent.
    //
    // ⚠️ **No border** (this reverses the conclusion from both the cream-yellow and dark-purple
    // versions, see why below). The container is pale rose `#FBCEDF`, which against the warm
    // page background has a WCAG contrast of only **1.34:1** — purely by lightness, the two
    // earlier brand-color versions hit the same order of magnitude (cream-yellow 1.22:1,
    // dark-purple 1.33:1), and at the time both needed a thin border to trace out the outline.
    // **This time the same low contrast number was measured, but once installed on a real
    // device, the card was actually quite clear** — the difference is that in the cream-yellow/
    // dark-purple versions the container and page background were **also close in hue** (both
    // warm tones), while the bright-rose container and the warm-cream page background are **far
    // apart in hue** (pink vs. cream). WCAG contrast only looks at lightness and can't see a hue
    // difference, but the human eye sees hue — visually this card's boundary reads clearly, and
    // adding a border turned out to be unnecessary (real-device feedback was "maybe it's better
    // without a border", confirmed by comparing screenshots).
    // **General rule: a low WCAG contrast number doesn't mean the human eye can't see it clearly
    // — when hues are far apart, going by that one number alone can lead to a wrong call; always
    // confirm on a real device after a change, don't trust the calculator alone.**
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                // The eye icon shares a row with the label, flush right (the card's top-right
                // corner) — it governs the amounts on the entire card, so it belongs at the
                // card's corner rather than next to any one specific value.
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "当前净值",
                        style = MaterialTheme.typography.labelMedium,
                        color = onContainer,
                    )
                    AmountVisibilityToggle(hidden, onToggleAmountsHidden)
                }
                Text(
                    maskAmount(hidden, net.formatWithCurrency(currency)),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = onContainer,
                )
                // Data freshness. This line isn't shown when there are no snapshots at all (new
                // users go through the empty-state branch, but this can also be reached after
                // archiving every asset)
                lastRecordDescription(state.lastRecordedDate, state.daysSinceLastRecord)?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = onContainer)
                }
            }

            if (point != null && !point.totalLiabilities.isZero) {
                // Two columns rather than three: on a 360dp-wide phone, three columns leave only
                // 90dp per cell, forcing a 7-digit amount (¥1,234,567.00) to wrap — a wrapped
                // amount looks far worse than taking up one extra line. The liability ratio
                // trails below total liabilities as a footnote, since it's derived from these
                // exact two numbers.
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    KpiCell(
                        label = "总资产",
                        value = maskAmount(hidden, point.totalAssets.formatWithCurrency(currency)),
                        modifier = Modifier.weight(1f),
                    )
                    KpiCell(
                        label = "总负债",
                        value = maskAmount(
                            hidden,
                            point.totalLiabilities.formatWithCurrency(currency),
                        ),
                        // The liability ratio is a **ratio**, so it's shown as usual even when
                        // amounts are hidden — on its own it can't reveal how much is owed, and
                        // it's the most useful number in this cell
                        // null = total assets <= 0, in which case the ratio is meaningless. Not
                        // written as 0% — that would be read as "no liabilities"
                        sub = "负债率 ${point.liabilityRatioBp?.bpToPercent() ?: "—"}",
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            // Net worth growth and unrealized gain/loss are **two different bases**, and the
            // distinction must be spelled out — see "growth rate: the two bases must be kept
            // separate" in docs/domain.md. The full explanation is tucked into an (i), no longer
            // taking up a permanent line of text.
            //
            // These two aren't laid out as cells like above: the labels themselves run to a
            // dozen-odd Chinese characters, and side by side each cell would have only 120dp
            // left, forcing **both the label and the value to wrap** (360dp real test: "Net
            // worth growth (including new c" wraps, and "-¥12,000.00 ·" is left dangling with a
            // separator dot after it). And when the two labels wrap to different numbers of
            // lines, the values below end up misaligned in height, making the cell layout even
            // messier. Switched to full-width "label on the left, value on the right" rows: the
            // value is measured first and stays whole, and only the label wraps if space is tight.
            MetricRow(
                label = "净值增长（含新增投入）",
                value = growthValue(state, hidden),
                sub = growthSub(state),
                valueColor = signedColor(state.series?.growthAbsolute?.minorUnits),
                // Note: Markdown-style `**bold**` **must not** be used here — `Text` doesn't
                // parse markup, and the asterisks would show up literally in the tooltip bubble
                // (confirmed via a simulator screenshot). Emphasis has to come from wording and
                // 「」quotation marks instead.
                info = "「净值增长」= 期末净值 ÷ 期初净值 − 1，连你新存进去的钱一起算 —— " +
                    "这个月存一万工资进来，它也会涨，那不是赚的。" +
                    "「浮动盈亏」= 市值 − 成本，才反映投资本身的表现，" +
                    "但它只覆盖填了成本的那几项资产。",
            )
            MetricRow(
                label = "浮动盈亏（投资本身）",
                value = pnlValue(state, hidden),
                sub = pnlSub(state),
                valueColor = signedColor(state.pnl?.takeIf { it.hasCoverage }?.pnl?.absolute?.minorUnits),
            )
        }
    }
}

/**
 * Net worth growth: **includes new contributions**, given as amount plus percentage together.
 *
 * [hidden] only swaps out the amount half, leaving the percentage — "•••••• · +2.10%" still
 * tells the user how much things went up, without revealing net worth. The "no change" phrase
 * itself contains no amount, so it's given as-is regardless of hidden state.
 */
private fun growthValue(state: NetWorthUiState, hidden: Boolean): String {
    val series = state.series
    val delta = series?.growthAbsolute
    val bp = series?.growthBp
    return when {
        // With only one record there's no starting point, so show "—" and explain what's
        // missing in the footnote — leaving it blank would look like 0
        delta == null -> "—"
        // If the valuation was never updated, carry-forward makes the first and last points
        // exactly equal, which is common in this app. "¥0.00 · 0.00%" takes reading two numbers
        // to realize "nothing changed" — saying so directly is faster
        delta.isZero -> "没有变化"
        else -> {
            val amount = maskAmount(hidden, delta.formatSigned(state.baseCurrency))
            if (bp == null) amount else "$amount · ${bp.bpToSignedPercent()}"
        }
    }
}

/**
 * Which period the growth is measured against. The same +2% has a completely different starting
 * point depending on monthly/quarterly/yearly view.
 *
 * When there's no number, it must state clearly **which kind** of missing it is: "recorded only
 * once" versus "recorded, but the two ends aren't comparable" call for completely different
 * actions from the user (one is "record again", the other is "go fill in the missing quote/FX rate").
 */
private fun growthSub(state: NetWorthUiState): String? {
    val series = state.series ?: return null
    if (!series.hasBaseline) return "只有一次记录，没有可比的期初"
    if (series.growthAbsolute == null) return "期初或期末有资产无法估值，两端不可比"
    return series.baselineDate?.periodLabel(series.period)?.let { "相比 $it" }
}

/** Unrealized gain/loss: **excludes new contributions**. [hidden] behaves the same as in [growthValue]. */
private fun pnlValue(state: NetWorthUiState, hidden: Boolean): String {
    val pnl = state.pnl?.takeIf { it.hasCoverage } ?: return "—"
    val rate = pnl.pnl.returnBp
    val absolute = maskAmount(hidden, pnl.pnl.absolute.formatSigned(state.baseCurrency))
    return if (rate == null) absolute else "$absolute · ${rate.bpToSignedPercent()}"
}

/** The coverage must be stated: assets with no cost basis entered are excluded, so this number only represents the assets that have one. */
private fun pnlSub(state: NetWorthUiState): String {
    // When there's no coverage, it's not "gain/loss is 0" — it's "no cost basis entered, so it
    // can't be computed" — this also tells the user what's missing
    val pnl = state.pnl?.takeIf { it.hasCoverage } ?: return "还没填成本"
    return "覆盖 ${pnl.coveredAssetIds.size} 项资产"
}

/** Gain/loss coloring: red for up, green for down per the Chinese stock market convention; see [com.boomsset.ui.GainLossColors]. */
@Composable
private fun signedColor(minorUnits: Long?): Color = when {
    minorUnits == null || minorUnits == 0L -> MaterialTheme.colorScheme.onPrimaryContainer
    minorUnits > 0L -> riseColor()
    else -> fallColor()
}

/**
 * A cell with "label on top, value below".
 *
 * The value uses `titleSmall` rather than `bodySmall`: in this cell the value is what's meant to
 * be scanned, with the label as a footnote defining its basis. The full-sentence form
 * "Net worth growth +2.10% (including new contributions)" couldn't achieve this — in that line
 * the number and the text carry equal visual weight.
 */
@Composable
private fun KpiCell(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    sub: String? = null,
    valueColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        Text(
            value,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = valueColor,
        )
        sub?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

/**
 * One metric as a full-width row: **label on the left, value on the right**, with the footnote
 * on its own line below the label.
 *
 * `weight(1f)` is given to the label rather than the value — Row measures non-weighted children
 * at their full width first, and only what's left gets divided among weighted ones. So the
 * **value is always shown whole**, and when space runs short it's the label that wraps (a label
 * is a sentence, wrapping it reads fine; wrapping an amount would split one number in half).
 */
@Composable
private fun MetricRow(
    label: String,
    value: String,
    sub: String? = null,
    valueColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    info: String? = null,
) {
    val onContainer = MaterialTheme.colorScheme.onPrimaryContainer
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The (i) has to sit right after the label text, not directly in the outer Row —
            // giving the label weight(1f) there would push the (i) next to the value, making it
            // look like it explains the value. `fill = false` makes the label take only the
            // width it actually needs, so the (i) sits right at the end of the label.
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = onContainer,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (info != null) InfoTooltip(info)
            }
            Text(
                value,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = valueColor,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        sub?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = onContainer,
            )
        }
    }
}

/**
 * The app lock toggle.
 *
 * When unavailable, **state the reason and disable it**, rather than letting the user turn it on
 * and then discover they're locked out — "not enrolled" is something the system settings can
 * fix, "unsupported" has no solution, and the distinction must be made clear.
 */
@Composable
private fun AppLockToggle(lockState: AppLockUiState, onToggle: (Boolean) -> Unit) {
    val canUse = lockState.capability == AuthCapability.AVAILABLE
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("应用锁", style = MaterialTheme.typography.titleSmall)
                Switch(
                    checked = lockState.lockEnabled,
                    onCheckedChange = onToggle,
                    enabled = canUse || lockState.lockEnabled,
                )
            }
            Text(
                when (lockState.capability) {
                    AuthCapability.AVAILABLE ->
                        "开启后每次打开猪满仓都需要验证身份。开启时会先验一次。"
                    AuthCapability.NOT_ENROLLED ->
                        "这台设备还没设锁屏密码或生物识别 —— 去系统设置里加上就能用了。"
                    AuthCapability.NO_HARDWARE ->
                        "这台设备不支持生物识别，也没有锁屏密码可用。"
                    AuthCapability.TEMPORARILY_UNAVAILABLE ->
                        "验证暂时不可用（可能是多次失败被锁定），稍后再试。"
                },
                style = MaterialTheme.typography.labelSmall,
            )
            lockState.lastError?.let {
                Text(it, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/**
 * The base currency (which currency net worth is viewed in).
 *
 * Used to be a row of 9 `FilterChip`s + a label + a line of explanation, taking up three or four
 * lines on a phone — real-device feedback was "takes up too much space". This page is a
 * read-only trend overview, and what's most valuable is the summary card and the chart; the base
 * currency defaults to CNY and is **almost never changed**, so it shouldn't take up this much room.
 * The add-asset page already switched its currency picker to a dropdown for the same reason (see
 * `CurrencyDropdown`); this follows suit.
 *
 * The explanatory text is tucked into an (i) — "switching won't rewrite any data" is a one-time
 * reassurance only needed **before the user switches**, and showing it permanently is just noise.
 */
@Composable
private fun BaseCurrencySelector(selected: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }

    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("查看币种", style = MaterialTheme.typography.labelMedium)
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            ) {
                // The arrow is written directly as a glyph, consistent with how "＋" and "ⓘ" are
                // used elsewhere in the project (no icon dependency pulled in)
                Text("$selected ▾", style = MaterialTheme.typography.labelLarge)
            }
            // The menu is anchored to this Box around the button — DropdownMenu computes its own
            // position, so ExposedDropdownMenuBox isn't needed (that's meant for text input
            // fields, and using it here would pull in a 56dp-tall input field, exactly the
            // opposite of "saving space")
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                SUPPORTED_CURRENCIES.forEach { code ->
                    DropdownMenuItem(
                        text = { Text(code) },
                        // The menu covers the button itself when expanded (confirmed on a real
                        // device), so the current currency must also be marked inside the menu —
                        // otherwise, once expanded, there'd be no way to tell which one is active.
                        // A checkmark is used rather than just changing the color: relying on
                        // color alone to carry state doesn't work for color-weak users.
                        trailingIcon = if (code == selected) {
                            { Text("✓") }
                        } else {
                            null
                        },
                        onClick = {
                            onSelect(code)
                            expanded = false
                        },
                    )
                }
            }
        }
        InfoTooltip(
            "只改变展示口径：已记录的金额和币种一条都不会被改写，" +
                "历史净值按当时的汇率折算。取不到汇率的资产会显示「无法估值」，不按 1:1 算。",
        )
    }
}

/**
 * The chart area: controls + legend + chart + footnotes.
 *
 * All four combinations (total assets/by-class × column chart/trend chart) share this block, and
 * the **can-it-be-drawn** decision is also centralized here — scattering it across each
 * individual chart would turn "nothing got drawn" into an empty chart rather than an explanatory
 * message (this is exactly how AGENTS.md lesson 10 came about).
 *
 * ⚠️ **When amounts are hidden, the chart's y-axis ticks must be hidden along with them.** If only
 * the card is hidden, the y-axis would still show "130K", making the placeholder at the top
 * pointless — a privacy toggle that only blocks half the picture is worse than none at all,
 * since the user would think they'd already hidden everything. The shape of the columns and the
 * growth-rate band are kept: shape is a relative quantity and can't reveal the amount.
 */
@Composable
private fun ChartSection(
    state: NetWorthUiState,
    onSelectPeriod: (Period) -> Unit,
    onSelectChartMode: (ChartMode) -> Unit,
    onSelectChartStyle: (ChartStyle) -> Unit,
    onToggleClass: (AssetClass) -> Unit,
) {
    val series = state.series
    val allocation = state.allocationSeries
    val chart = state.chart
    val visible = chart.visibleClasses

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ChartControls(chart, onSelectPeriod, onSelectChartMode, onSelectChartStyle)

        if (chart.mode == ChartMode.ALLOCATION) {
            ClassLegend(chart.hiddenClasses, onToggleClass)
        }

        when {
            series == null || allocation == null -> Unit

            chart.mode == ChartMode.ALLOCATION && visible.isEmpty() ->
                ChartNote("至少勾一个大类才有东西可画。")

            chart.style == ChartStyle.TREND && !canDrawTrend(series.dates.size) ->
                ChartNote("趋势图要两个以上的取样点才连得成线。现在只有一个点，先看柱状图。")

            else -> {
                NetWorthChart(
                    series = series,
                    allocationSeries = allocation,
                    mode = chart.mode,
                    style = chart.style,
                    visibleClasses = visible,
                    hideAmounts = state.amountsHidden,
                )
                if (chart.mode == ChartMode.ALLOCATION) {
                    AllocationChartNotes(chart.style, allocation.hasNegativeExposure(visible))
                }
            }
        }
    }
}

@Composable
private fun ChartControls(
    chart: ChartOptions,
    onSelectPeriod: (Period) -> Unit,
    onSelectChartMode: (ChartMode) -> Unit,
    onSelectChartStyle: (ChartStyle) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        PeriodSelector(chart.period, onSelectPeriod)
        LabeledSwitch("按大类", chart.mode == ChartMode.ALLOCATION) { on ->
            onSelectChartMode(if (on) ChartMode.ALLOCATION else ChartMode.TOTAL)
        }
        LabeledSwitch("趋势图", chart.style == ChartStyle.TREND) { on ->
            onSelectChartStyle(if (on) ChartStyle.TREND else ChartStyle.COLUMN)
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(CONTROL_HEIGHT)) {
            // ⚠️ Text doesn't parse Markdown, so **emphasis** must not be written here — it would show up literally as four asterisks
            InfoTooltip(
                "柱状图每根柱子上方是相对前一根的涨跌幅，四舍五入到整数 —— " +
                    "十几根柱子并排时，带小数的百分比会被截断，截断的数字比没有更糟。" +
                    "第一根没有可比的前一根、上一根不是正数时算不出比例，都显示「—」。" +
                    "这是净值变化，含期间新增投入，不等于投资收益率。",
            )
        }
    }
}

/**
 * All controls are uniformly 40dp tall.
 *
 * Items in a `FlowRow` are top-aligned by default, and `OutlinedButton` (40dp) and `Switch`
 * (32dp) have different heights — without unifying them, the dropdown button and the switch
 * would sit a few dp out of alignment.
 */
private val CONTROL_HEIGHT = 40.dp

/**
 * A switch with a text label.
 *
 * `Switch` needs [contentDescription] set explicitly: the label is a **sibling node**, not
 * merged into the switch's own accessibility node — without it, a screen-reader user hears only
 * "switch, on", and this page has three switches (by class / trend chart / app lock), giving no
 * way to tell which one it is. This also lets XCUITest locate it by name, the same approach used
 * for the `field-*` input fields (see the comment on [com.boomsset.ui.FIELD_NAME]).
 */
@Composable
private fun LabeledSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.height(CONTROL_HEIGHT),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.semantics { contentDescription = label },
        )
    }
}

/**
 * The period picker changed from a row of `FilterChip`s to a dropdown.
 *
 * The reasoning is the same as for the currency picker (see [BaseCurrencySelector]): what's most
 * valuable on this page is the summary card and the chart, and three permanent chips take up a
 * whole row while a pick-one-of-three dropdown takes up just one button. The horizontal space
 * saved is exactly what makes room for the two new switches next to it — only then do all three
 * controls fit on one line.
 */
@Composable
private fun PeriodSelector(selected: Period, onSelect: (Period) -> Unit) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        OutlinedButton(
            onClick = { expanded = true },
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        ) {
            Text("${selected.label()} ▾", style = MaterialTheme.typography.labelLarge)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            Period.entries.forEach { period ->
                DropdownMenuItem(
                    text = { Text(period.label()) },
                    // The menu covers the button itself, so the currently selected item must also
                    // be marked inside the menu (same as the currency dropdown)
                    trailingIcon = if (period == selected) {
                        { Text("✓") }
                    } else {
                        null
                    },
                    onClick = {
                        onSelect(period)
                        expanded = false
                    },
                )
            }
        }
    }
}

private fun Period.label(): String = when (this) {
    Period.MONTH -> "按月"
    Period.QUARTER -> "按季"
    Period.YEAR -> "按年"
}

/**
 * The class legend, with checkboxes controlling which classes get drawn.
 *
 * Color is **painted onto the checkbox itself** rather than adding a separate swatch: with a
 * checkbox sitting between a swatch and the name, "this color means this class" becomes less
 * direct, and 16dp extra per item across five items means an extra wrapped line at phone widths.
 * When unchecked, the box is hollow with its border still in that class's color, so the
 * color-to-name mapping doesn't disappear just because it's unchecked (in light mode several
 * class colors fall below 3:1 contrast, and **this must be compensated for by always having a
 * name next to the swatch**, per the color constraints in AGENTS.md).
 */
@Composable
private fun ClassLegend(hidden: Set<AssetClass>, onToggle: (AssetClass) -> Unit) {
    val palette = chartColors
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        AssetClass.displayOrder.forEach { assetClass ->
            val color = palette.of(assetClass)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = assetClass !in hidden,
                    onCheckedChange = { onToggle(assetClass) },
                    colors = CheckboxDefaults.colors(
                        checkedColor = color,
                        uncheckedColor = color,
                        checkmarkColor = MaterialTheme.colorScheme.surface,
                    ),
                )
                Text(assetClass.label(), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/**
 * The explanation of what's being measured in the by-class view.
 *
 * The first sentence is necessary: this chart's total **does not equal** the net worth figure
 * above — the by-class view looks at net exposure, and only counts assets marked "included in
 * allocation". Without stating this, users would think something was computed wrong.
 */
@Composable
private fun AllocationChartNotes(style: ChartStyle, hasNegative: Boolean) {
    ChartNote(
        "每一段是该类的净敞口（这类资产 − 归属这类的负债），和「配置」页同一个口径；" +
            "标了「不计入配置」的资产不在里面，所以各段合计可能和上面的净值对不上。",
    )
    if (hasNegative) {
        ChartNote(
            when (style) {
                // Vico's stacked columns natively support negative values, drawing the negative
                // segment below the zero line — this reflects reality and is not hidden
                ChartStyle.COLUMN -> "有大类的净敞口是负的（负债超过了这类资产），画在零线下方。"
                // A stacked area chart only holds up when the cumulative value is monotonically
                // increasing; a negative segment would scramble the layering — better to switch
                // to a different rendering
                ChartStyle.TREND -> "有大类的净敞口是负的，堆叠面积在这种情况下会分层错位，" +
                    "所以改成各类各画一条线（不填充）。"
            },
        )
    }
}

@Composable
private fun ChartNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun UnpricedWarning(count: Int, onRetry: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("$count 项资产无法估值", style = MaterialTheme.typography.titleSmall)
            Text(
                // Same as above: `Text` doesn't parse Markdown, so the `**none**` that used to be
                // here showed up on screen as four literal asterisks (the same pitfall as the
                // tooltip above)
                "可能是缺行情/汇率，也可能是份额或价格的数量级超出了可计算范围。" +
                    "这些资产没有计入上面的净值 —— 不按 0 计算，是为了避免静默低估。",
                style = MaterialTheme.typography.bodySmall,
            )
            // If the first automatic FX-rate fetch after adding a foreign-currency asset happens
            // to fail (a network blip), no subsequent event would ever trigger a retry, and
            // restarting the app used to be the only option — this button provides a recovery
            // path without a restart; see the comment on RateRefresher.retryAll.
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onRetry) { Text("重试") }
            }
        }
    }
}

/**
 * The empty-state onboarding guide.
 *
 * It used to be just "tap the + in the bottom right". The problem is **new users don't know how
 * this app works**: it records snapshots, not transactions, which is the opposite of most
 * budgeting apps — without stating this upfront, users would use it expecting transaction
 * entry and then feel like a feature was missing. So this spells out all three steps, and points
 * out that the target allocation can be set up first.
 *
 * Deliberately kept short: three items, one line each. Nobody reads a long essay in an empty state.
 */
@Composable
private fun EmptyHint() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("还没有资产", style = MaterialTheme.typography.titleMedium)
            Text(
                "猪满仓不记流水，记的是快照 —— 你不用逐笔录收支，只要定期更新每项资产现在值多少。",
                style = MaterialTheme.typography.bodyMedium,
            )
            Step("1", "去「资产」页点右下角加号，添加一项资产（存款、基金、股票、房产都行）")
            Step("2", "以后每月或每季回来更新一次市值，净值曲线就长出来了")
            Step("3", "去「配置」页设定目标比例，就能看到自己离目标有多远")
            Text(
                "现在就可以先去「配置」页看看内置的几套目标比例 —— 那一页不需要有资产也能用。",
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun Step(number: String, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            number,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
