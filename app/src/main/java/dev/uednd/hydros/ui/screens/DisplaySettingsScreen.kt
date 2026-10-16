package dev.uednd.hydros.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.uednd.hydros.data.storage.FontSizeOption
import dev.uednd.hydros.ui.theme.LocalSystemDensity
import dev.uednd.hydros.ui.components.*
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DisplaySettingsScreen(savedScale: Float, onPreview: (Float?) -> Unit, onSave: suspend (Float) -> Boolean) {
    val options = FontSizeOption.entries
    var draft by remember(savedScale) { mutableFloatStateOf(savedScale) }
    var saving by remember { mutableStateOf(false) }
    val index = options.indexOfFirst { it.scale == draft }.coerceAtLeast(0)
    val haptics = rememberHydrosHaptics()
    val scope = rememberCoroutineScope()
    val interactionSource = remember { MutableInteractionSource() }
    var sliderBounds by remember { mutableStateOf(Rect.Zero) }
    var thumbBounds by remember { mutableStateOf(Rect.Zero) }
    val density = LocalDensity.current
    val minimumTouchRadius = with(density) { 24.dp.toPx() }
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
                                interactionSource = interactionSource,
                                thumb = {
                                    SliderDefaults.Thumb(interactionSource, enabled = !saving,
                                        modifier = Modifier.onGloballyPositioned { thumbBounds = it.boundsInRoot() })
                                },
                                modifier = Modifier.fillMaxWidth()
                                    .onGloballyPositioned { sliderBounds = it.boundsInRoot() }
                                    .pointerInput(minimumTouchRadius) {
                                        awaitEachGesture {
                                            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                            val position = sliderBounds.topLeft + down.position
                                            val hitBounds = Rect(
                                                thumbBounds.center.x - maxOf(thumbBounds.width / 2f, minimumTouchRadius),
                                                thumbBounds.center.y - maxOf(thumbBounds.height / 2f, minimumTouchRadius),
                                                thumbBounds.center.x + maxOf(thumbBounds.width / 2f, minimumTouchRadius),
                                                thumbBounds.center.y + maxOf(thumbBounds.height / 2f, minimumTouchRadius)
                                            )
                                            // Leave thumb gestures to Material Slider. Block track taps/drags
                                            // before its built-in recognizers see them; semantics stay enabled.
                                            if (!hitBounds.contains(position)) {
                                                down.consume()
                                                do {
                                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                                    event.changes.forEach { it.consume() }
                                                } while (event.changes.any { it.pressed })
                                            } else {
                                                var dragged = false
                                                do {
                                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                                    val pointer = event.changes.find { it.id == down.id }
                                                    if (pointer != null && abs(pointer.position.x - down.position.x) > viewConfiguration.touchSlop) dragged = true
                                                    // A tap on the thumb's expanded touch target must not
                                                    // seek the track either. A real drag keeps native release/save.
                                                    if (!dragged && pointer?.pressed == false) pointer.consume()
                                                } while (event.changes.any { it.pressed })
                                            }
                                        }
                                    }.semantics {
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
                                }
                            )
                        }
                        Box(Modifier.fillMaxWidth().padding(horizontal = with(density) { (thumbBounds.width / 2f).toDp() })) {
                            Text("标准", Modifier.fillMaxWidth(2f / 3f), textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Text("A", Modifier.heightIn(min = 48.dp).wrapContentHeight(Alignment.CenterVertically),
                        fontSize = 40.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
