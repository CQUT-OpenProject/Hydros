package dev.uednd.hydros

import dev.uednd.hydros.data.network.*
import dev.uednd.hydros.data.repository.*
import dev.uednd.hydros.ui.components.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ReauthenticationTest {
    private val auth = Credentials("old", "1", "2", "3", "13800138000")

    @Test fun expirationIsAtomicDeduplicatedAndSurvivesRestoreUntilAcknowledged() = runTest {
        val store = MemoryStore().apply { this.auth = this@ReauthenticationTest.auth }
        val repo = RealAuthRepository(ScriptTransport(), store)
        repo.restore()
        coroutineScope { repeat(5) { launch { repo.expire(auth) } } }
        val notice = repo.authState.value.reauthentication!!
        assertNull(repo.authState.value.credentials)
        assertNull(repo.credentials.value)
        assertNull(store.auth)
        repo.restore()
        assertEquals(notice, repo.authState.value.reauthentication)
        repo.acknowledgeReauthentication(notice.id + 1)
        assertEquals(notice, repo.authState.value.reauthentication)
        repo.acknowledgeReauthentication(notice.id)
        assertNull(repo.authState.value.reauthentication)
        repo.expire(auth)
        assertNull(repo.authState.value.reauthentication)
    }

    @Test fun oldExpirationCannotInvalidateNewLoginAndLogoutHasNoNotice() = runTest {
        val store = MemoryStore().apply { this.auth = this@ReauthenticationTest.auth }
        val transport = ScriptTransport().apply {
            script = { _, _ -> response("""{"loginCode":"new","userId":"1","userAccount":{"accountId":"2","projectId":"3"}}""") }
        }
        val repo = RealAuthRepository(transport, store)
        repo.restore()
        repo.login(auth.phone, "password", false)
        repo.expire(auth)
        assertEquals("new", repo.credentials.value?.loginCode)
        assertNull(repo.authState.value.reauthentication)
        repo.logout()
        assertEquals(AuthState(), repo.authState.value)
    }

    @Test fun phoneChangeHasSpecificReasonAndPreservesAccountData() = runTest {
        val store = MemoryStore().apply { this.auth = this@ReauthenticationTest.auth }
        store.updateAccount(auth.key) { it.copy(recent = listOf("device")) }
        val repo = RealAuthRepository(ScriptTransport(), store)
        repo.restore()
        repo.savePhone("13900139000", "123456")
        assertEquals("手机号已修改，请重新登录", repo.authState.value.reauthentication?.message)
        assertEquals(listOf("device"), store.account(auth.key).recent)
    }

    @Test fun protectedSnackbarCannotBeOverwrittenAndCompletionDistinguishesReasons() = runTest {
        val state = HydrosSnackbarState()
        val ordinary = state.show("ordinary")!!
        val login = state.show("reauth", protected = true)!!
        assertEquals(SnackbarEnd.REPLACED, state.awaitDismissal(ordinary))
        assertNull(state.show("background error"))
        assertEquals(login.id, state.notice.value?.id)
        state.dismissOutside()
        assertFalse(login.completion.isCompleted)
        state.unlock(login.id)
        state.dismissOutside()
        assertEquals(SnackbarEnd.DISMISSED, state.awaitDismissal(login))
        val timed = state.show("timeout")!!
        state.dismiss(timed.id, SnackbarEnd.TIMEOUT)
        assertEquals(SnackbarEnd.TIMEOUT, state.awaitDismissal(timed))
    }
}
