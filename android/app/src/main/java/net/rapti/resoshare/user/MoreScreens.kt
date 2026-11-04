package net.rapti.resoshare.user

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import net.rapti.resoshare.core.net.AppJson
import net.rapti.resoshare.core.net.Res
import net.rapti.resoshare.core.net.call
import net.rapti.resoshare.core.ui.BrandLogo
import net.rapti.resoshare.core.ui.ConfirmDialog
import net.rapti.resoshare.core.ui.EmptyBlock
import net.rapti.resoshare.core.ui.ErrorBlock
import net.rapti.resoshare.core.ui.Field
import net.rapti.resoshare.core.ui.FilterPills
import net.rapti.resoshare.core.ui.HeaderPill
import net.rapti.resoshare.core.ui.InfoBox
import net.rapti.resoshare.core.ui.Load
import net.rapti.resoshare.core.ui.LoadingBlock
import net.rapti.resoshare.core.ui.PrimaryButton
import net.rapti.resoshare.core.ui.QrCode
import net.rapti.resoshare.core.ui.Refreshable
import net.rapti.resoshare.core.ui.ResoCard
import net.rapti.resoshare.core.ui.ResoIcons
import net.rapti.resoshare.core.ui.RowDivider
import net.rapti.resoshare.core.ui.ScreenHeader
import net.rapti.resoshare.core.ui.SecondaryButton
import net.rapti.resoshare.core.ui.StatusText
import net.rapti.resoshare.core.ui.T
import net.rapti.resoshare.core.ui.Tag
import net.rapti.resoshare.core.ui.Tone
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
            Toaster.show(if (ok) "সার্টিফিকেট সংরক্ষণ হয়েছে" else "সংরক্ষণ করা যায়নি")
        }
        pending = null
    }
    suspend fun load() {
        state = when (val r = call { Graph.shareholderApi.certificate() }) {
            is Res.Ok -> Load.Ready(r.value)
            is Res.Err -> Load.Failed(if (r.error.code == "NOT_FOUND") "শেয়ার কেনা নিশ্চিত হলে এখানে আপনার শেয়ার সার্টিফিকেট দেখা যাবে।" else r.error.message)
        }
    }
    LaunchedEffect(Unit) { load() }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("শেয়ার সার্টিফিকেট", onBack = { nav.popBackStack() })
        when (val s = state) {
            Load.Loading -> LoadingBlock()
            is Load.Failed -> ErrorBlock(s.message, onRetry = { scope.launch { load() } })
            is Load.Ready -> LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item { CertificateCard(s.data) }
                item {
                    PrimaryButton("PDF ডাউনলোড করুন", {
                        scope.launch {
                            val f = withContext(Dispatchers.Default) { Exporter.certificatePdf(ctx, s.data) }
                            pending = f
                            saver.launch(f.name)
                        }
                    }, icon = ResoIcons.Download)
                }
                item {
                    SecondaryButton("শেয়ার করুন", {
                        scope.launch {
                            val f = withContext(Dispatchers.Default) { Exporter.certificatePdf(ctx, s.data) }
                            runCatching { ctx.startActivity(Exporter.shareIntent(ctx, f)) }
                        }
                    }, icon = ResoIcons.Share)
                }
                item { InfoBox("QR কোড স্ক্যান করলে কোম্পানির সার্ভার থেকে সার্টিফিকেটটি আসল কিনা যাচাই হয়। শেয়ার সংখ্যা বদলালে নতুন সার্টিফিকেট তৈরি হয় এবং পুরনোটির QR আর কাজ করে না।", icon = ResoIcons.Shield) }
            }
        }
    }
}

@Composable
private fun CertificateCard(cert: Certificate) {
    // The certificate is always rendered on white paper, in both themes.
    val navy = Brand.Navy
    val muted = Color(0xFF5B6578)
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
            T("শেয়ার সার্টিফিকেট", 26.sp, FontWeight.Bold, navy)
            T("এই মর্মে প্রত্যয়ন করা যাচ্ছে যে", 14.sp, color = muted)
            T(cert.fullName, 24.sp, FontWeight.Bold, navy, align = TextAlign.Center)
            T("কোম্পানির ${Bn.num(cert.shares)}টি শেয়ারের মালিক", 16.sp, color = Color(0xFF0F172A))
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFE1E6EF)))
            listOf("শেয়ারহোল্ডার ID" to cert.shareholderNo, "সার্টিফিকেট নম্বর" to cert.certificateNo, "ইস্যুর তারিখ" to Bn.date(cert.issuedOn)).forEach { (k, v) ->
                Row(Modifier.fillMaxWidth()) {
                    T(k, 14.sp, color = muted, modifier = Modifier.weight(1f))
                    T(v, 14.sp, FontWeight.Bold, navy)
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Box(Modifier.width(120.dp).height(1.dp).background(navy))
                    Spacer(Modifier.height(6.dp))
                    T("চেয়ারম্যান", 13.sp, color = muted)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    QrCode(cert.verifyUrl, Modifier.size(104.dp))
                    Spacer(Modifier.height(4.dp))
                    T("স্ক্যান করে যাচাই করুন", 11.sp, color = muted)
                }
            }
        }
    }
}

