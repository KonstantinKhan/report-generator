package io.github.konstantinkhan.loodsman

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class SessionHolder(
    private val auth: Auth,
    private val config: LoodsmanConfig
) {

    private val mutex = Mutex()
    private var sessionId: String? = null

    internal suspend fun sessionId(): String = mutex.withLock {
        sessionId ?: login().also { sessionId = it }
    }

    internal suspend fun <T> withSession(block: suspend (sessionId: String) -> T): T {
        val id = sessionId()
        return try {
            block(id)
        } catch (e: LoodsmanApiException) {
            if (!e.isSessionExpired) throw e
            invalidate(id)
            block(sessionId())
        }
    }

    internal suspend fun invalidate(staleId: String) = mutex.withLock {
        if (sessionId == staleId) sessionId = null
    }

    internal suspend fun close() = mutex.withLock {
        if (sessionId != null) auth.logout()
        sessionId = null
    }

    private suspend fun login(): String =
        auth.login(config.dbName, config.username, config.password).sessionId
            ?: throw LoodsmanApiException("Loodsman login response did not contain a sessionId")
}
