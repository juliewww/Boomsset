package com.boomsset.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.boomsset.data.LIABILITY_SUBTYPE_NAMES
import com.boomsset.data.SUPPORTED_CURRENCIES
import com.boomsset.domain.AssetClass
import com.boomsset.domain.AssetSubtype
import com.boomsset.domain.Money
import com.boomsset.domain.Quantity
import com.boomsset.domain.ValuationMode
import com.boomsset.domain.parseMoneyMinor
import com.boomsset.domain.parseQuantity
import com.boomsset.network.TencentQuoteSource

/**
 * 添加资产。**独立页面，不是对话框。**
 *
 * 原来是 `AlertDialog`：实机上空间太窄，键盘一弹就只剩两三行可用，
 * 而这个表单最多有七八个字段。对话框适合"一两个决定"，不适合录一条完整记录。
 *
 * **两步，而且第一步是选品种、不是选大类。**
 * 原来第一步要用户自己选「大类」—— 但用户不知道支付宝该算哪一类，
 * "另类实物"这种术语对非专业用户也没有信息量。反过来做：选「支付宝」，
 * 大类和默认估值方式都从品种带出来（内置品种表本来就带这两个字段）。
 * 大类只作为结果显示出来，顺带教会用户，但不要求他理解才能完成操作。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddAssetScreen(
    subtypes: List<AssetSubtype>,
    defaultCurrency: String,
    onCancel: () -> Unit,
    onConfirm: (NewAsset) -> Unit,
    modifier: Modifier = Modifier,
) {
    var picked by remember { mutableStateOf<AssetSubtype?>(null) }

    val current = picked
    if (current == null) {
        SubtypePicker(
            subtypes = subtypes,
            onPick = { picked = it },
            modifier = modifier,
        )
    } else {
        AssetDetailForm(
            subtype = current,
            defaultCurrency = defaultCurrency,
            onChangeSubtype = { picked = null },
            onCancel = onCancel,
            onConfirm = onConfirm,
            modifier = modifier,
        )
    }
}

/**
 * 第一步：选品种。
 *
 * 负债单独成组 —— 房贷/车贷/信用卡这些放在资产的大类里会让人以为是资产。
 * 分组之后，选「房贷」就同时确定了"这是负债"和"抵扣另类实物"两件事。
 */
