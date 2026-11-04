package net.rapti.resoshare.admin

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.rapti.resoshare.Graph
import net.rapti.resoshare.Toaster
import net.rapti.resoshare.core.export.Exporter
import net.rapti.resoshare.core.format.Bn
import net.rapti.resoshare.core.format.V
import net.rapti.resoshare.core.i18n.t
import net.rapti.resoshare.core.net.Res
import net.rapti.resoshare.core.net.call
import net.rapti.resoshare.core.ui.BrandLogo
import net.rapti.resoshare.core.ui.CircleIconButton
import net.rapti.resoshare.core.ui.ConfirmDialog
import net.rapti.resoshare.core.ui.DangerButton
import net.rapti.resoshare.core.ui.EmptyBlock
import net.rapti.resoshare.core.ui.ErrorBlock
import net.rapti.resoshare.core.ui.Field
import net.rapti.resoshare.core.ui.InfoBox
import net.rapti.resoshare.core.ui.InkButton
import net.rapti.resoshare.core.ui.LinkText
import net.rapti.resoshare.core.ui.Load
import net.rapti.resoshare.core.ui.LoadingBlock
import net.rapti.resoshare.core.ui.PrimaryButton
import net.rapti.resoshare.core.ui.QrCode
import net.rapti.resoshare.core.ui.ReasonDialog
import net.rapti.resoshare.core.ui.ResoCard
import net.rapti.resoshare.core.ui.ResoIcons
import net.rapti.resoshare.core.ui.RowDivider
import net.rapti.resoshare.core.ui.ScreenHeader
import net.rapti.resoshare.core.ui.SearchField
import net.rapti.resoshare.core.ui.SecondaryButton
import net.rapti.resoshare.core.ui.SecretDialog
import net.rapti.resoshare.core.ui.StatusPill
import net.rapti.resoshare.core.ui.StatusText
import net.rapti.resoshare.core.ui.T
import net.rapti.resoshare.core.ui.Tag
import net.rapti.resoshare.core.ui.TextDisc
import net.rapti.resoshare.core.ui.Tone
import net.rapti.resoshare.core.ui.headerBubble
import net.rapti.resoshare.core.ui.methodShort
import net.rapti.resoshare.core.ui.pressable
import net.rapti.resoshare.core.ui.theme.Brand
import net.rapti.resoshare.core.ui.theme.Reso
import net.rapti.resoshare.data.api.Certificate
import net.rapti.resoshare.data.api.MessageRequest
import net.rapti.resoshare.data.api.Perm
import net.rapti.resoshare.data.api.Reason
import net.rapti.resoshare.data.api.ShareholderDetail
import net.rapti.resoshare.data.api.ShareholderRow
import net.rapti.resoshare.data.api.StatusCounts

private val STATUS = listOf("all", "verified", "pending", "suspended")
private val STATUS_LABELS: List<String>
    get() = listOf(t("সব", "All"), t("যাচাইকৃত", "Verified"), t("অপেক্ষমাণ", "Pending"), t("স্থগিত", "Suspended"))

