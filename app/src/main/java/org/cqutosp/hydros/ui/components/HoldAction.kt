package org.cqutosp.hydros.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import org.cqutosp.hydros.ui.theme.LocalFeedbackSettings
import kotlin.math.PI
import kotlin.math.sin

internal const val USAGE_HOLD_MILLIS = 1_200

private data class HoldAppearance(
    val workingText: String,
    val container: Color,
    val content: Color
)

@Stable
private class HoldActionController(private val scope: CoroutineScope) {
    val progress = Animatable(0f)
    var origin by mutableStateOf<Rect?>(null)
        private set
    var completed by mutableStateOf(false)
        private set
    private var job: Job? = null
    var appearance by mutableStateOf<HoldAppearance?>(null)
        private set

    fun begin(bounds: Rect, appearance: HoldAppearance, onComplete: () -> Unit) {
        if (origin != null || bounds.isEmpty) return
        this.appearance = appearance
        origin = bounds
        completed = false
        job = scope.launch {
            progress.snapTo(0f)
            val animation = launch { progress.animateTo(1f, tween(USAGE_HOLD_MILLIS, easing = LinearEasing)) }
            // Confirmation time is a gesture requirement, independent of animation duration scale.
            delay(USAGE_HOLD_MILLIS.toLong())
            animation.cancel()
            progress.snapTo(1f)
            completed = true
            onComplete()
        }
    }

    fun cancel(force: Boolean = false) {
        if ((!force && completed) || origin == null) return
        completed = false
        job?.cancel()
        job = scope.launch {
            progress.animateTo(0f, tween(200))
            origin = null
        }
    }

    fun release() {
        if (!completed) { reset(); return }
        // A synchronous session can remove the button on the completion frame.
        // Keep the final full-window shape until that frame has been presented.
        job?.cancel()
        job = scope.launch { withFrameNanos { }; reset() }
    }

    fun reset() {
        job?.cancel()
        origin = null
        completed = false
    }
}

private val LocalHoldAction = staticCompositionLocalOf<HoldActionController> {
    error("HoldActionButton requires HoldActionHost")
}

/** Window-sized drawing surface, outside Scaffold insets and scroll clipping. */
@Composable
fun HoldActionHost(content: @Composable () -> Unit) {
    val reduceMotion = LocalFeedbackSettings.current.reduceMotionEnabled
    val scope = rememberCoroutineScope()
    val controller = remember(scope) { HoldActionController(scope) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, controller) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE && !controller.completed) controller.reset()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); controller.reset() }
    }
    BackHandler(controller.origin != null && !controller.completed) { controller.cancel() }
    CompositionLocalProvider(LocalHoldAction provides controller) {
        var hostSize by remember { mutableStateOf(IntSize.Zero) }
        val wavePath = remember { Path() }
        val clipPath = remember { Path() }
        Box(Modifier.fillMaxSize().onSizeChanged {
            if (hostSize != IntSize.Zero && hostSize != it) controller.reset()
            hostSize = it
        }) {
            content()
            controller.origin?.let { origin ->
                val p = controller.progress.value
                val appearance = requireNotNull(controller.appearance)
                val color = appearance.container
                val foreground = appearance.content
                val bounds = if (reduceMotion) origin else expandedButtonBounds(origin, hostSize, p)
                val currentRadius = origin.height / 2f * (if (reduceMotion) 1f else 1f - p)
                // Fade out dynamic water effects near completion so full window corners cleanly solidify
                val effectAlpha = if (reduceMotion || controller.completed || p >= 0.96f) 0f else (1f - ((p - 0.86f) / 0.10f).coerceIn(0f, 1f))

                // Consume new gestures; the initiating pointer stays with its original input node.
                Box(Modifier.matchParentSize().testTag("hold-expansion").pointerInput(controller) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false).consume()
                        do {
                            val event = awaitPointerEvent()
                            event.changes.forEach { it.consume() }
                        } while (event.changes.any { it.pressed })
                    }
                }.semantics {
                    progressBarRangeInfo = ProgressBarRangeInfo(p, 0f..1f)
                    stateDescription = if (controller.completed) appearance.workingText else "继续按住"
                }) {
                    Canvas(Modifier.fillMaxSize()) {
                        // 1. Base expanding container surface
                        drawRoundRect(
                            color = color,
                            topLeft = bounds.topLeft,
                            size = bounds.size,
                            cornerRadius = CornerRadius(currentRadius)
                        )

                        // 2. Internal Liquid Wave & Fluid Glow (clipped inside the expanding shape)
                        if (effectAlpha > 0.01f && bounds.width > 0f && bounds.height > 0f) {
                            clipPath.reset()
                            clipPath.addRoundRect(RoundRect(bounds, CornerRadius(currentRadius)))
                            clipPath(clipPath) {
                                val waterLevelY = bounds.bottom - bounds.height * (0.10f + 0.90f * p)
                                val waveAmp = 5.dp.toPx() * (1f - p * 0.5f)
                                val waveFreq = (2f * PI.toFloat()) / (bounds.width.coerceAtLeast(100f) * 0.85f)
                                val phase = p * 22f

                                wavePath.reset()
                                wavePath.moveTo(bounds.left - 10f, bounds.bottom + 10f)
                                wavePath.lineTo(bounds.left - 10f, waterLevelY + sin(phase) * waveAmp)
                                val steps = 28
                                val stepW = (bounds.width + 20f) / steps
                                for (s in 1..steps) {
                                    val x = bounds.left - 10f + s * stepW
                                    val y = waterLevelY + sin((x - bounds.left) * waveFreq + phase) * waveAmp
                                    wavePath.lineTo(x, y)
                                }
                                wavePath.lineTo(bounds.right + 10f, bounds.bottom + 10f)
                                wavePath.close()

                                drawPath(wavePath, color = foreground.copy(alpha = 0.14f * effectAlpha))

                                val glowRadius = bounds.width.coerceAtLeast(100f) * 0.5f
                                drawCircle(
                                    brush = Brush.radialGradient(
                                        colors = listOf(foreground.copy(alpha = 0.18f * effectAlpha), Color.Transparent),
                                        center = Offset(bounds.center.x, bounds.bottom),
                                        radius = glowRadius
                                    ),
                                    center = Offset(bounds.center.x, bounds.bottom),
                                    radius = glowRadius
                                )
                            }
                        }

                    }

                    // 3. Center Progress Ring & Label
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.align(Alignment.Center).graphicsLayer {
                            translationX = bounds.center.x - hostSize.width / 2f
                            translationY = bounds.center.y - hostSize.height / 2f
                        }
                    ) {
                        Canvas(Modifier.size(24.dp)) {
                            drawCircle(
                                color = foreground.copy(alpha = 0.25f),
                                radius = size.minDimension / 2f - 1.3.dp.toPx(),
                                style = Stroke(width = 2.6.dp.toPx())
                            )
                            drawArc(
                                color = foreground,
                                startAngle = -90f,
                                sweepAngle = 360f * p,
                                useCenter = false,
                                style = Stroke(width = 2.6.dp.toPx(), cap = StrokeCap.Round)
                            )
                        }
                        Text(
                            text = if (controller.completed) appearance.workingText else "继续按住",
                            color = foreground,
                            style = MaterialTheme.typography.labelLarge.copy(fontSize = 18.sp, lineHeight = 24.sp)
                        )
                    }
                }
            }
        }
    }
}

