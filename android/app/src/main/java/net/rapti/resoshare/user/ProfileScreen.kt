package net.rapti.resoshare.user

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import net.rapti.resoshare.core.i18n.AppLang
import net.rapti.resoshare.core.i18n.Lang
import net.rapti.resoshare.core.i18n.t
import net.rapti.resoshare.core.net.Res
import net.rapti.resoshare.core.net.call
import net.rapti.resoshare.core.ui.Bubble
import net.rapti.resoshare.core.ui.ConfirmDialog
import net.rapti.resoshare.core.ui.DemoBadge
import net.rapti.resoshare.core.ui.EmptyBlock
import net.rapti.resoshare.core.ui.ErrorBlock
import net.rapti.resoshare.core.ui.GradientCard
import net.rapti.resoshare.core.ui.IconDisc
import net.rapti.resoshare.core.ui.LinkText
import net.rapti.resoshare.core.ui.Load
import net.rapti.resoshare.core.ui.LoadingBlock
import net.rapti.resoshare.core.ui.MenuRow
import net.rapti.resoshare.core.ui.Refreshable
import net.rapti.resoshare.core.ui.ResoCard
import net.rapti.resoshare.core.ui.ResoIcons
import net.rapti.resoshare.core.ui.RowDivider
import net.rapti.resoshare.core.ui.ScreenHeader
import net.rapti.resoshare.core.ui.SectionTitle
import net.rapti.resoshare.core.ui.SegmentedTabs
import net.rapti.resoshare.core.ui.SplitBar
import net.rapti.resoshare.core.ui.T
import net.rapti.resoshare.core.ui.Tag
import net.rapti.resoshare.core.ui.TextDisc
import net.rapti.resoshare.core.ui.ToggleRow
import net.rapti.resoshare.core.ui.headerBubble
import net.rapti.resoshare.core.ui.methodLabel
import net.rapti.resoshare.core.ui.pressable
import net.rapti.resoshare.core.ui.theme.Brand
import net.rapti.resoshare.core.ui.theme.Reso
import net.rapti.resoshare.core.ui.theme.ThemeMode
import net.rapti.resoshare.data.api.Dashboard
import net.rapti.resoshare.data.api.PublicInfo

@Composable
fun ProfileScreen(nav: NavHostController) {
    val state by UserRepo.dashboard.collectAsState()
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }

    suspend fun loadAll() {
        UserRepo.refresh()?.let { Toaster.show(it.message) }
        UserRepo.refreshOrders()
    }
    LaunchedEffect(Unit) { loadAll() }

    Refreshable(refreshing, onRefresh = { scope.launch { refreshing = true; loadAll(); refreshing = false } }) {
        when (val s = state) {
            is Load.Ready -> ProfileContent(nav, s.data)
            is Load.Failed -> ErrorBlock(s.message, onRetry = { scope.launch { loadAll() } }, Modifier.statusBarsPadding())
            Load.Loading -> LoadingBlock(Modifier.statusBarsPadding())
        }
    }
}

