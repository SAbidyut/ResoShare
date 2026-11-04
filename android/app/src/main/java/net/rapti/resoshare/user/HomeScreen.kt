package net.rapti.resoshare.user

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import kotlinx.coroutines.launch
import net.rapti.resoshare.Graph
import net.rapti.resoshare.Toaster
import net.rapti.resoshare.core.format.Bn
import net.rapti.resoshare.core.i18n.t
import net.rapti.resoshare.core.ui.Bubble
import net.rapti.resoshare.core.ui.CircleIconButton
import net.rapti.resoshare.core.ui.ErrorBlock
import net.rapti.resoshare.core.ui.GradientCard
import net.rapti.resoshare.core.ui.IconDisc
import net.rapti.resoshare.core.ui.InfoBox
import net.rapti.resoshare.core.ui.LinkText
import net.rapti.resoshare.core.ui.Load
import net.rapti.resoshare.core.ui.LoadingBlock
import net.rapti.resoshare.core.ui.Refreshable
import net.rapti.resoshare.core.ui.ResoCard
import net.rapti.resoshare.core.ui.ResoIcons
import net.rapti.resoshare.core.ui.RowDivider
import net.rapti.resoshare.core.ui.SectionTitle
import net.rapti.resoshare.core.ui.SegmentedTabs
import net.rapti.resoshare.core.ui.StatusPill
import net.rapti.resoshare.core.ui.T
import net.rapti.resoshare.core.ui.Tag
import net.rapti.resoshare.core.ui.TextDisc
import net.rapti.resoshare.core.ui.Tone
import net.rapti.resoshare.core.ui.headerBubble
import net.rapti.resoshare.core.ui.methodShort
import net.rapti.resoshare.core.ui.pressable
import net.rapti.resoshare.core.ui.theme.Brand
import net.rapti.resoshare.core.ui.theme.Reso
import net.rapti.resoshare.data.api.ChartPoint
import net.rapti.resoshare.data.api.Dashboard
import net.rapti.resoshare.data.api.Notice
import net.rapti.resoshare.data.api.Order

/** Money that respects the "hide amounts" eye toggle. */
fun money(paisa: Long, hide: Boolean) = if (hide) "৳ ••••••" else Bn.money(paisa)

@Composable
fun HomeScreen(nav: NavHostController) {
    val state by UserRepo.dashboard.collectAsState()
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { UserRepo.refreshHome() }

    Refreshable(refreshing, onRefresh = {
        scope.launch { refreshing = true; UserRepo.refreshHome()?.let { Toaster.show(it.message) }; refreshing = false }
    }) {
        when (val s = state) {
            is Load.Ready -> HomeContent(nav, s.data)
            is Load.Failed -> Column(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth().background(Reso.colors.header).statusBarsPadding().height(96.dp))
                ErrorBlock(s.message, onRetry = { scope.launch { UserRepo.refreshHome() } })
            }
            Load.Loading -> Column(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth().background(Reso.colors.header).statusBarsPadding().height(96.dp))
                LoadingBlock()
            }
        }
    }
}

