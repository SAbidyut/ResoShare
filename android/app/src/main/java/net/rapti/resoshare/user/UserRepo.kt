package net.rapti.resoshare.user

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import net.rapti.resoshare.Graph
import net.rapti.resoshare.core.net.ApiException
import net.rapti.resoshare.core.net.Res
import net.rapti.resoshare.core.net.call
import net.rapti.resoshare.core.ui.Load
import net.rapti.resoshare.data.api.Dashboard
import net.rapti.resoshare.data.api.PaymentInfo

/** Shared, in-memory state for the shareholder app (cleared on logout). */
object UserRepo {
    private val api get() = Graph.shareholderApi

    private val _dashboard = MutableStateFlow<Load<Dashboard>>(Load.Loading)
    val dashboard: StateFlow<Load<Dashboard>> = _dashboard

    private val _unread = MutableStateFlow(0)
    val unread: StateFlow<Int> = _unread

    var paymentInfo: PaymentInfo? = null
        private set

    /** Reloads the dashboard; keeps showing old data if the refresh fails. */
    suspend fun refresh(): ApiException? = when (val r = call { api.dashboard() }) {
        is Res.Ok -> {
            _dashboard.value = Load.Ready(r.value)
            _unread.value = r.value.unreadNotices
            null
        }
        is Res.Err -> {
            if (_dashboard.value !is Load.Ready) _dashboard.value = Load.Failed(r.error.message)
            r.error
        }
    }

    suspend fun loadPaymentInfo(): Res<PaymentInfo> = call { api.paymentInfo() }.also { if (it is Res.Ok) paymentInfo = it.value }

    fun setUnread(n: Int) { _unread.value = n }

    fun clear() {
        _dashboard.value = Load.Loading
        _unread.value = 0
        paymentInfo = null
    }
}