@Composable
private fun ProfileContent(nav: NavHostController, d: Dashboard) {
    val c = Reso.colors
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var confirmLogout by remember { mutableStateOf(false) }
    val unread by UserRepo.unread.collectAsState()
    val hide by UserRepo.hideAmounts.collectAsState()
    val orders by UserRepo.orders.collectAsState()

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 28.dp)) {
        item(key = "header") {
            Column(
                Modifier.fillMaxWidth().background(c.header).headerBubble().statusBarsPadding()
                    .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 18.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Box(Modifier.size(76.dp).clip(CircleShape).background(Brand.Amber.copy(alpha = 0.3f)).padding(4.dp)) {
                        TextDisc(Bn.initial(d.me.fullName), Brand.Amber, Brand.Navy, 68.dp, 30.sp)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        T(d.me.fullName, 21.sp, FontWeight.Bold, Color.White, maxLines = 2)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Tag(d.me.shareholderNo, c.headerChip, Brand.Gold, size = 13.sp)
                            when {
                                d.me.accountStatus == "suspended" -> Tag(t("স্থগিত", "Suspended"), Color(0xFFFDECEC), Color(0xFFB42318), size = 13.sp)
                                d.me.kycStatus == "verified" -> Tag(t("KYC যাচাইকৃত", "KYC verified"), Color(0xFF1C3D3A), Brand.Mint, size = 13.sp)
                                d.me.kycStatus == "rejected" -> Tag(t("KYC বাতিল", "KYC rejected"), Color(0xFFFDECEC), Color(0xFFB42318), size = 13.sp)
                                else -> Tag(t("KYC অপেক্ষমাণ", "KYC pending"), Color(0xFFFFF1D6), Color(0xFF8A4B00), size = 13.sp)
                            }
                        }
                    }
                }
                HeaderTabs(listOf(t("সারাংশ", "Summary"), t("আমার শেয়ার", "My shares"), t("নমিনি", "Nominees")), tab) { tab = it }
            }
        }
        when (tab) {
            0 -> item(key = "summary") {
                Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        GradientCard(c.shares, Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                IconDisc(ResoIcons.Layers, c.ink, c.onInk)
                                T(t("মোট কেনা শেয়ার", "Shares owned"), 14.sp, FontWeight.Bold, c.shares.label, maxLines = 2)
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.Bottom) {
                                T(if (hide) "••" else Bn.num(d.holding.shares), 30.sp, FontWeight.Bold, c.shares.value, lineHeight = 36.sp)
                                T(t(" টি", ""), 16.sp, color = c.shares.value, modifier = Modifier.padding(bottom = 4.dp))
                            }
                        }
                        GradientCard(c.paid, Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                IconDisc(ResoIcons.Wallet, c.green, Color.White)
                                T(t("মোট পরিশোধ", "Total paid"), 14.sp, FontWeight.Bold, c.paid.label, maxLines = 2)
                            }
                            Spacer(Modifier.height(8.dp))
                            T(money(d.holding.paidPaisa, hide), 19.sp, FontWeight.Bold, c.paid.value, maxLines = 1, lineHeight = 36.sp)
                        }
                    }
                    GradientCard(c.rate, Modifier.fillMaxWidth(), padding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextDisc("৳", Brand.Amber, Brand.Navy, 32.dp, 18.sp)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                T(t("একটি শেয়ার কিনতে", "Price of one share"), 14.sp, FontWeight.Bold, c.rate.label)
                                T(t("আজকের রেট", "Today's rate"), 12.sp, color = c.rate.label)
                            }
                            T(Bn.money(d.today.ratePaisa), 22.sp, FontWeight.Bold, c.rate.value)
                        }
                    }
                    SellNowCard(d, hide)
                    T(t("দ্রুত লিংক", "Quick links"), 14.sp, FontWeight.Bold, c.muted)
                    ResoCard(padding = PaddingValues(0.dp)) {
                        QuickLink(ResoIcons.Bell, c.infoBg, c.ink, t("নোটিশ", "Notices"), t("কোম্পানির বার্তা ও রেটের খবর", "Company messages and rate news"),
                            if (unread > 0) t("${Bn.num(unread)} নতুন", "$unread new") else null) { nav.tab(Routes.NOTICES) }
                        RowDivider()
                        QuickLink(ResoIcons.Shield, c.warnBg, c.brown, t("নিয়ম ও রেগুলেশন", "Rules & regulations"), t("শেয়ার কেনা-বেচা ও নমিনির নিয়ম", "Buying, selling and nominee rules")) {
                            nav.navigate(Routes.RULES)
                        }
                        RowDivider()
                        QuickLink(ResoIcons.Award, c.successBg, c.green, t("শেয়ার সার্টিফিকেট", "Share certificate"), t("ডাউনলোড বা শেয়ার করুন", "Download or share")) {
                            nav.navigate(Routes.CERTIFICATE)
                        }
                        RowDivider()
                        QuickLink(ResoIcons.Sun, c.tagBlueBg, c.tagBlueText, t("সেটিংস", "Settings"), t("ভাষা, থিম, অ্যাপ লক, পাসওয়ার্ড", "Language, theme, app lock, password")) {
                            nav.navigate(Routes.SETTINGS)
                        }
                        RowDivider()
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 60.dp).pressable(onClick = { confirmLogout = true }).padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            IconDisc(ResoIcons.Logout, c.dangerBg, c.red, 40.dp, 20.dp)
                            T(t("লগ আউট", "Log out"), 17.sp, FontWeight.Bold, c.red)
                        }
                    }
                }
            }
            1 -> {
                val confirmed = orders?.filter { it.status == "confirmed" }
                item(key = "sharesTitle") {
                    Box(Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 8.dp)) {
                        SectionTitle(t("আমার শেয়ারের তালিকা", "My share purchases"), t("সব লেনদেন", "All transactions")) { nav.navigate(Routes.ORDERS) }
                    }
                }
                when {
                    confirmed == null -> item(key = "sharesLoading") { LoadingBlock() }
                    confirmed.isEmpty() -> item(key = "sharesEmpty") { EmptyBlock(t("নিশ্চিত হওয়া কোনো শেয়ার এখনো নেই।", "No confirmed shares yet."), ResoIcons.Layers) }
                    else -> items(confirmed, key = { it.id }) { o ->
                        Box(Modifier.padding(horizontal = 20.dp, vertical = 5.dp)) {
                            ResoCard(padding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        T(t("${Bn.num(o.quantity)}টি × ${Bn.money(o.ratePaisa)}", "${o.quantity} × ${Bn.money(o.ratePaisa)}"), 15.sp, FontWeight.Bold)
                                        T("${Bn.dateOf(o.decidedAt ?: o.createdAt)} · ${methodLabel(o.method)}", 13.sp, color = c.muted)
                                    }
                                    T(money(o.amountPaisa, hide), 16.sp, FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
            else -> item(key = "nominees") {
                Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    NomineeCard(nav, d)
                }
            }
        }
    }
    if (confirmLogout) ConfirmDialog(t("লগআউট করবেন?", "Log out?"), t("আবার ঢুকতে মোবাইল/ইমেইল ও পাসওয়ার্ড লাগবে।", "You'll need your mobile/email and password to log in again."),
        t("লগআউট", "Log out"), {
            confirmLogout = false
            scope.launch { Auth.logout(null) }
        }, { confirmLogout = false }, danger = true)
}

