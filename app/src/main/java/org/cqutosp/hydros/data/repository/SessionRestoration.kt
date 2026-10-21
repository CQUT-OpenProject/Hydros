package org.cqutosp.hydros.data.repository

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A service and a page share one process initialization; retries never replay device commands. */
internal class SessionRestoration(private val auth: AuthRepository, private val sessions: SessionRepository) {
    private val lock = Mutex()
    private var restored = false

    suspend fun restore() = lock.withLock {
        if (!restored) {
            auth.restore()
            sessions.restore(auth.credentials.value)
            restored = true
        }
    }
}
