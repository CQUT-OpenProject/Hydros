package dev.uednd.hydros

import dev.uednd.hydros.data.update.*
import dev.uednd.hydros.ui.state.AppInfoViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppInfoViewModelTest {
    @Test fun deduplicatesChecksAndKeepsConfirmedUpdateAndTimeAfterFailure() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            var fail = false
            var calls = 0
            val gate = CompletableDeferred<Unit>()
            val model = AppInfoViewModel(AppUpdateSource {
                calls++; gate.await()
                if (fail) error("offline")
                AppRelease("9.0.0", "v9.0.0", "notes")
            })
            model.checkForUpdates(); runCurrent(); assertEquals(1, calls)
            gate.complete(Unit); advanceUntilIdle()
            assertTrue(model.updates.value.hasUpdate)
            val time = model.updates.value.lastChecked
            assertNotNull(time)
            fail = true; model.checkForUpdates(); advanceUntilIdle()
            assertTrue(model.updates.value.hasUpdate)
            assertEquals(time, model.updates.value.lastChecked)
            assertNotNull(model.updates.value.error)
            assertFalse(model.updates.value.checking)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun paginatesWithoutDuplicatesAndRetriesFailedPage() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            var fail = true
            val pages = mutableListOf<Int>()
            val model = AppInfoViewModel(object : AppUpdateSource {
                override suspend fun latest(): AppRelease? = null
                override suspend fun history(page: Int): ReleasePage {
                    pages += page
                    if (page == 2 && fail) error("offline")
                    val old = AppRelease("0.1.0", "v0.1.0", "")
                    return ReleasePage(if (page == 1) listOf(old) else listOf(old, AppRelease("0.0.1", "v0.0.1", "")), page == 1)
                }
            })
            model.loadHistory(); model.loadHistory(); advanceUntilIdle()
            model.loadHistory(); advanceUntilIdle()
            assertEquals(1, model.history.value.releases.size)
            assertNotNull(model.history.value.error)
            fail = false; model.loadHistory(); advanceUntilIdle()
            assertEquals(listOf(1, 2, 2), pages)
            assertEquals(2, model.history.value.releases.size)
            assertFalse(model.history.value.hasMore)
        } finally { Dispatchers.resetMain() }
    }
}