/** Tab switcher on the navy header (amber selected pill). */
@Composable
private fun HeaderTabs(items: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val c = Reso.colors
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.headerChip).padding(4.dp)) {
        items.forEachIndexed { i, label ->
            val sel = i == selected
            val bg by animateColorAsState(if (sel) Brand.Amber else Color.Transparent, tween(200), label = "htab")
            Box(
                Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(12.dp)).background(bg).clickable(role = Role.Tab) { onSelect(i) },
                contentAlignment = Alignment.Center,
            ) { T(label, 15.sp, FontWeight.Bold, if (sel) Brand.Navy else Color.White, maxLines = 1) }
        }
    }
}

@Composable
private fun QuickLink(icon: ImageVector, bg: Color, fg: Color, title: String, subtitle: String, badge: String? = null, onClick: () -> Unit) {
    val c = Reso.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).pressable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        IconDisc(icon, bg, fg, 40.dp, 20.dp)
        Column(Modifier.weight(1f)) {
            T(title, 16.sp, FontWeight.Bold)
            T(subtitle, 13.sp, color = c.muted, maxLines = 1)
        }
        if (badge != null) Tag(badge, Brand.Amber, Brand.Navy)
        Icon(ResoIcons.Chevron, null, tint = c.muted, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun SellNowCard(d: Dashboard, hide: Boolean) {
    val profit = d.holding.profitPaisa
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp))
            .background(Brush.linearGradient(listOf(Brand.Navy, Brand.NavyMid, Brand.NavyLight))),
    ) {
        Bubble(170.dp, Brand.Amber.copy(alpha = 0.14f), Modifier.align(Alignment.TopEnd).offset(x = 40.dp, y = (-50).dp))
        Column(Modifier.padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Tag(t("আজ বেচলে", "If sold today"), Brand.Amber, Brand.Navy)
                Spacer(Modifier.weight(1f))
                Row(
                    Modifier.clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.12f)).clickable { UserRepo.toggleHide() }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(if (hide) ResoIcons.Eye else ResoIcons.EyeOff, null, tint = Color.White, modifier = Modifier.size(16.dp))
                    T(if (hide) t("দেখান", "Show") else t("লুকান", "Hide"), 13.sp, FontWeight.Bold, Color.White)
                }
            }
            Column {
                T(t("সব শেয়ার আজই বেচে দিলে হাতে পাবেন", "Sell all shares today and receive"), 15.sp, color = Brand.HeaderSoft)
                T(money(d.holding.valuePaisa, hide), 34.sp, FontWeight.Bold, Brand.Gold, lineHeight = 44.sp, maxLines = 1)
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.2f)))
            val up = profit >= 0
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.08f)).padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                T(if (up) t("কেনার চেয়ে লাভ", "Gain vs. cost") else t("কেনার চেয়ে কম", "Below cost"), 14.sp, color = Brand.HeaderSoft, modifier = Modifier.weight(1f))
                T(if (hide) "••••" else (if (up) "▲ " else "▼ ") + Bn.money(kotlin.math.abs(profit)) + " (" + Bn.percentBp(d.holding.profitBp) + ")",
                    14.sp, FontWeight.Bold, if (up) Brand.Mint else Color(0xFFF7837A))
            }
        }
    }
}

