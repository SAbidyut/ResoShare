package net.rapti.resoshare.admin

import androidx.compose.animation.AnimatedContent
import net.rapti.resoshare.core.i18n.t
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.rapti.resoshare.Graph
import net.rapti.resoshare.core.ui.headerBubble
import net.rapti.resoshare.core.format.Bn
import net.rapti.resoshare.core.ui.BrandLogo
import net.rapti.resoshare.core.ui.CircleIconButton
import net.rapti.resoshare.core.ui.EmptyBlock
import net.rapti.resoshare.core.ui.ErrorBlock
import net.rapti.resoshare.core.ui.GradientCard
import net.rapti.resoshare.core.ui.Load
import net.rapti.resoshare.core.ui.LoadingBlock
import net.rapti.resoshare.core.ui.ResoCard
import net.rapti.resoshare.core.ui.ResoIcons
import net.rapti.resoshare.core.ui.RowDivider
import net.rapti.resoshare.core.ui.SalesBars
import net.rapti.resoshare.core.ui.SectionTitle
import net.rapti.resoshare.core.ui.SegmentedTabs
import net.rapti.resoshare.core.ui.StatusText
import net.rapti.resoshare.core.ui.T
import net.rapti.resoshare.core.ui.animatedLong
import net.rapti.resoshare.core.ui.dataOrNull
import net.rapti.resoshare.core.ui.pressable
import net.rapti.resoshare.core.ui.theme.Brand
import net.rapti.resoshare.core.ui.theme.Reso
import net.rapti.resoshare.data.api.AdminOrder
import net.rapti.resoshare.data.api.Perm
import net.rapti.resoshare.data.api.SalesStats

private val PERIODS = listOf("daily", "weekly", "monthly", "yearly")
private val PERIOD_LABELS: List<String>
    get() = listOf(t("দৈনিক", "Daily"), t("সাপ্তাহিক", "Weekly"), t("মাসিক", "Monthly"), t("বার্ষিক", "Yearly"))
private val PERIOD_SPAN: List<String>
    get() = listOf(t("গত ৭ দিন", "Last 7 days"), t("গত ৮ সপ্তাহ", "Last 8 weeks"), t("গত ১২ মাস", "Last 12 months"), t("গত ৫ বছর", "Last 5 years"))

