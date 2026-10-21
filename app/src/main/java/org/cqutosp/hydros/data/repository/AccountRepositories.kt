package org.cqutosp.hydros.data.repository

import org.cqutosp.hydros.data.ble.DeviceSigner
import org.cqutosp.hydros.data.network.*
import org.cqutosp.hydros.data.storage.HydrosStore
import org.cqutosp.hydros.ui.state.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

enum class ReauthenticationReason { EXPIRED, ACCOUNT_CHANGED }
data class ReauthenticationNotice(val id: Long, val message: String,
    val reason: ReauthenticationReason = ReauthenticationReason.EXPIRED)
data class AuthState(val credentials: Credentials? = null, val reauthentication: ReauthenticationNotice? = null)

@OptIn(kotlinx.coroutines.InternalCoroutinesApi::class)
private class CredentialState(private val source: StateFlow<AuthState>) : StateFlow<Credentials?> {
    override val value get() = source.value.credentials
    override val replayCache get() = listOf(value)
    override suspend fun collect(collector: FlowCollector<Credentials?>): Nothing {
        source.map { it.credentials }.distinctUntilChanged().collect(collector)
        error("Authentication state completed")
    }
}

interface AuthRepository {
    val authState: Flow<AuthState> get() = credentials.map { AuthState(it) }
    suspend fun acknowledgeReauthentication(id: Long) {}
    val credentials: kotlinx.coroutines.flow.StateFlow<Credentials?>
    suspend fun restore()
    suspend fun login(phone: String, secret: String, sms: Boolean)
    val loginRetryAfterMillis: Long get() = 0
    suspend fun sendCode(phone: String, type: Int)
    suspend fun logout()
    suspend fun savePhone(newPhone: String, code: String)
    suspend fun savePassword(old: String?, password: String, code: String?)
    fun requireCredentials(): Credentials
}
private val SMS_SALT = byteArrayOf(107, 108, 99, 120)
fun passwordHash(password: String) = DeviceSigner.md5Hex(password).takeLast(10).uppercase()
fun smsSecret(phone: String) = DeviceSigner.md5Hex(phone.take(3) + phone.takeLast(4) + String(SMS_SALT, Charsets.UTF_8))
class RealAuthRepository(private val transport: CampusTransport, private val store: HydrosStore, nowMillis: () -> Long = { System.nanoTime() / 1_000_000 }, private val validateIdentity: (Credentials) -> Unit = {}) : AuthRepository {
    private val current = MutableStateFlow(AuthState())
    override val authState = current.asStateFlow()
    override val credentials: StateFlow<Credentials?> = CredentialState(authState)
    private var noticeSequence = 0L
    private val lock = Mutex()
    private val loginThrottle = LoginThrottle(nowMillis)
    override val loginRetryAfterMillis: Long get() = loginThrottle.retryAfterMillis()
    override fun requireCredentials() = credentials.value ?: throw ApiFailure(null, "请先登录")
    override suspend fun restore() = lock.withLock {
        if (current.value.reauthentication == null) current.value = AuthState(store.credentials())
    }
    override suspend fun login(phone: String, secret: String, sms: Boolean) = loginThrottle.run { lock.withLock {
        val fields = mapOf("telephone" to phone, "phoneSystem" to "android", "version" to "6.5.28", "type" to if(sms) "5" else "0") +
            if (sms) mapOf("smsCode" to secret) else mapOf("password" to passwordHash(secret))
        val data = transport.post(if(sms) "user/registerAndLogin" else "user/login", fields).requireData()
        val account = data.obj("userAccount") ?: throw ApiFailure(null, "登录返回缺少账户")
        fun needed(value: String?) = value?.takeIf { it.isNotBlank() } ?: throw ApiFailure(null, "登录返回缺少凭证")
        val auth = Credentials(needed(data.text("loginCode")), needed(data.text("userId")), needed(account.text("accountId")), needed(account.text("projectId")), phone)
        validateIdentity(auth)
        store.credentials(auth); current.value = AuthState(auth)
    } }
    override suspend fun sendCode(phone: String, type: Int) {
        transport.get("user/verification/code/get", mapOf("telephone" to phone, "secret" to smsSecret(phone), "typeId" to type.toString(), "platform" to "1"),
            if(type == 3) null else requireCredentials()).requireSuccess()
    }
    override suspend fun logout() = lock.withLock { store.credentials(null); current.value = AuthState() }
    suspend fun expire(expected: Credentials, message: String = "登录已失效，请重新登录",
        reason: ReauthenticationReason = ReauthenticationReason.EXPIRED) = lock.withLock {
        if(current.value.credentials == expected) {
            store.credentials(null)
            current.value = AuthState(reauthentication = ReauthenticationNotice(++noticeSequence, message, reason))
        }
    }
    override suspend fun acknowledgeReauthentication(id: Long) = lock.withLock {
        if (current.value.reauthentication?.id == id) current.value = current.value.copy(reauthentication = null)
    }
    override suspend fun savePhone(newPhone: String, code: String) {
        val auth = requireCredentials()
        transport.post("user/phone/update", mapOf("newTelephone" to newPhone, "code" to code), auth).requireSuccess()
        // Re-authenticate after changing the identity fields; never invent a replacement loginCode.
        expire(auth, "手机号已修改，请重新登录", ReauthenticationReason.ACCOUNT_CHANGED)
    }
    override suspend fun savePassword(old: String?, password: String, code: String?) {
        val auth = requireCredentials()
        val fields = mapOf("password" to passwordHash(password)) + if(old != null) mapOf("oldPassword" to passwordHash(old)) else mapOf("code" to requireNotNull(code))
        transport.post(if(old != null) "user/password/update" else "user/password/forget", fields, auth).requireSuccess()
    }
}
interface AccountRepository { suspend fun load(auth: Credentials): AccountUi }
class RealAccountRepository(private val transport: CampusTransport) : AccountRepository {
    override suspend fun load(auth: Credentials): AccountUi {
        val account = transport.get("account/info", auth=auth).requireData()
        val project = transport.get("project/info/triple", auth=auth).requireData()
        return AccountUi(account.text("name")?.takeIf(String::isNotBlank) ?: "未提供姓名", auth.phone,
            account.text("idCardNumber") ?: "未提供学号", project.text("projectName") ?: "未提供学校")
    }
}
interface WalletRepository {
    suspend fun load(auth: Credentials): WalletUi
    suspend fun bills(auth: Credentials, month: String, type: BillType = BillType.CONSUMPTION): List<BillUi>
    suspend fun detail(auth: Credentials, bill: BillUi): BillUi
}
class RealWalletRepository(private val transport: CampusTransport) : WalletRepository {
    override suspend fun load(auth: Credentials): WalletUi {
        val wallet = transport.get("account/wallet", auth = auth).requireData()
        return WalletUi(yuanMilli(wallet.text("money")) ?: throw ApiFailure(null, "余额数据格式不正确"))
    }
    override suspend fun bills(auth: Credentials, month: String, type: BillType): List<BillUi> {
        val response = transport.get("order/query/account/bill/list", mapOf("month" to month, "billRequestType" to type.requestType), auth)
        response.requireSuccess()
        val rows = response.data as? JsonArray ?: throw ApiFailure(null, "账单列表格式不正确")
        return rows.mapIndexed { index, item ->
            val row = item as? JsonObject ?: throw ApiFailure(null, "账单格式不正确")
            when (type) {
                BillType.CONSUMPTION -> parseBill(row.obj("consumeBillDTO") ?: throw ApiFailure(null, "消费账单格式不正确"))
                BillType.RECHARGE -> parseRechargeBill(row.obj("rechargeBillDTO") ?: throw ApiFailure(null, "充值账单格式不正确"), index)
            }
        }
    }
    override suspend fun detail(auth: Credentials, bill: BillUi): BillUi {
        val obj = transport.get("order/query/account/bill/detail", mapOf("orderId" to bill.id, "consumeDate" to bill.consumeDate), auth).requireData()
        return parseBill(obj, bill)
    }
}
fun parseRechargeBill(obj: JsonObject, index: Int): BillUi {
    val date = obj.text("dealDate")?.takeIf(String::isNotBlank) ?: throw ApiFailure(null, "充值账单缺少交易时间")
    val time = parseDate(date)
    val typeName = obj.text("typeName")?.takeIf(String::isNotBlank) ?: "充值"
    return BillUi(
        id = "recharge:${time}:${obj.text("dealMoney")}:${typeName}:$index",
        title = typeName,
        location = typeName,
        category = null,
        amountMilli = yuanMilli(obj.text("dealMoney")),
        time = time,
        consumeDate = date,
        recharge = true,
        detailAvailable = false,
    )
}
fun category(name: String, bigType: Int? = null): DeviceCategory = when {
    bigType == 5 || listOf("饮水", "直饮", "开水机", "开水器", "冷水").any(name::contains) -> DeviceCategory.DRINK
    listOf("洗手台", "洗漱").any(name::contains) -> DeviceCategory.WASH
    else -> DeviceCategory.SHOWER
}
fun parseDate(value: String): LocalDateTime = listOf("yyyy-MM-dd HH:mm:ss", "yyyyMMddHHmmss", "yyyy-MM-dd'T'HH:mm:ss").firstNotNullOfOrNull {
    runCatching { LocalDateTime.parse(value, DateTimeFormatter.ofPattern(it)) }.getOrNull()
} ?: throw ApiFailure(null, "账单时间格式无法识别")
fun parseBill(obj: JsonObject, previous: BillUi? = null): BillUi {
    val id = obj.text("orderId") ?: previous?.id ?: throw ApiFailure(null, "账单缺少编号")
    val date = obj.text("consumeDate") ?: previous?.consumeDate ?: throw ApiFailure(null, "账单缺少消费时间")
    val description = obj.text("description")
    val title = description?.substringAfter(":") ?: previous?.title ?: "消费账单"
    val location = description?.substringAfter(":") ?: previous?.location ?: title
    val recharge = description?.contains("充值") == true || previous?.recharge == true
    return BillUi(
        id = id,
        title = title,
        location = location,
        category = if (recharge) null else previous?.category ?: category(description ?: title),
        amountMilli = yuanMilli(obj.text("consumeMoney")) ?: previous?.amountMilli,
        time = parseDate(date),
        consumeDate = date,
        orderNo = obj.text("orderNo") ?: previous?.orderNo,
        durationText = obj.text("usingTimeDisplay")?.trim()?.takeIf(String::isNotEmpty) ?: previous?.durationText,
        recharge = recharge,
    )
}