@Composable
private fun NomineeCard(nav: NavHostController, d: Dashboard) {
    val c = Reso.colors
    ResoCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            T(t("নমিনি (সর্বোচ্চ ২ জন)", "Nominees (up to 2)"), 17.sp, FontWeight.Bold, modifier = Modifier.weight(1f))
            LinkText(t("সম্পাদনা", "Edit"), { nav.navigate(Routes.NOMINEES) })
        }
        Spacer(Modifier.height(10.dp))
        val list = d.me.nominees
        if (list.isEmpty()) {
            T(t("কোনো নমিনি যোগ করা হয়নি।", "No nominees added."), 15.sp, color = c.muted)
            return@ResoCard
        }
        SplitBar(list.first().sharePercent / 100f)
        list.forEachIndexed { i, n ->
            if (i > 0) RowDivider()
            Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp)).background(if (i == 0) c.ink else Brand.Amber))
                Column(Modifier.weight(1f)) {
                    T(n.name, 15.sp, FontWeight.Bold)
                    T(n.relation + " · " + t("নমিনি ${Bn.num(n.slot)}", "Nominee ${n.slot}") + " · " +
                        (if (n.idType == "nid") "NID" else t("জন্ম নিবন্ধন", "Birth reg.")) + " " + Bn.digits(n.idNumberMasked), 13.sp, color = c.muted)
                }
                Column(horizontalAlignment = Alignment.End) {
                    T(Bn.num(n.sharePercent) + "%", 18.sp, FontWeight.Bold)
                    T(t("${Bn.num(d.holding.shares * n.sharePercent / 100)}টি শেয়ার", "${d.holding.shares * n.sharePercent / 100} shares"), 13.sp, color = c.muted)
                }
            }
        }
    }
}

/** Shareholder settings: language, theme, app lock, password, hotline. */
@Composable
fun UserSettingsScreen(nav: NavHostController) {
    val c = Reso.colors
    val ctx = LocalContext.current
    var info by remember { mutableStateOf<PublicInfo?>(null) }
    LaunchedEffect(Unit) { (call { Graph.shareholderApi.publicInfo() } as? Res.Ok)?.let { info = it.value } }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(t("সেটিংস", "Settings"), onBack = { nav.popBackStack() })
        LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item(key = "lang") {
                ResoCard {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(ResoIcons.Globe, null, tint = c.ink, modifier = Modifier.size(20.dp))
                        T(t("ভাষা", "Language"), 16.sp, FontWeight.Bold)
                    }
                    Spacer(Modifier.height(10.dp))
                    SegmentedTabs(listOf("বাংলা", "English"), if (Lang.isEn) 1 else 0, { Lang.set(if (it == 1) AppLang.EN else AppLang.BN) })
                }
            }
            item(key = "theme") {
                ResoCard {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(ResoIcons.Moon, null, tint = c.ink, modifier = Modifier.size(20.dp))
                        T(t("অ্যাপের থিম", "App theme"), 16.sp, FontWeight.Bold)
                    }
                    Spacer(Modifier.height(10.dp))
                    val mode by Graph.themeMode.collectAsState()
                    SegmentedTabs(listOf(t("সিস্টেম", "System"), t("লাইট", "Light"), t("ডার্ক", "Dark")), mode.ordinal, { i ->
                        val m = ThemeMode.entries[i]
                        Graph.themeMode.value = m; Graph.prefs.themeMode = m
                    })
                }
            }
            item(key = "security") {
                ResoCard(padding = PaddingValues(0.dp)) {
                    val activity = ctx as? MainActivity
                    val canLock = activity?.canUseDeviceAuth() == true
                    var lock by remember { mutableStateOf(Graph.prefs.appLock) }
                    Box(Modifier.padding(horizontal = 16.dp)) {
                        ToggleRow(
                            t("অ্যাপ লক", "App lock"),
                            if (canLock) t("অ্যাপে ফিরলে ফিঙ্গারপ্রিন্ট/পিন লাগবে", "Ask for fingerprint/PIN when returning to the app")
                            else t("ফোনে স্ক্রিন লক চালু করলে ব্যবহার করা যাবে", "Turn on a phone screen lock to use this"),
                            lock && canLock, { lock = it; Graph.prefs.appLock = it }, enabled = canLock,
                        )
                    }
                    RowDivider()
                    MenuRow(ResoIcons.Key, t("পাসওয়ার্ড পরিবর্তন", "Change password"), onClick = { nav.navigate(Routes.PASSWORD) })
                    RowDivider()
                    MenuRow(ResoIcons.List, t("সব লেনদেন", "All transactions"), onClick = { nav.navigate(Routes.ORDERS) })
                    RowDivider()
                    val hotline = info?.hotline ?: "01617-345534"
                    MenuRow(ResoIcons.Phone, t("হটলাইন", "Hotline"), Bn.digits(hotline), onClick = {
                        runCatching { ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + hotline.filter { it.isDigit() || it == '+' }))) }
                    })
                }
            }
            item(key = "about") {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (Graph.isDemo) DemoBadge()
                    T(t("সংস্করণ ", "Version ") + Bn.digits(BuildConfig.VERSION_NAME) + " · Resonance Automation", 12.sp, color = c.muted, align = TextAlign.Center)
                }
            }
        }
    }
}

