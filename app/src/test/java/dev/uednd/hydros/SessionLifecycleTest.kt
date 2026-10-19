package dev.uednd.hydros

import dev.uednd.hydros.data.ble.*
import dev.uednd.hydros.data.network.*
import dev.uednd.hydros.data.repository.*
import dev.uednd.hydros.ui.state.*
import java.io.IOException
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
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
    private fun repo(t: ScriptTransport, s: MemoryStore, b: BlePort) = RealSessionRepository(t, s, b, Mutex(), 1, {})
    private fun encryptedBack(): String = Cipher.getInstance("AES/ECB/PKCS5Padding").run {
        init(Cipher.ENCRYPT_MODE, SecretKeySpec(DeviceCrypto.aesKey, "AES"))
        Base64.getEncoder().encodeToString(doFinal("prefix-001122".toByteArray()))
    }

    @Test fun staleCloudConfirmationCannotDropTheNewActiveOrder() = runTest {
        val store=MemoryStore(); val transport=ScriptTransport(); val repo=repo(transport,store,ScriptBle())
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
                val store=MemoryStore(); val transport=ScriptTransport(); val repo=repo(transport,store,ScriptBle())
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
        val store=MemoryStore(); val transport=ScriptTransport(); val repo=repo(transport,store,ScriptBle())
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

    @Test fun bleAllocationIntentSurvivesLostResponseAndPreventsAnotherRequestAcrossRestart() = runTest {
        val store=MemoryStore(); val transport=ScriptTransport(); val ble=ScriptBle()
        transport.script={_,_->
            assertNotNull(store.account(auth.key).session)
            throw IOException("lost allocation response")
        }
        val repo=repo(transport,store,ble)
        assertTrue(runCatching { repo.start(auth,bleDevice) }.isFailure)
        assertEquals(SessionStage.SETTLEMENT_PENDING,repo.session.value?.stage)
        val restarted=repo(transport,store,ble); restarted.restore(auth)
        assertTrue(runCatching { restarted.start(auth,bleDevice) }.isFailure)
        restarted.finish(auth)
        assertNotNull(restarted.session.value)
        assertEquals(1,transport.requests.size)
        assertEquals(0,ble.rates+ble.closes+ble.backs)
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
        val repo=repo(transport,store,ScriptBle())
        repo.start(auth,bleDevice.copy(channel=ControlChannel.CLOUD))
        assertEquals(SessionStage.OPEN_UNCONFIRMED,repo.session.value?.stage)
        assertEquals("",repo.session.value?.orderNo)
        repo.reconcile(auth)
        assertEquals("new",repo.session.value?.orderNo)
        assertTrue(transport.requests.none { it.first.contains("consumeOrder") })
    }

    @Test fun cancellationDuringAllocationKeepsTheIntentWithoutSendingAValveCommand() = runTest {
        val store=MemoryStore(); val transport=ScriptTransport(); val ble=ScriptBle()
        val entered=CompletableDeferred<Unit>()
        transport.script={_,_-> entered.complete(Unit); awaitCancellation() }
        val repo=repo(transport,store,ble)
        val opening=launch { repo.start(auth,bleDevice) }
        entered.await(); opening.cancelAndJoin()
        assertEquals(SessionStage.SETTLEMENT_PENDING,store.account(auth.key).session?.stage)
        assertEquals(0,ble.rates)
    }

    @Test fun allocatedButUnwrittenRateIsOnlyClearedAfterConfirmedCancellation() = runTest {
        val store=MemoryStore(); val transport=ScriptTransport(); val ble=ScriptBle()
        store.updateAccount(auth.key) { it.copy(session=SessionUi(bleDevice,auth.key,orderNo="own",consumeDate="20261016120000")) }
        val repo=repo(transport,store,ble); repo.restore(auth)
        transport.script={path,_-> assertTrue(path.endsWith("upload/bluetooth/fail")); response(code=1) }
        assertTrue(runCatching { repo.finish(auth) }.isFailure)
        assertNotNull(store.account(auth.key).session)
        transport.script={path,fields->
            assertTrue(path.endsWith("upload/bluetooth/fail"))
            assertEquals("20261016120000",fields["consumeDate"])
            response()
        }
        val restarted=repo(transport,store,ble); restarted.restore(auth); restarted.reconcile(auth)
        assertNull(restarted.session.value)
        assertEquals(0,ble.rates+ble.closes+ble.backs)
    }

    @Test fun missingOrderAndOversizedRateAreCancelledBeforeWriting() = runTest {
        for (data in listOf(
            """{"downData":"0011","consumeDate":"20261016120000"}""",
            """{"downData":"${"00".repeat(253)}","consumeDate":"20261016120000","orderNo":"own"}""")) {
            val store=MemoryStore(); val transport=ScriptTransport(); val ble=ScriptBle()
            transport.script={path,_-> if(path.endsWith("bluetooth/rateOrder")) response(data) else {
                assertTrue(path.endsWith("upload/bluetooth/fail")); response()
            } }
            val repo=repo(transport,store,ble)
            assertTrue(runCatching { repo.start(auth,bleDevice) }.isFailure)
            assertNull(repo.session.value)
            assertEquals(1,transport.requests.count { it.first.endsWith("upload/bluetooth/fail") })
            assertEquals(0,ble.rates)
        }
    }

    @Test fun emptyOrderUsesTheAvailableLiquidOrder() = runTest {
        val store=MemoryStore(); val transport=ScriptTransport(); val ble=ScriptBle()
        transport.script={_,_-> response("""{"downData":"0011","consumeDate":"20261016120000","orderNo":" ","liquidOrderNo":"ble-order"}""") }
        val repo=repo(transport,store,ble); repo.start(auth,bleDevice)
        assertEquals("ble-order",store.account(auth.key).session?.orderNo)
        assertEquals(SessionStage.USING,repo.session.value?.stage)
    }

    @Test fun lostUploadResponseIsRecoveredByOrderWithoutAnotherUpload() = runTest {
        val store=MemoryStore(); val transport=ScriptTransport(); val ble=ScriptBle()
        transport.script={path,_-> when {
            path.endsWith("bluetooth/rateOrder") -> response("""{"downData":"0011","consumeDate":"20261016120000","orderNo":"own"}""")
            path.endsWith("upload/bluetooth/data") -> throw IOException("lost upload response")
            else -> error(path)
        } }
        val repo=repo(transport,store,ble); repo.start(auth,bleDevice)
        assertTrue(runCatching { repo.finish(auth) }.isFailure)
        val restarted=repo(transport,store,ble); restarted.restore(auth)
        transport.script={path,fields->
            assertTrue(path.contains("consumeOrder")); assertEquals("own",fields["orderNo"])
            response("""{"orderNo":"own","consumeMoney":40,"clData":"${encryptedBack()}"}""")
        }
        restarted.finish(auth)
        assertNull(restarted.session.value)
        assertEquals(1,transport.requests.count { it.first.endsWith("upload/bluetooth/data") })
        assertEquals(1,ble.backs)
    }

    @Test fun uploadedOldBleOrderNeverClosesOrClearsTheNewUse() = runTest {
        val store=MemoryStore(); val transport=ScriptTransport(); val ble=ScriptBle()
        store.updateAccount(auth.key) { it.copy(session=SessionUi(bleDevice,auth.key,stage=SessionStage.WRITEBACK_PENDING,
            orderNo="old",rateAttempted=true,uploaded=true,uploadAttempted=true,writeBackHex="001122")) }
        ble.state=ble.state.copy(state=1)
        val repo=repo(transport,store,ble); repo.restore(auth)
        repeat(2) { repo.finish(auth) }
        assertNull(repo.session.value)
        assertEquals(0,ble.closes+ble.backs)
        assertTrue(transport.requests.isEmpty())
    }

    @Test fun observingNewUsePermanentlyPreventsOldWritesEvenAfterRestartAndAnotherClose() = runTest {
        val store=MemoryStore(); val transport=ScriptTransport(); val ble=ScriptBle()
        store.updateAccount(auth.key) { it.copy(session=SessionUi(bleDevice,auth.key,stage=SessionStage.SETTLEMENT_PENDING,
            orderNo="old",rateAttempted=true,endedAt=System.currentTimeMillis())) }
        ble.state=ble.state.copy(state=1)
        transport.script={path,_-> assertTrue(path.contains("consumeOrder")); response(code=1) }
        val repo=repo(transport,store,ble); repo.restore(auth); repo.finish(auth)
        assertEquals(true,store.account(auth.key).session?.deviceUseChanged)
        ble.state=ble.state.copy(state=3)
        val restarted=repo(transport,store,ble); restarted.restore(auth); restarted.finish(auth)
        assertEquals(0,ble.closes+ble.backs)
        transport.script={_,_-> response("""{"orderNo":"old","consumeMoney":0}""") }
        restarted.reconcile(auth)
        assertNull(restarted.session.value)
        assertEquals(0,ble.closes+ble.backs)
    }

    @Test fun healthyFiveSecondReconnectCanRecoverInTheBackground() = runTest {
        val store=MemoryStore(); val transport=ScriptTransport(); val base=ScriptBle().apply { ready=false }
        var connections=0
        val ble=object : BlePort by base {
            override suspend fun connect(mac: String): BleConnectResult { delay(5_000); connections++; return base.connect(mac) }
        }
        store.updateAccount(auth.key) { it.copy(session=SessionUi(bleDevice,auth.key,stage=SessionStage.OPEN_UNCONFIRMED,orderNo="own",rateAttempted=true)) }
        base.state=base.state.copy(state=1)
        val repo=repo(transport,store,ble); repo.restore(auth)
        repeat(3) { repo.reconcile(auth) }
        assertEquals(SessionStage.USING,repo.session.value?.stage)
        assertEquals(1,connections)
        assertEquals(5_000L,testScheduler.currentTime)
    }

    @Test fun deviceStartedAgainDuringUploadIsCheckedBeforeSendingWriteback() = runTest {
        val store=MemoryStore(); val transport=ScriptTransport(); val ble=ScriptBle()
        store.updateAccount(auth.key) { it.copy(session=SessionUi(bleDevice,auth.key,stage=SessionStage.SETTLEMENT_PENDING,
            orderNo="own",rateAttempted=true)) }
        ble.state=ble.state.copy(state=3)
        transport.script={_,_->
            ble.state=ble.state.copy(state=1)
            response("""{"clData":"${encryptedBack()}"}""")
        }
        val repo=repo(transport,store,ble); repo.restore(auth); repo.finish(auth)
        assertNull(repo.session.value)
        assertEquals(0,ble.closes+ble.backs)
        assertEquals(1,ble.state.state)
    }

    @Test fun readyConnectionForAnotherDeviceIsNeverReusedByFinish() = runTest {
        val store=MemoryStore(); val transport=ScriptTransport(); val base=ScriptBle()
        base.connect("AA:BB:CC:DD:EE:01")
        var closeTarget: String?=null
        val ble=object : BlePort by base {
            override suspend fun closeValve(): Boolean { closeTarget=connectedMac; return base.closeValve() }
        }
        store.updateAccount(auth.key) { it.copy(session=SessionUi(bleDevice,auth.key,stage=SessionStage.USING,orderNo="own",rateAttempted=true)) }
        val repo=repo(transport,store,ble); repo.restore(auth); repo.finish(null)
        assertEquals(bleDevice.connectMac,closeTarget)
    }

    @Test fun queuedPreparationRechecksSessionAfterOpeningFails() = runTest {
        val store=MemoryStore().apply { this.auth=this@SessionLifecycleTest.auth }
        val transport=ScriptTransport(); val base=ScriptBle(); val lock=Mutex()
        val gate=CompletableDeferred<Unit>(); var queries=0
        val connections=mutableListOf<String>()
        val ble=object : BlePort by base {
            override suspend fun connect(mac: String): BleConnectResult { connections+=mac; return base.connect(mac) }
            override suspend fun query(): DeviceQueryState { if(++queries==2) gate.await(); return base.state }
        }
        transport.script={path,fields-> when(path) {
            "device/info/mac" -> response("""{"snCode":"${fields["macAddress"]}","deviceName":"水表","deviceId":"7","macAddress":"${fields["macAddress"]}","bigTypeId":"4","smallTypeId":"1","isBle":true}""")
            "order/tcpDevice/query/rateOrder/using" -> response(code=306)
            else -> throw IOException("lost allocation response")
        } }
        val authRepo=RealAuthRepository(transport,store); authRepo.restore()
        val sessions=RealSessionRepository(transport,store,ble,lock)
        val devices=RealDeviceRepository(transport,store,ble,lock)
        val preparation=DevicePreparation(authRepo,devices,sessions,store,backgroundScope)
        preparation.prepare(bleDevice); runCurrent()
        val opening=backgroundScope.async { runCatching { preparation.startSession(bleDevice.id) } }
        runCurrent()
        val second=bleDevice.copy(id="AABBCCDDEE01",ledgerMac="AABBCCDDEE01",connectMac="AA:BB:CC:DD:EE:01")
        preparation.prepare(second); runCurrent()
        gate.complete(Unit); runCurrent()
        assertTrue(opening.await().isFailure)
        assertEquals(bleDevice.id,sessions.session.value?.device?.id)
        assertEquals(ConnectionStage.IDLE,preparation.state.value.stage)
        assertFalse(connections.contains(second.connectMac))
    }
}
