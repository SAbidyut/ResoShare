package net.rapti.resoshare.user

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import kotlinx.coroutines.launch
import net.rapti.resoshare.Graph
import net.rapti.resoshare.core.format.Bn
import net.rapti.resoshare.core.net.Res
import net.rapti.resoshare.core.net.call
import net.rapti.resoshare.core.ui.ChangeBars
import net.rapti.resoshare.core.ui.ErrorBlock
import net.rapti.resoshare.core.ui.LegendItem
import net.rapti.resoshare.core.ui.Load
import net.rapti.resoshare.core.ui.LoadingBlock
import net.rapti.resoshare.core.ui.PriceLineChart
import net.rapti.resoshare.core.ui.ResoCard
import net.rapti.resoshare.core.ui.ScreenHeader
import net.rapti.resoshare.core.ui.SegmentedTabs
import net.rapti.resoshare.core.ui.T
import net.rapti.resoshare.core.ui.animatedLong
import net.rapti.resoshare.core.ui.theme.Brand
import net.rapti.resoshare.core.ui.theme.Reso
import net.rapti.resoshare.data.api.Chart

private val PERIODS = listOf("daily", "weekly", "monthly", "yearly")
private val PERIOD_LABELS = listOf("দৈনিক", "সাপ্তাহিক", "মাসিক", "বার্ষিক")
private val PERIOD_SPANS = listOf("গত ৭ দিনে", "গত ৮ সপ্তাহে", "গত ১২ মাসে", "গত ৫ বছরে")

@Composable
fun ChartScreen(nav: NavHostController) {
    val c = Reso.colors
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val cache = remember { mutableStateMapOf<String, Chart>() }
    var state by remember { mutableStateOf<Load<Chart>>(Load.Loading) }
    val scope = rememberCoroutineScope()

    suspend fun load(period: String) {
        cache[period]?.let { state = Load.Ready(it) } ?: run { state = Load.Loading }
        when (val r = call { Graph.shareholderApi.chart(period) }) {
            is Res.Ok -> { cache[period] = r.value; state = Load.Ready(r.value) }
            is Res.Err -> if (cache[period] == null) state = Load.Failed(r.error.message)
        }
    }
    LaunchedEffect(tab) { load(PERIODS[tab]) }

    val ch = (state as? Load.Ready)?.data ?: cache.values.firstOrNull()
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("শেয়ারের চার্ট", onBack = { nav.tab(Routes.HOME) }) {
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    T("প্রতি শেয়ারের আজকের মূল্য", 14.sp, color = Brand.HeaderMuted)
                    T(if (ch != null) Bn.money(animatedLong(ch.todayRatePaisa)) else "—", 34.sp, FontWeight.Bold, Color.White, lineHeight = 44.sp)
                }
                if (ch != null) {
                    val up = ch.dayChangePaisa >= 0
                    Box(Modifier.clip(RoundedCornerShape(18.dp)).background(if (up) Color(0xFFE3F5EC) else Color(0xFFFDECEC)).padding(horizontal = 12.dp, vertical = 6.dp)) {
                        T((if (up) "▲ " else "▼ ") + Bn.signedMoney(ch.dayChangePaisa) + " আজ", 14.sp, FontWeight.Bold, if (up) Color(0xFF0F7B4F) else Color(0xFFB42318))
                    }
                }
            }
        }
        LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { SegmentedTabs(PERIOD_LABELS, tab, { tab = it }) }
            when (val s = state) {
                is Load.Failed -> item { ErrorBlock(s.message, onRetry = { scope.launch { load(PERIODS[tab]) } }) }
                Load.Loading -> item { LoadingBlock() }
                is Load.Ready -> {
                    val d = s.data
                    val values = d.points.map { it.ratePaisa }
                    val labels = d.points.mapNotNull { Bn.parseDate(it.date) }.map { Bn.axisLabel(it, d.period) }
                    item {
                        ResoCard {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                T("শেয়ারের মূল্য (৳)", 17.sp, FontWeight.Bold, modifier = Modifier.weight(1f))
                                T(PERIOD_SPANS[tab], 13.sp, color = c.muted)
                            }
                            Spacer(Modifier.height(12.dp))
                            PriceLineChart(values, labels)
                        }
                    }
                    item {
                        ResoCard {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                T("বৃদ্ধি ও হ্রাস", 17.sp, FontWeight.Bold, modifier = Modifier.weight(1f))
                                T("আগের পয়েন্টের তুলনায়", 13.sp, color = c.muted)
                            }
                            Spacer(Modifier.height(12.dp))
                            ChangeBars(values.zipWithNext { a, b -> b - a })
                            Spacer(Modifier.height(12.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                LegendItem(c.green, "বেড়েছে ▲")
                                LegendItem(c.red, "কমেছে ▼")
                            }
                        }
                    }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            ResoCard(Modifier.weight(1f)) {
                                T("${PERIOD_SPANS[tab]} পরিবর্তন", 13.sp, color = c.muted)
                                val up = d.periodChangePaisa >= 0
                                T((if (up) "▲ " else "▼ ") + Bn.signedMoney(d.periodChangePaisa), 19.sp, FontWeight.Bold, if (up) c.green else c.red, maxLines = 1)
                                T(Bn.percentBp(d.periodChangeBp), 13.sp, FontWeight.Bold, if (up) c.green else c.red)
                            }
                            ResoCard(Modifier.weight(1f)) {
                                T("সর্বোচ্চ / সর্বনিম্ন", 13.sp, color = c.muted)
                                T(Bn.money(d.highPaisa), 19.sp, FontWeight.Bold, maxLines = 1)
                                T("সর্বনিম্ন ${Bn.money(d.lowPaisa)}", 13.sp, color = c.muted, maxLines = 1)
                            }
                        }
                    }
                    item {
                        ResoCard {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    T("আপনার ${Bn.num(d.myShares)}টি শেয়ারের মূল্য", 15.sp, FontWeight.Bold)
                                    T("এই সময়ে বদল", 13.sp, color = c.muted)
                                }
                                val v = d.periodChangePaisa * d.myShares
                                T(Bn.signedMoney(v), 20.sp, FontWeight.Bold, if (v >= 0) c.green else c.red)
                            }
                        }
                    }
                    item {
                        val month = Bn.monthName(Bn.today().monthValue)
                        val total = d.monthChangePaisa * d.myShares
                        Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Brand.Amber).padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            T("চলতি মাসে ($month) মোট ${if (d.monthChangePaisa >= 0) "বৃদ্ধি" else "হ্রাস"}", 15.sp, FontWeight.Bold, Brand.Navy)
                            T(Bn.signedMoney(animatedLong(total)), 30.sp, FontWeight.Bold, Brand.Navy, lineHeight = 40.sp)
                            T("${Bn.num(d.myShares)}টি শেয়ারে · প্রতি শেয়ারে ${Bn.signedMoney(d.monthChangePaisa)}", 14.sp, color = Brand.Navy)
                        }
                    }
                }
            }
        }
    }
}
