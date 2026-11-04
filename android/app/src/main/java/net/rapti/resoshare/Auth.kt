package net.rapti.resoshare

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import net.rapti.resoshare.core.session.Session
import net.rapti.resoshare.data.api.RefreshRequest

/** Logout that also revokes the refresh token on the server (best effort, bounded time). */
object Auth {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    suspend fun logout(message: String?) {
        val rt = Graph.tokens.refreshToken
        if (rt != null) {
            withTimeoutOrNull(4_000) {
                runCatching {
                    if (BuildConfig.IS_ADMIN) Graph.adminApi.logout(RefreshRequest(rt))
                    else Graph.shareholderApi.logout(RefreshRequest(rt))
                }
            }
        }
        Session.expire(message)
        Graph.tokens.clear()
    }

    fun logoutAsync(message: String?) { scope.launch { logout(message) } }
}
