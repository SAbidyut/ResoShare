package net.rapti.resoshare.user

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.rapti.resoshare.Graph
import net.rapti.resoshare.Toaster
import net.rapti.resoshare.core.export.ExportFile
import net.rapti.resoshare.core.export.Exporter
import net.rapti.resoshare.core.format.Bn
import net.rapti.resoshare.core.format.V
import net.rapti.resoshare.core.i18n.t
import net.rapti.resoshare.core.net.AppJson
import net.rapti.resoshare.core.net.Res
import net.rapti.resoshare.core.net.call
import net.rapti.resoshare.core.ui.EnterLazyColumn
import net.rapti.resoshare.core.ui.BrandLogo
import net.rapti.resoshare.core.ui.ConfirmDialog
import net.rapti.resoshare.core.ui.EmptyBlock
import net.rapti.resoshare.core.ui.ErrorBlock
import net.rapti.resoshare.core.ui.Field
import net.rapti.resoshare.core.ui.FilterPills
import net.rapti.resoshare.core.ui.HeaderPill
import net.rapti.resoshare.core.ui.IconDisc
import net.rapti.resoshare.core.ui.InfoBox
import net.rapti.resoshare.core.ui.LinkText
import net.rapti.resoshare.core.ui.Load
import net.rapti.resoshare.core.ui.LoadingBlock
import net.rapti.resoshare.core.ui.PrimaryButton
import net.rapti.resoshare.core.ui.ProgressTrack
import net.rapti.resoshare.core.ui.QrCode
import net.rapti.resoshare.core.ui.Refreshable
import net.rapti.resoshare.core.ui.ResoCard
import net.rapti.resoshare.core.ui.ResoIcons
import net.rapti.resoshare.core.ui.RowDivider
import net.rapti.resoshare.core.ui.ScreenHeader
import net.rapti.resoshare.core.ui.SecondaryButton
import net.rapti.resoshare.core.ui.StatusPill
import net.rapti.resoshare.core.ui.T
import net.rapti.resoshare.core.ui.Tag
import net.rapti.resoshare.core.ui.TextDisc
import net.rapti.resoshare.core.ui.Tone
import net.rapti.resoshare.core.ui.headerBubble
import net.rapti.resoshare.core.ui.methodLabel
import net.rapti.resoshare.core.ui.pressable
import net.rapti.resoshare.core.ui.theme.Brand
import net.rapti.resoshare.core.ui.theme.Reso
import net.rapti.resoshare.data.api.Certificate
import net.rapti.resoshare.data.api.KycData
import net.rapti.resoshare.data.api.Notice
import net.rapti.resoshare.data.api.NomineesUpdate
import net.rapti.resoshare.data.api.Order
import net.rapti.resoshare.data.api.Rules
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

// ============================================================== Certificate