@Composable
private fun HomeContent(nav: NavHostController, d: Dashboard) {
    val c = Reso.colors
    val hide by UserRepo.hideAmounts.collectAsState()
    val unread by UserRepo.unread.collectAsState()
    val week by UserRepo.week.collectAsState()
    val notices by UserRepo.notices.collectAsState()
    val orders by UserRepo.orders.collectAsState()

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 28.dp)) {
        item(key = "header", contentType = "header") {
            Column(
                Modifier.fillMaxWidth().background(c.header).statusBarsPadding().headerBubble()
                    .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(48.dp).clip(CircleShape).background(Brand.Amber.copy(alpha = 0.35f)).padding(3.dp)) {
                        TextDisc(Bn.initial(d.me.fullName), Brand.Amber, Brand.Navy, 42.dp, 18.sp)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        T(t("আসসালামু আলাইকুম", "Assalamu alaikum"), 13.sp, color = Brand.HeaderMuted, maxLines = 1)
                        T(d.me.fullName, 20.sp, FontWeight.Bold, Color.White, maxLines = 1)
                    }
                    CircleIconButton(if (hide) ResoIcons.EyeOff else ResoIcons.Eye, if (hide) t("টাকা দেখান", "Show amounts") else t("টাকা লুকান", "Hide amounts"),
                        { UserRepo.toggleHide() })
                    Spacer(Modifier.width(10.dp))
                    CircleIconButton(ResoIcons.Bell, t("নোটিশ", "Notices"), { nav.tab(Routes.NOTICES) }, badge = unread)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Tag(d.me.shareholderNo, c.headerChip, Brand.Gold, size = 13.sp)
                    KycTag(d.me.kycStatus, d.me.accountStatus)
                }
            }
        }
        item(key = "rate", contentType = "rate") {
            Box(
                Modifier.fillMaxWidth()
                    .drawBehind { drawRect(c.header, size = Size(size.width, 52.dp.toPx())) }
                    .padding(horizontal = 20.dp),
            ) { RateCard(d, week, nav) }
        }
        // Separate lazy items: only what is on screen gets composed, the rest is
        // prefetched while scrolling (one giant item made the first frame heavy).
        val pad = Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp)
        if (d.me.accountStatus == "suspended" || d.me.kycStatus != "verified") {
            item(key = "kyc", contentType = "banner") { Box(pad) { KycBanner(nav, d) } }
        }
        item(key = "totals", contentType = "totals") {
            Row(pad, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GradientCard(c.shares, Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IconDisc(ResoIcons.Layers, c.ink, c.onInk)
                        T(t("আমার মোট শেয়ার", "My shares"), 14.sp, FontWeight.Bold, c.shares.label, maxLines = 2)
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
        }
        item(key = "sell", contentType = "sell") { Box(pad) { SellCard(d, hide) { nav.tab(Routes.PROFILE) } } }
        item(key = "actions", contentType = "actions") {
            Row(pad, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                QuickAction(t("শেয়ার কিনুন", "Buy"), ResoIcons.Plus, Brand.Navy, Color.White.copy(alpha = 0.45f), primary = true, Modifier.weight(1f)) {
                    nav.navigate(Routes.BUY) { launchSingleTop = true }
                }
                QuickAction(t("রেট চার্ট", "Rate chart"), ResoIcons.Chart, c.ink, c.infoBg, false, Modifier.weight(1f)) { nav.tab(Routes.CHART) }
                QuickAction(t("সার্টিফিকেট", "Certificate"), ResoIcons.Award, c.green, c.successBg, false, Modifier.weight(1f)) { nav.navigate(Routes.CERTIFICATE) }
                QuickAction(t("নিয়ম-কানুন", "Rules"), ResoIcons.Shield, c.brown, c.warnBg, false, Modifier.weight(1f)) { nav.navigate(Routes.RULES) }
            }
        }
        item(key = "goal", contentType = "goal") { Box(pad) { GoalCard(d.holding.shares, hide) } }
        item(key = "notices", contentType = "notices") { Box(pad) { NoticeBoard(notices, onAll = { nav.tab(Routes.NOTICES) }) } }
        item(key = "orders", contentType = "orders") { Box(pad) { RecentOrders(orders ?: d.recentOrders, hide) { nav.navigate(Routes.ORDERS) } } }
    }
}

@Composable
private fun KycTag(kyc: String, account: String) {
    when {
        account == "suspended" -> Tag(t("স্থগিত", "Suspended"), Color(0xFFFDECEC), Color(0xFFB42318), size = 13.sp)
        kyc == "verified" -> Tag("✓ " + t("KYC যাচাইকৃত", "KYC verified"), Color(0xFF1C3D3A), Brand.Mint, size = 13.sp)
        kyc == "rejected" -> Tag(t("KYC বাতিল", "KYC rejected"), Color(0xFFFDECEC), Color(0xFFB42318), size = 13.sp)
        else -> Tag(t("KYC অপেক্ষমাণ", "KYC pending"), Color(0xFFFFF1D6), Color(0xFF8A4B00), size = 13.sp)
    }
}