// ============================================================== Notices

private fun categoryTag(cat: String): Triple<String, Color, Color> = when (cat) {
    "rate" -> Triple("রেট পরিবর্তন", Color(0xFFFFF1D6), Color(0xFF8A4B00))
    "payment" -> Triple("পেমেন্ট", Color(0xFFE3F5EC), Color(0xFF0F7B4F))
    "dividend" -> Triple("ডিভিডেন্ড", Color(0xFFE6ECF8), Color(0xFF1F3A6B))
    "rules" -> Triple("নিয়ম", Color(0xFFFFF1D6), Color(0xFF8A4B00))
    "kyc" -> Triple("KYC", Color(0xFFE3F5EC), Color(0xFF0F7B4F))
    "message" -> Triple("বার্তা", Color(0xFFE6ECF8), Color(0xFF1F3A6B))
    else -> Triple("কোম্পানি", Color(0xFFE6ECF8), Color(0xFF1F3A6B))
}

@Composable
fun NoticesScreen(nav: NavHostController) {
    val c = Reso.colors
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<Load<List<Notice>>>(Load.Loading) }
    var unread by remember { mutableIntStateOf(0) }
    var refreshing by remember { mutableStateOf(false) }
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }

    suspend fun load() {
        when (val r = call { Graph.shareholderApi.notices() }) {
            is Res.Ok -> { state = Load.Ready(r.value.items); unread = r.value.unread; UserRepo.setUnread(r.value.unread) }
            is Res.Err -> if (state !is Load.Ready) state = Load.Failed(r.error.message)
        }
    }
    LaunchedEffect(Unit) { load() }

    fun open(n: Notice) {
        expanded = if (expanded == n.id) null else n.id
        if (!n.read) scope.launch {
            if (call { Graph.shareholderApi.markRead(n.id) } is Res.Ok) {
                (state as? Load.Ready)?.let { s -> state = Load.Ready(s.data.map { if (it.id == n.id) it.copy(read = true) else it }) }
                unread = (unread - 1).coerceAtLeast(0); UserRepo.setUnread(unread)
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("নোটিশ", onBack = { nav.popBackStack() }, actions = { if (unread > 0) HeaderPill("${Bn.num(unread)} নতুন", Brand.Amber, Brand.Navy) })
        Refreshable(refreshing, { scope.launch { refreshing = true; load(); refreshing = false } }) {
            when (val s = state) {
                Load.Loading -> LoadingBlock()
                is Load.Failed -> ErrorBlock(s.message, onRetry = { scope.launch { load() } })
                is Load.Ready -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            T("কোম্পানির বার্তা শুধু এই অ্যাপেই আসে।", 14.sp, color = c.muted, modifier = Modifier.weight(1f))
                            if (unread > 0) net.rapti.resoshare.core.ui.LinkText("সব পড়া হয়েছে", {
                                scope.launch { if (call { Graph.shareholderApi.markAllRead() } is Res.Ok) load() }
                            })
                        }
                    }
                    if (s.data.isEmpty()) item { EmptyBlock("এখনো কোনো নোটিশ নেই", ResoIcons.Bell) }
                    items(s.data, key = { it.id }) { n ->
                        val (label, bg, fg) = categoryTag(n.category)
                        val open = expanded == n.id
                        Row(
                            Modifier.animateItem().fillMaxWidth().height(IntrinsicSize.Min).clip(RoundedCornerShape(14.dp)).background(c.surface)
                                .border(1.dp, c.border, RoundedCornerShape(14.dp)).pressable(onClick = { open(n) }),
                        ) {
                            Box(Modifier.width(4.dp).fillMaxHeight().background(if (n.read) Color.Transparent else Brand.Amber))
                            Column(Modifier.padding(16.dp).animateContentSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Tag(label, if (c.dark) fg.copy(alpha = 0.18f) else bg, if (c.dark) c.text else fg)
                                    Spacer(Modifier.weight(1f))
                                    T(Bn.relative(n.createdAt, withTime = false), 13.sp, color = c.muted)
                                }
                                T(n.title, 17.sp, FontWeight.Bold)
                                T(n.body, 14.sp, color = c.textSoft, maxLines = if (open) Int.MAX_VALUE else 2)
                            }
                        }
                    }
                }
            }
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
    suspend fun load() {
        state = when (val r = call { Graph.shareholderApi.rules() }) {
            is Res.Ok -> Load.Ready(r.value)
            is Res.Err -> Load.Failed(if (r.error.code == "NOT_FOUND") "নিয়ম ও রেগুলেশন এখনো প্রকাশ করা হয়নি।" else r.error.message)
        }
    }
    LaunchedEffect(Unit) { load() }
    val rules = (state as? Load.Ready)?.data
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("নিয়ম ও রেগুলেশন", onBack = { nav.popBackStack() }, below = if (rules != null) ({
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HeaderPill("সংস্করণ ${Bn.digits(rules.version)}")
                HeaderPill("কার্যকর: ${Bn.date(rules.effectiveDate)}", fg = Color.White)
            }
        }) else null)
        when (val s = state) {
            Load.Loading -> LoadingBlock()
            is Load.Failed -> ErrorBlock(s.message, onRetry = { scope.launch { load() } })
            is Load.Ready -> LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { T("Resonance Automation এর শেয়ার কেনা, রাখা ও বেচার নিয়মগুলো এখানে আছে। শেয়ার কেনার আগে একবার দেখে নিন।", 15.sp, color = c.textSoft) }
                s.data.sections.forEachIndexed { i, sec ->
                    item(key = "sec$i") {
                        val isOpen = i in open
                        val rot by animateFloatAsState(if (isOpen) 180f else 0f, label = "chev")
                        val shape = RoundedCornerShape(14.dp)
                        Column(
                            Modifier.fillMaxWidth().clip(shape).background(c.surface)
                                .border(if (isOpen) 1.5.dp else 1.dp, if (isOpen) c.ink else c.border, shape),
                        ) {
                            Row(
                                Modifier.fillMaxWidth().background(if (isOpen) c.infoBg else Color.Transparent)
                                    .pressable(onClick = { if (isOpen) open.remove(i) else open.add(i) }).padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                NumberDisc(i + 1)
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
                item { T("নিয়ম বদলালে অ্যাপের নোটিশে জানানো হবে।", 13.sp, color = c.muted, align = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
            }
        }
    }
}

// ============================================================== Orders (transactions)

@Composable
fun OrdersScreen(nav: NavHostController) {
    val c = Reso.colors
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<Load<List<Order>>>(Load.Loading) }
    var filter by rememberSaveable { mutableIntStateOf(0) }
    var cancelTarget by remember { mutableStateOf<Order?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    val filters = listOf(null, "confirmed", "pending", "cancelled")

    suspend fun load() {
        when (val r = call { Graph.shareholderApi.orders(null, 50) }) {
            is Res.Ok -> state = Load.Ready(r.value.items)
            is Res.Err -> if (state !is Load.Ready) state = Load.Failed(r.error.message)
        }
    }
    LaunchedEffect(Unit) { load() }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("লেনদেন", onBack = { nav.popBackStack() })
        Refreshable(refreshing, { scope.launch { refreshing = true; load(); refreshing = false } }) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { FilterPills(listOf("সব", "নিশ্চিত", "যাচাই চলছে", "বাতিল"), filter, { filter = it }) }
                when (val s = state) {
                    Load.Loading -> item { LoadingBlock() }
                    is Load.Failed -> item { ErrorBlock(s.message, onRetry = { scope.launch { load() } }) }
                    is Load.Ready -> {
                        val list = s.data.filter { filters[filter] == null || it.status == filters[filter] }
                        if (list.isEmpty()) item { EmptyBlock("এই তালিকায় কোনো লেনদেন নেই", ResoIcons.List) }
                        items(list, key = { it.id }) { o ->
                            ResoCard(Modifier.animateItem(), padding = PaddingValues(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        T("${Bn.num(o.quantity)}টি × ${Bn.money(o.ratePaisa)}", 16.sp, FontWeight.Bold)
                                        T("${Bn.relative(o.createdAt)} · ${methodLabel(o.method)}", 13.sp, color = c.muted)
                                    }
                                    Column(horizontalAlignment = Alignment.End) {
                                        T(Bn.money(o.amountPaisa), 16.sp, FontWeight.Bold)
                                        StatusText(o.status, 13.sp)
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                                RowDivider()
                                Spacer(Modifier.height(8.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    T("${o.orderNo}${o.reference?.let { " · $it" } ?: ""}", 13.sp, color = c.muted, modifier = Modifier.weight(1f))
                                    if (o.status == "pending") net.rapti.resoshare.core.ui.LinkText("বাতিল করুন", { cancelTarget = o }, color = c.red, size = 13.sp)
                                }
                                if (o.status == "pending" && o.method == "cash") T("অফিসে টাকা জমার সময় রসিদ নম্বর ${o.reference} বলুন।", 13.sp, color = c.brown)
                                if (o.status == "cancelled" && !o.note.isNullOrBlank()) T("কারণ: ${o.note}", 13.sp, color = c.red)
                            }
                        }
                    }
                }
            }
        }
    }
    cancelTarget?.let { o ->
        ConfirmDialog("অর্ডার বাতিল করবেন?", "${Bn.num(o.quantity)}টি শেয়ারের (${Bn.money(o.amountPaisa)}) অর্ডারটি বাতিল হবে। টাকা জমা দিয়ে থাকলে বাতিল করবেন না।", "বাতিল করুন", {
            cancelTarget = null
            scope.launch {
                when (val r = call { Graph.shareholderApi.cancelOrder(o.id) }) {
                    is Res.Ok -> { Toaster.show("অর্ডার বাতিল হয়েছে"); load(); UserRepo.refresh() }
                    is Res.Err -> Toaster.show(r.error.message)
                }
            }
        }, { cancelTarget = null }, danger = true)
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
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("নমিনি সম্পাদনা", onBack = { nav.popBackStack() })
        if (!loaded) { LoadingBlock(); return@Column }
        LazyColumn(Modifier.imePadding(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { InfoBox("নিরাপত্তার জন্য আগের পরিচয়পত্র নম্বর দেখানো হয় না। সংরক্ষণের জন্য প্রতিটি নমিনির NID/জন্ম নিবন্ধন নম্বর আবার দিন।", icon = ResoIcons.Lock) }
            item { NomineeEditor(state) }
            item {
                PrimaryButton("সংরক্ষণ করুন", {
                    if (!state.validate()) return@PrimaryButton
                    loading = true
                    scope.launch {
                        when (val r = call { Graph.shareholderApi.updateNominees(NomineesUpdate(state.inputs())) }) {
                            is Res.Ok -> { Toaster.show("নমিনির তথ্য সংরক্ষণ হয়েছে"); UserRepo.refresh(); nav.popBackStack() }
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

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("KYC আবার জমা দিন", onBack = { nav.popBackStack() })
        LazyColumn(Modifier.imePadding(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (!note.isNullOrBlank()) item { InfoBox("আগের আবেদন বাতিলের কারণ: $note", tone = Tone.DANGER) }
            item { Field("NID নম্বর", nid, { nid = it }, placeholder = "আপনার NID নম্বর", keyboard = KeyboardType.Number, error = errs["nid"], maxLength = 20) }
            item { DateField("জন্ম তারিখ", dob, { dob = it }, error = errs["dob"], maxDate = Bn.today().minusYears(18)) }
            item { ImagePickTile("NID এর সামনের ছবি", front, { front = it }, error = "files" in errs && front == null) }
            item { ImagePickTile("NID এর পেছনের ছবি", back, { back = it }, error = "files" in errs && back == null) }
            item { ImagePickTile("আপনার সাম্প্রতিক ছবি", photo, { photo = it }, error = "files" in errs && photo == null) }
            errs["files"]?.let { item { T(it, 13.sp, color = c.red) } }
            item {
                PrimaryButton("জমা দিন", {
                    errs = buildMap {
                        V.nid(nid)?.let { put("nid", it) }
                        if (dob.isBlank()) put("dob", "জন্ম তারিখ দিন")
                        if (front == null || back == null || photo == null) put("files", "তিনটি ছবিই দিন")
                    }
                    if (errs.isNotEmpty()) return@PrimaryButton
                    loading = true
                    scope.launch {
                        val data = AppJson.encodeToString(KycData.serializer(), KycData(V.digitsOnly(nid), dob)).toRequestBody("application/json; charset=utf-8".toMediaType())
                        when (val r = call { Graph.shareholderApi.resubmitKyc(data, jpegPart("nidFront", front!!), jpegPart("nidBack", back!!), jpegPart("photo", photo!!)) }) {
                            is Res.Ok -> { Toaster.show("KYC জমা হয়েছে। যাচাই হলে নোটিশে জানানো হবে।"); UserRepo.refresh(); nav.popBackStack() }
                            is Res.Err -> Toaster.show(r.error.message)
                        }
                        loading = false
                    }
                }, loading = loading)
            }
        }
    }
}
