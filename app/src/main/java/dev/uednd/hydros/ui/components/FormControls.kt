package dev.uednd.hydros.ui.components

import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.uednd.hydros.R
import dev.uednd.hydros.ui.theme.LocalFeedbackSettings

/** Step indicator pills (w-6 h-1.5 rounded-full). */
@Composable
fun StepIndicator(
    totalSteps: Int,
    currentStep: Int,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(totalSteps) { index ->
            Box(
                modifier = Modifier
                    .size(width = 24.dp, height = 6.dp)
                    .clip(CircleShape)
                    .background(
                        if (index <= currentStep) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outlineVariant
                    )
            )
        }
    }
}

/** Shared pill track and inset sliding selection. */
@Composable
fun SegmentedControl(
    options: List<String>,
    selectedIndex: Int?,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    icons: List<Int>? = null,
    compact: Boolean = false
) {
    require(options.isNotEmpty() && (selectedIndex == null || selectedIndex in options.indices))
    require(icons == null || icons.size == options.size)
    val colors = MaterialTheme.colorScheme
    val haptics = rememberHydrosHaptics()
    val reduceMotion = LocalFeedbackSettings.current.reduceMotionEnabled
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = CircleShape,
        color = colors.surface,
        border = BorderStroke(1.dp, colors.outlineVariant)
    ) {
        BoxWithConstraints(Modifier.padding(if (compact) 2.dp else 6.dp)) {
            val segmentWidth = maxWidth / options.size
            val transition = updateTransition(selectedIndex ?: 0, label = "分栏选择")
            val start by transition.animateDp(
                transitionSpec = { tween(if (reduceMotion) 0 else 260, delayMillis = if (!reduceMotion && targetState > initialState) 80 else 0) },
                label = "滑块起点"
            ) { segmentWidth * it }
            val end by transition.animateDp(
                transitionSpec = { tween(if (reduceMotion) 0 else 260, delayMillis = if (!reduceMotion && targetState < initialState) 80 else 0) },
                label = "滑块终点"
            ) { segmentWidth * (options.lastIndex - it) }
            if (selectedIndex != null) Box(
                Modifier.matchParentSize().padding(start = start, end = end)
                    .background(colors.secondaryContainer, CircleShape)
            )
            Row(Modifier.fillMaxWidth().selectableGroup()) {
                options.forEachIndexed { index, label ->
                    Box(
                        modifier = Modifier.weight(1f).clip(CircleShape)
                            .selectable(
                                selected = index == selectedIndex,
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                role = Role.Tab,
                                onClick = { if (index != selectedIndex) { haptics.medium(); onSelect(index) } }
                            )
                            .heightIn(min = if (compact) 44.dp else if (icons == null) 36.dp else 48.dp)
                            .padding(horizontal = if (icons == null) 12.dp else 4.dp, vertical = if (compact) 0.dp else 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        val contentColor = if (index == selectedIndex) colors.onSecondaryContainer else colors.onSurfaceVariant
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            icons?.get(index)?.let { Glyph(it, modifier = Modifier.size(if (compact) 22.dp else 24.dp), tint = contentColor) }
                            Text(
                                label,
                                style = MaterialTheme.typography.labelLarge,
                                textAlign = TextAlign.Center,
                                color = contentColor
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Fixed label and an underline; no floating label or individual outlined box. */
@Composable
fun FormField(
    value: String,
    onValue: (String) -> Unit,
    label: String,
    secret: Boolean = false,
    numeric: Boolean = false,
    error: String? = null,
    maxLength: Int? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    require(maxLength == null || maxLength > 0)
    val snackbar = LocalHydrosSnackbar.current
    var visible by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val colors = MaterialTheme.colorScheme
    val lineColor = when {
        error != null -> colors.error
        focused -> colors.primary
        else -> colors.onSurfaceVariant.copy(alpha = 0.35f)
    }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = if (error != null) colors.error else colors.onSurfaceVariant
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Column(Modifier.weight(1f)) {
                BasicTextField(
                    value = value,
                    onValueChange = { next ->
                        // Reject the edit rather than silently truncating pasted credentials.
                        if (maxLength == null || next.length <= maxLength) onValue(next)
                        else snackbar.show("${label}最多输入 $maxLength 字", error = true)
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .semantics {
                            contentDescription = label
                            if (error != null) error(error)
                        },
                    singleLine = true,
                    interactionSource = interaction,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
                    cursorBrush = SolidColor(colors.primary),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (secret) KeyboardType.Password else if (numeric) KeyboardType.Number else KeyboardType.Text
                    ),
                    visualTransformation = if (secret && !visible) PasswordVisualTransformation() else VisualTransformation.None,
                    decorationBox = { innerTextField ->
                        Box(Modifier.heightIn(min = 48.dp), contentAlignment = Alignment.CenterStart) {
                            innerTextField()
                        }
                    }
                )
                HorizontalDivider(thickness = if (focused) 2.dp else 1.dp, color = lineColor)
            }
            trailing?.invoke()
            if (secret) {
                HydrosIconButton(
                    onClick = { visible = !visible },
                    variant = IconBtnVariant.TONAL,
                    size = IconBtnSize.MD,
                    description = if (visible) "隐藏密码" else "显示密码"
                ) {
                    Glyph(
                        if (visible) R.drawable.ic_eye else R.drawable.ic_eye_off,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
        if (maxLength != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Spacer(Modifier.weight(1f))
                Text(
                        "${value.length}/$maxLength",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (value.length >= maxLength) colors.error else colors.onSurfaceVariant,
                        modifier = Modifier.semantics {
                            contentDescription = "$label，已输入 ${value.length} 字，上限 $maxLength 字"
                        }
                )
            }
        }
    }
}
