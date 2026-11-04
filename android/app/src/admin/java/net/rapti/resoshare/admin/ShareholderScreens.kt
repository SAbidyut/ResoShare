package net.rapti.resoshare.admin

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import net.rapti.resoshare.Graph
import net.rapti.resoshare.Toaster
import net.rapti.resoshare.core.format.Bn
import net.rapti.resoshare.core.format.V
import net.rapti.resoshare.core.ui.Avatar
import net.rapti.resoshare.core.ui.CircleIconButton
import net.rapti.resoshare.core.ui.ConfirmDialog
import net.rapti.resoshare.core.ui.DangerButton
import net.rapti.resoshare.core.ui.Field
import net.rapti.resoshare.core.ui.FilterPills
import net.rapti.resoshare.core.ui.InfoBox
import net.rapti.resoshare.core.ui.InkButton
import net.rapti.resoshare.core.ui.KycText
import net.rapti.resoshare.core.ui.LinkText
import net.rapti.resoshare.core.ui.Load
import net.rapti.resoshare.core.ui.ReasonDialog
import net.rapti.resoshare.core.ui.ResoCard
import net.rapti.resoshare.core.ui.ResoIcons
import net.rapti.resoshare.core.ui.RowDivider
import net.rapti.resoshare.core.ui.SearchField
import net.rapti.resoshare.core.ui.SecondaryButton
import net.rapti.resoshare.core.ui.SecretDialog
import net.rapti.resoshare.core.ui.ScreenHeader
import net.rapti.resoshare.core.ui.StatusText
import net.rapti.resoshare.core.ui.T
import net.rapti.resoshare.core.ui.Tag
import net.rapti.resoshare.core.ui.Tone
import net.rapti.resoshare.core.ui.methodShort
import net.rapti.resoshare.core.ui.pressable
import net.rapti.resoshare.core.ui.theme.Brand
import net.rapti.resoshare.core.ui.theme.Reso
import net.rapti.resoshare.data.api.MessageRequest
import net.rapti.resoshare.data.api.Perm
import net.rapti.resoshare.data.api.Reason
import net.rapti.resoshare.data.api.ShareholderDetail
import net.rapti.resoshare.data.api.ShareholderRow

private val STATUS = listOf("all", "verified", "pending", "suspended")
private val STATUS_LABELS = listOf("সব", "যাচাইকৃত", "অপেক্ষমাণ", "স্থগিত")

@Composable
fun ShareholdersScreen(an: AdminNav) {
    var query by rememberSaveable { mutableStateOf("") }
    var debounced by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(query) {
        if (query != debounced) { delay(350); debounced = query.trim() }
    }
    val pager = rememberPager(
        "holders:${STATUS[filter]}:$debounced",
        { cursor -> Graph.adminApi.shareholders(debounced.ifBlank { null }, STATUS[filter], cursor) },
        { it.items }, { it.nextCursor },
    )
    val (export, exportUi) = rememberReportExport("shareholders", "শেয়ারহোল্ডার তালিকা")
    val total = pager.first?.total

    AdminScreen(
        header = {
            ScreenHeader(
                "শেয়ারহোল্ডার",
                subtitle = total?.let { "মোট নিবন্ধিত ${Bn.num(it)} জন" } ?: "লোড হচ্ছে…",
                actions = { CircleIconButton(ResoIcons.Download, "শেয়ারহোল্ডার তালিকা এক্সপোর্ট", export.start) },
            )
        },
        refreshing = pager.refreshing,
        onRefresh = { pager.refresh() },
        spacing = 0.dp,
    ) {
        item("search") { SearchField(query, { query = it }, "নাম, মোবাইল বা ID দিয়ে খুঁজুন") }
        item("filters") { FilterPills(STATUS_LABELS, filter, { filter = it }, Modifier.padding(vertical = 14.dp)) }
        pagedItems(pager, if (debounced.isBlank()) "কোনো শেয়ারহোল্ডার নেই" else "“$debounced” দিয়ে কাউকে পাওয়া যায়নি", { it.id }) { row, first, last ->
            HolderRow(row, first, last) { an.go(Routes.holder(row.id)) }
        }
        if (pager.state is Load.Ready && pager.items.isNotEmpty()) item("footer") {
            T("${Bn.num(pager.items.size)} / ${Bn.num(total ?: pager.items.size)} জন${if (pager.hasMore) " · আরও দেখতে নিচে স্ক্রল করুন" else ""}",
                13.sp, color = Reso.colors.muted, align = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 14.dp))
        }
    }
    exportUi()
}