@Composable
private fun RateCard(d: Dashboard, week: List<ChartPoint>?, nav: NavHostController) {
    val c = Reso.colors
    val g = c.rate
    val change = d.today.prevRatePaisa?.let { d.today.ratePaisa - it } ?: 0L
    val bp = d.today.prevRatePaisa?.takeIf { it > 0 }?.let { (change * 10_000 / it).toInt() } ?: 0
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
            .background(Brush.linearGradient(listOf(g.start, g.end)))
            .border(1.dp, g.border, RoundedCornerShape(20.dp)),
    ) {
        Bubble(120.dp, Color.White.copy(alpha = if (c.dark) 0.05f else 0.45f), Modifier.align(Alignment.TopEnd).offset(x = 30.dp, y = (-34).dp))
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextDisc("৳", Brand.Amber, Brand.Navy, 34.dp, 18.sp)
                Spacer(Modifier.width(10.dp))
                T(t("আজকের রেট", "Today's rate"), 16.sp, FontWeight.Bold, g.label, modifier = Modifier.weight(1f))
                Box(Modifier.clip(RoundedCornerShape(12.dp)).background(Brand.Navy).padding(horizontal = 10.dp, vertical = 4.dp)) {
                    T(t("আজ", "Today") + " · " + Bn.dayMonth(Bn.parseDate(d.today.date) ?: Bn.today()), 12.sp, FontWeight.Bold, Brand.Gold, maxLines = 1)
                }
            }
            T(Bn.money(d.today.ratePaisa), 34.sp, FontWeight.Bold, g.value, lineHeight = 42.sp)
            T(t("প্রতি শেয়ার · কেনা ও বিক্রয়ের রেট একই", "Per share · buy and sell rate are the same"), 13.sp, color = g.label)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val up = change > 0
                val flat = change == 0L
                Tag(
                    (if (flat) "— " else if (up) "▲ " else "▼ ") + Bn.signedMoney(change) + " (" + Bn.percentBp(bp, signed = true) + ")",
                    when { flat -> c.tagBlueBg; up -> c.successBg; else -> c.dangerBg },
                    when { flat -> c.tagBlueText; up -> c.green; else -> c.red }, size = 13.sp,
                )
                T(
                    when { flat -> t("গতকালের সমান", "Same as yesterday"); up -> t("গতকালের চেয়ে বেড়েছে", "Up since yesterday"); else -> t("গতকালের চেয়ে কমেছে", "Down since yesterday") },
                    13.sp, color = g.label, maxLines = 1,
                )
            }
            if (!week.isNullOrEmpty()) WeekBars(week)
            Row(
                Modifier.fillMaxWidth().heightIn(min = 36.dp).clip(RoundedCornerShape(8.dp)).pressable(onClick = { nav.tab(Routes.CHART) }),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                T(t("৭ দিনের রেট · পুরো চার্ট দেখুন", "7-day rate · open full chart"), 14.sp, FontWeight.Bold, g.value)
                Icon(ResoIcons.Chevron, null, tint = g.value, modifier = Modifier.size(16.dp))
            }
        }
    }
}

/** Mini bar chart of the last 7 days (today highlighted). */
@Composable
private fun WeekBars(points: List<ChartPoint>) {
    val c = Reso.colors
    val values = points.map { it.ratePaisa }
    val min = values.min(); val max = values.max()
    val span = (max - min).coerceAtLeast(1)
    val progress = remember(points) { Animatable(0f) }
    LaunchedEffect(points) { progress.animateTo(1f, tween(700, easing = FastOutSlowInEasing)) }
    val barColor = if (c.dark) Color(0xFF8C6A2A) else Color(0xFFE3C478)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Canvas(Modifier.fillMaxWidth().height(58.dp)) {
            val slot = size.width / values.size
            val bw = slot * 0.72f
            values.forEachIndexed { i, v ->
                val frac = 0.25f + 0.75f * ((v - min).toFloat() / span)
                val h = size.height * frac * progress.value
                drawRoundRect(
                    if (i == values.lastIndex) c.rate.value else barColor,
                    topLeft = Offset(slot * i + (slot - bw) / 2, size.height - h),
                    size = Size(bw, h),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(5.dp.toPx()),
                )
            }
        }
        Row(Modifier.fillMaxWidth()) {
            points.forEachIndexed { i, p ->
                val label = if (i == points.lastIndex) t("আজ", "Today") else Bn.digits(p.date.takeLast(2))
                T(label, 11.sp, if (i == points.lastIndex) FontWeight.Bold else FontWeight.Normal, c.rate.label,
                    modifier = Modifier.weight(1f), align = TextAlign.Center, maxLines = 1)
            }
        }
    }
}

