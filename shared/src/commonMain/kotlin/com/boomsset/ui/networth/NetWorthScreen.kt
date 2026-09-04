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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.boomsset.data.SUPPORTED_CURRENCIES
import com.boomsset.security.AppLockUiState
import com.boomsset.security.AuthCapability
import com.boomsset.domain.AssetClass
import com.boomsset.domain.Money
import com.boomsset.domain.Period
import com.boomsset.ui.InfoTooltip
import com.boomsset.ui.bpToPercent
import com.boomsset.ui.bpToSignedPercent
import com.boomsset.ui.fallColor
import com.boomsset.ui.formatSigned
import com.boomsset.ui.formatWithCurrency
import com.boomsset.ui.label
import com.boomsset.ui.lastRecordDescription
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
                // 空状态下也要能开应用锁 —— 提前 return 会砍掉这个入口，
                // 这是 AGENTS.md 里那条「空状态不要用提前 return」的教训
                AppLockToggle(lockState, onToggleLock)
            }

            else -> {
                SummaryCard(state)
                BaseCurrencySelector(state.baseCurrency, onSelectBaseCurrency)
                ChartSection(
                    state = state,
                    onSelectPeriod = onSelectPeriod,
                    onSelectChartMode = onSelectChartMode,
                    onSelectChartStyle = onSelectChartStyle,
                    onToggleClass = onToggleClass,
                )
                if (state.unpricedCount > 0) UnpricedWarning(state.unpricedCount)
                AppLockToggle(lockState, onToggleLock)
            }
        }
    }
}

/**
 * 顶部总资产卡片。
 *
 * 原来是**四五行句子**（"净值增长 +2.10%（含新增投入）"、"浮动盈亏 …"、
 * "仅覆盖已填成本的 3 项资产"），每行都要读完整句才知道那个数是什么，而且：
 * - **没说数据是哪天的** —— 记快照不记流水，净值不会自己更新，三个月前的记录和
 *   今天的记录长得一模一样；这是这类 App 最该显示、我们偏偏没显示的一个数
 * - **没说"增长"是相比什么时候** —— 同一个 +2% 在按月/按季/按年下比的起点完全不同
 * - **只有百分比没有金额** —— "+2%" 记不住，"+¥12,345" 才记得住
 * - **总资产和总负债根本看不到**，负债率也没有
 *
 * 现在分三段（参考家庭记账类 App 的顶部卡片做法）：
 * 1. 净值本身 + 数据是哪天记的
 * 2. 总资产 / 总负债 的分格（标签在上、数值在下），一眼扫到的是数值
 * 3. 净值增长 / 浮动盈亏 整行一条（标签在左、数值在右），口径解释收进 (i)
 *
 * 后两段用了**不同的排布**不是随手写的：短标签短数值适合分格，
 * 十来个汉字的标签并排就会连数值一起挤断行（见 [MetricRow] 上的注释）。
 *
 * **负债那一段只在真的有负债时出现** —— 无债用户看到"总负债 ¥0.00 / 负债率 0.00%"
 * 是纯噪音，而且此时总资产恒等于净值，再写一遍也是重复。
 */