@Composable
private fun HolderRow(r: ShareholderRow, first: Boolean, last: Boolean, onClick: () -> Unit) {
    Column(Modifier.grouped(first, last)) {
        if (!first) RowDivider(Modifier.padding(horizontal = 16.dp))
        Row(
            Modifier.fillMaxWidth().heightIn(min = 72.dp).pressable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Avatar(r.fullName, 44.dp)
            Column(Modifier.weight(1f)) {
                T(r.fullName, 16.sp, FontWeight.Bold, maxLines = 1)
                T(r.shareholderNo, 13.sp, color = Reso.colors.muted)
            }
            Column(horizontalAlignment = Alignment.End) {
                T("${Bn.num(r.shares)}টি", 16.sp, FontWeight.Bold)
                KycText(r.kycStatus, r.accountStatus)
            }
        }
    }
}

// ---------------------------------------------------------------- detail

@Composable
fun ShareholderDetailScreen(id: String, an: AdminNav) {
    val loader = rememberLoader("holder:$id") { Graph.adminApi.shareholder(id) }
    val d = (loader.state as? Load.Ready)?.data
    var viewer by remember { mutableStateOf<Pair<String, String>?>(null) }

    AdminScreen(
        header = { DetailHeader(d, onBack = an::back) },
        refreshing = loader.refreshing,
        onRefresh = { loader.refresh() },
    ) {
        loadItems(loader.state, { loader.load() }) { v ->
            item("stats") { StatsStrip(v) }
            if (v.accountStatus == "suspended") item("susp") { InfoBox("এই অ্যাকাউন্টটি স্থগিত আছে। শেয়ারহোল্ডার লগইন বা শেয়ার কিনতে পারবেন না।", tone = Tone.DANGER) }
            if (v.kycStatus == "rejected" && v.kycNote != null) item("kycnote") { InfoBox("KYC বাতিলের কারণ: ${v.kycNote}", tone = Tone.WARN) }
            item("info") { InfoCard(v) }
            item("history") {
                ResoCard {
                    T("কেনার ইতিহাস", 17.sp, FontWeight.Bold)
                    if (v.orders.isEmpty()) T("এখনো কোনো কেনা হয়নি", 14.sp, color = Reso.colors.muted, modifier = Modifier.padding(top = 10.dp))
                    v.orders.forEach { o ->
                        RowDivider(Modifier.padding(vertical = 8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                T("${Bn.num(o.quantity)}টি × ${Bn.money(o.ratePaisa)}", 15.sp, FontWeight.Bold)
                                T("${Bn.dateOf(o.createdAt)} · ${methodShort(o.method)}", 13.sp, color = Reso.colors.muted)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                T(Bn.money(o.amountPaisa), 16.sp, FontWeight.Bold)
                                if (o.status != "confirmed") StatusText(o.status)
                            }
                        }
                    }
                }
            }
            item("kyc") {
                ResoCard {
                    T("KYC নথি", 17.sp, FontWeight.Bold)
                    Spacer(Modifier.height(12.dp))
                    if (v.kycFiles.isEmpty()) T("কোনো নথি নেই", 14.sp, color = Reso.colors.muted)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        v.kycFiles.forEach { f ->
                            DocTile(kycLabel(f.kind), Modifier.weight(1f)) {
                                if (AdminSession.can(Perm.KYC)) viewer = f.id to "${v.fullName} · ${kycLabel(f.kind)}"
                                else Toaster.show("KYC নথি দেখার অনুমতি আপনার নেই")
                            }
                        }
                    }
                }
            }
            item("actions") { Actions(v) { loader.load(silent = true) } }
        }
    }
    viewer?.let { (fid, title) -> KycImageViewer(fid, title) { viewer = null } }
}

