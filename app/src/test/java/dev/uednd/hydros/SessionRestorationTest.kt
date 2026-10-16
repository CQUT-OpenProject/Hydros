package dev.uednd.hydros

import dev.uednd.hydros.data.network.Credentials
import dev.uednd.hydros.data.repository.*
import dev.uednd.hydros.data.storage.HydrosStore
import dev.uednd.hydros.ui.state.*
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionRestorationTest {
    private val credentials=Credentials("key","1","2","3","13800138000")
    private val device=DeviceUi("water","水表","宿舍",DeviceCategory.SHOWER,channel=ControlChannel.CLOUD)

    @Test fun serviceAndPageShareRestorationWithoutReplayingAnyCommands() = runTest {
        val store=MemoryStore().apply { auth=credentials }
        store.updateAccount(credentials.key) { it.copy(session=SessionUi(device,credentials.key,stage=SessionStage.OPENING)) }
        val transport=ScriptTransport()
        val realAuth=RealAuthRepository(transport,store)
        val gate=CompletableDeferred<Unit>(); var authRestores=0; var sessionRestores=0
        val auth=object : AuthRepository by realAuth {
            override suspend fun restore() { authRestores++; gate.await(); realAuth.restore() }
        }
        val realSessions=RealSessionRepository(transport,store,ScriptBle(),Mutex())
        val sessions=object : SessionRepository by realSessions {
            override suspend fun restore(auth: Credentials?) { sessionRestores++; realSessions.restore(auth) }
        }
        val restoration=SessionRestoration(auth,sessions)
        val service=launch { restoration.restore() }
        val page=launch { restoration.restore() }
        runCurrent(); assertEquals(1,authRestores)
        gate.complete(Unit); service.join(); page.join()
        assertEquals(credentials,auth.credentials.value)
        assertEquals(SessionStage.OPEN_UNCONFIRMED,sessions.session.value?.stage)
        assertEquals(1,sessionRestores)
        assertTrue(transport.requests.isEmpty())
    }

    @Test fun cancelledOrFailedInitializationCanBeRetried() = runTest {
        for (cancel in listOf(false,true)) {
            val store=MemoryStore().apply { auth=credentials }
            val transport=ScriptTransport(); val realAuth=RealAuthRepository(transport,store)
            var attempts=0
            val entered=CompletableDeferred<Unit>()
            val auth=object : AuthRepository by realAuth {
                override suspend fun restore() {
                    if(++attempts==1) {
                        entered.complete(Unit)
                        if(cancel) awaitCancellation() else throw IOException("transient store error")
                    }
                    realAuth.restore()
                }
            }
            val sessions=RealSessionRepository(transport,store,ScriptBle(),Mutex())
            val restoration=SessionRestoration(auth,sessions)
            val initial=launch { runCatching { restoration.restore() } }
            entered.await()
            if(cancel) initial.cancelAndJoin() else initial.join()
            restoration.restore()
            assertEquals(2,attempts)
            assertEquals(credentials,auth.credentials.value)
        }
    }

    @Test fun expiredCredentialsStillRestoreTheOriginalAccountsLocalBleSession() = runTest {
        val records=MemoryStore()
        records.updateAccount(credentials.key) { it.copy(session=SessionUi(device.copy(channel=ControlChannel.BLE),
            credentials.key,stage=SessionStage.STOPPING,rateAttempted=true,orderNo="own")) }
        val store=object : HydrosStore by records {
            override suspend fun lastAccount()=credentials.key
        }
        val transport=ScriptTransport()
        val auth=RealAuthRepository(transport,store)
        val sessions=RealSessionRepository(transport,store,ScriptBle(),Mutex())
        SessionRestoration(auth,sessions).restore()
        assertNull(auth.credentials.value)
        assertEquals(credentials.key,sessions.session.value?.ownerKey)
        assertEquals(SessionStage.CLOSE_UNCONFIRMED,sessions.session.value?.stage)
        assertTrue(transport.requests.isEmpty())
    }
}
