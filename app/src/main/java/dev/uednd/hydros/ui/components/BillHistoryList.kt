package dev.uednd.hydros.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.uednd.hydros.R
import dev.uednd.hydros.ui.state.BillUi
import dev.uednd.hydros.ui.state.BillType
import java.time.YearMonth

/** Adds the complete bill history heading, month picker, status and rows to a parent lazy list. */
fun LazyListScope.billHistoryList(
    month: String,
    billType: BillType,
    bills: List<BillUi>,
    loading: Boolean,
    error: Boolean,
    errorMessage: String? = null,
    onMonth: (String) -> Unit,
    onBillType: (BillType) -> Unit,
    onBill: (String) -> Unit
) {
    val selectedMonth = YearMonth.parse(month)
    val monthBills = bills.filter { it.month == selectedMonth && it.recharge == (billType == BillType.RECHARGE) }

    item("bills-heading", "heading") {
        var showMonths by rememberSaveable { mutableStateOf(false) }
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "账单记录",
                Modifier.weight(1f).padding(start = 16.dp, end = 8.dp),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Normal),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(onClick = { showMonths = true }) {
                Text("${selectedMonth.year}年${selectedMonth.monthValue}月")
                Glyph(R.drawable.ic_chevron, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(8.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            BillType.entries.forEachIndexed { index, type ->
                SegmentedButton(
                    selected = billType == type,
                    onClick = { if (billType != type) onBillType(type) },
                    shape = SegmentedButtonDefaults.itemShape(index, BillType.entries.size),
                    label = { Text(type.label) },
                    icon = {},
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        if (showMonths) {
            BillMonthPickerSheet(
                month = selectedMonth,
                onDismiss = { showMonths = false },
                onConfirm = { selected ->
                    showMonths = false
                    if (selected != selectedMonth) onMonth(selected.toString())
                }
            )
        }
    }

    when {
        loading -> item("bills-loading", "loading") {
            InlineState("正在加载${billType.label}", "正在查询所选月份…", loading = true)
        }
        error -> item("bills-error", "error") {
            InlineState("${billType.label}加载未完成", errorMessage ?: "请重新加载账单", action = "重新加载${billType.label}", onAction = { onMonth(month) })
        }
        monthBills.isEmpty() -> item("bills-empty", "empty") {
            StateCard("所选月份暂无${billType.label}", "服务端尚无对应记录。")
        }
        else -> itemsIndexed(
            monthBills,
            key = { _, bill -> "bill:${bill.id}" },
            contentType = { _, _ -> "bill" }
        ) { index, bill ->
            LazySectionItem(
                first = index == 0,
                last = index == monthBills.lastIndex
            ) {
                BillRow(bill, if (bill.detailAvailable) ({ onBill(bill.id) }) else null)
                if (index < monthBills.lastIndex) SubtleDivider()
            }
        }
    }
}
