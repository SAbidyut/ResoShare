package net.rapti.resoshare.user

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import kotlinx.coroutines.launch
import net.rapti.resoshare.Auth
import net.rapti.resoshare.BuildConfig
import net.rapti.resoshare.Graph
import net.rapti.resoshare.MainActivity
import net.rapti.resoshare.Toaster
import net.rapti.resoshare.core.format.Bn
import net.rapti.resoshare.core.net.Res
import net.rapti.resoshare.core.net.call
import net.rapti.resoshare.core.ui.Bubble
import net.rapti.resoshare.core.ui.ConfirmDialog
import net.rapti.resoshare.core.ui.DangerButton
import net.rapti.resoshare.core.ui.DemoBadge
import net.rapti.resoshare.core.ui.ErrorBlock
import net.rapti.resoshare.core.ui.GradientCard
import net.rapti.resoshare.core.ui.IconDisc
import net.rapti.resoshare.core.ui.LinkText
import net.rapti.resoshare.core.ui.Load
import net.rapti.resoshare.core.ui.LoadingBlock
import net.rapti.resoshare.core.ui.MenuRow
import net.rapti.resoshare.core.ui.PrimaryButton
import net.rapti.resoshare.core.ui.Refreshable
import net.rapti.resoshare.core.ui.ResoCard
import net.rapti.resoshare.core.ui.ResoIcons
import net.rapti.resoshare.core.ui.RowDivider
import net.rapti.resoshare.core.ui.SectionTitle
import net.rapti.resoshare.core.ui.SegmentedTabs
import net.rapti.resoshare.core.ui.SplitBar
import net.rapti.resoshare.core.ui.T
import net.rapti.resoshare.core.ui.Tag
import net.rapti.resoshare.core.ui.TextDisc
import net.rapti.resoshare.core.ui.ToggleRow
import net.rapti.resoshare.core.ui.animatedLong
import net.rapti.resoshare.core.ui.methodLabel
import net.rapti.resoshare.core.ui.pressable
import net.rapti.resoshare.core.ui.theme.Brand
import net.rapti.resoshare.core.ui.theme.Reso
import net.rapti.resoshare.core.ui.theme.ThemeMode
import net.rapti.resoshare.data.api.Dashboard
import net.rapti.resoshare.data.api.Order
import net.rapti.resoshare.data.api.PublicInfo

@Composable
fun ProfileScreen(nav: NavHostController) {
    val state by UserRepo.dashboard.collectAsState()
    val scope = rememberCoroutineScope()
    var orders by remember { mutableStateOf<List<Order>?>(null) }
    var refreshing by remember { mutableStateOf(false) }

    suspend fun loadAll() {
        UserRepo.refresh()?.let { Toaster.show(it.message) }
        (call { Graph.shareholderApi.orders(null, 50) } as? Res.Ok)?.let { orders = it.value.items }
    }
    LaunchedEffect(Unit) { loadAll() }

    Refreshable(refreshing, onRefresh = { scope.launch { refreshing = true; loadAll(); refreshing = false } }) {
        when (val s = state) {
            is Load.Ready -> ProfileContent(nav, s.data, orders)
            is Load.Failed -> ErrorBlock(s.message, onRetry = { scope.launch { loadAll() } }, Modifier.statusBarsPadding())
            Load.Loading -> LoadingBlock(Modifier.statusBarsPadding())
        }
    }
}

