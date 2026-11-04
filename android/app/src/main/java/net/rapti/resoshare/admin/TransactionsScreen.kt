package net.rapti.resoshare.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import net.rapti.resoshare.Graph
import net.rapti.resoshare.core.format.Bn
import net.rapti.resoshare.core.ui.EmptyBlock
import net.rapti.resoshare.core.ui.ErrorBlock
import net.rapti.resoshare.core.ui.FilterPills
import net.rapti.resoshare.core.ui.HeaderStat
import net.rapti.resoshare.core.ui.Load
import net.rapti.resoshare.core.ui.LoadingBlock
import net.rapti.resoshare.core.ui.PrimaryButton
import net.rapti.resoshare.core.ui.ResoIcons
import net.rapti.resoshare.core.ui.RowDivider
import net.rapti.resoshare.core.ui.ScreenHeader
import net.rapti.resoshare.core.ui.SearchField
import net.rapti.resoshare.core.ui.T
import net.rapti.resoshare.core.ui.methodShort
import net.rapti.resoshare.core.ui.theme.Reso
import net.rapti.resoshare.data.api.AdminOrder

private val TX_STATUS = listOf("all", "confirmed", "pending", "cancelled")
private val TX_LABELS = listOf("সব", "নিশ্চিত", "যাচাই চলছে", "বাতিল")

@Composable
fun TransactionsScreen(an: AdminNav) {
    var query by rememberSaveable { mutableStateOf("") }
    var debounced by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(query) { if (query != debounced) { delay(350); debounced = query.trim() } }
    val pager = rememberPager(
        "orders:${TX_STATUS[filter]}:$debounced",
        { cursor -> Graph.adminApi.orders(TX_STATUS[filter], debounced.ifBlank { null }, cursor) },
        { it.items }, { it.nextCursor },
    )
    val (export, exportUi) = rememberReportExport("transactions", "লেনদেন এক্সপোর্ট")
    val first = pager.first

    AdminScreen(
        header = {
            ScreenHeader("লেনদেন", onBack = an::back) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    HeaderStat("আজ মোট", first?.let { Bn.money(it.todayTotalPaisa) } ?: "—", Modifier.weight(1f))
                    HeaderStat("এই মাসে মোট", first?.let { Bn.money(it.monthTotalPaisa) } ?: "—", Modifier.weight(1f))
                }
            }
        },
        refreshing = pager.refreshing,
        onRefresh = { pager.refresh() },
        spacing = 0.dp,
    ) {
        item("search") { SearchField(query, { query = it }, "নাম, অর্ডার বা রেফারেন্স দিয়ে খুঁজুন") }
        item("filters") { FilterPills(TX_LABELS, filter, { filter = it }, Modifier.padding(vertical = 14.dp)) }
        when (val s = pager.state) {
            is Load.Loading -> item("l") { LoadingBlock() }
            is Load.Failed -> item("e") { ErrorBlock(s.message, { pager.load() }) }
            is Load.Ready -> {
                if (pager.items.isEmpty()) item("empty") { EmptyBlock("কোনো লেনদেন পাওয়া যায়নি", ResoIcons.List) }
                val groups = pager.items.groupBy { Bn.parseInstant(it.createdAt)?.toLocalDate() ?: Bn.today() }
                groups.forEach { (day, list) ->
                    item("h:$day") { T(dayHeader(day), 14.sp, FontWeight.Bold, Reso.colors.muted, modifier = Modifier.padding(top = 6.dp, bottom = 8.dp)) }
                    itemsIndexed(list, key = { _, o -> o.id }) { i, o ->
                        Column(Modifier.grouped(i == 0, i == list.lastIndex)) {
                            if (i > 0) RowDivider(Modifier.padding(horizontal = 16.dp))
                            Box(Modifier.padding(horizontal = 16.dp)) {
                                OrderLine(o, txSubtitle(o)) { an.go(Routes.holder(o.shareholderId)) }
                            }
                        }
                    }
                    item("gap:$day") { Box(Modifier.padding(top = 12.dp)) }
                }
                if (pager.hasMore) item("more") {
                    LaunchedEffect(pager.items.size) { pager.more() }
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(24.dp), color = Reso.colors.ink, strokeWidth = 2.dp)
                    }
                }
                item("export") {
                    Box(Modifier.padding(top = 8.dp)) {
                        PrimaryButton("লেনদেন এক্সপোর্ট করুন", export.start, icon = ResoIcons.Download, loading = export.busy)
                    }
                }
            }
        }
    }
    exportUi()
}

private fun txSubtitle(o: AdminOrder): String {
    val t = Bn.parseInstant(o.createdAt)?.let { Bn.time(it) } ?: ""
    return "${Bn.num(o.quantity)}টি · ${methodShort(o.method)} · $t"
}