@Composable
private fun SummaryCard(state: NetWorthUiState) {
    val point = state.series?.latest
    val net = point?.netWorth ?: Money.ZERO
    val currency = state.baseCurrency
    val onContainer = MaterialTheme.colorScheme.onPrimaryContainer

    // 卡片用品牌色的浅色容器打底 —— 反馈是净值页太灰暗；整页只有这一处用容器强调，
    // 不会和"表面是中性白灰"的整体设计冲突（见 AGENTS.md）。
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "当前净值",
                    style = MaterialTheme.typography.labelMedium,
                    color = onContainer,
                )
                Text(
                    net.formatWithCurrency(currency),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = onContainer,
                )
                // 数据新鲜度。没有任何快照时不显示这一行（新用户走的是空状态分支，
                // 但归档全部资产后也可能落到这里）
                lastRecordDescription(state.lastRecordedDate, state.daysSinceLastRecord)?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = onContainer)
                }
            }

            if (point != null && !point.totalLiabilities.isZero) {
                // 两列而不是三列：360dp 宽的手机上三列每格只有 90dp，
                // 七位数金额（¥1,234,567.00）就要断行 —— 金额断行比多占一行难看得多。
                // 负债率跟在总负债下面当注脚，它本来就是这两个数除出来的。
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    KpiCell(
                        label = "总资产",
                        value = point.totalAssets.formatWithCurrency(currency),
                        modifier = Modifier.weight(1f),
                    )
                    KpiCell(
                        label = "总负债",
                        value = point.totalLiabilities.formatWithCurrency(currency),
                        // null = 总资产 ≤ 0，此时比率无意义。不写成 0% —— 那会被读成"没负债"
                        sub = "负债率 ${point.liabilityRatioBp?.bpToPercent() ?: "—"}",
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            // 净值增长和浮动盈亏是**两个不同口径**，必须写清区别 ——
            // 见 docs/domain.md「增长率：两种口径必须分开」。
            // 完整解释收进 (i)，不再占一整行常驻文字。
            //
            // 这两条不做成上面那种分格：标签本身就有十来个汉字，并排时每格
            // 只剩 120dp，**标签和数值都会断行**（360dp 实测："净值增长（含新增投"
            // 换行、"-¥12,000.00 ·" 后面吊着一个分隔点）。而且两个标签换行行数不同时，
            // 下面的数值会一高一低，分格排布反而更乱。改成整行「标签在左、数值在右」：
            // 数值先量、永远完整，宽度不够只让标签折行。
            MetricRow(
                label = "净值增长（含新增投入）",
                value = growthValue(state),
                sub = growthSub(state),
                valueColor = signedColor(state.series?.growthAbsolute?.minorUnits),
                // 注意这里**不能**写 Markdown 的 `**加粗**`：`Text` 不解析标记，
                // 星号会原样显示在气泡里（模拟器上截图确认过）。要强调就靠措辞和「」。
                info = "「净值增长」= 期末净值 ÷ 期初净值 − 1，连你新存进去的钱一起算 —— " +
                    "这个月存一万工资进来，它也会涨，那不是赚的。" +
                    "「浮动盈亏」= 市值 − 成本，才反映投资本身的表现，" +
                    "但它只覆盖填了成本的那几项资产。",
            )
            MetricRow(
                label = "浮动盈亏（投资本身）",
                value = pnlValue(state),
                sub = pnlSub(state),
                valueColor = signedColor(state.pnl?.takeIf { it.hasCoverage }?.pnl?.absolute?.minorUnits),
            )
        }
    }
}

/** 净值增长：**含新增投入**，金额和百分比一起给。 */
private fun growthValue(state: NetWorthUiState): String {
    val series = state.series
    val delta = series?.growthAbsolute
    val bp = series?.growthBp
    return when {
        // 只有一次记录时没有期初，显示"—"并在注脚说清缺什么 —— 空着会让人以为是 0
        delta == null -> "—"
        // 一直没更新估值时结转会让首尾两点完全相等，这在本 App 里很常见。
        // "¥0.00 · 0.00%" 要读两个数才知道"没动"，直说更快
        delta.isZero -> "没有变化"
        bp == null -> delta.formatSigned(state.baseCurrency)
        else -> "${delta.formatSigned(state.baseCurrency)} · ${bp.bpToSignedPercent()}"
    }
}

/**
 * 增长的基准是哪一期。同一个 +2% 在按月/按季/按年下比的起点完全不同。
 *
 * 没有数的时候必须说清是**哪一种**没有：只记过一次，和"记过但两端不可比"，
 * 用户要做的事完全不同（一个是再记一次，一个是去补行情/汇率）。
 */
private fun growthSub(state: NetWorthUiState): String? {
    val series = state.series ?: return null
    if (!series.hasBaseline) return "只有一次记录，没有可比的期初"
    if (series.growthAbsolute == null) return "期初或期末有资产无法估值，两端不可比"
    return series.baselineDate?.periodLabel(series.period)?.let { "相比 $it" }
}

