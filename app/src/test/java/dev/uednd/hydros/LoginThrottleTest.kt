package dev.uednd.hydros

import dev.uednd.hydros.data.network.ApiFailure
import dev.uednd.hydros.data.repository.RealAuthRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class LoginThrottleTest {
    @Test fun failedAttemptsShareSpacingAndRollingWindowAcrossPhonesAndMethods() = runTest {
        var now = 0L
        val transport = ScriptTransport().apply { script = { _, _ -> response(code = 12) } }
        val repo = RealAuthRepository(transport, MemoryStore(), nowMillis = { now })
        suspend fun attempt(sms: Boolean = false) {
            assertTrue(runCatching { repo.login(if (sms) "13900139000" else "13800138000", "123456", sms) }.exceptionOrNull() is ApiFailure)
        }
        attempt()
        assertEquals(3_000L, repo.loginRetryAfterMillis)
        now = 2_999
        attempt(true)
        assertEquals(1, transport.requests.size)
        assertEquals(1L, repo.loginRetryAfterMillis)
        for (time in listOf(3_000L, 6_000L, 9_000L, 12_000L)) {
            now = time
            attempt(true)
        }
        assertEquals(5, transport.requests.size)
        assertEquals(48_000L, repo.loginRetryAfterMillis)
        now = 59_999
        attempt()
        assertEquals(5, transport.requests.size)
        now = 60_000
        attempt()
        assertEquals(6, transport.requests.size)
        assertEquals(3_000L, repo.loginRetryAfterMillis)
    }

    @Test fun overlappingLoginIsRejectedWithoutQueuingAndCancellationReleasesPermit() = runTest {
        var now = 0L
        val started = CompletableDeferred<Unit>()
        val transport = ScriptTransport().apply {
            script = { _, _ -> started.complete(Unit); awaitCancellation() }
        }
        val repo = RealAuthRepository(transport, MemoryStore(), nowMillis = { now })
        val first = launch { repo.login("13800138000", "123456", false) }
        started.await()
        now = 4_000
        val failure = runCatching { repo.login("13900139000", "654321", true) }.exceptionOrNull()
        assertTrue(failure is ApiFailure)
        assertEquals("正在登录，请勿重复提交", failure?.message)
        assertEquals(1, transport.requests.size)
        first.cancelAndJoin()
        transport.script = { _, _ -> response(code = 12) }
        runCatching { repo.login("13800138000", "123456", false) }
        assertEquals(2, transport.requests.size)
    }

    @Test fun successfulLoginAndLogoutDoNotResetCooldown() = runTest {
        val store = MemoryStore()
        val transport = ScriptTransport().apply {
            script = { _, _ -> response("""{"loginCode":"test","userId":1,"userAccount":{"accountId":2,"projectId":3}}""") }
        }
        val repo = RealAuthRepository(transport, store, nowMillis = { 0 })
        repo.login("13800138000", "123456", false)
        assertNotNull(store.auth)
        repo.logout()
        repo.restore()
        assertEquals(3_000L, repo.loginRetryAfterMillis)
        assertTrue(runCatching { repo.login("13800138000", "123456", true) }.exceptionOrNull() is ApiFailure)
        assertEquals(1, transport.requests.size)
    }
}
