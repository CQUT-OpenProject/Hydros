package org.cqutosp.hydros.data.repository

import org.cqutosp.hydros.data.network.ApiFailure

/** Process-local protection shared by both login methods and all phone numbers. */
internal class LoginThrottle(private val nowMillis: () -> Long) {
    private val attempts = ArrayDeque<Long>()
    private var inFlight = false

    @Synchronized
    fun retryAfterMillis(): Long {
        val now = nowMillis()
        while (attempts.isNotEmpty() && now - attempts.first() >= 60_000) attempts.removeFirst()
        val spacing = attempts.lastOrNull()?.let { (3_000 - (now - it)).coerceAtLeast(0) } ?: 0
        val window = if (attempts.size >= 5) (60_000 - (now - attempts.first())).coerceAtLeast(0) else 0
        return maxOf(spacing, window)
    }

    @Synchronized
    private fun begin() {
        if (inFlight) throw ApiFailure(null, "正在登录，请勿重复提交")
        val remaining = retryAfterMillis()
        if (remaining > 0) throw ApiFailure(null, "登录过于频繁，请在 ${(remaining + 999) / 1_000} 秒后重试")
        attempts.addLast(nowMillis())
        inFlight = true
    }

    @Synchronized
    private fun finish() { inFlight = false }

    suspend fun <T> run(block: suspend () -> T): T {
        begin()
        return try { block() } finally { finish() }
    }
}