@Composable
private fun ProfileContent(nav: NavHostController, d: Dashboard, orders: List<Order>?) {
    val c = Reso.colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirmLogout by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<PublicInfo?>(null) }
    val unread by UserRepo.unread.collectAsState()
    LaunchedEffect(Unit) { (call { Graph.shareholderApi.publicInfo() } as? Res.Ok)?.let { info = it.value } }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 28.dp)) {
        item {
            Row(
                Modifier.fillMaxWidth().background(c.header).statusBarsPadding().padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 24.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                TextDisc(Bn.initial(d.me.fullName), Brand.Amber, Brand.Navy, 68.dp, 30.sp)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    T(d.me.fullName, 21.sp, FontWeight.Bold, Color.White)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Tag(d.me.shareholderNo, c.headerChip, Brand.Gold, size = 13.sp)
                        when {
                            d.me.accountStatus == "suspended" -> Tag("স্থগিত", Color(0xFFFDECEC), Color(0xFFB42318), size = 13.sp)
                            d.me.kycStatus == "verified" -> Tag("KYC যাচাইকৃত", Color(0xFFE3F5EC), Color(0xFF0F7B4F), size = 13.sp)
                            d.me.kycStatus == "rejected" -> Tag("KYC বাতিল", Color(0xFFFDECEC), Color(0xFFB42318), size = 13.sp)
                            else -> Tag("KYC অপেক্ষমাণ", Color(0xFFFFF1D6), Color(0xFF8A4B00), size = 13.sp)
                        }
                    }
                }
            }
        }
        item {
            Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    GradientCard(c.shares, Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            IconDisc(ResoIcons.Layers, c.ink, c.onInk)
                            T("মোট কেনা শেয়ার", 14.sp, FontWeight.Bold, c.shares.label)
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.Bottom) {
                            T(Bn.num(d.holding.shares), 32.sp, FontWeight.Bold, c.shares.value, lineHeight = 38.sp)
                            T(" টি", 16.sp, color = c.shares.value, modifier = Modifier.padding(bottom = 4.dp))
                        }
                    }
                    GradientCard(c.paid, Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            IconDisc(ResoIcons.Wallet, c.green, Color.White)
                            T("মোট পরিশোধ", 14.sp, FontWeight.Bold, c.paid.label)
                        }
                        Spacer(Modifier.height(8.dp))
                        T(Bn.money(d.holding.paidPaisa), 20.sp, FontWeight.Bold, c.paid.value, maxLines = 1, lineHeight = 38.sp)
                    }
                }
                GradientCard(c.rate, Modifier.fillMaxWidth(), padding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextDisc("৳", Brand.Amber, Brand.Navy, 32.dp, 18.sp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            T("একটি শেয়ার কিনতে", 14.sp, FontWeight.Bold, c.rate.label)
                            T("আজকের রেট", 12.sp, color = c.rate.label)
                        }
                        T(Bn.money(d.today.ratePaisa), 22.sp, FontWeight.Bold, c.rate.value)
                    }
                }
                SellNowCard(d)
                ResoCard(padding = PaddingValues(0.dp), onClick = { nav.navigate(Routes.RULES) }) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        IconDisc(ResoIcons.Shield, c.warnBg, c.brown, 40.dp, 22.dp)
                        Column(Modifier.weight(1f)) {
                            T("নিয়ম ও রেগুলেশন", 16.sp, FontWeight.Bold)
                            T("শেয়ার কেনা-বেচা ও নমিনির নিয়ম", 13.sp, color = c.muted)
                        }
                        Icon(ResoIcons.Chevron, null, tint = c.muted, modifier = Modifier.size(18.dp))
                    }
                }
                SectionTitle("আমার শেয়ারের তালিকা")
                val confirmed = orders?.filter { it.status == "confirmed" }
                when {
                    confirmed == null -> LoadingBlock()
                    confirmed.isEmpty() -> ResoCard { T("নিশ্চিত হওয়া কোনো শেয়ার এখনো নেই।", 15.sp, color = c.muted) }
                    else -> ResoCard(padding = PaddingValues(0.dp)) {
                        confirmed.forEachIndexed { i, o ->
                            if (i > 0) RowDivider()
                            Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    T("${Bn.num(o.quantity)}টি × ${Bn.money(o.ratePaisa)}", 15.sp, FontWeight.Bold)
                                    T("${Bn.dateOf(o.decidedAt ?: o.createdAt)} · ${methodLabel(o.method)}", 13.sp, color = c.muted)
                                }
                                T(Bn.money(o.amountPaisa), 16.sp, FontWeight.Bold)
                            }
                        }
                    }
                }
                NomineeCard(nav, d)
                ResoCard(padding = PaddingValues(0.dp), onClick = { nav.navigate(Routes.NOTICES) }) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        IconDisc(ResoIcons.Bell, c.infoBg, c.ink, 40.dp, 22.dp)
                        Column(Modifier.weight(1f)) {
                            T("নোটিশ", 16.sp, FontWeight.Bold)
                            T("কোম্পানির বার্তা ও রেটের খবর", 13.sp, color = c.muted)
                        }
                        if (unread > 0) Tag("${Bn.num(unread)} নতুন", Brand.Amber, Brand.Navy)
                        Icon(ResoIcons.Chevron, null, tint = c.muted, modifier = Modifier.size(18.dp))
                    }
                }
                PrimaryButton("শেয়ার সার্টিফিকেট দেখুন", { nav.navigate(Routes.CERTIFICATE) })

                SectionTitle("সেটিংস ও নিরাপত্তা")
                ResoCard(padding = PaddingValues(0.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        T("অ্যাপের থিম", 15.sp, FontWeight.Bold)
                        val mode by Graph.themeMode.collectAsState()
                        SegmentedTabs(listOf("সিস্টেম", "লাইট", "ডার্ক"), mode.ordinal, { i ->
                            val m = ThemeMode.entries[i]
                            Graph.themeMode.value = m; Graph.prefs.themeMode = m
                        })
                    }
                    RowDivider()
                    val activity = ctx as? MainActivity
                    val canLock = activity?.canUseDeviceAuth() == true
                    var lock by remember { mutableStateOf(Graph.prefs.appLock) }
                    Box(Modifier.padding(horizontal = 16.dp)) {
                        ToggleRow(
                            "অ্যাপ লক", if (canLock) "অ্যাপে ফিরলে ফিঙ্গারপ্রিন্ট/পিন লাগবে" else "ফোনে স্ক্রিন লক চালু করলে ব্যবহার করা যাবে",
                            lock && canLock, { lock = it; Graph.prefs.appLock = it }, enabled = canLock,
                        )
                    }
                    RowDivider()
                    MenuRow(ResoIcons.Key, "পাসওয়ার্ড পরিবর্তন", onClick = { nav.navigate(Routes.PASSWORD) })
                    RowDivider()
                    MenuRow(ResoIcons.List, "সব লেনদেন", onClick = { nav.navigate(Routes.ORDERS) })
                    RowDivider()
                    val hotline = info?.hotline ?: "01617-345534"
                    MenuRow(ResoIcons.Phone, "হটলাইন", Bn.digits(hotline), onClick = {
                        runCatching { ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + hotline.filter { it.isDigit() || it == '+' }))) }
                    })
                }
                DangerButton("লগআউট", { confirmLogout = true })
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (Graph.isDemo) DemoBadge()
                    T("সংস্করণ ${Bn.digits(BuildConfig.VERSION_NAME)} · Resonance Automation", 12.sp, color = c.muted)
                }
            }
        }
    }
    if (confirmLogout) ConfirmDialog("লগআউট করবেন?", "আবার ঢুকতে মোবাইল/ইমেইল ও পাসওয়ার্ড লাগবে।", "লগআউট", {
        confirmLogout = false
        scope.launch { Auth.logout(null) }
    }, { confirmLogout = false }, danger = true)
}