/** Status of a shareholder row for pills/avatars. */
private fun holderStatus(kyc: String, account: String) = when {
    account == "suspended" -> "suspended"
    kyc == "verified" -> "verified"
    kyc == "rejected" -> "rejected"
    else -> "kyc_pending"
}

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
    val (export, exportUi) = rememberReportExport("shareholders", t("শেয়ারহোল্ডার তালিকা", "Shareholder list"))
    val total = pager.first?.total
    // Keep the last known counts so the pill badges don't flicker while a filter reloads.
    var counts by remember { mutableStateOf<StatusCounts?>(null) }
    LaunchedEffect(pager.first) { pager.first?.counts?.let { counts = it } }

    AdminScreen(
        header = {
            ScreenHeader(
                t("শেয়ারহোল্ডার", "Shareholders"),
                subtitle = total?.let { t("মোট নিবন্ধিত ${Bn.num(it)} জন", "${Bn.num(it)} registered") } ?: t("লোড হচ্ছে…", "Loading…"),
                actions = { CircleIconButton(ResoIcons.Download, t("শেয়ারহোল্ডার তালিকা এক্সপোর্ট", "Export shareholder list"), export.start) },
            )
        },
        refreshing = pager.refreshing,
        onRefresh = { pager.refresh() },
        spacing = 0.dp,
    ) {
        item("search") { SearchField(query, { query = it }, t("নাম বা ID দিয়ে খুঁজুন", "Search by name or ID")) }
        item("filters") {
            val c = counts
            CountPills(
                STATUS_LABELS,
                c?.let { listOf(it.all, it.verified, it.pending, it.suspended) },
                filter, { filter = it }, Modifier.padding(vertical = 14.dp),
            )
        }
        pagedItems(
            pager,
            if (debounced.isBlank()) t("কোনো শেয়ারহোল্ডার নেই", "No shareholders")
            else t("“$debounced” দিয়ে কাউকে পাওয়া যায়নি", "Nobody found for “$debounced”"),
            { it.id },
        ) { row, first, last ->
            HolderRow(row, first, last) { an.go(Routes.holder(row.id)) }
        }
        if (pager.state is Load.Ready && pager.items.isNotEmpty()) item("footer") {
            val shown = Bn.num(pager.items.size)
            val all = Bn.num(total ?: pager.items.size)
            T(
                t("$shown জন দেখানো হচ্ছে · মোট $all জন", "Showing $shown · $all in total") +
                    if (pager.hasMore) t(" · আরও দেখতে নিচে স্ক্রল করুন", " · scroll for more") else "",
                13.sp, color = Reso.colors.muted, align = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
            )
        }
    }
    exportUi()
}