@Composable
private fun SellCard(d: Dashboard, hide: Boolean, onClick: () -> Unit) {
    val up = d.holding.profitPaisa >= 0
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
            .background(Brush.linearGradient(listOf(Brand.Navy, Brand.NavyMid, Brand.NavyLight)))
            .pressable(onClick = onClick),
    ) {
        Bubble(130.dp, Color.White.copy(alpha = 0.06f), Modifier.align(Alignment.TopEnd).offset(x = 36.dp, y = (-40).dp))
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                T(t("সব শেয়ার আজ বেচলে পাবেন", "Sell all shares today and get"), 14.sp, color = Brand.HeaderSoft)
                T(money(d.holding.valuePaisa, hide), 28.sp, FontWeight.Bold, Brand.Gold, lineHeight = 36.sp, maxLines = 1)
                if (!hide) T(
                    (if (up) "▲ " + t("কেনার চেয়ে লাভ ", "Gain vs. cost ") else "▼ " + t("কেনার চেয়ে কম ", "Below cost ")) +
                        Bn.money(kotlin.math.abs(d.holding.profitPaisa)) + " (" + Bn.percentBp(d.holding.profitBp) + ")",
                    13.sp, FontWeight.Bold, if (up) Brand.Mint else Color(0xFFF7837A),
                )
            }
            Box(Modifier.size(40.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                Icon(ResoIcons.Chevron, null, tint = Color.White, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun QuickAction(label: String, icon: ImageVector, fg: Color, discBg: Color, primary: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = Reso.colors
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier.heightIn(min = 92.dp).clip(shape)
            .background(if (primary) Brand.Amber else c.surface)
            .then(if (primary) Modifier else Modifier.border(1.dp, c.border, shape))
            .pressable(onClick = onClick).padding(horizontal = 4.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(discBg), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = fg, modifier = Modifier.size(22.dp))
        }
        T(label, 13.sp, FontWeight.Bold, if (primary) Brand.Navy else c.text, maxLines = 1, align = TextAlign.Center)
    }
}

/** "আমার লক্ষ্য" — personal share goal with a progress ring (stored on the device). */
@Composable
private fun GoalCard(shares: Int, hide: Boolean) {
    val c = Reso.colors
    var goal by rememberSaveable { mutableIntStateOf(Graph.prefs.goalShares) }
    val frac = if (goal > 0) (shares.toFloat() / goal).coerceIn(0f, 1f) else 0f
    val anim by animateFloatAsState(frac, tween(800), label = "goal")
    ResoCard(padding = PaddingValues(16.dp), radius = 18.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(76.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val sw = 9.dp.toPx()
                    drawArc(c.track, 0f, 360f, false, style = Stroke(sw), topLeft = Offset(sw / 2, sw / 2), size = Size(size.width - sw, size.height - sw))
                    drawArc(Brand.Amber, -90f, 360f * anim, false, style = Stroke(sw, cap = StrokeCap.Round),
                        topLeft = Offset(sw / 2, sw / 2), size = Size(size.width - sw, size.height - sw))
                }
                T(Bn.num((frac * 100).toInt()) + "%", 16.sp, FontWeight.Bold)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                T(t("আমার লক্ষ্য", "My goal"), 17.sp, FontWeight.Bold)
                T(if (hide) "•• / ${Bn.num(goal)}" else t("${Bn.num(shares)} / ${Bn.num(goal)}টি শেয়ার", "$shares / $goal shares"), 14.sp, color = c.muted)
                T(
                    if (shares >= goal) t("অভিনন্দন! লক্ষ্য পূরণ হয়েছে", "Congratulations! Goal reached")
                    else t("আর ${Bn.num(goal - shares)}টি কিনলেই লক্ষ্য পূরণ", "${goal - shares} more shares to reach your goal"),
                    14.sp, FontWeight.Bold, if (shares >= goal) c.green else c.brown,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            T(t("লক্ষ্য বাছুন", "Pick goal"), 13.sp, color = c.muted)
            listOf(100, 200, 500).forEach { n ->
                val sel = goal == n
                val shape = RoundedCornerShape(22.dp)
                Box(
                    Modifier.weight(1f).height(42.dp).clip(shape).background(if (sel) c.ink else c.surface).border(1.dp, if (sel) c.ink else c.inputBorder, shape)
                        .pressable(onClick = { goal = n; Graph.prefs.goalShares = n }),
                    contentAlignment = Alignment.Center,
                ) { T(Bn.num(n), 15.sp, FontWeight.Bold, if (sel) c.onInk else c.text) }
            }
        }
    }
}

@Composable
private fun NoticeBoard(notices: List<Notice>?, onAll: () -> Unit) {
    val c = Reso.colors
    val list = notices?.take(5).orEmpty()
    ResoCard(padding = PaddingValues(16.dp), radius = 18.dp) {
        SectionTitle(t("নোটিশ বোর্ড", "Notice board"), t("সব নোটিশ", "All notices"), onAll)
        Spacer(Modifier.height(10.dp))
        if (list.isEmpty()) {
            T(if (notices == null) t("লোড হচ্ছে…", "Loading…") else t("এখনো কোনো নোটিশ নেই", "No notices yet"), 14.sp, color = c.muted)
            return@ResoCard
        }
        val pager = rememberPagerState { list.size }
        val scope = rememberCoroutineScope()
        HorizontalPager(pager, pageSpacing = 12.dp, modifier = Modifier.fillMaxWidth()) { i ->
            val n = list[i]
            val (label, bg, fg) = noticeTag(n.category)
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.cashBg).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Tag(label, bg, fg)
                    Spacer(Modifier.weight(1f))
                    T(Bn.relative(n.createdAt, withTime = false), 12.sp, color = c.muted)
                }
                T(n.title, 16.sp, FontWeight.Bold, maxLines = 2)
                T(n.body, 14.sp, color = c.textSoft, maxLines = 2)
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircleIconButton(ResoIcons.Back, t("আগের নোটিশ", "Previous"), { scope.launch { pager.animateScrollToPage((pager.currentPage - 1).coerceAtLeast(0)) } },
                bg = c.surface, fg = c.text, size = 40.dp)
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
                repeat(list.size) { i ->
                    val sel = i == pager.currentPage
                    Box(Modifier.height(6.dp).width(if (sel) 22.dp else 6.dp).clip(RoundedCornerShape(3.dp)).background(if (sel) Brand.Amber else c.track))
                }
            }
            CircleIconButton(ResoIcons.Chevron, t("পরের নোটিশ", "Next"), { scope.launch { pager.animateScrollToPage((pager.currentPage + 1).coerceAtMost(list.lastIndex)) } },
                bg = c.ink, fg = c.onInk, size = 40.dp)
        }
    }
}

