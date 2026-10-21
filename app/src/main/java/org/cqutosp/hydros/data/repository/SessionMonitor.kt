package org.cqutosp.hydros.data.repository

import org.cqutosp.hydros.data.network.Credentials
import org.cqutosp.hydros.data.network.OrderPush
import org.cqutosp.hydros.ui.state.ControlChannel
import org.cqutosp.hydros.ui.state.SessionUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.selects.onTimeout
import org.cqutosp.hydros.ui.state.SessionStage

/** Push shortens the wait; periodic reconciliation remains available without it. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SessionMonitor(
    private val credentials: StateFlow<Credentials?>,
    private val sessions: SessionRepository,
    private val push: OrderPush,
    private val pollIntervalMillis: Long = 10_000,
    private val recoveryIntervalMillis: Long = 3_000,
    private val preparationTimeoutMillis: Long = 450_000,
    private val onRecoveryFailure: (SessionStage, Throwable) -> Unit = { _, _ -> },
) {
    private data class PushTarget(
        val auth: Credentials,
        val orderNo: String,
        val startedAt: Long,
    )

    suspend fun watch(onSessionChanged: (SessionUi) -> Unit) = coroutineScope {
        // Observe the session before a first reconciliation can finish it.
        val initial = withTimeoutOrNull(preparationTimeoutMillis) {
            sessions.session.first { it != null }
        }
        if (initial == null) return@coroutineScope

        val signals = Channel<PushTarget>(Channel.CONFLATED)
        val scheduleChanges = Channel<Unit>(Channel.CONFLATED)
        val scheduling = launch {
            combine(credentials, sessions.session) { auth, session ->
                listOf(auth, session?.ownerKey, session?.device?.id, session?.startedAt, session?.stage, session?.device?.channel)
            }.distinctUntilChanged().collect { scheduleChanges.trySend(Unit) }
        }
        val subscription = launch {
            combine(credentials, sessions.session, ::pushTarget)
                .distinctUntilChanged()
                .collectLatest { target ->
                    push.close()
                    if (target == null) return@collectLatest

                    var retryMillis = 3_000L
                    while (isActive) {
                        try {
                            push.subscribe(target.auth, target.orderNo) { signals.trySend(target) }
                            awaitCancellation()
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Exception) {
                            // Keep HTTP polling alive while a blocked/unavailable push connection recovers.
                        } finally {
                            push.close()
                        }
                        delay(retryMillis)
                        retryMillis = (retryMillis * 2).coerceAtMost(60_000)
                    }
                }
        }
        // One worker owns both triggers; changes never cancel an in-flight device operation.
        val polling = launch {
            suspend fun perform() {
                val session = sessions.session.value ?: return
                val auth = credentials.value
                if (auth == null || auth.key != session.ownerKey) return
                reconcile()
                // Coalesce signals already covered by the completed reconciliation.
                while (signals.tryReceive().isSuccess) { }
                while (scheduleChanges.tryReceive().isSuccess) { }
            }
            perform()
            while (isActive && sessions.session.value != null) {
                val session = sessions.session.value ?: break
                val auth = credentials.value
                val canPoll = auth != null && auth.key == session.ownerKey
                val interval = if (session.stage == SessionStage.USING) pollIntervalMillis else recoveryIntervalMillis
                val shouldReconcile = select<Boolean> {
                    scheduleChanges.onReceive { false }
                    signals.onReceive { target -> target == pushTarget(credentials.value, sessions.session.value) }
                    if (canPoll) onTimeout(interval) { true }
                }
                if (shouldReconcile) perform()
            }
        }

        try {
            sessions.session.takeWhile { it != null }.collect { session ->
                onSessionChanged(requireNotNull(session))
            }
        } finally {
            subscription.cancel()
            scheduling.cancel()
            polling.cancel()
            signals.close()
            scheduleChanges.close()
            push.close()
        }
    }

    private fun pushTarget(auth: Credentials?, session: SessionUi?): PushTarget? {
        if (auth == null || session == null) return null
        if (session.ownerKey != auth.key) return null
        if (session.device.channel != ControlChannel.CLOUD) return null
        if (session.orderNo.isBlank()) return null
        return PushTarget(auth, session.orderNo, session.startedAt)
    }

    private suspend fun reconcile() {
        try {
            sessions.reconcile(credentials.value)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // Durable session state is retained for the next attempt.
            sessions.session.value?.stage?.let { onRecoveryFailure(it, error) }
        }
    }
}