@Composable
private fun DetailHeader(d: ShareholderDetail?, onBack: () -> Unit) {
    val c = Reso.colors
    Column(
        Modifier.fillMaxWidth().background(c.header).statusBarsPadding().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 22.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircleIconButton(ResoIcons.Back, "পেছনে যান", onBack)
            T("শেয়ারহোল্ডারের বিস্তারিত", 22.sp, FontWeight.Bold, Color.White, maxLines = 1)
        }
        if (d != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Avatar(d.fullName, 64.dp, Brand.Amber, Brand.Navy)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                T(d.fullName, 21.sp, FontWeight.Bold, Color.White, maxLines = 2)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Tag(d.shareholderNo, c.headerChip, Brand.Gold, size = 13.sp)
                    val (t, bg, fg) = when {
                        d.accountStatus == "suspended" -> Triple("স্থগিত", c.dangerBg, c.red)
                        d.kycStatus == "verified" -> Triple("KYC যাচাইকৃত", c.successBg, c.green)
                        d.kycStatus == "rejected" -> Triple("KYC বাতিল", c.dangerBg, c.red)
                        else -> Triple("KYC অপেক্ষমাণ", c.warnBg, c.brown)
                    }
                    Tag(t, bg, fg, size = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun StatsStrip(v: ShareholderDetail) {
    ResoCard(padding = androidx.compose.foundation.layout.PaddingValues(vertical = 14.dp, horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Stat("মোট শেয়ার", "${Bn.num(v.shares)}টি", Modifier.weight(0.8f))
            VDivider()
            Stat("মোট পরিশোধ", Bn.money(v.paidPaisa), Modifier.weight(1.1f))
            VDivider()
            Stat("বর্তমান মূল্য", Bn.money(v.valuePaisa), Modifier.weight(1.1f))
        }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier) {
    Column(modifier.padding(horizontal = 6.dp)) {
        T(label, 13.sp, color = Reso.colors.muted, maxLines = 1)
        T(value, 15.sp, FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun VDivider() = Box(Modifier.width(1.dp).fillMaxHeight().background(Reso.colors.border))

@Composable
private fun InfoCard(v: ShareholderDetail) {
    val c = Reso.colors
    var contact by remember { mutableStateOf<net.rapti.resoshare.data.api.Contact?>(null) }
    val action = rememberAction()
    LaunchedEffect(contact) { if (contact != null) { delay(30_000); contact = null } }
    ResoCard(padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 6.dp)) {
        InfoLine("মোবাইল", contact?.mobile?.let { Bn.digits(it) } ?: v.mobileMasked) {
            if (contact == null) LinkText(if (action.busy) "…" else "দেখুন", {
                action.run({ Graph.adminApi.contact(v.id) }) { contact = it }
            })
        }
        RowDivider()
        InfoLine("ইমেইল", contact?.email ?: v.emailMasked)
        if (contact != null) T("নিরাপত্তার জন্য ৩০ সেকেন্ড পর আবার লুকানো হবে। এই দেখা অডিট লগে রেকর্ড হয়েছে।", 12.sp, color = c.muted, modifier = Modifier.padding(bottom = 6.dp))
        v.nominees.forEach { n ->
            RowDivider()
            InfoLine("নমিনি ${Bn.num(n.slot)} (${Bn.num(n.sharePercent)}%)", "${n.name} (${n.relation})")
        }
        RowDivider()
        InfoLine("যোগদান", Bn.dateOf(v.joinedAt))
        RowDivider()
        InfoLine("জন্ম তারিখ", Bn.date(v.dob))
        RowDivider()
        InfoLine("NID", Bn.digits(v.nidMasked))
    }
}

@Composable
private fun InfoLine(label: String, value: String, trailing: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().heightIn(min = 50.dp).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        T(label, 15.sp, color = Reso.colors.muted, modifier = Modifier.weight(0.9f))
        T(value, 15.sp, FontWeight.Bold, align = TextAlign.End, modifier = Modifier.weight(1.4f))
        if (trailing != null) { Spacer(Modifier.width(6.dp)); trailing() }
    }
}

@Composable
fun DocTile(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = Reso.colors
    Column(
        modifier.heightIn(min = 76.dp).clip(RoundedCornerShape(12.dp)).background(c.tagBlueBg).pressable(onClick = onClick).padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        Icon(ResoIcons.Image, null, tint = c.tagBlueText, modifier = Modifier.size(22.dp))
        T(label, 13.sp, FontWeight.Bold, c.tagBlueText, align = TextAlign.Center)
    }
}

@Composable
private fun Actions(v: ShareholderDetail, onChanged: () -> Unit) {
    var message by remember { mutableStateOf(false) }
    var suspend by remember { mutableStateOf(false) }
    var activate by remember { mutableStateOf(false) }
    var reset by remember { mutableStateOf(false) }
    var temp by remember { mutableStateOf<String?>(null) }
    val action = rememberAction()
    val canMsg = AdminSession.can(Perm.NOTICES)
    val canAdmin = AdminSession.can(Perm.SETTINGS)
    if (!canMsg && !canAdmin) return
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (canMsg) InkButton("বার্তা পাঠান", { message = true }, Modifier.weight(1f))
            if (canAdmin) SecondaryButton("পাসওয়ার্ড রিসেট", { reset = true }, Modifier.weight(1f), height = 50.dp, loading = action.busy && reset)
        }
        if (canAdmin) {
            if (v.accountStatus == "suspended") InkButton("অ্যাকাউন্ট আবার চালু করুন", { activate = true }, loading = action.busy)
            else DangerButton("অ্যাকাউন্ট স্থগিত করুন", { suspend = true }, loading = action.busy)
        }
    }
    if (message) MessageDialog(v.fullName, onDismiss = { message = false }) { title, body, done ->
        action.run({ Graph.adminApi.message(v.id, MessageRequest(title, body)) }) { Toaster.show("বার্তা পাঠানো হয়েছে"); done() ; message = false }
    }
    if (suspend) ReasonDialog("অ্যাকাউন্ট স্থগিত করবেন?", "স্থগিত করুন", { reason ->
        suspend = false
        action.run({ Graph.adminApi.suspend(v.id, Reason(reason)) }) { Toaster.show("অ্যাকাউন্ট স্থগিত করা হয়েছে"); onChanged() }
    }, { suspend = false }, placeholder = "স্থগিত করার কারণ লিখুন")
    if (activate) ConfirmDialog("অ্যাকাউন্ট চালু করবেন?", "${v.fullName} আবার লগইন ও শেয়ার কিনতে পারবেন।", "চালু করুন", {
        activate = false
        action.run({ Graph.adminApi.activate(v.id) }) { Toaster.show("অ্যাকাউন্ট চালু হয়েছে"); onChanged() }
    }, { activate = false })
    if (reset) ConfirmDialog(
        "পাসওয়ার্ড রিসেট করবেন?",
        "একটি অস্থায়ী পাসওয়ার্ড তৈরি হবে এবং আগের সব লগইন বাতিল হবে। পরিচয় নিশ্চিত হয়েই কেবল শেয়ারহোল্ডারকে পাসওয়ার্ডটি দিন।",
        "রিসেট করুন",
        { action.run({ Graph.adminApi.resetUserPassword(v.id) }) { temp = it.tempPassword; reset = false } },
        { reset = false }, danger = true,
    )
    temp?.let {
        SecretDialog("অস্থায়ী পাসওয়ার্ড", it, "এটি শুধু একবার দেখানো হবে। শেয়ারহোল্ডার প্রথম লগইনে নতুন পাসওয়ার্ড দিতে বাধ্য হবেন।") { temp = null }
    }
}

/** Title + body message composer (used for a single shareholder). */
@Composable
fun MessageDialog(to: String, onDismiss: () -> Unit, onSend: (title: String, body: String, done: () -> Unit) -> Unit) {
    val c = Reso.colors
    var title by rememberSaveable { mutableStateOf("") }
    var body by rememberSaveable { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        title = { T("বার্তা পাঠান · $to", 18.sp, FontWeight.Bold, maxLines = 2) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Field("শিরোনাম", title, { title = it; err = null }, maxLength = 120)
                Field("বার্তা", body, { body = it; err = null }, singleLine = false, minHeight = 110.dp, maxLength = 2000, ime = ImeAction.Default)
                AnimatedVisibility(err != null) { T(err ?: "", 13.sp, color = c.red) }
                T("শুধু অ্যাপের নোটিশে যাবে।", 13.sp, color = c.muted)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                err = V.required(title, "শিরোনাম", 3, 120) ?: V.required(body, "বার্তা", 3, 2000)
                if (err == null) onSend(title.trim(), body.trim()) { title = ""; body = "" }
            }) { T("পাঠান", 16.sp, FontWeight.Bold, c.ink) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { T("বাতিল", 16.sp, color = c.muted) } },
    )
}