fun noticeTag(cat: String): Triple<String, Color, Color> = when (cat) {
    "rate" -> Triple(t("রেট পরিবর্তন", "Rate change"), Color(0xFFFFF1D6), Color(0xFF8A4B00))
    "payment" -> Triple(t("পেমেন্ট", "Payment"), Color(0xFFE3F5EC), Color(0xFF0F7B4F))
    "dividend" -> Triple(t("ডিভিডেন্ড", "Dividend"), Color(0xFFE6ECF8), Color(0xFF1F3A6B))
    "rules" -> Triple(t("নিয়ম", "Rules"), Color(0xFFFFF1D6), Color(0xFF8A4B00))
    "kyc" -> Triple("KYC", Color(0xFFE3F5EC), Color(0xFF0F7B4F))
    "message" -> Triple(t("বার্তা", "Message"), Color(0xFFE6ECF8), Color(0xFF1F3A6B))
    else -> Triple(t("কোম্পানি", "Company"), Color(0xFFE6ECF8), Color(0xFF1F3A6B))
}

@Composable
private fun RecentOrders(all: List<Order>, hide: Boolean, onAll: () -> Unit) {
    val c = Reso.colors
    var filter by rememberSaveable { mutableIntStateOf(0) }
    val list = when (filter) {
        1 -> all.filter { it.status == "confirmed" }
        2 -> all.filter { it.status == "pending" }
        else -> all
    }.take(4)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionTitle(t("সাম্প্রতিক লেনদেন", "Recent transactions"), t("সব দেখুন", "See all"), onAll)
        SegmentedTabs(listOf(t("সব", "All"), t("নিশ্চিত", "Confirmed"), t("অপেক্ষমাণ", "Pending")), filter, { filter = it })
        if (list.isEmpty()) {
            ResoCard { T(t("এই তালিকায় কোনো লেনদেন নেই।", "No transactions here."), 15.sp, color = c.muted) }
        } else {
            ResoCard(padding = PaddingValues(0.dp)) {
                list.forEachIndexed { i, o ->
                    if (i > 0) RowDivider()
                    OrderRow(o, hide)
                }
            }
        }
    }
}