/** 浮动盈亏：**剔除新增投入**。 */
private fun pnlValue(state: NetWorthUiState): String {
    val pnl = state.pnl?.takeIf { it.hasCoverage } ?: return "—"
    val rate = pnl.pnl.returnBp
    val absolute = pnl.pnl.absolute.formatSigned(state.baseCurrency)
    return if (rate == null) absolute else "$absolute · ${rate.bpToSignedPercent()}"
}

/** 覆盖面必须写出来：没填成本的资产不参与，这个数只代表填了的那几项。 */
private fun pnlSub(state: NetWorthUiState): String {
    // 没有覆盖时不是"盈亏为 0"，是"没填成本所以算不了" —— 顺便告诉用户缺什么
    val pnl = state.pnl?.takeIf { it.hasCoverage } ?: return "还没填成本"
    return "覆盖 ${pnl.coveredAssetIds.size} 项资产"
}

/** 涨跌配色：中国股市语境红涨绿跌，见 [com.boomsset.ui.GainLossColors]。 */
@Composable
private fun signedColor(minorUnits: Long?): Color = when {
    minorUnits == null || minorUnits == 0L -> MaterialTheme.colorScheme.onPrimaryContainer
    minorUnits > 0L -> riseColor()
    else -> fallColor()
}

/**
 * 「标签在上、数值在下」的一格。
 *
 * 数值用 `titleSmall` 而不是 `bodySmall`：这一格里数值才是要被扫视的东西，
 * 标签是给它定口径的注脚。整句式的 "净值增长 +2.10%（含新增投入）" 做不到这一点 ——
 * 那行里数字和文字一样重。
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
 * 整行一个指标：**标签在左、数值在右**，注脚另起一行贴在标签下面。
 *
 * 给标签加 `weight(1f)` 而不是给数值 —— Row 先按完整宽度量没有 weight 的子项，
 * 剩下的才分给带 weight 的。所以**数值总是完整的一行**，宽度不够时折的是标签
 * （标签是句子，折行读起来无所谓；金额折行会把一个数劈成两半）。
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
            // (i) 要紧跟在标签文字后面，不能直接放进外层 Row —— 标签占了 weight(1f)
            // 会把 (i) 推到数值旁边，看起来像是在解释数值。`fill = false` 让标签
            // 只占它真正需要的宽度，(i) 才贴着标签末尾。
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
 * 应用锁开关。
 *
 * 不可用时**说明原因并禁用**，而不是让用户开了之后发现进不去 ——
 * 「没录入」是去系统设置能解决的，「不支持」是无解的，两者要说清区别。
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
                        "开启后每次打开旺资都需要验证身份。开启时会先验一次。"
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
 * 基准币种（以哪种币种看净值）。
 *
 * 原来是一排 9 个 `FilterChip` + 标签 + 一行说明，在手机上占掉三四行 ——
 * 实机反馈"位置占比太大"。这一页是只读的趋势概览，最值钱的是概览卡片和图表；
 * 而基准币种默认 CNY、**几乎从不改**，不该占这么大一块。
 * 添加资产页早就因为同样的理由把币种换成了下拉（见 `CurrencyDropdown`），这里跟上。
 *
 * 说明文字收进 (i) —— "切换不会改写数据"是**用户切之前**才需要的一次性保证，
 * 常驻显示只是噪音。
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
                // 箭头直接写字形，和项目里"＋""ⓘ"的用法一致（没有引图标依赖）
                Text("$selected ▾", style = MaterialTheme.typography.labelLarge)
            }
            // 菜单锚在按钮这个 Box 上 —— DropdownMenu 自己会算位置，
            // 不需要 ExposedDropdownMenuBox 那套（那是给文本输入框用的，
            // 在这里会带进一个 56dp 高的输入框，正好和"省空间"相反）
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                SUPPORTED_CURRENCIES.forEach { code ->
                    DropdownMenuItem(
                        text = { Text(code) },
                        // 菜单展开时会盖住按钮本身（实机确认），所以当前币种必须在菜单里
                        // 也标出来 —— 否则展开后就看不出现在是哪一种了。
                        // 用勾而不是只换颜色：颜色单独承载状态对色弱用户不成立。
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
 * 图表区：控件 + 图例 + 图表 + 脚注。
 *
 * 四种组合（总资产/按大类 × 柱状图/趋势图）共用这一块，**能不能画**的判断也集中在这里 ——
 * 分散到各个图表里的话，"什么都没画出来"就会变成一张空图，而不是一句说明
 * （AGENTS.md 教训 10 就是这么来的）。
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
            // ⚠️ Text 不解析 Markdown，这里不能写 **强调**，会原样显示成四个星号
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
 * 所有控件统一 40dp 高。
 *
 * `FlowRow` 里的项默认按顶端对齐，而 `OutlinedButton`（40dp）和 `Switch`（32dp）
 * 高度不一样，不统一的话下拉按钮和开关会差着几 dp 错开。
 */
