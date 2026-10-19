package dev.uednd.hydros

import dev.uednd.hydros.data.network.*
import dev.uednd.hydros.data.repository.*
import dev.uednd.hydros.ui.state.*
import java.io.IOException
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionLifecycleTest {
    private val auth = Credentials("key", "1", "2", "3", "13800138000")
    private val bleDevice = DeviceUi("AABBCCDDEEFF", "水表", "宿舍", DeviceCategory.SHOWER,
        channel=ControlChannel.BLE, deviceId="7", ledgerMac="AABBCCDDEEFF", connectMac="AA:BB:CC:DD:EE:FF",
        bigTypeId="4", smallTypeId="1")
    private fun repo(t: ScriptTransport, s: MemoryStore) = RealSessionRepository(t, s, Mutex(), 1, {})

    @Test fun staleCloudConfirmationCannotDropTheNewActiveOrder() = runTest {
        val store=MemoryStore(); val transport=ScriptTransport(); val repo=repo(transport,store)
        var running=false
        transport.script={path,_-> when {
            path.endsWith("using") -> if(running) response("""{"orderNo":"new","isOwner":true}""") else response()
            path.endsWith("downRate/rateOrder") -> { running=true; response() }
            path.endsWith("downRateResult") -> response("""{"state":1,"result":0,"orderNo":"old","consumeDate":"20200101120000"}""")
            else -> error("Must not settle a stale order: $path")
        } }
        repo.start(auth,bleDevice.copy(channel=ControlChannel.CLOUD))
        assertEquals(SessionStage.OPEN_UNCONFIRMED,repo.session.value?.stage)
        assertEquals("",repo.session.value?.orderNo)
        repo.reconcile(auth)
        assertEquals("new",repo.session.value?.orderNo)
        assertEquals(SessionStage.USING,repo.session.value?.stage)
        assertTrue(transport.requests.none { it.first.contains("consumeOrder") })
    }

    @Test fun openingAndRecoveryBothRejectForeignDeviceAndAccountFields() = runTest {
        val date=LocalDateTime.now(ZoneId.of("Asia/Shanghai")).format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
        for (field in listOf("\"deviceSnCode\":\"other\"", "\"accountId\":999", "\"projectId\":999", "\"isOwner\":false")) {
            for (recovering in listOf(false,true)) {
                val store=MemoryStore(); val transport=ScriptTransport(); val repo=repo(transport,store)
                val device=bleDevice.copy(channel=ControlChannel.CLOUD)
                transport.script={path,_-> when {
                    path.endsWith("using") || path.endsWith("downRate/rateOrder") -> response()
                    path.endsWith("downRateResult") -> response("""{"state":1,"result":0,"orderNo":"foreign","consumeDate":"$date",$field}""")
                    else -> error("Foreign result must not be used: $path")
                } }
                if(recovering) {
                    store.updateAccount(auth.key) { it.copy(session=SessionUi(device,auth.key,consumeDate=date,stage=SessionStage.OPEN_UNCONFIRMED)) }
                    repo.restore(auth); repo.reconcile(auth)
                } else repo.start(auth,device)
                assertNotNull(repo.session.value)
                assertEquals("",repo.session.value?.orderNo)
            }
        }
    }

    @Test fun matchingCloudOpeningStillSucceedsAndPersistsItsIdentity() = runTest {
        val date=LocalDateTime.now(ZoneId.of("Asia/Shanghai")).format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
        val store=MemoryStore(); val transport=ScriptTransport(); val repo=repo(transport,store)
        var accepted=false
        transport.script={path,_-> when {
            path.endsWith("downRate/rateOrder") -> { accepted=true; response() }
            path.endsWith("using") && accepted -> response("""{"orderNo":"own","isOwner":true}""")
            path.endsWith("downRateResult") -> response("""{"state":1,"result":0,"orderNo":"own","consumeDate":"$date","deviceSnCode":"aa:bb:cc:dd:ee:ff","accountId":2,"projectId":3}""")
            else -> response()
        } }
        repo.start(auth,bleDevice.copy(channel=ControlChannel.CLOUD))
        assertEquals(SessionStage.USING,repo.session.value?.stage)
        assertEquals("own",store.account(auth.key).session?.orderNo)
    }

    @Test fun recentCachedOpeningCannotOverrideTheActualActiveOrder() = runTest {
        val date=LocalDateTime.now(ZoneId.of("Asia/Shanghai")).minusSeconds(1).format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
        val transport=ScriptTransport(); val store=MemoryStore(); var accepted=false
        transport.script={path,_-> when {
            path.endsWith("downRate/rateOrder") -> { accepted=true; response() }
            path.endsWith("using") && accepted -> response("""{"orderNo":"new","isOwner":true}""")
            path.endsWith("downRateResult") -> response("""{"state":1,"result":0,"orderNo":"old","consumeDate":"$date"}""")
            else -> response()
        } }
        val repo=repo(transport,store)
        repo.start(auth,bleDevice.copy(channel=ControlChannel.CLOUD))
        assertEquals(SessionStage.OPEN_UNCONFIRMED,repo.session.value?.stage)
        assertEquals("",repo.session.value?.orderNo)
        repo.reconcile(auth)
        assertEquals("new",repo.session.value?.orderNo)
        assertTrue(transport.requests.none { it.first.contains("consumeOrder") })
    }

    @Test fun queuedPreparationRechecksSessionAfterOpeningFails() = runTest {
        val store=MemoryStore().apply { this.auth=this@SessionLifecycleTest.auth }
        val transport=ScriptTransport(); val lock=Mutex()
        val gate=CompletableDeferred<Unit>()
        transport.script={path,fields-> when(path) {
            "device/info/mac" -> response("""{"snCode":"${fields["macAddress"]}","deviceName":"水表","deviceId":"7","macAddress":"${fields["macAddress"]}","bigTypeId":"4","smallTypeId":"1"}""")
            "order/tcpDevice/downRate/rateOrder" -> { gate.await(); throw IOException("lost allocation response") }
            else -> response()
        } }
        val authRepo=RealAuthRepository(transport,store); authRepo.restore()
        val sessions=RealSessionRepository(transport,store,lock)
        val devices=RealDeviceRepository(transport,store,lock)
        val preparation=DevicePreparation(authRepo,devices,sessions,store,backgroundScope)
        preparation.prepare(bleDevice.copy(channel=ControlChannel.UNKNOWN)); runCurrent()
        val opening=backgroundScope.async { runCatching { preparation.startSession(bleDevice.id) } }
        runCurrent()
        val second=bleDevice.copy(id="AABBCCDDEE01",ledgerMac="AABBCCDDEE01",connectMac="AA:BB:CC:DD:EE:01")
        preparation.prepare(second); runCurrent()
        gate.complete(Unit); runCurrent()
        assertTrue(opening.await().isFailure)
        assertEquals(bleDevice.id,sessions.session.value?.device?.id)
        assertEquals(bleDevice.id,preparation.state.value.device?.id)
    }
}