@Composable
fun OrderRow(o: Order, hide: Boolean = false) {
    val c = Reso.colors
    val (icon, bg, fg) = when (o.status) {
        "pending" -> Triple(ResoIcons.Clock, c.warnBg, c.brown)
        "cancelled" -> Triple(ResoIcons.Close, c.dangerBg, c.red)
        else -> Triple(ResoIcons.Layers, c.tagBlueBg, c.tagBlueText)
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        IconDisc(icon, bg, fg, 40.dp, 20.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            T(t("${Bn.num(o.quantity)}টি শেয়ার কেনা", "Bought ${o.quantity} shares"), 15.sp, FontWeight.Bold, maxLines = 1)
            T(Bn.dateOf(o.createdAt) + " · " + methodShort(o.method), 13.sp, color = c.muted, maxLines = 1)
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            T(money(o.amountPaisa, hide), 15.sp, FontWeight.Bold)
            StatusPill(o.status)
        }
    }
}

@Composable
private fun KycBanner(nav: NavHostController, d: Dashboard) {
    when {
        d.me.accountStatus == "suspended" -> InfoBox(t("আপনার অ্যাকাউন্ট সাময়িকভাবে স্থগিত। বিস্তারিত জানতে হটলাইনে যোগাযোগ করুন।",
            "Your account is temporarily suspended. Please call the hotline for details."), title = t("অ্যাকাউন্ট স্থগিত", "Account suspended"), tone = Tone.DANGER, icon = ResoIcons.Lock)
        d.me.kycStatus == "pending" -> InfoBox(t("অ্যাডমিন আপনার NID ও ছবি যাচাই করছেন। যাচাই হলে নোটিশে জানানো হবে এবং শেয়ার কেনা চালু হবে।",
            "An admin is verifying your NID and photo. You'll get a notice when done, and share buying will open."),
            title = t("KYC যাচাই চলছে", "KYC verification in progress"), tone = Tone.WARN, icon = ResoIcons.Clock)
        d.me.kycStatus == "rejected" -> Column(Modifier.pressable(onClick = { nav.navigate(Routes.KYC) })) {
            InfoBox(t("কারণ: ", "Reason: ") + (d.me.kycNote ?: t("তথ্য সঠিক নয়", "information incorrect")) +
                t("\nসঠিক তথ্য ও ছবি দিয়ে আবার জমা দিতে এখানে চাপুন।", "\nTap here to submit again with correct details and photos."),
                title = t("KYC বাতিল হয়েছে", "KYC rejected"), tone = Tone.DANGER)
        }
    }
}