private val CONTROL_HEIGHT = 40.dp

@Composable
private fun LabeledSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.height(CONTROL_HEIGHT),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * 周期从一排 `FilterChip` 改成下拉。
 *
 * 理由和币种那个一样（见 [BaseCurrencySelector]）：这一页最值钱的是概览卡片和图表，
 * 三个常驻 chip 占一整行、而三选一的下拉只占一个按钮。省下来的横向空间正好给了
 * 旁边两个新开关 —— 三个控件挤在一行才放得下。
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
                    // 菜单会盖住按钮本身，当前选中项必须在菜单里也标出来（同币种下拉）
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
 * 大类图例，勾选控制画哪几类。
 *
 * 颜色**画在复选框上**而不是另加一个色块：色块和名字之间隔着一个复选框会让
 * "这个颜色是这一类"变得不那么直接，而且五项各多 16dp 在手机宽度上就是多折一行。
 * 未勾选时方框是空心的、边框仍是该类的颜色，所以颜色和名称的对应关系不会因为
 * 取消勾选就消失（浅色模式下几个大类色低于 3:1，**必须靠"色块旁边永远有名字"补偿**，
 * 见 AGENTS.md 的配色约束）。
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
 * 按大类看时的口径说明。
 *
 * 第一句是必须的：这张图的合计**不等于**上面那个净值 —— 分类看的是净敞口、
 * 且只算"计入配置"的资产。不说清楚，用户会以为哪里算错了。
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
                // Vico 的堆叠柱原生支持负值，负的那段画在零线下方，是真实情况，不遮掩
                ChartStyle.COLUMN -> "有大类的净敞口是负的（负债超过了这类资产），画在零线下方。"
                // 堆叠面积靠"累计值单调递增"才成立，有负段就会分层错位 —— 宁可换一种画法
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
private fun UnpricedWarning(count: Int) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("$count 项资产无法估值", style = MaterialTheme.typography.titleSmall)
            Text(
                // 同上：`Text` 不解析 Markdown，原来这里的 `**没有**` 在界面上
                // 就是四个星号（和上面那个 tooltip 是同一个坑）
                "可能是缺行情/汇率，也可能是份额或价格的数量级超出了可计算范围。" +
                    "这些资产没有计入上面的净值 —— 不按 0 计算，是为了避免静默低估。",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/**
 * 空状态的上手指引。
 *
 * 原来只有一句"点右下角加号"。问题是**新用户不知道这个 App 的工作方式**：
 * 它不记流水、记快照，这跟大部分记账 App 相反 —— 不先说清楚，用户会按记流水的
 * 预期去用，然后觉得功能缺失。所以这里把三步说完，并且指出配置可以先设。
 *
 * 刻意保持短：三条各一行。空状态放长篇说明没人看。
 */
@Composable
private fun EmptyHint() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("还没有资产", style = MaterialTheme.typography.titleMedium)
            Text(
                "旺资不记流水，记的是快照 —— 你不用逐笔录收支，只要定期更新每项资产现在值多少。",
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