@Composable
fun CertificateScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<Load<Certificate>>(Load.Loading) }
    var pending by remember { mutableStateOf<ExportFile?>(null) }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val f = pending
        if (uri != null && f != null) scope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { Exporter.writeTo(ctx, uri, f) }.isSuccess }
            Toaster.show(if (ok) t("সার্টিফিকেট সংরক্ষণ হয়েছে", "Certificate saved") else t("সংরক্ষণ করা যায়নি", "Could not save"))
        }
        pending = null
    }
    suspend fun load() {
        state = when (val r = call { Graph.shareholderApi.certificate() }) {
            is Res.Ok -> Load.Ready(r.value)
            is Res.Err -> Load.Failed(
                if (r.error.code == "NOT_FOUND") t("শেয়ার কেনা নিশ্চিত হলে এখানে আপনার শেয়ার সার্টিফিকেট দেখা যাবে।", "Your share certificate appears here once a purchase is confirmed.")
                else r.error.message,
            )
        }
    }
    LaunchedEffect(Unit) { load() }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(t("শেয়ার সার্টিফিকেট", "Share certificate"), onBack = { nav.popBackStack() })
        when (val s = state) {
            Load.Loading -> LoadingBlock()
            is Load.Failed -> ErrorBlock(s.message, onRetry = { scope.launch { load() } })
            is Load.Ready -> EnterLazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item(key = "card") { CertificateCard(s.data) }
                item(key = "pdf") {
                    PrimaryButton(t("PDF ডাউনলোড করুন", "Download PDF"), {
                        scope.launch {
                            val f = withContext(Dispatchers.Default) { Exporter.certificatePdf(ctx, s.data) }
                            pending = f
                            saver.launch(f.name)
                        }
                    }, icon = ResoIcons.Download)
                }
                item(key = "share") {
                    SecondaryButton(t("শেয়ার করুন", "Share"), {
                        scope.launch {
                            val f = withContext(Dispatchers.Default) { Exporter.certificatePdf(ctx, s.data) }
                            runCatching { ctx.startActivity(Exporter.shareIntent(ctx, f)) }
                        }
                    }, icon = ResoIcons.Share)
                }
                item(key = "note") {
                    T(t("নতুন শেয়ার যুক্ত হলে সার্টিফিকেট নিজে থেকেই হালনাগাদ হয়।", "The certificate updates automatically when new shares are added."),
                        13.sp, color = Reso.colors.muted, align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
fun CertificateCard(cert: Certificate) {
    // The certificate is always rendered on white paper, in both themes.
    val navy = Brand.Navy
    val muted = Color(0xFF5B6578)
    Box(Modifier.fillMaxWidth().padding(top = 10.dp, end = 6.dp)) {
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color.White).border(2.dp, navy, RoundedCornerShape(18.dp)).padding(6.dp),
        ) {
            Column(
                Modifier.fillMaxWidth().border(1.dp, Brand.Amber, RoundedCornerShape(13.dp)).padding(horizontal = 20.dp, vertical = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(navy), contentAlignment = Alignment.Center) { BrandLogo(26.dp) }
                    T(cert.companyName, 17.sp, FontWeight.Bold, navy)
                }
                T(t("শেয়ার সার্টিফিকেট", "Share Certificate"), 26.sp, FontWeight.Bold, navy)
                T(t("এই মর্মে প্রত্যয়ন করা যাচ্ছে যে", "This is to certify that"), 14.sp, color = muted)
                T(cert.fullName, 24.sp, FontWeight.Bold, navy, align = TextAlign.Center)
                T(t("কোম্পানির ${Bn.num(cert.shares)}টি শেয়ারের মালিক", "owns ${cert.shares} shares of the company"), 16.sp, color = Color(0xFF0F172A), align = TextAlign.Center)
                Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFE1E6EF)))
                listOf(
                    t("শেয়ারহোল্ডার ID", "Shareholder ID") to cert.shareholderNo,
                    t("সার্টিফিকেট নম্বর", "Certificate no.") to cert.certificateNo,
                    t("ইস্যুর তারিখ", "Issue date") to Bn.date(cert.issuedOn),
                ).forEach { (k, v) ->
                    Row(Modifier.fillMaxWidth()) {
                        T(k, 14.sp, color = muted, modifier = Modifier.weight(1f))
                        T(v, 14.sp, FontWeight.Bold, navy)
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f)) {
                        Box(Modifier.width(120.dp).height(1.dp).background(navy))
                        Spacer(Modifier.height(6.dp))
                        T(t("চেয়ারম্যান", "Chairman"), 13.sp, color = muted)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        QrCode(cert.verifyUrl, Modifier.size(104.dp))
                        Spacer(Modifier.height(4.dp))
                        T(t("স্ক্যান করে যাচাই করুন", "Scan to verify"), 11.sp, color = muted)
                    }
                }
            }
        }
        // "যাচাইকৃত" seal overlapping the corner (v2 design).
        Column(
            Modifier.align(Alignment.TopEnd).offset(x = 6.dp, y = (-10).dp).size(62.dp).clip(CircleShape).background(Brand.Amber)
                .border(3.dp, Color.White, CircleShape),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
        ) {
            Icon(ResoIcons.Check, null, tint = navy, modifier = Modifier.size(20.dp))
            T(t("যাচাইকৃত", "Verified"), 10.sp, FontWeight.Bold, navy, lineHeight = 12.sp)
        }
    }
}

