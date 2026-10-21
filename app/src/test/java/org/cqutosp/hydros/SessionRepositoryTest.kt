package org.cqutosp.hydros

import org.cqutosp.hydros.data.storage.FeedbackSettings
import org.cqutosp.hydros.data.network.*
import org.cqutosp.hydros.data.repository.*
import org.cqutosp.hydros.data.storage.*
import org.cqutosp.hydros.ui.state.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

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
}
class ScriptTransport : CampusTransport {
    val requests=mutableListOf<Pair<String,Map<String,String>>>()
    var script: suspend (String,Map<String,String>)->ApiResponse<JsonElement> = { _,_-> response() }
    override suspend fun get(path: String, fields: Map<String,String>, auth: Credentials?) = request(path,fields)
    override suspend fun post(path: String, fields: Map<String,String>, auth: Credentials?) = request(path,fields)
    private suspend fun request(path: String, fields: Map<String,String>): ApiResponse<JsonElement> { requests+=path to fields; return script(path,fields) }
}
fun response(data: String?=null, code: Int=0) = ApiResponse(success=code==0, errorCode=code, data=data?.let(Json::parseToJsonElement))
class SessionRepositoryTest {
    private val auth=Credentials("key","1","2","3","13800138000")
    private fun device(channel: ControlChannel)=DeviceUi("AABBCCDDEEFF","真实水表","宿舍",DeviceCategory.SHOWER,
        channel=channel, deviceId="7", ledgerMac="AABBCCDDEEFF",connectMac="AA:BB:CC:DD:EE:FF",bigTypeId="4",smallTypeId="1")
    private fun repo(t: ScriptTransport,s: MemoryStore)=RealSessionRepository(t,s,Mutex(),2,{})
    @Test fun unconfirmedCloudStartPersistsAndDoesNotAllowSecondStart()=runTest {
        val t=ScriptTransport(); val s=MemoryStore(); val r=repo(t,s)
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
            val r=repo(t,MemoryStore())
            assertTrue(runCatching { r.start(auth,device(ControlChannel.CLOUD)) }.isFailure)
            assertNull(r.session.value)
            assertEquals(1,t.requests.size)
        }
    }
    @Test fun cloudCloseAcceptanceDoesNotProveClosed()=runTest {
        val t=ScriptTransport(); val s=MemoryStore(); val r=repo(t,s)
        s.updateAccount(auth.key) { it.copy(session=SessionUi(device(ControlChannel.CLOUD),auth.key,stage=SessionStage.USING,orderNo="own")) }; r.restore(auth)
        t.script={path,_-> if(path.endsWith("using")) response("""{"orderNo":"own","isOwner":true}""") else response() }
        r.finish(auth)
        assertEquals(SessionStage.CLOSE_UNCONFIRMED,r.session.value?.stage)
        assertFalse(t.requests.any { it.first.contains("consumeOrder") })
    }
    @Test fun cloudSettlementUsesRealZeroAndConfirmsOrderIdentity()=runTest {
        val t=ScriptTransport(); val s=MemoryStore(); val r=repo(t,s)
        s.updateAccount(auth.key) { it.copy(session=SessionUi(device(ControlChannel.CLOUD),auth.key,stage=SessionStage.USING,orderNo="own")) }; r.restore(auth)
        t.script={path,_-> if(path.contains("consumeOrder")) response("""{"orderNo":"own","consumeMoney":0}""") else response() }
        r.finish(auth); assertNull(r.session.value); assertNull(s.account(auth.key).session)
    }
    @Test fun cloudCloseAcceptsClosingStatusAndSettles()=runTest {
        val t=ScriptTransport(); val s=MemoryStore(); val r=repo(t,s)
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
            val s=MemoryStore(); val t=ScriptTransport()
            t.script={path,_-> if(path=="device/info/mac") response("""{"deviceId":7,"deviceName":"热水表-宿舍","snCode":"AABBCCDDEEFF","macAddress":"AABBCCDDEEFF","smallTypeId":1,"onlineStatusId":$online}""") else response() }
            val ready=RealDeviceRepository(t,s,Mutex()).prepare(auth,device(ControlChannel.UNKNOWN))
            assertEquals(ControlChannel.CLOUD,ready.channel)
        }
        val s=MemoryStore(); val t=ScriptTransport()
        t.script={path,_-> if(path=="device/info/mac") response("""{"deviceId":7,"deviceName":"热水表-宿舍","snCode":"AABBCCDDEEFF","macAddress":"AABBCCDDEEFF","smallTypeId":1,"onlineStatusId":0}""") else response() }
        val ready=RealDeviceRepository(t,s,Mutex()).prepare(auth,device(ControlChannel.UNKNOWN))
        assertEquals(ControlChannel.CLOUD,ready.channel)
        assertEquals(DeviceStatus.OFFLINE,ready.status)
    }
    @Test fun rememberedBleAndIsBleStillUseCloudWhenStatusQuerySucceeds() = runTest {
        val store = MemoryStore()
        store.updateAccount(auth.key) { it.copy(devices = listOf(device(ControlChannel.BLE).copy(isBle = true))) }
        val transport = ScriptTransport()
        transport.script = { path, _ ->
            if (path == "device/info/mac") {
                response("""{"deviceId":7,"deviceName":"热水表-宿舍","snCode":"AABBCCDDEEFF","macAddress":"AABBCCDDEEFF","smallTypeId":1,"isBle":true,"onlineStatusId":1}""")
            } else response()
        }
        val ready = RealDeviceRepository(transport, store, Mutex()).prepare(auth, device(ControlChannel.BLE).copy(isBle = true))
        assertEquals(ControlChannel.CLOUD, ready.channel)
        assertEquals(1, transport.requests.count { it.first == "order/tcpDevice/query/rateOrder/using" })
        assertEquals(ControlChannel.CLOUD, store.account(auth.key).devices.single().channel)
    }
    @Test fun cloudSettlementSupportsDecimalConsumeMoney()=runTest {
        val t=ScriptTransport(); val s=MemoryStore(); val r=repo(t,s)
        s.updateAccount(auth.key) { it.copy(session=SessionUi(device(ControlChannel.CLOUD),auth.key,stage=SessionStage.USING,orderNo="own")) }; r.restore(auth)
        t.script={path,_-> if(path.contains("consumeOrder")) response("""{"orderNo":"own","consumeMoney":"0.50"}""") else response() }
        r.finish(auth); assertNull(r.session.value); assertNull(s.account(auth.key).session)
    }
    @Test fun findTranslatesPublicLedgerMacToConnectAddress()=runTest {
        val s=MemoryStore(); val t=ScriptTransport()
        t.script={_,_-> response("""{"deviceId":7,"deviceName":"热水表-宿舍","snCode":"00158304D300","macAddress":"00158304D300","smallTypeId":1}""") }
        val found=RealDeviceRepository(t,s,Mutex()).find(auth,"00158304D300")
        assertEquals("C0158304D300", found.connectMac)
    }
}
