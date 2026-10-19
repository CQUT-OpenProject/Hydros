package dev.uednd.hydros

import dev.uednd.hydros.data.network.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.net.ConnectException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLHandshakeException

@OptIn(ExperimentalCoroutinesApi::class)
class QueryRetryTest {
    @Test fun transientFailureRecoversWithinSharedRequestBudget() = runTest {
        var calls = 0
        val result = retryReadQuery { budget -> budget.attempt {
            calls++
            if (calls < 3) throw ConnectException()
            "recovered"
        } }
        assertEquals("recovered", result)
        assertEquals(3, calls)
        assertEquals(2_100L, testScheduler.currentTime)
    }

    @Test fun tlsAndLongRetryAfterStopWithoutAnotherRequest() = runTest {
        for (failure in listOf(SSLHandshakeException("certificate"), QueryHttpFailure(429), QueryHttpFailure(503, 4_000))) {
            var calls = 0
            val result = runCatching { retryReadQuery { budget -> budget.attempt { calls++; throw failure } } }
            assertEquals(failure.javaClass, result.exceptionOrNull()?.javaClass)
            assertEquals(failure.message, result.exceptionOrNull()?.message)
            assertEquals(1, calls)
        }
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test fun totalDeadlineCancelsPendingRequest() = runTest {
        var cancelled = false
        val result = runCatching { retryReadQuery { budget -> budget.attempt {
            try { awaitCancellation() } finally { cancelled = true }
        } } }
        assertTrue(result.exceptionOrNull() is SocketTimeoutException)
        assertTrue(cancelled)
        assertEquals(READ_QUERY_RECOVERY_MILLIS, testScheduler.currentTime)
    }

    @Test fun cancellationDuringBackoffDoesNotSendAnotherRequest() = runTest {
        var calls = 0
        val request = launch { retryReadQuery { budget -> budget.attempt { calls++; throw ConnectException() } } }
        runCurrent()
        request.cancelAndJoin()
        advanceUntilIdle()
        assertEquals(1, calls)
    }
}
