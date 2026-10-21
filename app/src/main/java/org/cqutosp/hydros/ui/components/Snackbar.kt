package org.cqutosp.hydros.ui.components

import org.cqutosp.hydros.data.feedback.*
import androidx.lifecycle.repeatOnLifecycle

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.*
import kotlinx.coroutines.delay
import org.cqutosp.hydros.ui.theme.LocalFeedbackSettings

// Standalone previews can render components without an app host. Production provides one at the root.
internal val LocalHydrosSnackbar = staticCompositionLocalOf { HydrosSnackbarState() }

@Composable
internal fun HydrosSnackbarProvider(
    state: HydrosSnackbarState = remember { HydrosSnackbarState() },
    bottomPadding: Dp = 0.dp,
    content: @Composable () -> Unit,
) {
    val notice by state.notice.collectAsState()
    val haptics = rememberHydrosHaptics()
    // Only the provider emits feedback; modal hosts and notice updates must not repeat it.
    LaunchedEffect(notice?.id) {
        if (notice?.error == true) haptics.error()
    }
    val accessibility = LocalAccessibilityManager.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    LaunchedEffect(notice?.id, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            val current = notice ?: return@repeatOnLifecycle
            val timeout = accessibility?.calculateRecommendedTimeoutMillis(
                originalTimeoutMillis = 4_000, containsIcons = false, containsText = true,
                containsControls = current.action != null) ?: 4_000
            delay(minOf(1_000, timeout))
            state.unlock(current.id)
            delay((timeout - 1_000).coerceAtLeast(0))
            state.dismiss(current.id, SnackbarEnd.TIMEOUT)
        }
    }
    CompositionLocalProvider(LocalHydrosSnackbar provides state) {
        Box(Modifier.fillMaxSize().dismissSnackbarOnInteraction()) { content() }
        HydrosSnackbarHost(bottomPadding = bottomPadding)
    }
}

@Composable
internal fun Modifier.dismissSnackbarOnInteraction(): Modifier {
    val state = LocalHydrosSnackbar.current
    return this.pointerInput(state) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.any { it.changedToDownIgnoreConsumed() }) state.dismissOutside()
            }
        }
    }.onPreviewKeyEvent { event ->
        if (event.key == Key.Escape && event.type == KeyEventType.KeyDown && state.notice.value?.canDismissOutside == true) {
            state.dismissOutside(); true
        } else false
    }
}

/** Registers the dialog/sheet window so notifications remain readable above modal scrims. */
@Composable
internal fun ModalSnackbarHost() {
    val state = LocalHydrosSnackbar.current
    val owner = remember { Any() }
    DisposableEffect(state, owner) {
        state.registerModal(owner)
        onDispose { state.unregisterModal(owner) }
    }
    HydrosSnackbarHost(modalOwner = owner)
}

@Composable
private fun HydrosSnackbarHost(bottomPadding: Dp = 0.dp, modalOwner: Any? = null) {
    val state = LocalHydrosSnackbar.current
    val notice by state.notice.collectAsState()
    val owners by state.modalOwners.collectAsState()
    val current = notice ?: return
    val reduceMotion = LocalFeedbackSettings.current.reduceMotionEnabled
    if (owners.lastOrNull() !== modalOwner) return
    // Read insets from the host window: a non-focusable Popup has no IME target of its own.
    val density = LocalDensity.current
    val systemBottom = maxOf(WindowInsets.navigationBars.getBottom(density), WindowInsets.ime.getBottom(density))
    val extraBottom = with(density) { bottomPadding.roundToPx() } + systemBottom
    val position = remember(extraBottom) { object : PopupPositionProvider {
        override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize) =
            IntOffset((windowSize.width - popupContentSize.width) / 2,
                (windowSize.height - popupContentSize.height - extraBottom).coerceAtLeast(0))
    } }
    Popup(popupPositionProvider = position, onDismissRequest = state::dismissOutside,
        properties = PopupProperties(focusable = false, dismissOnBackPress = false, dismissOnClickOutside = true, clippingEnabled = false)) {
        val entrance = remember { Animatable(0f) }
        LaunchedEffect(reduceMotion) {
            if (reduceMotion) entrance.snapTo(1f) else entrance.animateTo(1f, tween(250))
        }
        val distance = with(LocalDensity.current) { 20.dp.toPx() }
        Box(Modifier.widthIn(max = 480.dp).fillMaxWidth().padding(16.dp)
            .graphicsLayer { alpha = entrance.value; translationY = (1f - entrance.value) * distance }) {
            Snackbar(
                modifier = Modifier.semantics { liveRegion = if (current.error) LiveRegionMode.Assertive else LiveRegionMode.Polite },
                shape = RoundedCornerShape(16.dp),
                containerColor = MaterialTheme.colorScheme.inverseSurface,
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                action = current.action?.let { action -> {
                    TextButton(onClick = { state.performAction(current.id) },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.inversePrimary)) { Text(action.label) }
                } },
            ) { Text(current.text, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

/** Announce a state transition once; transient details never become permanent page text. */
@Composable
internal fun SnackbarMessage(message: String?, eventKey: Any? = message, action: SnackbarAction? = null, error: Boolean = true, source: String = "user", severity: FeedbackSeverity = if (error) FeedbackSeverity.ERROR else FeedbackSeverity.INFO) {
    val snackbar = LocalHydrosSnackbar.current
    DisposableEffect(snackbar, message, eventKey) {
        val ticket = message?.let { snackbar.show(it, action, error, source = source, severity = severity, dedupKey = eventKey?.toString()) }
        onDispose { ticket?.let { snackbar.dismiss(it.id) } }
    }
}
