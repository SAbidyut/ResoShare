package net.rapti.resoshare.user

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import net.rapti.resoshare.Graph
import net.rapti.resoshare.core.net.ApiException
import net.rapti.resoshare.core.net.Res
import net.rapti.resoshare.core.net.call
import net.rapti.resoshare.core.ui.Load
import net.rapti.resoshare.data.api.ChartPoint
import net.rapti.resoshare.data.api.Dashboard
import net.rapti.resoshare.data.api.Notice
import net.rapti.resoshare.data.api.Order
import net.rapti.resoshare.data.api.PaymentInfo

/** Shared, in-memory state for the shareholder portal (cleared on logout). */
object UserRepo {
    private val api get() = Graph.shareholderApi

    private val _dashboard = MutableStateFlow<Load<Dashboard>>(Load.Loading)
    val dashboard: StateFlow<Load<Dashboard>> = _dashboard

    private val _unread = MutableStateFlow(0)
    val unread: StateFlow<Int> = _unread

    private val _notices = MutableStateFlow<List<Notice>?>(null)
    val notices: StateFlow<List<Notice>?> = _notices

    private val _orders = MutableStateFlow<List<Order>?>(null)
    val orders: StateFlow<List<Order>?> = _orders

    private val _week = MutableStateFlow<List<ChartPoint>?>(null)
    /** Last 7 days of rates (home mini chart). */
    val week: StateFlow<List<ChartPoint>?> = _week

    /** Privacy toggle: hide money amounts on home/profile. */
    val hideAmounts by lazy { MutableStateFlow(Graph.prefs.hideAmounts) }

    var paymentInfo: PaymentInfo? = null
        private set

    fun toggleHide() {
        hideAmounts.value = !hideAmounts.value
        Graph.prefs.hideAmounts = hideAmounts.value
    }

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

    suspend fun refreshNotices(): ApiException? = when (val r = call { api.notices(null, 50) }) {
        is Res.Ok -> { _notices.value = r.value.items; _unread.value = r.value.unread; null }
        is Res.Err -> r.error
    }

    suspend fun refreshOrders(): ApiException? = when (val r = call { api.orders(null, 50) }) {
        is Res.Ok -> { _orders.value = r.value.items; null }
        is Res.Err -> r.error
    }

    suspend fun refreshWeek() {
        (call { api.chart("daily") } as? Res.Ok)?.let { _week.value = it.value.points }
    }

    /** Everything the home tab shows. */
    suspend fun refreshHome(): ApiException? {
        val e = refresh()
        refreshWeek()
        refreshNotices()
        refreshOrders()
        return e
    }

    suspend fun loadPaymentInfo(): Res<PaymentInfo> = call { api.paymentInfo() }.also { if (it is Res.Ok) paymentInfo = it.value }

    fun markNoticeRead(id: String) {
        _notices.value = _notices.value?.map { if (it.id == id) it.copy(read = true) else it }
        _unread.value = (_unread.value - 1).coerceAtLeast(0)
    }

    fun markAllRead() {
        _notices.value = _notices.value?.map { it.copy(read = true) }
        _unread.value = 0
    }

    fun clear() {
        _dashboard.value = Load.Loading
        _unread.value = 0
        _notices.value = null
        _orders.value = null
        _week.value = null
        paymentInfo = null
    }
}
