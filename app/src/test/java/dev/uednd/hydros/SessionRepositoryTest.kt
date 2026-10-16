package dev.uednd.hydros

import dev.uednd.hydros.data.storage.FeedbackSettings
import dev.uednd.hydros.data.ble.*
import dev.uednd.hydros.data.network.*
import dev.uednd.hydros.data.repository.*
import dev.uednd.hydros.data.storage.*
import dev.uednd.hydros.ui.state.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import java.util.Base64

class MemoryStore : HydrosStore {
    var auth: Credentials?=null
    var mode="SYSTEM"
    val records=mutableMapOf<String,SavedAccount>()
    override suspend fun credentials()=auth
    override suspend fun credentials(value: Credentials?) { auth=value }
    override suspend fun account(key: String)=records[key] ?: SavedAccount()
    override suspend fun updateAccount(key: String, transform: (SavedAccount)->SavedAccount): SavedAccount = transform(account(key)).also { records[key]=it }
    override suspend fun theme()=mode
    override suspend fun theme(value: String) { mode=value }
    private var fontScale = 1f
    override suspend fun fontSizeScale() = fontScale
    override suspend fun fontSizeScale(value: Float) { fontScale = value }
    private var feedbackSettings = FeedbackSettings()
    override suspend fun feedback() = feedbackSettings
    override suspend fun updateFeedback(transform: (FeedbackSettings) -> FeedbackSettings): FeedbackSettings =
        transform(feedbackSettings).also { feedbackSettings = it }
    var onboarding = false
    override suspend fun onboardingCompleted() = onboarding
    override suspend fun onboardingCompleted(value: Boolean) { onboarding = value }
    var analytics = false
    override suspend fun analyticsEnabled() = analytics
    override suspend fun analyticsEnabled(value: Boolean) { analytics = value }
}
class ScriptTransport : CampusTransport {
    val requests=mutableListOf<Pair<String,Map<String,String>>>()
    var script: suspend (String,Map<String,String>)->ApiResponse<JsonElement> = { _,_-> response() }
    override suspend fun get(path: String, fields: Map<String,String>, auth: Credentials?) = request(path,fields)
    override suspend fun post(path: String, fields: Map<String,String>, auth: Credentials?) = request(path,fields)
    private suspend fun request(path: String, fields: Map<String,String>): ApiResponse<JsonElement> { requests+=path to fields; return script(path,fields) }
}
fun response(data: String?=null, code: Int=0) = ApiResponse(success=code==0, errorCode=code, data=data?.let(Json::parseToJsonElement))
class ScriptBle : BlePort {
    override var ready=true
    var state=DeviceQueryState("20","ABCDEF01",0,1,2)
    var acknowledgeRate=true
    var acknowledgeClose=true
    var acknowledgeBack=true
    var rates=0; var closes=0; var backs=0
    var beforeRate: (() -> Unit)?=null
    override suspend fun connect(mac: String): BleConnectResult { ready=true; return BleConnectResult.Ready }
    override suspend fun query()=state
    override suspend fun writeRate(bytes: ByteArray): Boolean { beforeRate?.invoke(); rates++; state=state.copy(state=1); return acknowledgeRate }
    override suspend fun closeValve(): Boolean { closes++; if(acknowledgeClose) state=state.copy(state=3); return acknowledgeClose }
    override suspend fun collect()=byteArrayOf(0xAA.toByte(),0xBB.toByte())
    override suspend fun writeBack(bytes: ByteArray): Boolean { backs++; if(acknowledgeBack) state=state.copy(state=0); return acknowledgeBack }
    override fun disconnect() { ready=false }
}
class SessionRepositoryTest {
    private val auth=Credentials("key","1","2","3","13800138000")
    private fun device(channel: ControlChannel)=DeviceUi("AABBCCDDEEFF","真实水表","宿舍",DeviceCategory.SHOWER,
        channel=channel, deviceId="7", ledgerMac="AABBCCDDEEFF",connectMac="AA:BB:CC:DD:EE:FF",bigTypeId="4",smallTypeId="1")
    private fun repo(t: ScriptTransport,s: MemoryStore,b: ScriptBle)=RealSessionRepository(t,s,b,Mutex(),2,{})
    @Test fun unconfirmedCloudStartPersistsAndDoesNotAllowSecondStart()=runTest {
        val t=ScriptTransport(); val s=MemoryStore(); val b=ScriptBle(); val r=repo(t,s,b)
        t.script={path,_-> when { path.endsWith("downRateResult") -> response(code=1); else -> response() } }
        r.start(auth,device(ControlChannel.CLOUD))
        assertEquals(SessionStage.OPEN_UNCONFIRMED,r.session.value?.stage)
        assertNotNull(s.account(auth.key).session)
        assertTrue(runCatching { r.start(auth,device(ControlChannel.CLOUD)) }.isFailure)
        assertEquals(1,t.requests.count { it.first.endsWith("rateOrder") })
    }
    @Test fun otherPersonsAndUnknownOwnershipNeverOpenOrClose()=runTest {
        for(owner in listOf("false","null")) {
            val t=ScriptTransport(); t.script={_,_->response("""{"orderNo":"others","isOwner":$owner}""")}
            val r=repo(t,MemoryStore(),ScriptBle())
            assertTrue(runCatching { r.start(auth,device(ControlChannel.CLOUD)) }.isFailure)
            assertNull(r.session.value)
            assertEquals(1,t.requests.size)
        }
    }
    @Test fun cloudCloseAcceptanceDoesNotProveClosed()=runTest {
        val t=ScriptTransport(); val s=MemoryStore(); val r=repo(t,s,ScriptBle())
        s.updateAccount(auth.key) { it.copy(session=SessionUi(device(ControlChannel.CLOUD),auth.key,stage=SessionStage.USING,orderNo="own")) }; r.restore(auth)
        t.script={path,_-> if(path.endsWith("using")) response("""{"orderNo":"own","isOwner":true}""") else response() }
        r.finish(auth)
        assertEquals(SessionStage.CLOSE_UNCONFIRMED,r.session.value?.stage)
        assertFalse(t.requests.any { it.first.contains("consumeOrder") })
    }
    @Test fun cloudSettlementUsesRealZeroAndConfirmsOrderIdentity()=runTest {
        val t=ScriptTransport(); val s=MemoryStore(); val r=repo(t,s,ScriptBle())
        s.updateAccount(auth.key) { it.copy(session=SessionUi(device(ControlChannel.CLOUD),auth.key,stage=SessionStage.USING,orderNo="own")) }; r.restore(auth)
        t.script={path,_-> if(path.contains("consumeOrder")) response("""{"orderNo":"own","consumeMoney":0}""") else response() }
        r.finish(auth); assertNull(r.session.value); assertNull(s.account(auth.key).session)
    }
    private fun encryptedBack(): String {
        val c=Cipher.getInstance("AES/ECB/PKCS5Padding"); c.init(Cipher.ENCRYPT_MODE,SecretKeySpec(DeviceCrypto.aesKey,"AES"))
        return Base64.getEncoder().encodeToString(c.doFinal("prefix-001122".toByteArray()))
    }
    @Test fun blePersistsBeforeWriteAndResumesWhenAckIsLost()=runTest {
        val s=MemoryStore(); val t=ScriptTransport(); val b=ScriptBle(); val r=repo(t,s,b)
        t.script={_,_->response("""{"downData":"0011","consumeDate":"20261014120000","orderNo":"own"}""")}
        b.acknowledgeRate=false
        b.beforeRate={ assertTrue(s.records[auth.key]?.session?.rateAttempted==true) }
        r.start(auth,device(ControlChannel.BLE))
        assertEquals(SessionStage.OPEN_UNCONFIRMED,r.session.value?.stage)
        val restarted=repo(t,s,b); restarted.restore(auth); restarted.reconcile(auth)
        assertEquals(SessionStage.USING,restarted.session.value?.stage); assertEquals(1,b.rates)
    }
    @Test fun bleWritebackRetryNeverUploadsAgainEvenAcrossRestart()=runTest {
        val s=MemoryStore(); val t=ScriptTransport(); val b=ScriptBle(); val r=repo(t,s,b)
        s.updateAccount(auth.key) { it.copy(session=SessionUi(device(ControlChannel.BLE),auth.key,stage=SessionStage.USING,orderNo="own",consumeDate="20261014120000",rateAttempted=true)) }; r.restore(auth)
        t.script={_,fields-> assertEquals("aabb",fields["xfData"]); response("""{"clData":"${encryptedBack()}"}""") }
        b.acknowledgeBack=false
        assertTrue(runCatching { r.finish(auth) }.isFailure)
        assertTrue(r.session.value?.uploaded==true); assertEquals(SessionStage.WRITEBACK_PENDING,r.session.value?.stage)
        b.acknowledgeBack=true
        val restarted=repo(t,s,b); restarted.restore(auth); restarted.finish(auth)
        assertNull(restarted.session.value)
        assertEquals(1,t.requests.count { it.first.contains("upload/bluetooth/data") })
        assertEquals(1,b.closes); assertEquals(2,b.backs)
    }
    @Test fun ambiguousUploadIsNotRetriedAndCannotBecomeFakeSuccess()=runTest {
        val s=MemoryStore(); val t=ScriptTransport(); val b=ScriptBle(); val r=repo(t,s,b)
        s.updateAccount(auth.key) { it.copy(session=SessionUi(device(ControlChannel.BLE),auth.key,stage=SessionStage.USING,orderNo="own",consumeDate="date",rateAttempted=true)) }; r.restore(auth)
        t.script={path,_-> if(path.contains("upload/bluetooth/data")) throw java.io.IOException("lost response") else response(code=1) }
        runCatching { r.finish(auth) }; runCatching { r.finish(auth) }
        assertTrue(r.session.value?.uploadAttempted==true); assertFalse(r.session.value?.uploaded==true)
        assertEquals(1,t.requests.count { it.first.contains("upload/bluetooth/data") })
    }
    @Test fun logoutStillAllowsLocalBleCloseButRequiresLoginForUpload()=runTest {
        val s=MemoryStore(); val t=ScriptTransport(); val b=ScriptBle(); val r=repo(t,s,b)
        s.updateAccount(auth.key) { it.copy(session=SessionUi(device(ControlChannel.BLE),auth.key,stage=SessionStage.USING,rateAttempted=true)) }; r.restore(auth)
        r.restore(null); r.finish(null)
        assertEquals(1,b.closes); assertEquals(SessionStage.SETTLEMENT_PENDING,r.session.value?.stage); assertTrue(t.requests.isEmpty())
    }
    @Test fun cloudCloseAcceptsClosingStatusAndSettles()=runTest {
        val t=ScriptTransport(); val s=MemoryStore(); val r=repo(t,s,ScriptBle())
        s.updateAccount(auth.key) { it.copy(session=SessionUi(device(ControlChannel.CLOUD),auth.key,stage=SessionStage.USING,orderNo="own")) }; r.restore(auth)
        t.script={path,_-> when {
            path.contains("closeOrder") && !path.contains("consumeOrder") -> ApiResponse(success=false, errorCode=307, errorMessage="已在关闭中")
            path.contains("consumeOrder") -> response("""{"orderNo":"own","consumeMoney":0}""")
            else -> response()
        }}
        r.finish(auth); assertNull(r.session.value); assertNull(s.account(auth.key).session)
    }
    @Test fun deviceSmallTypeOneDoesNotSelectBleWhenCloudIsAvailable()=runTest {
        for(online in listOf("1", "null")) {
            val s=MemoryStore(); val t=ScriptTransport(); val b=ScriptBle()
            t.script={path,_-> if(path=="device/info/mac") response("""{"deviceId":7,"deviceName":"热水表-宿舍","snCode":"AABBCCDDEEFF","macAddress":"AABBCCDDEEFF","smallTypeId":1,"onlineStatusId":$online}""") else response() }
            val ready=RealDeviceRepository(t,s,b,Mutex()).prepare(auth,device(ControlChannel.UNKNOWN))
            assertEquals(ControlChannel.CLOUD,ready.channel)
        }
        val s=MemoryStore(); val t=ScriptTransport(); val b=ScriptBle()
        t.script={path,_-> if(path=="device/info/mac") response("""{"deviceId":7,"deviceName":"热水表-宿舍","snCode":"AABBCCDDEEFF","macAddress":"AABBCCDDEEFF","smallTypeId":1,"onlineStatusId":0}""") else response() }
        val ready=RealDeviceRepository(t,s,b,Mutex()).prepare(auth,device(ControlChannel.UNKNOWN))
        assertEquals(ControlChannel.CLOUD,ready.channel)
        assertEquals(DeviceStatus.OFFLINE,ready.status)
    }
    @Test fun findTranslatesPublicLedgerMacToConnectAddress()=runTest {
        val s=MemoryStore(); val t=ScriptTransport(); val b=ScriptBle()
        t.script={_,_-> response("""{"deviceId":7,"deviceName":"热水表-宿舍","snCode":"00158304D300","macAddress":"00158304D300","smallTypeId":1}""") }
        val found=RealDeviceRepository(t,s,b,Mutex()).find(auth,"00158304D300")
        assertEquals("C0158304D300", found.connectMac)
    }
}
