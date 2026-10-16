package dev.uednd.hydros.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import java.time.YearMonth

private const val FIRST_BILL_YEAR = 2017
private val MONTH_WHEEL_HEIGHT = 184.dp
private val MONTH_WHEEL_ROW_HEIGHT = 56.dp
private val MONTH_WHEEL_EDGE = (MONTH_WHEEL_HEIGHT - MONTH_WHEEL_ROW_HEIGHT) / 2

/** Snapping year/month wheels presented inside the shared Hydros bottom sheet. */
@Composable
internal fun BillMonthPickerSheet(
    month: YearMonth,
    onDismiss: () -> Unit,
    onConfirm: (YearMonth) -> Unit
) {
    var draft by remember(month) { mutableStateOf(month) }
    val currentMonth = remember { YearMonth.now() }
    val colors = MaterialTheme.colorScheme
    val haptics = rememberHydrosHaptics()

    HydrosBottomSheetContainer(onDismiss = onDismiss, sheetGesturesEnabled = false) {
        Text(
            "选择月份",
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 22.dp)
                .semantics { heading() },
            style = MaterialTheme.typography.titleLarge,
            color = colors.onSurface
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("年份", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant, textAlign = TextAlign.Center)
            Text("月份", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        MonthWheelPair(month = draft, currentMonth = currentMonth, onMonthChange = { draft = it })
        HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.4f))
        Row(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            TextButton(onClick = onDismiss, modifier = Modifier.weight(1f).heightIn(min = 44.dp)) {
                Text("取消")
            }
            Button(onClick = { haptics.light(); onConfirm(draft) },
                modifier = Modifier.weight(1f).heightIn(min = 44.dp)) {
                Text("确定")
            }
        }
    }
}

@Composable
private fun MonthWheelPair(month: YearMonth, currentMonth: YearMonth, onMonthChange: (YearMonth) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val maxMonth = if (month.year == currentMonth.year) currentMonth.monthValue else 12
    Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp).height(MONTH_WHEEL_HEIGHT)) {
        Row(Modifier.fillMaxSize()) {
            MonthWheelColumn(
                values = (FIRST_BILL_YEAR..currentMonth.year).toList(),
                selectedIndex = month.year - FIRST_BILL_YEAR,
                label = "年份",
                modifier = Modifier.weight(1f),
                onSelected = { index ->
                    val year = index + FIRST_BILL_YEAR
                    val yearMaxMonth = if (year == currentMonth.year) currentMonth.monthValue else 12
                    onMonthChange(YearMonth.of(year, month.monthValue.coerceAtMost(yearMaxMonth)))
                }
            ) { "${it}年" }
            MonthWheelColumn(
                values = (1..maxMonth).toList(),
                selectedIndex = month.monthValue - 1,
                label = "月份",
                modifier = Modifier.weight(1f),
                onSelected = { onMonthChange(YearMonth.of(month.year, it + 1)) }
            ) { "${it}月" }
        }
        HorizontalDivider(
            modifier = Modifier.align(Alignment.TopCenter).offset(y = MONTH_WHEEL_EDGE),
            color = colors.outlineVariant
        )
        HorizontalDivider(
            modifier = Modifier.align(Alignment.TopCenter).offset(y = MONTH_WHEEL_EDGE + MONTH_WHEEL_ROW_HEIGHT),
            color = colors.outlineVariant
        )
        Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(MONTH_WHEEL_EDGE)
            .background(Brush.verticalGradient(listOf(colors.surfaceContainerHigh, Color.Transparent))))
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(MONTH_WHEEL_EDGE)
            .background(Brush.verticalGradient(listOf(Color.Transparent, colors.surfaceContainerHigh))))
    }
}

@Composable
private fun MonthWheelColumn(
    values: List<Int>,
    selectedIndex: Int,
    label: String,
    modifier: Modifier = Modifier,
    onSelected: (Int) -> Unit,
    valueLabel: (Int) -> String
) {
    val centeredIndex = selectedIndex.coerceIn(values.indices)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = centeredIndex)
    val scope = rememberCoroutineScope()
    val latestOnSelected by rememberUpdatedState(onSelected)
    val flingBehavior = rememberSnapFlingBehavior(listState, SnapPosition.Center)

    LaunchedEffect(values.size) {
        if (listState.firstVisibleItemIndex != centeredIndex) listState.scrollToItem(centeredIndex)
    }
    LaunchedEffect(listState, values.size) {
        snapshotFlow {
            val layout = listState.layoutInfo
            val center = (layout.viewportStartOffset + layout.viewportEndOffset) / 2
            layout.visibleItemsInfo.minByOrNull { item -> kotlin.math.abs(item.offset + item.size / 2 - center) }?.index
        }.filterNotNull().distinctUntilChanged().collect { latestOnSelected(it) }
    }

    LazyColumn(
        modifier = modifier.height(MONTH_WHEEL_HEIGHT).clipToBounds().semantics { contentDescription = label },
        state = listState,
        flingBehavior = flingBehavior,
        contentPadding = PaddingValues(vertical = MONTH_WHEEL_EDGE)
    ) {
        itemsIndexed(values, key = { _, value -> value }) { index, value ->
            Box(
                Modifier.fillMaxWidth().height(MONTH_WHEEL_ROW_HEIGHT)
                    .semantics { selected = index == centeredIndex }
                    .clickable { scope.launch { listState.animateScrollToItem(index) } },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    valueLabel(value),
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 20.sp),
                    color = if (index == centeredIndex) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.22f),
                    fontWeight = if (index == centeredIndex) FontWeight.SemiBold else FontWeight.Normal,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