@Composable
fun DashboardScreen(an: AdminNav) {
    val dash = rememberLoader("dash") { Graph.adminApi.dashboard() }
    var period by rememberSaveable { mutableIntStateOf(2) }
    val sales = rememberLoader("sales:${PERIODS[period]}") { Graph.adminApi.sales(PERIODS[period]) }
    val me by AdminSession.me.collectAsState()
    LaunchedEffect(dash.state) {
        dash.state.dataOrNull?.let { AdminSession.pending.value = it.pending.payments + it.pending.kyc }
    }
    val d = dash.state.dataOrNull

    AdminScreen(
        header = {
            val c = Reso.colors
            Column(
                Modifier.fillMaxWidth().background(c.header).headerBubble().statusBarsPadding().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    BrandLogo(44.dp)
                    Column(Modifier.weight(1f)) {
                        T(t("অ্যাডমিন প্যানেল", "Admin panel"), 14.sp, color = Brand.HeaderMuted)
                        T(t("ড্যাশবোর্ড", "Dashboard"), 22.sp, FontWeight.Bold, Color.White)
                    }
                    CircleIconButton(ResoIcons.Bell, t("নোটিশ ও বার্তা", "Notices & messages"), { an.go(Routes.NOTICES) })
                }
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.headerChip).padding(horizontal = 18.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        T(t("আজকের শেয়ার রেট", "Today's share rate"), 14.sp, color = Brand.HeaderMuted)
                        val rate = animatedLong(d?.today?.ratePaisa ?: 0L)
                        T(if (d == null) "—" else Bn.money(rate), 30.sp, FontWeight.Bold, Brand.Gold, maxLines = 1)
                    }
                    Box(
                        Modifier.clip(RoundedCornerShape(24.dp)).background(Brand.Amber)
                            .pressable(onClick = { an.go(Routes.RATE) }).padding(horizontal = 18.dp, vertical = 12.dp),
                    ) { T(if (me?.permissions?.contains(Perm.RATE) == true) t("রেট বদলান", "Change rate") else t("রেট দেখুন", "View rate"), 15.sp, FontWeight.Bold, Brand.Navy) }
                }
            }
        },
        refreshing = dash.refreshing,
        onRefresh = { dash.refresh(); sales.refresh() },
    ) {
        when (val s = dash.state) {
            is Load.Loading -> item("l") { LoadingBlock() }
            is Load.Failed -> item("e") { ErrorBlock(s.message, { dash.load() }) }
            is Load.Ready -> {
                val v = s.data
                item("tiles") {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            StatTile(t("মোট শেয়ার", "Total shares"), Bn.num(animatedLong(v.pool.totalShares.toLong())), Modifier.weight(1f))
                            StatTile(t("বিক্রিত", "Sold"), Bn.num(animatedLong(v.pool.soldShares.toLong())), Modifier.weight(1f))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            StatTile(t("অবশিষ্ট", "Remaining"), Bn.num(animatedLong(v.pool.remainingShares.toLong())), Modifier.weight(1f))
                            StatTile(t("শেয়ারহোল্ডার", "Shareholders"), t("${Bn.num(animatedLong(v.shareholderCount.toLong()))} জন", Bn.num(animatedLong(v.shareholderCount.toLong()))), Modifier.weight(1f))
                        }
                    }
                }
                item("sales") {
                    ResoCard {
                        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                T(t("শেয়ার বিক্রির চার্ট", "Share sales chart"), 17.sp, FontWeight.Bold, modifier = Modifier.weight(1f))
                                T(PERIOD_SPAN[period], 13.sp, color = Reso.colors.muted)
                            }
                            SegmentedTabs(PERIOD_LABELS, period, { period = it })
                            AnimatedContent(sales.state, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "sales") { st ->
                                when (st) {
                                    is Load.Loading -> LoadingBlock()
                                    is Load.Failed -> ErrorBlock(st.message, { sales.load() })
                                    is Load.Ready -> SalesBody(st.data)
                                }
                            }
                        }
                    }
                }
                val pend = v.pending.payments + v.pending.kyc
                if (pend > 0) item("pending") {
                    GradientCard(Reso.colors.rules, Modifier.fillMaxWidth(), radius = 14.dp, padding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                        onClick = { an.tab(Routes.APPROVALS) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                T(t("${Bn.num(pend)}টি অনুমোদন বাকি", "${Bn.num(pend)} approval${if (pend == 1) "" else "s"} pending"), 17.sp, FontWeight.Bold, Reso.colors.rules.value)
                                T(t("${Bn.num(v.pending.payments)}টি পেমেন্ট · ${Bn.num(v.pending.kyc)}টি KYC", "${Bn.num(v.pending.payments)} payment${if (v.pending.payments == 1) "" else "s"} · ${Bn.num(v.pending.kyc)} KYC"), 14.sp, color = Reso.colors.rules.label)
                            }
                            T(t("দেখুন ›", "View ›"), 15.sp, FontWeight.Bold, Reso.colors.rules.value)
                        }
                    }
                }
                item("recent") {
                    ResoCard {
                        SectionTitle(t("সাম্প্রতিক লেনদেন", "Recent transactions"), t("সব দেখুন", "See all")) { an.go(Routes.TRANSACTIONS) }
                        if (v.recentOrders.isEmpty()) EmptyBlock(t("এখনো কোনো লেনদেন নেই", "No transactions yet"), ResoIcons.List)
                        v.recentOrders.forEach { o ->
                            RowDivider(Modifier.padding(vertical = 2.dp))
                            OrderLine(o)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    ResoCard(modifier) {
        T(label, 14.sp, color = Reso.colors.muted, maxLines = 1)
        T(value, 24.sp, FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun SalesBody(s: SalesStats) {
    val c = Reso.colors
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row {
            Column(Modifier.weight(1f)) {
                T(t("বিক্রিত শেয়ার", "Shares sold"), 14.sp, color = c.muted)
                T(t("${Bn.num(s.totalShares)}টি", Bn.num(s.totalShares)), 20.sp, FontWeight.Bold, maxLines = 1)
            }
            Column(Modifier.weight(1.3f)) {
                T(t("মোট আয়", "Total income"), 14.sp, color = c.muted)
                T(Bn.money(s.totalAmountPaisa), 20.sp, FontWeight.Bold, maxLines = 1)
            }
        }
        SalesBars(
            s.points.map { it.shares.toLong() },
            s.points.map { p -> Bn.parseDate(p.date)?.let { Bn.axisLabel(it, s.period) } ?: "" },
        )
        val ch = s.lastChangeShares
        val (txt, col) = when {
            ch > 0 -> t("▲ শেষ পয়েন্টে আগের চেয়ে +${Bn.num(ch)}টি", "▲ +${Bn.num(ch)} vs previous period") to c.green
            ch < 0 -> t("▼ শেষ পয়েন্টে আগের চেয়ে −${Bn.num(-ch)}টি", "▼ −${Bn.num(-ch)} vs previous period") to c.red
            else -> t("— শেষ পয়েন্টে আগের সমান", "— Same as previous period") to c.muted
        }
        T(txt, 14.sp, FontWeight.Bold, col)
    }
}

/** Compact order row: name + shares on the left, amount + status on the right. */
@Composable
fun OrderLine(
    o: AdminOrder,
    subtitle: String = t("${Bn.num(o.quantity)}টি শেয়ার", "${Bn.num(o.quantity)} shares"),
    rich: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val c = Reso.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).then(if (onClick != null) Modifier.pressable(onClick = onClick) else Modifier).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (rich) {
            // v2: initial avatar coloured by status.
            val (bg, fg) = when (o.status) {
                "pending" -> c.warnBg to c.brown
                "cancelled" -> c.dangerBg to c.red
                else -> c.successBg to c.green
            }
            net.rapti.resoshare.core.ui.TextDisc(Bn.initial(o.shareholderName), bg, fg, 40.dp, 16.sp)
        }
        Column(Modifier.weight(1f)) {
            T(o.shareholderName, 16.sp, FontWeight.Bold, maxLines = 1)
            T(subtitle, 13.sp, color = c.muted, maxLines = 1)
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            T(Bn.money(o.amountPaisa), 16.sp, FontWeight.Bold, maxLines = 1)
            if (rich) net.rapti.resoshare.core.ui.StatusPill(o.status) else StatusText(o.status)
        }
    }
}