@Composable
private fun SellNowCard(d: Dashboard) {
    val profit = d.holding.profitPaisa
    Box(
        Modifier.fillMaxWidth()
            .shadow(16.dp, RoundedCornerShape(22.dp), spotColor = Brand.Navy.copy(alpha = 0.4f))
            .clip(RoundedCornerShape(22.dp))
            .background(Brush.linearGradient(listOf(Brand.Navy, Brand.NavyMid, Brand.NavyLight))),
    ) {
        Bubble(170.dp, Brand.Amber.copy(alpha = 0.16f), Modifier.align(Alignment.TopEnd).offset(x = 40.dp, y = (-50).dp))
        Bubble(130.dp, Color.White.copy(alpha = 0.06f), Modifier.align(Alignment.BottomStart).offset(x = (-30).dp, y = 60.dp))
        Column(Modifier.padding(start = 18.dp, end = 18.dp, top = 20.dp, bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Tag("আজ বেচলে", Brand.Amber, Brand.Navy)
            Column {
                T("সব শেয়ার আজই বেচে দিলে হাতে পাবেন", 15.sp, color = Brand.HeaderSoft)
                T(Bn.money(animatedLong(d.holding.valuePaisa)), 36.sp, FontWeight.Bold, Brand.Gold, lineHeight = 46.sp)
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.2f)))
            val up = profit >= 0
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background((if (up) Brand.Mint else Color(0xFFF7837A)).copy(alpha = 0.16f)).padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                T(if (up) "কেনার চেয়ে লাভ" else "কেনার চেয়ে কম", 14.sp, color = Brand.HeaderSoft, modifier = Modifier.weight(1f))
                T((if (up) "▲ " else "▼ ") + Bn.money(kotlin.math.abs(profit)) + " (" + Bn.percentBp(d.holding.profitBp) + ")", 14.sp, FontWeight.Bold,
                    if (up) Brand.Mint else Color(0xFFF7837A))
            }
        }
    }
}

@Composable
private fun NomineeCard(nav: NavHostController, d: Dashboard) {
    val c = Reso.colors
    ResoCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            T("নমিনি (সর্বোচ্চ ২ জন)", 17.sp, FontWeight.Bold, modifier = Modifier.weight(1f))
            LinkText("সম্পাদনা", { nav.navigate(Routes.NOMINEES) })
        }
        Spacer(Modifier.height(10.dp))
        val list = d.me.nominees
        if (list.isEmpty()) {
            T("কোনো নমিনি যোগ করা হয়নি।", 15.sp, color = c.muted)
            return@ResoCard
        }
        SplitBar(list.first().sharePercent / 100f)
        list.forEachIndexed { i, n ->
            if (i > 0) RowDivider()
            Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp)).background(if (i == 0) c.ink else Brand.Amber))
                Column(Modifier.weight(1f)) {
                    T(n.name, 15.sp, FontWeight.Bold)
                    T("${n.relation} · নমিনি ${Bn.num(n.slot)} · ${if (n.idType == "nid") "NID" else "জন্ম নিবন্ধন"} ${Bn.digits(n.idNumberMasked)}", 13.sp, color = c.muted)
                }
                Column(horizontalAlignment = Alignment.End) {
                    T(Bn.num(n.sharePercent) + "%", 18.sp, FontWeight.Bold)
                    T("${Bn.num(d.holding.shares * n.sharePercent / 100)}টি শেয়ার", 13.sp, color = c.muted)
                }
            }
        }
    }
}