@Composable
private fun SubtypePicker(
    subtypes: List<AssetSubtype>,
    onPick: (AssetSubtype) -> Unit,
    modifier: Modifier = Modifier,
) {
    val visible = subtypes.filterNot { it.hidden }
    val liabilities = visible.filter { it.name in LIABILITY_SUBTYPE_NAMES }
    val assets = visible - liabilities.toSet()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text(
            "要记的是什么？",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            "选一个最接近的类型就行 —— 属于哪个大类会自动带出来，不用你判断。",
            style = MaterialTheme.typography.bodySmall,
        )

        AssetClass.displayOrder.forEach { assetClass ->
            val group = assets.filter { it.assetClass == assetClass }
            if (group.isNotEmpty()) {
                SubtypeGroup(
                    title = assetClass.label(),
                    hint = assetClass.hint(),
                    items = group,
                    onPick = onPick,
                )
            }
        }

        if (liabilities.isNotEmpty()) {
            SubtypeGroup(
                title = "负债",
                hint = "会从它归属的大类里抵扣，所以比例仍然加总 100%",
                items = liabilities,
                onPick = onPick,
            )
        }

        Text(
            "找不到完全对应的？选最接近的一个，名称里写清楚具体是什么 —— " +
                "比如品种选「银行活期」，名称写「招行活期」。",
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun SubtypeGroup(
    title: String,
    hint: String,
    items: List<AssetSubtype>,
    onPick: (AssetSubtype) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(hint, style = MaterialTheme.typography.labelSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items.forEach { subtype ->
                FilterChip(
                    selected = false,
                    onClick = { onPick(subtype) },
                    label = { Text(subtype.name) },
                )
            }
        }
    }
}

/**
 * 第二步：填详情。品种已定，所以大类、默认估值方式、是否负债都有了初值。
 *
 * **`verticalScroll` 不够，还要 `imePadding`。** 只有 `verticalScroll` 时，键盘弹出
 * 不会改变 Column 的可视高度 —— 滚动容器仍然按"整屏都看得见"来算，聚焦字段被键盘挡住
 * 之后也不会多滚一截露出来（实机反馈：按份额取行情时的「持有份额」「总投入成本」
 * 字段被键盘挡住）。`imePadding()` 让内容区域随键盘高度收缩，滚动容器才知道
 * 视口变矮了，聚焦字段的"滚入可视区"逻辑才会真的多滚那一截。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AssetDetailForm(
    subtype: AssetSubtype,
    defaultCurrency: String,
    onChangeSubtype: () -> Unit,
    onCancel: () -> Unit,
    onConfirm: (NewAsset) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 名称预填品种名 —— 大多数情况这就是用户想写的（"支付宝"），要改也就是加两个字
    var name by remember { mutableStateOf(subtype.name) }
    var currency by remember { mutableStateOf(defaultCurrency) }
    var amountText by remember { mutableStateOf("") }
    var costText by remember { mutableStateOf("") }
    var isLiability by remember { mutableStateOf(subtype.name in LIABILITY_SUBTYPE_NAMES) }
    var includeInAllocation by remember { mutableStateOf(true) }
    var mode by remember { mutableStateOf(subtype.defaultValuationMode) }
    var symbolText by remember { mutableStateOf("") }
    var quantityText by remember { mutableStateOf("") }

    val amount = amountText.toMinorUnitsOrNull()
    val cost = costText.takeIf { it.isNotBlank() }?.toMinorUnitsOrNull()
    val quantity = parseQuantity(quantityText)
    val symbol = symbolText.trim()
    val symbolOk = TencentQuoteSource.isRecognized(symbol)
    val isQuoted = mode == ValuationMode.QUOTED && !isLiability

    // ⚠️ QUOTED 的币种由代码前缀决定，不让用户选。
    // 行情价是以该市场的币种计价的，而估值时按 asset.currency 折算 ——
    // 两者不一致会静默算错（比如港股价按人民币折算）。强制对齐避免这个 bug。
    val effectiveCurrency = if (isQuoted && symbolOk) {
        TencentQuoteSource.currencyOf(symbol)
    } else {
        currency
    }

    val canConfirm = name.isNotBlank() && when {
        isQuoted -> symbolOk && quantity != null
        else -> amount != null
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // 已选的品种 + 它带出来的大类。可以退回上一步换
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(subtype.name, style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (isLiability) "负债 · 从${subtype.assetClass.label()}抵扣"
                        else "归入${subtype.assetClass.label()}",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                TextButton(onClick = onChangeSubtype) { Text("换一个") }
            }
        }

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("名称") },
            supportingText = { Text("写清楚是哪一个，比如「招行活期」") },
            singleLine = true,
            // 显式 contentDescription：OutlinedTextField 的 label 只在某些状态下才映射成
            // 无障碍 label（实测 iOS 上聚焦后就没了），读屏用户会听到空白。
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = FIELD_NAME },
        )

        if (!isLiability) {
            Text("怎么估值", style = MaterialTheme.typography.labelMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = mode == ValuationMode.MANUAL,
                    onClick = { mode = ValuationMode.MANUAL },
                    label = { Text("手动填市值") },
                )
                FilterChip(
                    selected = mode == ValuationMode.QUOTED,
                    onClick = { mode = ValuationMode.QUOTED },
                    label = { Text("按份额取行情") },
                )
            }
        }

        if (isQuoted) {
            OutlinedTextField(
                value = symbolText,
                onValueChange = { symbolText = it },
                label = { Text("行情代码") },
                supportingText = {
                    Text(
                        "沪市 sh600519 / 深市 sz000858 / 港股 hk00700 / 美股 usAAPL" +
                            if (symbolOk) "。币种自动设为 $effectiveCurrency" else "",
                    )
                },
                singleLine = true,
                isError = symbolText.isNotBlank() && !symbolOk,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = quantityText,
                onValueChange = { quantityText = it },
                label = { Text("持有份额") },
                supportingText = { Text("市值 = 份额 × 行情单价。取不到行情时显示「无法估值」，不会按 0 算") },
                singleLine = true,
                isError = quantityText.isNotBlank() && quantity == null,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            CurrencyDropdown(
                selected = currency,
                defaultCurrency = defaultCurrency,
                onSelect = { currency = it },
            )

            OutlinedTextField(
                value = amountText,
                onValueChange = { amountText = it },
                label = { Text(if (isLiability) "欠款金额" else "当前市值") },
                singleLine = true,
                isError = amountText.isNotBlank() && amount == null,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = FIELD_AMOUNT },
            )
        }

        if (!isLiability) {
            OutlinedTextField(
                value = costText,
                onValueChange = { costText = it },
                label = { Text("总投入成本（可留空）") },
                supportingText = { Text("填了才能显示浮动盈亏和收益率") },
                singleLine = true,
                isError = costText.isNotBlank() && cost == null,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = FIELD_COST },
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = isLiability, onCheckedChange = { isLiability = it })
            Text("这是一笔负债", style = MaterialTheme.typography.bodyMedium)
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = includeInAllocation,
                onCheckedChange = { includeInAllocation = it },
            )
            Text("计入配置比例", style = MaterialTheme.typography.bodyMedium)
        }
        if (!includeInAllocation) {
            Text(
                "取消勾选后，这项不进配置饼图的分子和分母。自住房常这么处理。",
                style = MaterialTheme.typography.labelSmall,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                enabled = canConfirm,
                onClick = {
                    onConfirm(
                        NewAsset(
                            name = name.trim(),
                            assetClass = subtype.assetClass,
                            subtypeId = subtype.id,
                            currency = effectiveCurrency,
                            mode = if (isLiability) ValuationMode.MANUAL else mode,
                            quoteSymbol = if (isQuoted) symbol else null,
                            value = if (isQuoted) null else Money(amount!!),
                            quantity = if (isQuoted) quantity else null,
                            costBasis = cost?.let { Money(it) },
                            isLiability = isLiability,
                            includeInAllocation = includeInAllocation,
                        ),
                    )
                },
            ) { Text("添加") }
            TextButton(onClick = onCancel) { Text("取消") }
        }
    }
}

/**
 * 币种用 dropdown 而不是一排 chip。
 *
 * chip 排开来会占掉两三行，而绝大多数资产就是基准币种、根本不用改 ——
 * 让一个"很少改的选项"占据表单最显眼的一块是本末倒置。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CurrencyDropdown(
    selected: String,
    defaultCurrency: String,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
        ) {
            OutlinedTextField(
                value = selected,
                onValueChange = {},
                readOnly = true,
                label = { Text("币种") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                    .semantics { contentDescription = FIELD_CURRENCY },
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                SUPPORTED_CURRENCIES.forEach { code ->
                    DropdownMenuItem(
                        text = { Text(code) },
                        onClick = {
                            onSelect(code)
                            expanded = false
                        },
                    )
                }
            }
        }
        if (selected != defaultCurrency) {
            Text(
                "非基准币种。净值会按当时汇率折算成 $defaultCurrency —— " +
                    "取不到汇率时这项显示「无法估值」，不会按 1:1 算。",
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

/**
 * 输入框的无障碍标识。
 *
 * 抽成常量是因为 UI 测试要按同样的字符串定位 —— 字面量散在两处早晚会不一致。
 */
const val FIELD_NAME = "field-asset-name"
const val FIELD_AMOUNT = "field-asset-amount"
const val FIELD_COST = "field-asset-cost"
const val FIELD_CURRENCY = "field-asset-currency"

/** 新建资产的入参。字段多了之后用 data class 比十个位置参数安全。 */
data class NewAsset(
    val name: String,
    val assetClass: AssetClass,
    val subtypeId: Long,
    val currency: String,
    val mode: ValuationMode,
    val quoteSymbol: String?,
    val value: Money?,
    val quantity: Quantity?,
    val costBasis: Money?,
    val isLiability: Boolean,
    val includeInAllocation: Boolean,
)

/**
 * 「元」字符串 → 分。委托给 [com.boomsset.domain.parseMoneyMinor]。
 */
internal fun String.toMinorUnitsOrNull(): Long? = parseMoneyMinor(this)