/** Filter pills with a small count badge inside each pill (v2 design). */
@Composable
private fun CountPills(labels: List<String>, counts: List<Int>?, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = Reso.colors
    Row(modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.forEachIndexed { i, label ->
            val sel = i == selected
            val bg by animateColorAsState(if (sel) c.ink else c.surface, tween(180), label = "cpill")
            val shape = RoundedCornerShape(24.dp)
            Row(
                Modifier.heightIn(min = 44.dp).clip(shape).background(bg).border(1.5.dp, c.ink, shape)
                    .clickable(role = Role.Tab) { onSelect(i) }.padding(start = 16.dp, end = if (counts != null) 8.dp else 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                T(label, 15.sp, FontWeight.Bold, if (sel) c.onInk else c.text, maxLines = 1)
                val n = counts?.getOrNull(i)
                if (n != null) {
                    Box(
                        Modifier.widthIn(min = 24.dp).clip(RoundedCornerShape(12.dp))
                            .background(if (sel) c.onInk.copy(alpha = 0.18f) else c.segmentBg).padding(horizontal = 7.dp, vertical = 1.dp),
                        contentAlignment = Alignment.Center,
                    ) { T(Bn.num(n), 12.sp, FontWeight.Bold, if (sel) c.onInk else c.text, maxLines = 1) }
                }
            }
        }
    }
}

@Composable
private fun StatusAvatar(name: String, status: String, size: androidx.compose.ui.unit.Dp = 44.dp) {
    val c = Reso.colors
    val (bg, fg) = when (status) {
        "kyc_pending", "pending" -> c.warnBg to c.brown
        "suspended", "rejected", "cancelled" -> c.dangerBg to c.red
        "confirmed" -> c.successBg to c.green
        else -> c.tagBlueBg to c.tagBlueText
    }
    TextDisc(Bn.initial(name), bg, fg, size, (size.value * 0.4f).sp)
}

@Composable
private fun HolderRow(r: ShareholderRow, first: Boolean, last: Boolean, onClick: () -> Unit) {
    val status = holderStatus(r.kycStatus, r.accountStatus)
    Column(Modifier.grouped(first, last)) {
        if (!first) RowDivider(Modifier.padding(horizontal = 16.dp))
        Row(
            Modifier.fillMaxWidth().heightIn(min = 72.dp).pressable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            StatusAvatar(r.fullName, status)
            Column(Modifier.weight(1f)) {
                T(r.fullName, 16.sp, FontWeight.Bold, maxLines = 1)
                T(r.shareholderNo, 13.sp, color = Reso.colors.muted)
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                T(t("${Bn.num(r.shares)}টি", Bn.num(r.shares)), 16.sp, FontWeight.Bold)
                StatusPill(status)
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
            if (v.accountStatus == "suspended") item("susp") {
                InfoBox(t("এই অ্যাকাউন্টটি স্থগিত আছে। শেয়ারহোল্ডার লগইন বা শেয়ার কিনতে পারবেন না।", "This account is suspended. The shareholder can't log in or buy shares."), tone = Tone.DANGER)
            }
            if (v.kycStatus == "rejected" && v.kycNote != null) item("kycnote") {
                InfoBox(t("KYC বাতিলের কারণ: ${v.kycNote}", "KYC rejection reason: ${v.kycNote}"), tone = Tone.WARN)
            }
            item("info") { InfoCard(v) }
            item("history") {
                ResoCard {
                    T(t("কেনার ইতিহাস", "Purchase history"), 17.sp, FontWeight.Bold)
                    if (v.orders.isEmpty()) T(t("এখনো কোনো কেনা হয়নি", "No purchases yet"), 14.sp, color = Reso.colors.muted, modifier = Modifier.padding(top = 10.dp))
                    v.orders.forEach { o ->
                        RowDivider(Modifier.padding(vertical = 8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                T(t("${Bn.num(o.quantity)}টি × ${Bn.money(o.ratePaisa)}", "${Bn.num(o.quantity)} × ${Bn.money(o.ratePaisa)}"), 15.sp, FontWeight.Bold)
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
                    T(t("KYC নথি", "KYC documents"), 17.sp, FontWeight.Bold)
                    Spacer(Modifier.height(12.dp))
                    if (v.kycFiles.isEmpty()) T(t("কোনো নথি নেই", "No documents"), 14.sp, color = Reso.colors.muted)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        v.kycFiles.forEach { f ->
                            DocTile(kycLabel(f.kind), Modifier.weight(1f)) {
                                if (AdminSession.can(Perm.KYC)) viewer = f.id to "${v.fullName} · ${kycLabel(f.kind)}"
                                else Toaster.show(t("KYC নথি দেখার অনুমতি আপনার নেই", "You don't have permission to view KYC documents"))
                            }
                        }
                    }
                }
            }
            item("actions") { Actions(v, onCertificate = { an.go(Routes.holderCert(v.id)) }) { loader.load(silent = true) } }
        }
    }
    viewer?.let { (fid, title) -> KycImageViewer(fid, title) { viewer = null } }
}

@Composable
private fun DetailHeader(d: ShareholderDetail?, onBack: () -> Unit) {
    val c = Reso.colors
    Column(
        Modifier.fillMaxWidth().background(c.header).headerBubble().statusBarsPadding().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 22.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircleIconButton(ResoIcons.Back, t("পেছনে যান", "Back"), onBack)
            T(t("শেয়ারহোল্ডারের বিস্তারিত", "Shareholder details"), 22.sp, FontWeight.Bold, Color.White, maxLines = 1)
        }
        if (d != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            TextDisc(Bn.initial(d.fullName), Brand.Amber, Brand.Navy, 64.dp, 27.sp)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                T(d.fullName, 21.sp, FontWeight.Bold, Color.White, maxLines = 2)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Tag(d.shareholderNo, c.headerChip, Brand.Gold, size = 13.sp)
                    val (label, bg, fg) = when {
                        d.accountStatus == "suspended" -> Triple(t("স্থগিত", "Suspended"), c.dangerBg, c.red)
                        d.kycStatus == "verified" -> Triple(t("KYC যাচাইকৃত", "KYC verified"), c.successBg, c.green)
                        d.kycStatus == "rejected" -> Triple(t("KYC বাতিল", "KYC rejected"), c.dangerBg, c.red)
                        else -> Triple(t("KYC অপেক্ষমাণ", "KYC pending"), c.warnBg, c.brown)
                    }
                    Tag(label, bg, fg, size = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun StatsStrip(v: ShareholderDetail) {
    ResoCard(padding = PaddingValues(vertical = 14.dp, horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Stat(t("মোট শেয়ার", "Shares"), t("${Bn.num(v.shares)}টি", Bn.num(v.shares)), Modifier.weight(0.8f))
            VDivider()
            Stat(t("মোট পরিশোধ", "Total paid"), Bn.money(v.paidPaisa), Modifier.weight(1.1f))
            VDivider()
            Stat(t("বর্তমান মূল্য", "Current value"), Bn.money(v.valuePaisa), Modifier.weight(1.1f))
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
    ResoCard(padding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)) {
        InfoLine(t("মোবাইল", "Mobile"), contact?.mobile?.let { Bn.digits(it) } ?: v.mobileMasked) {
            if (contact == null) LinkText(if (action.busy) "…" else t("দেখুন", "Show"), {
                action.run({ Graph.adminApi.contact(v.id) }) { contact = it }
            })
        }
        RowDivider()
        InfoLine(t("ইমেইল", "Email"), contact?.email ?: v.emailMasked)
        if (contact != null) {
            T(
                t("নিরাপত্তার জন্য ৩০ সেকেন্ড পর আবার লুকানো হবে। এই দেখা অডিট লগে রেকর্ড হয়েছে।", "Hidden again after 30 seconds for security. This view was recorded in the audit log."),
                12.sp, color = c.muted, modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        v.nominees.forEach { n ->
            RowDivider()
            InfoLine(
                t("নমিনি ${Bn.num(n.slot)} (${Bn.num(n.sharePercent)}%)", "Nominee ${Bn.num(n.slot)} (${Bn.num(n.sharePercent)}%)"),
                "${n.name} (${n.relation})",
            )
        }
        RowDivider()
        InfoLine(t("যোগদান", "Joined"), Bn.dateOf(v.joinedAt))
        RowDivider()
        InfoLine(t("জন্ম তারিখ", "Date of birth"), Bn.date(v.dob))
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
private fun Actions(v: ShareholderDetail, onCertificate: () -> Unit, onChanged: () -> Unit) {
    var message by remember { mutableStateOf(false) }
    var suspend by remember { mutableStateOf(false) }
    var activate by remember { mutableStateOf(false) }
    var reset by remember { mutableStateOf(false) }
    var temp by remember { mutableStateOf<String?>(null) }
    val action = rememberAction()
    val canMsg = AdminSession.can(Perm.NOTICES)
    val canAdmin = AdminSession.can(Perm.SETTINGS)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PrimaryButton(t("সার্টিফিকেট", "Certificate"), onCertificate, Modifier.weight(1f), height = 50.dp)
            if (canMsg) SecondaryButton(t("বার্তা পাঠান", "Send message"), { message = true }, Modifier.weight(1f), height = 50.dp)
        }
        if (canAdmin) {
            if (v.accountStatus == "suspended") InkButton(t("অ্যাকাউন্ট চালু করুন", "Reactivate account"), { activate = true }, loading = action.busy)
            else DangerButton(t("অ্যাকাউন্ট স্থগিত করুন", "Suspend account"), { suspend = true }, loading = action.busy)
            LinkText(t("পাসওয়ার্ড রিসেট করুন", "Reset password"), { reset = true }, Modifier.align(Alignment.CenterHorizontally))
        }
    }
    if (message) MessageDialog(v.fullName, onDismiss = { message = false }) { title, body, done ->
        action.run({ Graph.adminApi.message(v.id, MessageRequest(title, body)) }) {
            Toaster.show(t("বার্তা পাঠানো হয়েছে", "Message sent")); done(); message = false
        }
    }
    if (suspend) ReasonDialog(t("অ্যাকাউন্ট স্থগিত করবেন?", "Suspend this account?"), t("স্থগিত করুন", "Suspend"), { reason ->
        suspend = false
        action.run({ Graph.adminApi.suspend(v.id, Reason(reason)) }) { Toaster.show(t("অ্যাকাউন্ট স্থগিত করা হয়েছে", "Account suspended")); onChanged() }
    }, { suspend = false }, placeholder = t("স্থগিত করার কারণ লিখুন", "Write the reason for suspending"))
    if (activate) ConfirmDialog(
        t("অ্যাকাউন্ট চালু করবেন?", "Reactivate this account?"),
        t("${v.fullName} আবার লগইন ও শেয়ার কিনতে পারবেন।", "${v.fullName} will be able to log in and buy shares again."),
        t("চালু করুন", "Reactivate"),
        {
            activate = false
            action.run({ Graph.adminApi.activate(v.id) }) { Toaster.show(t("অ্যাকাউন্ট চালু হয়েছে", "Account reactivated")); onChanged() }
        },
        { activate = false },
    )
    if (reset) ConfirmDialog(
        t("পাসওয়ার্ড রিসেট করবেন?", "Reset the password?"),
        t(
            "একটি অস্থায়ী পাসওয়ার্ড তৈরি হবে এবং আগের সব লগইন বাতিল হবে। পরিচয় নিশ্চিত হয়েই কেবল শেয়ারহোল্ডারকে পাসওয়ার্ডটি দিন।",
            "A temporary password is created and all existing logins are ended. Only give it to the shareholder after confirming their identity.",
        ),
        t("রিসেট করুন", "Reset"),
        { action.run({ Graph.adminApi.resetUserPassword(v.id) }) { temp = it.tempPassword; reset = false } },
        { reset = false }, danger = true,
    )
    temp?.let {
        SecretDialog(
            t("অস্থায়ী পাসওয়ার্ড", "Temporary password"), it,
            t("এটি শুধু একবার দেখানো হবে। শেয়ারহোল্ডার প্রথম লগইনে নতুন পাসওয়ার্ড দিতে বাধ্য হবেন।", "Shown only once. The shareholder must set a new password at first login."),
        ) { temp = null }
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
        title = { T(t("বার্তা পাঠান · $to", "Message · $to"), 18.sp, FontWeight.Bold, maxLines = 2) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Field(t("শিরোনাম", "Title"), title, { title = it; err = null }, maxLength = 120)
                Field(t("বার্তা", "Message"), body, { body = it; err = null }, singleLine = false, minHeight = 110.dp, maxLength = 2000, ime = ImeAction.Default)
                AnimatedVisibility(err != null) { T(err ?: "", 13.sp, color = c.red) }
                T(t("শুধু অ্যাপের নোটিশে যাবে।", "Delivered as an app notice only."), 13.sp, color = c.muted)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                err = V.required(title, t("শিরোনাম", "a title"), 3, 120) ?: V.required(body, t("বার্তা", "a message"), 3, 2000)
                if (err == null) onSend(title.trim(), body.trim()) { title = ""; body = "" }
            }) { T(t("পাঠান", "Send"), 16.sp, FontWeight.Bold, c.ink) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { T(t("বাতিল", "Cancel"), 16.sp, color = c.muted) } },
    )
}

// ---------------------------------------------------------------- certificate (admin view)

@Composable
fun HolderCertificateScreen(id: String, an: AdminNav) {
    val ctx = LocalContext.current
    val saver = rememberFileSaver()
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<Load<Certificate>>(Load.Loading) }
    var attempt by remember { mutableIntStateOf(0) }
    var working by remember { mutableStateOf(false) }
    var notFound by remember { mutableStateOf(false) }
    LaunchedEffect(id, attempt) {
        state = Load.Loading
        state = when (val r = call { Graph.adminApi.shareholderCertificate(id) }) {
            is Res.Ok -> Load.Ready(r.value)
            is Res.Err -> { notFound = r.error.code == "NOT_FOUND"; Load.Failed(r.error.message) }
        }
    }
    AdminScreen(header = { ScreenHeader(t("শেয়ার সার্টিফিকেট", "Share certificate"), onBack = an::back) }) {
        when (val s = state) {
            is Load.Loading -> item("l") { LoadingBlock() }
            is Load.Failed -> item("e") {
                if (notFound) {
                    EmptyBlock(
                        t("এই শেয়ারহোল্ডারের এখনো কোনো নিশ্চিত শেয়ার নেই, তাই সার্টিফিকেট তৈরি হয়নি।", "This shareholder has no confirmed shares yet, so there is no certificate."),
                        ResoIcons.Document,
                    )
                } else ErrorBlock(s.message, { attempt++ })
            }
            is Load.Ready -> {
                item("card") { AdminCertificateCard(s.data) }
                item("pdf") {
                    PrimaryButton(t("PDF ডাউনলোড করুন", "Download PDF"), {
                        if (working) return@PrimaryButton
                        working = true
                        scope.launch {
                            val f = withContext(Dispatchers.Default) { Exporter.certificatePdf(ctx, s.data) }
                            working = false
                            saver.save(f)
                        }
                    }, icon = ResoIcons.Download, loading = working)
                }
                item("share") {
                    SecondaryButton(t("শেয়ার করুন", "Share"), {
                        scope.launch {
                            val f = withContext(Dispatchers.Default) { Exporter.certificatePdf(ctx, s.data) }
                            saver.share(f)
                        }
                    }, icon = ResoIcons.Share)
                }
                item("note") {
                    T(
                        t("নতুন শেয়ার যুক্ত হলে সার্টিফিকেট নিজে থেকেই হালনাগাদ হয়। QR স্ক্যান করে সার্ভার থেকে যাচাই করা যায়।", "The certificate updates automatically when new shares are added. Scan the QR to verify it with the server."),
                        13.sp, color = Reso.colors.muted, align = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun AdminCertificateCard(cert: Certificate) {
    // Always printed on white paper, in both themes.
    val navy = Brand.Navy
    val muted = Color(0xFF5B6578)
    Box(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().padding(top = 10.dp, end = 6.dp).clip(RoundedCornerShape(18.dp)).background(Color.White).border(2.dp, navy, RoundedCornerShape(18.dp)).padding(6.dp)) {
            Column(
                Modifier.fillMaxWidth().border(1.dp, Brand.Amber, RoundedCornerShape(13.dp)).padding(horizontal = 18.dp, vertical = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(navy), contentAlignment = Alignment.Center) { BrandLogo(26.dp) }
                    T(cert.companyName, 17.sp, FontWeight.Bold, navy, maxLines = 1)
                }
                T(t("শেয়ার সার্টিফিকেট", "Share Certificate"), 26.sp, FontWeight.Bold, navy)
                T(t("এই মর্মে প্রত্যয়ন করা যাচ্ছে যে", "This is to certify that"), 14.sp, color = muted)
                T(cert.fullName, 24.sp, FontWeight.Bold, navy, align = TextAlign.Center)
                T(
                    t("কোম্পানির ${Bn.num(cert.shares)}টি শেয়ারের মালিক", "is the owner of ${Bn.num(cert.shares)} shares of the company"),
                    16.sp, color = Color(0xFF0F172A), align = TextAlign.Center,
                )
                Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFE1E6EF)))
                listOf(
                    t("শেয়ারহোল্ডার ID", "Shareholder ID") to cert.shareholderNo,
                    t("সার্টিফিকেট নম্বর", "Certificate No.") to cert.certificateNo,
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
        // "Verified" seal overlapping the top-right corner (v2 design).
        Column(
            Modifier.align(Alignment.TopEnd).size(64.dp).clip(RoundedCornerShape(32.dp)).background(Brand.Amber)
                .border(3.dp, Color.White, RoundedCornerShape(32.dp)),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(ResoIcons.Check, null, tint = navy, modifier = Modifier.size(20.dp))
            T(t("যাচাইকৃত", "Verified"), 10.sp, FontWeight.Bold, navy, maxLines = 1)
        }
    }
}