// ============================================================== Notices

@Composable
fun NoticesScreen(nav: NavHostController) {
    val c = Reso.colors
    val scope = rememberCoroutineScope()
    val notices by UserRepo.notices.collectAsState()
    val unread by UserRepo.unread.collectAsState()
    var failed by remember { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var filter by rememberSaveable { mutableIntStateOf(0) }
    val expanded = remember { mutableStateListOf<String>() }

    suspend fun load() { failed = UserRepo.refreshNotices()?.message }
    LaunchedEffect(Unit) { load() }

    fun open(n: Notice) {
        if (n.id in expanded) expanded.remove(n.id) else expanded.add(n.id)
        if (!n.read) scope.launch {
            if (call { Graph.shareholderApi.markRead(n.id) } is Res.Ok) UserRepo.markNoticeRead(n.id)
        }
    }

    val filters = listOf(t("সব", "All"), t("রেট", "Rate"), t("পেমেন্ট", "Payment"), t("কোম্পানি", "Company"))
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxWidth().background(c.header).statusBarsPadding().headerBubble().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                net.rapti.resoshare.core.ui.CircleIconButton(ResoIcons.Back, t("পেছনে যান", "Back"), { nav.tab(Routes.HOME) })
                Spacer(Modifier.width(12.dp))
                T(t("নোটিশ", "Notices"), 22.sp, FontWeight.Bold, Color.White, modifier = Modifier.weight(1f))
                Row(
                    Modifier.clip(RoundedCornerShape(18.dp)).background(c.headerChip)
                        .clickable(enabled = unread > 0) {
                            scope.launch { if (call { Graph.shareholderApi.markAllRead() } is Res.Ok) UserRepo.markAllRead() }
                        }.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(ResoIcons.DoubleCheck, null, tint = Color.White.copy(alpha = if (unread > 0) 1f else 0.5f), modifier = Modifier.size(16.dp))
                    T(t("সব পড়া হয়েছে", "Mark all read"), 13.sp, FontWeight.Bold, Color.White.copy(alpha = if (unread > 0) 1f else 0.5f))
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                filters.forEachIndexed { i, label ->
                    val sel = i == filter
                    Box(
                        Modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(20.dp)).background(if (sel) Brand.Amber else Color.Transparent)
                            .border(1.5.dp, if (sel) Brand.Amber else Color.White.copy(alpha = 0.35f), RoundedCornerShape(20.dp))
                            .clickable(role = Role.Tab) { filter = i }.padding(horizontal = 16.dp),
                        contentAlignment = Alignment.Center,
                    ) { T(label, 14.sp, FontWeight.Bold, if (sel) Brand.Navy else Color.White) }
                }
            }
        }
        Refreshable(refreshing, { scope.launch { refreshing = true; load(); refreshing = false } }) {
            val list = notices
            when {
                list == null && failed != null -> ErrorBlock(failed!!, onRetry = { scope.launch { load() } })
                list == null -> LoadingBlock()
                else -> {
                    val shown = list.filter {
                        when (filter) {
                            1 -> it.category == "rate"
                            2 -> it.category == "payment" || it.category == "kyc"
                            3 -> it.category !in setOf("rate", "payment", "kyc")
                            else -> true
                        }
                    }
                    EnterLazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        item(key = "hdr") {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                T(t("কোম্পানির বার্তা শুধু এই অ্যাপেই আসে", "Company messages come only in this app"), 13.sp, color = c.muted, modifier = Modifier.weight(1f))
                                if (unread > 0) T(t("${Bn.num(unread)}টি নতুন", "$unread new"), 13.sp, FontWeight.Bold, c.brown)
                            }
                        }
                        if (shown.isEmpty()) item(key = "empty") { EmptyBlock(t("এখানে কোনো নোটিশ নেই", "No notices here"), ResoIcons.Bell) }
                        items(shown, key = { it.id }, contentType = { "notice" }) { n -> NoticeCard(n, n.id in expanded) { open(n) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun NoticeCard(n: Notice, open: Boolean, onClick: () -> Unit) {
    val c = Reso.colors
    val (label, bg, fg) = noticeTag(n.category)
    val (glyph, discBg, discFg) = when (n.category) {
        "rate" -> Triple("৳", c.warnBg, c.brown)
        "payment", "kyc" -> Triple("✓", c.successBg, c.green)
        else -> Triple("i", c.tagBlueBg, c.tagBlueText)
    }
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(if (n.read) c.surface else c.cashBg)
            .border(if (n.read) 1.dp else 1.5.dp, if (n.read) c.border else Brand.Amber.copy(alpha = 0.7f), shape)
            .pressable(onClick = onClick).padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TextDisc(glyph, discBg, discFg, 40.dp, 18.sp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Tag(label, if (c.dark) fg.copy(alpha = 0.18f) else bg, if (c.dark) c.text else fg)
                Spacer(Modifier.weight(1f))
                T(Bn.relative(n.createdAt, withTime = false), 12.sp, color = c.muted)
                if (!n.read) {
                    Spacer(Modifier.width(6.dp))
                    Box(Modifier.size(8.dp).clip(CircleShape).background(Brand.Amber))
                }
            }
            T(n.title, 17.sp, FontWeight.Bold)
            T(n.body, 14.sp, color = c.textSoft, maxLines = if (open) Int.MAX_VALUE else 2)
            T(if (open) t("কম দেখুন", "Show less") else t("বিস্তারিত দেখুন", "View details"), 13.sp, FontWeight.Bold, c.ink)
        }
    }
}

// ============================================================== Rules

@Composable
fun RulesScreen(nav: NavHostController) {
    val c = Reso.colors
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<Load<Rules>>(Load.Loading) }
    val open = remember { mutableStateListOf(0) }
    val seen = remember { mutableStateListOf(0) }
    suspend fun load() {
        state = when (val r = call { Graph.shareholderApi.rules() }) {
            is Res.Ok -> Load.Ready(r.value)
            is Res.Err -> Load.Failed(if (r.error.code == "NOT_FOUND") t("নিয়ম ও রেগুলেশন এখনো প্রকাশ করা হয়নি।", "Rules have not been published yet.") else r.error.message)
        }
    }
    LaunchedEffect(Unit) { load() }
    val rules = (state as? Load.Ready)?.data
    val total = rules?.sections?.size ?: 0
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(t("নিয়ম ও রেগুলেশন", "Rules & regulations"), onBack = { nav.popBackStack() }, below = if (rules != null) ({
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HeaderPill(t("সংস্করণ ", "Version ") + Bn.digits(rules.version))
                HeaderPill(t("কার্যকর: ", "Effective: ") + Bn.date(rules.effectiveDate), fg = Color.White)
            }
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.headerChip).padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row {
                    T(t("আপনি পড়ছেন", "Reading progress"), 13.sp, color = Brand.HeaderMuted, modifier = Modifier.weight(1f))
                    T(t("${Bn.num(seen.size)} / ${Bn.num(total)} অংশ", "${seen.size} / $total sections"), 13.sp, FontWeight.Bold, Brand.Gold)
                }
                ProgressTrack(if (total > 0) seen.size / total.toFloat() else 0f, height = 6.dp, color = Brand.Amber)
            }
        }) else null)
        when (val s = state) {
            Load.Loading -> LoadingBlock()
            is Load.Failed -> ErrorBlock(s.message, onRetry = { scope.launch { load() } })
            is Load.Ready -> EnterLazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item(key = "intro") {
                    T(t("Resonance Automation এর শেয়ার কেনা, রাখা ও বেচার নিয়মগুলো এখানে আছে। শেয়ার কেনার আগে একবার দেখে নিন।",
                        "These are Resonance Automation's rules for buying, holding and selling shares. Please read them before buying."), 15.sp, color = c.textSoft)
                }
                item(key = "openAll") {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        val all = open.size == s.data.sections.size
                        Box(
                            Modifier.clip(RoundedCornerShape(20.dp)).background(c.surface).border(1.dp, c.inputBorder, RoundedCornerShape(20.dp))
                                .clickable {
                                    if (all) open.clear() else {
                                        open.clear(); open.addAll(s.data.sections.indices)
                                        seen.clear(); seen.addAll(s.data.sections.indices)
                                    }
                                }.padding(horizontal = 16.dp, vertical = 8.dp),
                        ) { T(if (all) t("সব বন্ধ করুন", "Collapse all") else t("সব খুলে পড়ুন", "Open all"), 14.sp, FontWeight.Bold) }
                    }
                }
                s.data.sections.forEachIndexed { i, sec ->
                    item(key = "sec$i", contentType = "section") {
                        val isOpen = i in open
                        val rot by animateFloatAsState(if (isOpen) 180f else 0f, label = "chev")
                        val shape = RoundedCornerShape(14.dp)
                        Column(
                            Modifier.fillMaxWidth().clip(shape).background(c.surface)
                                .border(if (isOpen) 1.5.dp else 1.dp, if (isOpen) c.ink else c.border, shape),
                        ) {
                            Row(
                                Modifier.fillMaxWidth().background(if (isOpen) c.infoBg else Color.Transparent)
                                    .pressable(onClick = {
                                        if (isOpen) open.remove(i) else { open.add(i); if (i !in seen) seen.add(i) }
                                    }).padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                NumberDisc(i + 1, done = isOpen)
                                T(sec.title, 17.sp, FontWeight.Bold, modifier = Modifier.weight(1f))
                                Icon(ResoIcons.ChevronDown, null, tint = c.muted, modifier = Modifier.size(18.dp).rotate(rot))
                            }
                            AnimatedVisibility(isOpen, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                                Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    sec.items.forEach { line ->
                                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                            Box(Modifier.padding(top = 8.dp).size(7.dp).clip(RoundedCornerShape(4.dp)).background(Brand.Amber))
                                            T(line, 15.sp, color = c.textSoft, lineHeight = 24.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                item(key = "foot") {
                    T(t("নিয়ম বদলালে অ্যাপের নোটিশে জানানো হবে।", "You'll get an app notice when the rules change."), 13.sp, color = c.muted,
                        align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

// ============================================================== Orders (transactions)

@Composable
fun OrdersScreen(nav: NavHostController) {
    val c = Reso.colors
    val scope = rememberCoroutineScope()
    val orders by UserRepo.orders.collectAsState()
    var failed by remember { mutableStateOf<String?>(null) }
    var filter by rememberSaveable { mutableIntStateOf(0) }
    var cancelTarget by remember { mutableStateOf<Order?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    val filters = listOf(null, "confirmed", "pending", "cancelled")

    suspend fun load() { failed = UserRepo.refreshOrders()?.message }
    LaunchedEffect(Unit) { load() }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(t("লেনদেন", "Transactions"), onBack = { nav.popBackStack() })
        Refreshable(refreshing, { scope.launch { refreshing = true; load(); refreshing = false } }) {
            EnterLazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item(key = "filters") {
                    FilterPills(listOf(t("সব", "All"), t("নিশ্চিত", "Confirmed"), t("যাচাই চলছে", "Verifying"), t("বাতিল", "Cancelled")), filter, { filter = it })
                }
                val all = orders
                when {
                    all == null && failed != null -> item(key = "err") { ErrorBlock(failed!!, onRetry = { scope.launch { load() } }) }
                    all == null -> item(key = "loading") { LoadingBlock() }
                    else -> {
                        val list = all.filter { filters[filter] == null || it.status == filters[filter] }
                        if (list.isEmpty()) item(key = "empty") { EmptyBlock(t("এই তালিকায় কোনো লেনদেন নেই", "No transactions here"), ResoIcons.List) }
                        items(list, key = { it.id }, contentType = { "order" }) { o ->
                            ResoCard(padding = PaddingValues(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        T(t("${Bn.num(o.quantity)}টি × ${Bn.money(o.ratePaisa)}", "${o.quantity} × ${Bn.money(o.ratePaisa)}"), 16.sp, FontWeight.Bold)
                                        T("${Bn.relative(o.createdAt)} · ${methodLabel(o.method)}", 13.sp, color = c.muted)
                                    }
                                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        T(Bn.money(o.amountPaisa), 16.sp, FontWeight.Bold)
                                        StatusPill(o.status)
                                    }
                                }
                                Spacer(Modifier.height(8.dp)); RowDivider(); Spacer(Modifier.height(8.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    T("${o.orderNo}${o.reference?.let { " · $it" } ?: ""}", 13.sp, color = c.muted, modifier = Modifier.weight(1f))
                                    if (o.status == "pending") LinkText(t("বাতিল করুন", "Cancel"), { cancelTarget = o }, color = c.red, size = 13.sp)
                                }
                                if (o.status == "pending" && o.method == "cash") {
                                    T(t("অফিসে টাকা জমার সময় রসিদ নম্বর ${o.reference} বলুন।", "Mention receipt number ${o.reference} when you pay at the office."), 13.sp, color = c.brown)
                                }
                                if (o.status == "cancelled" && !o.note.isNullOrBlank()) T(t("কারণ: ", "Reason: ") + o.note, 13.sp, color = c.red)
                            }
                        }
                    }
                }
            }
        }
    }
    cancelTarget?.let { o ->
        ConfirmDialog(
            t("অর্ডার বাতিল করবেন?", "Cancel this order?"),
            t("${Bn.num(o.quantity)}টি শেয়ারের (${Bn.money(o.amountPaisa)}) অর্ডারটি বাতিল হবে। টাকা জমা দিয়ে থাকলে বাতিল করবেন না।",
                "The order for ${o.quantity} shares (${Bn.money(o.amountPaisa)}) will be cancelled. Don't cancel if you have already paid."),
            t("বাতিল করুন", "Cancel order"),
            {
                cancelTarget = null
                scope.launch {
                    when (val r = call { Graph.shareholderApi.cancelOrder(o.id) }) {
                        is Res.Ok -> { Toaster.show(t("অর্ডার বাতিল হয়েছে", "Order cancelled")); load(); UserRepo.refresh() }
                        is Res.Err -> Toaster.show(r.error.message)
                    }
                }
            },
            { cancelTarget = null }, danger = true,
        )
    }
}

// ============================================================== Nominee edit

@Composable
fun NomineeEditScreen(nav: NavHostController) {
    val scope = rememberCoroutineScope()
    val state = remember { NomineesState() }
    var loaded by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val me = (UserRepo.dashboard.value as? Load.Ready)?.data?.me ?: (call { Graph.shareholderApi.me() } as? Res.Ok)?.value
        // Existing ID numbers are masked by the server, so they must be re-entered.
        me?.nominees?.let { list ->
            if (list.isNotEmpty()) {
                state.count = list.size.coerceIn(1, 2)
                state.firstPercent = if (list.size == 2) list[0].sharePercent.coerceIn(5, 95) else 60
                list.take(2).forEachIndexed { i, n ->
                    state.forms[i].name = n.name; state.forms[i].relation = n.relation; state.forms[i].idType = n.idType
                }
            }
        }
        loaded = true
    }
    Column(Modifier.fillMaxSize().imePadding()) {
        ScreenHeader(t("নমিনি সম্পাদনা", "Edit nominees"), onBack = { nav.popBackStack() })
        if (!loaded) { LoadingBlock(); return@Column }
        LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item(key = "info") {
                InfoBox(t("নিরাপত্তার জন্য আগের পরিচয়পত্র নম্বর দেখানো হয় না। সংরক্ষণের জন্য প্রতিটি নমিনির NID/জন্ম নিবন্ধন নম্বর আবার দিন।",
                    "For security, saved ID numbers are not shown. Re-enter each nominee's NID/birth registration number to save."), icon = ResoIcons.Lock)
            }
            item(key = "editor") { NomineeEditor(state) }
            item(key = "save") {
                PrimaryButton(t("সংরক্ষণ করুন", "Save"), {
                    if (!state.validate()) return@PrimaryButton
                    loading = true
                    scope.launch {
                        when (val r = call { Graph.shareholderApi.updateNominees(NomineesUpdate(state.inputs())) }) {
                            is Res.Ok -> { Toaster.show(t("নমিনির তথ্য সংরক্ষণ হয়েছে", "Nominees saved")); UserRepo.refresh(); nav.popBackStack() }
                            is Res.Err -> Toaster.show(r.error.message)
                        }
                        loading = false
                    }
                }, loading = loading)
            }
        }
    }
}

// ============================================================== KYC resubmission

@Composable
fun KycResubmitScreen(nav: NavHostController) {
    val c = Reso.colors
    val scope = rememberCoroutineScope()
    var nid by rememberSaveable { mutableStateOf("") }
    var dob by rememberSaveable { mutableStateOf("") }
    var front by remember { mutableStateOf<ByteArray?>(null) }
    var back by remember { mutableStateOf<ByteArray?>(null) }
    var photo by remember { mutableStateOf<ByteArray?>(null) }
    var errs by remember { mutableStateOf(mapOf<String, String>()) }
    var loading by remember { mutableStateOf(false) }
    val dash by UserRepo.dashboard.collectAsState()
    val note = (dash as? Load.Ready)?.data?.me?.kycNote

    Column(Modifier.fillMaxSize().imePadding()) {
        ScreenHeader(t("KYC আবার জমা দিন", "Resubmit KYC"), onBack = { nav.popBackStack() })
        LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (!note.isNullOrBlank()) item(key = "note") { InfoBox(t("আগের আবেদন বাতিলের কারণ: ", "Reason the last request was rejected: ") + note, tone = Tone.DANGER) }
            item(key = "nid") {
                Field(t("NID নম্বর", "NID number"), nid, { nid = it }, placeholder = t("আপনার NID নম্বর", "Your NID number"),
                    keyboard = KeyboardType.Number, error = errs["nid"], maxLength = 20, leading = ResoIcons.IdCard)
            }
            item(key = "dob") { DateField(t("জন্ম তারিখ", "Date of birth"), dob, { dob = it }, error = errs["dob"], maxDate = Bn.today().minusYears(18)) }
            item(key = "front") { ImagePickTile(t("NID এর সামনের ছবি", "NID front"), front, { front = it }, error = "files" in errs && front == null) }
            item(key = "back") { ImagePickTile(t("NID এর পেছনের ছবি", "NID back"), back, { back = it }, error = "files" in errs && back == null) }
            item(key = "photo") { ImagePickTile(t("আপনার সাম্প্রতিক ছবি", "Your recent photo"), photo, { photo = it }, error = "files" in errs && photo == null) }
            errs["files"]?.let { item(key = "ferr") { T(it, 13.sp, color = c.red) } }
            item(key = "submit") {
                PrimaryButton(t("জমা দিন", "Submit"), {
                    errs = buildMap {
                        V.nid(nid)?.let { put("nid", it) }
                        V.dob(Bn.parseDate(dob))?.let { put("dob", it) }
                        if (front == null || back == null || photo == null) put("files", t("তিনটি ছবিই দিন", "Add all three photos"))
                    }
                    if (errs.isNotEmpty()) return@PrimaryButton
                    loading = true
                    scope.launch {
                        val data = AppJson.encodeToString(KycData.serializer(), KycData(V.digitsOnly(nid), dob)).toRequestBody("application/json; charset=utf-8".toMediaType())
                        when (val r = call { Graph.shareholderApi.resubmitKyc(data, jpegPart("nidFront", front!!), jpegPart("nidBack", back!!), jpegPart("photo", photo!!)) }) {
                            is Res.Ok -> { Toaster.show(t("KYC জমা হয়েছে। যাচাই হলে নোটিশে জানানো হবে।", "KYC submitted. You'll get a notice when it's verified.")); UserRepo.refresh(); nav.popBackStack() }
                            is Res.Err -> Toaster.show(r.error.message)
                        }
                        loading = false
                    }
                }, loading = loading)
            }
        }
    }
}

