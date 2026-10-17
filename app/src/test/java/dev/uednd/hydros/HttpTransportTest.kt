package dev.uednd.hydros

import dev.uednd.hydros.data.analytics.NetworkOperation
import dev.uednd.hydros.data.network.*
import dev.uednd.hydros.data.repository.*
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class HttpTransportTest {
    private val auth=Credentials("test-token","1","2","3","13800138000")
    @Test fun diagnosticsDistinguishAccountChangesFromLoginAndVerification() = runTest {
        val server = MockWebServer(); server.start()
        try {
            val paths = listOf("user/phone/update", "user/password/update", "user/password/forget",
                "account/info", "project/info/triple", "user/login", "user/verification/code/get")
            paths.forEach { server.enqueue(MockResponse.Builder().body("""{"success":false,"errorCode":12}""").build()) }
            val operations = mutableListOf<NetworkOperation>()
            val transport = HttpCampusTransport(CampusApiFactory.create(server.url("/").toString()),
                onDiagnostic = { operation, _, _, _ -> operations += operation }, onExpired = { fail("Unexpected expiry") })
            paths.forEach { transport.post(it) }
            assertEquals(List(5) { NetworkOperation.ACCOUNT } +
                List(2) { NetworkOperation.LOGIN }, operations)
        } finally { server.close() }
    }
    @Test fun getAndPostCarryAuthAndSmsKeepsTargetSeparate()=runTest {
        val server=MockWebServer(); server.start()
        try {
            repeat(3) { server.enqueue(MockResponse.Builder().body("""{"success":true,"data":null}""").build()) }
            val transport=HttpCampusTransport(CampusApiFactory.create(server.url("/").toString(),OkHttpClient())) { fail("Unexpected expiry") }
            transport.get("account/wallet",auth=auth)
            val get=server.takeRequest()
            assertEquals(auth.phone,get.url.queryParameter("telephone")); assertEquals(auth.phone,get.url.queryParameter("telPhone"))
            assertEquals("3",get.headers["Config-Project"])
            transport.post("order/tcpDevice/closeOrder",mapOf("snCode" to "device","orderNo" to "own"),auth)
            val post=server.takeRequest()
            val body=post.body?.utf8() ?: ""
            assertTrue(body.contains("telephone=13800138000")); assertTrue(body.contains("telPhone=13800138000"))
            assertTrue(post.headers["Content-Type"]!!.startsWith("application/x-www-form-urlencoded"))
            transport.get("user/verification/code/get",mapOf("telephone" to "13900139000","typeId" to "5"),auth)
            val sms=server.takeRequest()
            assertEquals("13900139000",sms.url.queryParameter("telephone")); assertEquals(auth.phone,sms.url.queryParameter("telPhone"))
        } finally { server.close() }
    }
    @Test fun authenticationFailuresOnlyExpireProtectedRequests()=runTest {
        val server=MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse.Builder().code(401).body("{}").build())
            server.enqueue(MockResponse.Builder().body("""{"success":false,"errorCode":12,"errorMessage":"账号或密码错误"}""").build())
            server.enqueue(MockResponse.Builder().body("""{"success":false,"errorCode":1,"errorMessage":"登录已失效"}""").build())
            var expirations=0
            val transport=HttpCampusTransport(CampusApiFactory.create(server.url("/").toString())) { assertEquals(auth,it); expirations++ }
            assertTrue(runCatching { transport.get("account/info",auth=auth) }.exceptionOrNull() is ApiFailure)
            val login=transport.post("user/login",mapOf("telephone" to auth.phone,"password" to "bad"))
            assertFalse(login.ok); assertEquals(1,expirations)
            assertTrue(runCatching { transport.get("account/info",auth=auth) }.isFailure); assertEquals(2,expirations)
        } finally { server.close() }
    }
    @Test fun ordinaryFailuresDoNotExpireAndInvalidatedRequestsNeverLeaveTransport() = runTest {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse.Builder().code(500).body("{}").build())
            server.enqueue(MockResponse.Builder().body("""{"success":false,"errorMessage":"余额不足"}""").build())
            var valid = true
            val transport = HttpCampusTransport(CampusApiFactory.create(server.url("/").toString()), isCurrent = { valid }) {
                fail("Ordinary failure must not expire credentials")
            }
            assertFalse((runCatching { transport.get("account/info", auth = auth) }.exceptionOrNull()?.let { if (it is ApiFailure) it else it.cause } as ApiFailure).expired)
            assertFalse(transport.get("account/info", auth = auth).ok)
            valid = false
            assertTrue((runCatching { transport.get("account/info", auth = auth) }.exceptionOrNull()?.let { if (it is ApiFailure) it else it.cause } as ApiFailure).expired)
            assertEquals(2, server.requestCount)
        } finally { server.close() }
    }

    @Test fun forbiddenOnlyExpiresAuthenticatedRequests() = runTest {
        val server = MockWebServer(); server.start()
        try {
            repeat(2) { server.enqueue(MockResponse.Builder().code(403).body("{}").build()) }
            var expirations = 0
            val transport = HttpCampusTransport(CampusApiFactory.create(server.url("/").toString())) { expirations++ }
            val anonymous = runCatching { transport.post("user/login") }.exceptionOrNull()?.cause as ApiFailure
            assertFalse(anonymous.expired)
            val protected = runCatching { transport.get("account/info", auth = auth) }.exceptionOrNull() as ApiFailure
            assertTrue(protected.expired)
            assertEquals(1, expirations)
        } finally { server.close() }
    }

    @Test fun accountOperationsUseSmsPurposeAndRealSecrets()=runTest {
        val store=MemoryStore(); store.auth=auth
        val transport=ScriptTransport()
        val repo=RealAuthRepository(transport,store); repo.restore()
        repo.sendCode("13900139000",5)
        repo.sendCode(auth.phone,2)
        repo.sendCode(auth.phone,3)
        assertEquals(listOf("5","2","3"),transport.requests.map { it.second["typeId"] })
        repo.savePassword("oldpass", "newpass",null)
        assertEquals(passwordHash("oldpass"),transport.requests.last().second["oldPassword"])
        repo.savePassword(null,"newpass","123456")
        assertEquals("123456",transport.requests.last().second["code"])
        repo.savePhone("13900139000","654321")
        assertEquals("13900139000",transport.requests.last().second["newTelephone"])
        assertNull(repo.credentials.value)
    }
}