internal fun expandedButtonBounds(origin: Rect, size: IntSize, progress: Float): Rect {
    val p = progress.coerceIn(0f, 1f)
    return Rect(origin.left * (1 - p), origin.top * (1 - p),
        origin.right + (size.width - origin.right) * p,
        origin.bottom + (size.height - origin.bottom) * p)
}

@Composable
fun HoldActionButton(actionKey: String, enabled: Boolean, working: Boolean, workingText: String = "正在开启", onComplete: () -> Unit) {
    val controller = LocalHoldAction.current
    val haptics = rememberHydrosHaptics()
    val reduceMotion = LocalFeedbackSettings.current.reduceMotionEnabled
    var bounds by remember { mutableStateOf(Rect.Zero) }
    var isPressed by remember { mutableStateOf(false) }
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed && !reduceMotion) 0.96f else 1f,
        animationSpec = if (reduceMotion) tween(0) else spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "holdActionPressScale"
    )
    val latestEnabled by rememberUpdatedState(enabled)
    val latestWorking by rememberUpdatedState(working)
    val latestComplete by rememberUpdatedState(onComplete)
    DisposableEffect(controller, actionKey) { onDispose { controller.release() } }
    LaunchedEffect(enabled) { if (!enabled) controller.cancel() }
    LaunchedEffect(controller.completed, working) {
        if (controller.completed && !working) {
            // Also handles a synchronous failure whose working=true emission was conflated.
            withFrameNanos { }
            if (!latestWorking) controller.cancel(force = true)
        }
    }
    val colors = MaterialTheme.colorScheme
    val container = colors.primary
    val content = colors.onPrimary
    Surface(
        shape = CircleShape,
        color = if (enabled) container else container.copy(alpha = 0.4f),
        contentColor = if (enabled) content else content.copy(alpha = 0.4f),
        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .onGloballyPositioned { bounds = it.boundsInRoot() }
            .testTag("hold-to-start")
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = "长按以开始使用，松手取消"
                if (!enabled) disabled()
            }
            .pointerInput(actionKey, enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    isPressed = true
                    controller.begin(bounds, HoldAppearance(workingText, container, content)) {
                        if (latestEnabled) { haptics.heavy(); latestComplete() } else controller.reset()
                    }
                    try {
                        do {
                            val event = awaitPointerEvent()
                            val pointer = event.changes.find { it.id == down.id }
                            val held = pointer?.pressed == true
                            event.changes.forEach { it.consume() }
                        } while (held)
                    } finally {
                        isPressed = false
                        controller.cancel()
                    }
                }
            }
    ) {
        Box(Modifier.padding(horizontal = 24.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
            Text(if (working) workingText else "长按以开始使用", style = MaterialTheme.typography.labelLarge)
        }
    }
}

