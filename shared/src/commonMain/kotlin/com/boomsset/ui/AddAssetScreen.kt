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
 * Add asset. **A standalone screen, not a dialog.**
 *
 * It used to be an `AlertDialog`: on a real device there's too little room — once the
 * keyboard pops up only two or three lines remain visible, but this form can have seven
 * or eight fields. A dialog suits "one or two decisions," not entering a whole record.
 *
 * **Two steps, and the first step picks a subtype, not an asset class.**
 * The first step used to have the user pick an "asset class" directly — but users don't
 * know which class Alipay falls under, and jargon like "alternative/physical assets"
 * carries no information for non-expert users either. So it's inverted: pick "Alipay,"
 * and the asset class and default valuation mode are both derived from the subtype (the
 * built-in subtype table already carries both fields). The asset class is shown only as
 * a result — it incidentally teaches the user, but doesn't require understanding it to
 * complete the action.
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
 * Step 1: pick a subtype.
 *
 * Liabilities form their own group — putting mortgage/car loan/credit card under an
 * asset class would make them look like assets. Once grouped, picking "mortgage"
 * simultaneously decides both "this is a liability" and "it offsets alternative/physical
 * assets."
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
 * Step 2: fill in details. The subtype is already chosen, so the asset class, default
 * valuation mode, and whether it's a liability all have initial values.
 *
 * **`verticalScroll` alone isn't enough — `imePadding` is also needed.** With only
 * `verticalScroll`, showing the keyboard doesn't change the Column's visible height — the
 * scroll container still computes as if "the whole screen is visible," so once a focused
 * field is covered by the keyboard, it won't scroll further to reveal it (real-device
 * feedback: the "holding quantity" / "total cost basis" fields were covered by the keyboard
 * when using share-based quotes). `imePadding()` makes the content area shrink with the
 * keyboard height, so the scroll container actually knows the viewport got shorter, and the
 * focused field's "scroll into view" logic then really does scroll that extra bit.
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
    // Name is pre-filled with the subtype name — in most cases that's exactly what the
    // user wants ("Alipay"); if it needs changing, it's just adding a couple of characters
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

    // ⚠️ QUOTED's currency is determined by the ticker's prefix; the user doesn't choose it.
    // The quoted price is denominated in that market's currency, while valuation converts
    // using asset.currency — a mismatch would silently produce wrong numbers (e.g. converting
    // a Hong Kong stock price as if it were RMB). Forcing them to align avoids this bug.
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
        // The chosen subtype + the asset class it derives. Can go back a step to change it
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
            // Explicit contentDescription: OutlinedTextField's label only maps to an
            // accessibility label in certain states (confirmed on iOS: it disappears once
            // focused), so screen reader users would hear nothing.
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

            AmountField(
                value = amountText,
                onValueChange = { amountText = it },
                label = if (isLiability) "欠款金额" else "当前市值",
                currency = currency,
                current = null,
                isError = amountText.isNotBlank() && amount == null,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = FIELD_AMOUNT },
            )
        }

        if (!isLiability) {
            AmountField(
                value = costText,
                onValueChange = { costText = it },
                label = "总投入成本（可留空）",
                currency = currency,
                current = null,
                isError = costText.isNotBlank() && cost == null,
                note = "填了才能显示浮动盈亏和收益率",
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
 * Currency uses a dropdown rather than a row of chips.
 *
 * A row of chips would take up two or three lines, and the vast majority of assets are
 * already in the base currency and never need changing — giving a "rarely-changed option"
 * the most prominent spot on the form gets the priorities backwards.
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
 * Accessibility identifiers for the input fields.
 *
 * Pulled out into constants because UI tests need to locate them by the same string —
 * literals scattered across two places would eventually drift out of sync.
 */
const val FIELD_NAME = "field-asset-name"
const val FIELD_AMOUNT = "field-asset-amount"
const val FIELD_COST = "field-asset-cost"
const val FIELD_CURRENCY = "field-asset-currency"

/** Input parameters for creating a new asset. With this many fields, a data class is safer
 * than ten positional parameters. */
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
 * "Yuan" string → cents. Delegates to [com.boomsset.domain.parseMoneyMinor].
 */
internal fun String.toMinorUnitsOrNull(): Long? = parseMoneyMinor(this)
