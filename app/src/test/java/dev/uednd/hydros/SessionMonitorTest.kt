package dev.uednd.hydros

import dev.uednd.hydros.data.network.Credentials
import dev.uednd.hydros.data.network.OrderPush
import dev.uednd.hydros.data.repository.SessionMonitor
import dev.uednd.hydros.data.repository.SessionRepository
import dev.uednd.hydros.ui.state.ControlChannel
import dev.uednd.hydros.ui.state.DeviceCategory
import dev.uednd.hydros.ui.state.DeviceUi
import dev.uednd.hydros.ui.state.SessionStage
import dev.uednd.hydros.ui.state.SessionUi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class SessionMonitorTest {
    private val auth = Credentials("token", "1", "2", "3", "13800138000")
    private val device = DeviceUi("water", "热水表", "宿舍", DeviceCategory.SHOWER, channel = ControlChannel.CLOUD)

    private fun session(order: String = "first") = SessionUi(
        device, auth.key, startedAt = 1, stage = SessionStage.USING, orderNo = order,
    )

    @Test fun unavailablePushAndFailedReconciliationDoNotStopPolling() = runTest {
        val sessions = RecordingSessions(session())
        sessions.failure = IOException("offline")
        val push = RecordingPush().apply { failure = IOException("blocked") }
        val monitor = SessionMonitor(MutableStateFlow(auth), sessions, push)
        val watch = backgroundScope.launch { monitor.watch {} }
        runCurrent()
        assertEquals(1, sessions.reconciliations.size)

        sessions.failure = null
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(2, sessions.reconciliations.size)
        assertTrue(watch.isActive)

        sessions.session.value = null
        runCurrent()
        assertTrue(watch.isCompleted)
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(2, sessions.reconciliations.size)
    }

    @Test fun oldOrderSignalsAreIgnoredAfterSwitchAndCompletion() = runTest {
        val sessions = RecordingSessions(session())
        val push = RecordingPush()
        val monitor = SessionMonitor(MutableStateFlow(auth), sessions, push)
        val watch = backgroundScope.launch { monitor.watch {} }
        runCurrent()
        val old = push.subscriptions.single()

        sessions.session.value = session("second")
        runCurrent()
        val current = push.subscriptions.last()
        assertEquals("second", current.orderNo)
        val calls = sessions.reconciliations.size

        old.signal()
        runCurrent()
        assertEquals(calls, sessions.reconciliations.size)
        current.signal()
        runCurrent()
        assertEquals(calls + 1, sessions.reconciliations.size)

        sessions.session.value = null
        runCurrent()
        current.signal()
        runCurrent()
        assertTrue(watch.isCompleted)
        assertNull(push.active)
        assertEquals(calls + 1, sessions.reconciliations.size)
    }

    @Test fun recoveredSessionWithSameOrderNumberDoesNotAcceptOldSessionSignals() = runTest {
        val sessions = RecordingSessions(session())
        val push = RecordingPush()
        val monitor = SessionMonitor(MutableStateFlow(auth), sessions, push)
        backgroundScope.launch { monitor.watch {} }
        runCurrent()
        val old = push.subscriptions.single()

        sessions.session.value = session().copy(startedAt = 2)
        runCurrent()
        assertEquals(2, push.subscriptions.size)
        val calls = sessions.reconciliations.size
        old.signal()
        runCurrent()
        assertEquals(calls, sessions.reconciliations.size)
        push.subscriptions.last().signal()
        runCurrent()
        assertEquals(calls + 1, sessions.reconciliations.size)
    }

    @Test fun expiredCredentialsStopCloudRecovery() = runTest {
        val credentials = MutableStateFlow<Credentials?>(auth)
        val sessions = RecordingSessions(session())
        val push = RecordingPush()
        val monitor = SessionMonitor(credentials, sessions, push)
        backgroundScope.launch { monitor.watch {} }
        runCurrent()
        val old = push.subscriptions.single()

        credentials.value = null
        runCurrent()
        assertNull(push.active)
        val calls = sessions.reconciliations.size
        old.signal()
        runCurrent()
        assertEquals(calls, sessions.reconciliations.size)

        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(calls, sessions.reconciliations.size)
    }

    @Test fun cancellationClosesSubscriptionAndStopsBothTriggers() = runTest {
        val sessions = RecordingSessions(session())
        val push = RecordingPush()
        val monitor = SessionMonitor(MutableStateFlow(auth), sessions, push)
        val watch = backgroundScope.launch { monitor.watch {} }
        runCurrent()
        val signal = push.subscriptions.single().signal
        watch.cancelAndJoin()
        assertNull(push.active)

        val calls = sessions.reconciliations.size
        signal()
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(calls, sessions.reconciliations.size)
    }

    @Test fun preparationTimeoutDoesNotCreateAReconciliationOrSubscription() = runTest {
        val sessions = RecordingSessions(null)
        val push = RecordingPush()
        val monitor = SessionMonitor(MutableStateFlow(auth), sessions, push, preparationTimeoutMillis = 30_000)
        val watch = backgroundScope.launch { monitor.watch {} }
        runCurrent()
        advanceTimeBy(30_000)
        runCurrent()

        assertTrue(watch.isCompleted)
        assertTrue(sessions.reconciliations.isEmpty())
        assertTrue(push.subscriptions.isEmpty())
    }

    @Test fun immediateSettlementStopsMonitorWithoutWaitingForPreparationTimeout() = runTest {
        val sessions = RecordingSessions(session()).apply { completesSession = true }
        val push = RecordingPush()
        val monitor = SessionMonitor(MutableStateFlow(auth), sessions, push)
        val watch = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { monitor.watch {} }

        assertTrue(watch.isCompleted)
        assertEquals(1, sessions.reconciliations.size)
        assertNull(push.active)
    }

    @Test fun sessionArrivalAndStageChangesUpdateHostWithoutWaitingForPoll() = runTest {
        val sessions = RecordingSessions(null)
        val push = RecordingPush()
        val updates = mutableListOf<SessionStage>()
        val monitor = SessionMonitor(MutableStateFlow(auth), sessions, push)
        backgroundScope.launch { monitor.watch { updates += it.stage } }
        runCurrent()

        sessions.session.value = session()
        runCurrent()
        sessions.session.value = session().copy(stage = SessionStage.SETTLEMENT_PENDING)
        runCurrent()
        assertEquals(listOf(SessionStage.USING, SessionStage.SETTLEMENT_PENDING), updates)
        assertEquals(1, push.subscriptions.size)
    }

    @Test fun stageChangesShortenDeadlineButOrdinaryUpdatesDoNotResetIt() = runTest {
        val sessions = RecordingSessions(session())
        val monitor = SessionMonitor(MutableStateFlow(auth), sessions, RecordingPush())
        backgroundScope.launch { monitor.watch {} }
        runCurrent()
        advanceTimeBy(2_000)
        sessions.session.value = sessions.session.value!!.copy(recoveryReason = dev.uednd.hydros.ui.state.SessionRecoveryReason.STATE_UNKNOWN)
        runCurrent()
        advanceTimeBy(8_000); runCurrent()
        assertEquals(2, sessions.reconciliations.size)
        sessions.session.value = sessions.session.value!!.copy(stage = SessionStage.SETTLEMENT_PENDING)
        runCurrent()
        advanceTimeBy(2_999); runCurrent()
        assertEquals(2, sessions.reconciliations.size)
        advanceTimeBy(1); runCurrent()
        assertEquals(3, sessions.reconciliations.size)
        sessions.session.value = sessions.session.value!!.copy(stage = SessionStage.USING)
        runCurrent()
        advanceTimeBy(9_999); runCurrent()
        assertEquals(3, sessions.reconciliations.size)
        advanceTimeBy(1); runCurrent()
        assertEquals(4, sessions.reconciliations.size)
    }

    @Test fun expiredCloudCredentialsSuspendPollingUntilRelogin() = runTest {
        val credentials = MutableStateFlow<Credentials?>(auth)
        val sessions = RecordingSessions(session())
        val push = RecordingPush()
        backgroundScope.launch { SessionMonitor(credentials, sessions, push).watch {} }
        runCurrent()
        credentials.value = null
        runCurrent()
        advanceTimeBy(60_000); runCurrent()
        assertEquals(1, sessions.reconciliations.size)
        assertNull(push.active)
        credentials.value = auth.copy(loginCode = "new")
        runCurrent()
        advanceTimeBy(10_000); runCurrent()
        assertEquals(2, sessions.reconciliations.size)
        assertEquals("new", sessions.reconciliations.last()?.loginCode)
    }

    @Test fun pushBurstsAndTimerNeverOverlapAndWaitFromCompletion() = runTest {
        val sessions = RecordingSessions(session()).apply { duration = 2_000 }
        val push = RecordingPush()
        backgroundScope.launch { SessionMonitor(MutableStateFlow(auth), sessions, push).watch {} }
        runCurrent()
        repeat(10) { push.subscriptions.single().signal() }
        advanceTimeBy(2_000); runCurrent()
        assertEquals(1, sessions.reconciliations.size)
        advanceTimeBy(9_999); runCurrent()
        assertEquals(1, sessions.reconciliations.size)
        advanceTimeBy(1); runCurrent()
        assertEquals(2, sessions.reconciliations.size)
        repeat(10) { push.subscriptions.single().signal() }
        advanceTimeBy(2_000); runCurrent()
        assertEquals(2, sessions.reconciliations.size)
        assertEquals(1, sessions.maxConcurrent)
    }

    private class RecordingSessions(initial: SessionUi?) : SessionRepository {
        override val session = MutableStateFlow(initial)
        val reconciliations = mutableListOf<Credentials?>()
        var failure: Exception? = null
        var completesSession = false
        var duration = 0L
        var concurrent = 0
        var maxConcurrent = 0
        override suspend fun restore(auth: Credentials?) = Unit
        override suspend fun start(auth: Credentials, device: DeviceUi) = Unit
        override suspend fun finish(auth: Credentials?) = Unit
        override suspend fun reconcile(auth: Credentials?) {
            reconciliations += auth
            concurrent++
            maxConcurrent = maxOf(maxConcurrent, concurrent)
            kotlinx.coroutines.delay(duration)
            concurrent--
            failure?.let { throw it }
            if (completesSession) session.value = null
        }
    }

    private class RecordingPush : OrderPush {
        data class Subscription(val orderNo: String, val signal: () -> Unit)
        val subscriptions = mutableListOf<Subscription>()
        var active: Subscription? = null
        var failure: Exception? = null

        override suspend fun subscribe(auth: Credentials, orderNo: String, onSignal: () -> Unit) {
            failure?.let { throw it }
            active = Subscription(orderNo, onSignal).also { subscriptions += it }
        }

        override fun close() {
            active = null
        }
    }
}
