package org.cqutosp.hydros.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.cqutosp.hydros.data.storage.FontSizeOption
import org.cqutosp.hydros.data.storage.PostSessionDestination
import org.cqutosp.hydros.ui.components.*
import org.cqutosp.hydros.ui.theme.LocalSystemDensity
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun DisplaySettingsScreen(savedScale: Float, onPreview: (Float?) -> Unit, onSave: suspend (Float) -> Boolean,
    postSessionDestination: PostSessionDestination, onPostSessionDestination: (PostSessionDestination) -> Unit) {
    val options = FontSizeOption.entries
    var draft by remember(savedScale) { mutableFloatStateOf(savedScale) }
    var saving by remember { mutableStateOf(false) }
    val index = options.indexOfFirst { it.scale == draft }.coerceAtLeast(0)
    val haptics = rememberHydrosHaptics()
    val scope = rememberCoroutineScope()
    val latestPreview by rememberUpdatedState(onPreview)
    DisposableEffect(Unit) { onDispose { latestPreview(null) } }
    PageColumn {
        Section("字体大小") {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("A", Modifier.heightIn(min = 48.dp).wrapContentHeight(Alignment.CenterVertically),
                        fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Column(Modifier.weight(1f)) {
                        CompositionLocalProvider(LocalDensity provides LocalSystemDensity.current) {
                            Slider(
                                value = index.toFloat(), valueRange = 0f..3f, steps = 2, enabled = !saving,
                                modifier = Modifier.fillMaxWidth().semantics {
                                    contentDescription = "字体大小"
                                    stateDescription = options[index].label
                                },
                                onValueChange = { value ->
                                    val next = options[value.roundToInt().coerceIn(0, 3)].scale
                                    if (next != draft) { haptics.medium(); draft = next; onPreview(next) }
                                },
                                onValueChangeFinished = {
                                    if (draft != savedScale) {
                                        val selected = draft
                                        saving = true
                                        scope.launch {
                                            try {
                                                if (!onSave(selected)) draft = savedScale
                                            } finally { saving = false; onPreview(null) }
                                        }
                                    } else onPreview(null)
                                },
                            )
                            Box(Modifier.fillMaxWidth()) {
                                // "标准" 是第二档：居中在前 2/3 宽度的中点，即 0..3 的第 1 个停靠点。
                                Text("标准", Modifier.fillMaxWidth(2f / 3f), textAlign = TextAlign.Center,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    Text("A", Modifier.heightIn(min = 48.dp).wrapContentHeight(Alignment.CenterVertically),
                        fontSize = 40.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Section("结算后跳转") {
            Column(Modifier.selectableGroup()) {
                PostSessionDestination.entries.forEachIndexed { index, destination ->
                    RadioRow(
                        title = destination.label,
                        supporting = when (destination) {
                            PostSessionDestination.DEVICE -> "结束使用后回到首页设备页"
                            PostSessionDestination.BILL -> "结束使用后查看本次消费账单"
                        },
                        selected = destination == postSessionDestination,
                        onClick = { onPostSessionDestination(destination) }
                    )
                    if (index < PostSessionDestination.entries.lastIndex) SubtleDivider()
                }
            }
        }
    }
}
