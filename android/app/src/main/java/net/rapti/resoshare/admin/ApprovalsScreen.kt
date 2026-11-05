package net.rapti.resoshare.admin

import androidx.compose.foundation.background
import net.rapti.resoshare.core.i18n.t
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import net.rapti.resoshare.Graph
import net.rapti.resoshare.Toaster
import net.rapti.resoshare.core.format.Bn
import net.rapti.resoshare.core.ui.ConfirmDialog
import net.rapti.resoshare.core.ui.DangerButton
import net.rapti.resoshare.core.ui.EmptyBlock
import net.rapti.resoshare.core.ui.InkButton
import net.rapti.resoshare.core.ui.KeyValue
import net.rapti.resoshare.core.ui.Load
import net.rapti.resoshare.core.ui.ReasonDialog
import net.rapti.resoshare.core.ui.ResoCard
import net.rapti.resoshare.core.ui.ResoIcons
import net.rapti.resoshare.core.ui.ScreenHeader
import net.rapti.resoshare.core.ui.SegmentedTabs
import net.rapti.resoshare.core.ui.T
import net.rapti.resoshare.core.ui.theme.Reso
import net.rapti.resoshare.data.api.AdminOrder
import net.rapti.resoshare.data.api.KycRequest
import net.rapti.resoshare.data.api.Perm
import net.rapti.resoshare.data.api.Reason

@Composable
fun ApprovalsScreen() {
    val loader = rememberLoader("approvals") { Graph.adminApi.approvals() }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val done = remember { mutableStateListOf<String>() }
    var viewer by remember { mutableStateOf<Pair<String, String>?>(null) }
    val scope = rememberCoroutineScope()
    val data = (loader.state as? Load.Ready)?.data
    val payments = data?.payments?.filter { it.id !in done }.orEmpty()
    val kyc = data?.kyc?.filter { it.shareholderId !in done }.orEmpty()
    LaunchedEffect(payments.size, kyc.size, data) { if (data != null) AdminSession.pending.value = payments.size + kyc.size }

    fun decided(id: String) {
        done.add(id)
        scope.launch { refreshPending() }
    }

    AdminScreen(
        header = {
            ScreenHeader(
                t("অনুমোদন", "Approvals"),
                subtitle = if (data == null) t("লোড হচ্ছে…", "Loading…") else t("${Bn.num(payments.size + kyc.size)}টি আপনার সিদ্ধান্তের অপেক্ষায়", "${Bn.num(payments.size + kyc.size)} awaiting your decision"),
            )
        },
        refreshing = loader.refreshing,
        onRefresh = { done.clear(); loader.refresh() },
    ) {
        item("tabs") { SegmentedTabs(listOf(t("পেমেন্ট (${Bn.num(payments.size)})", "Payments (${Bn.num(payments.size)})"), "KYC (${Bn.num(kyc.size)})"), tab, { tab = it }) }
        loadItems(loader.state, { loader.load() }) {
            if (tab == 0) {
                if (payments.isEmpty()) item("pe") { EmptyBlock(t("কোনো পেমেন্ট অনুমোদনের অপেক্ষায় নেই", "No payments awaiting approval"), ResoIcons.CheckCircle) }
                items(payments, key = { it.id }, contentType = { "pay" }) { o ->
                    Box { PaymentCard(o) { decided(o.id) } }
                }
            } else {
                if (kyc.isEmpty()) item("ke") { EmptyBlock(t("কোনো KYC যাচাইয়ের অপেক্ষায় নেই", "No KYC awaiting review"), ResoIcons.Shield) }
                items(kyc, key = { it.shareholderId }, contentType = { "kyc" }) { k ->
                    Box {
                        KycCard(k, onView = { id, kind -> viewer = id to "${k.fullName} · ${kycLabel(kind)}" }) { decided(k.shareholderId) }
                    }
                }
            }
        }
    }
    viewer?.let { (fid, title) -> KycImageViewer(fid, title) { viewer = null } }
}

@Composable
private fun DetailBox(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Reso.colors.bg).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) { content() }
}

@Composable
private fun PaymentCard(o: AdminOrder, onDecided: () -> Unit) {
    val action = rememberAction()
    var confirm by remember { mutableStateOf(false) }
    var reject by remember { mutableStateOf(false) }
    val can = AdminSession.can(Perm.PAYMENT)
    ResoCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    T(o.shareholderName, 17.sp, FontWeight.Bold, maxLines = 1)
                    T(o.shareholderNo, 13.sp, color = Reso.colors.muted)
                }
                Column(horizontalAlignment = Alignment.End) {
                    T(Bn.money(o.amountPaisa), 19.sp, FontWeight.Bold)
                    T(t("${Bn.num(o.quantity)}টি শেয়ার", "${Bn.num(o.quantity)} shares"), 13.sp, color = Reso.colors.muted)
                }
            }
            DetailBox {
                KeyValue(t("মাধ্যম", "Method"), if (o.method == "cash") t("ক্যাশ (অফিসে জমা)", "Cash (paid at office)") else t("ব্যাংক ট্রান্সফার", "Bank transfer"), size = 14.sp)
                KeyValue(if (o.method == "cash") t("রসিদ নম্বর", "Receipt no.") else t("রেফারেন্স", "Reference"), o.reference ?: "—", size = 14.sp)
                KeyValue(t("রেট", "Rate"), Bn.money(o.ratePaisa), size = 14.sp)
                KeyValue(t("সময়", "Time"), Bn.relative(o.createdAt), size = 14.sp)
            }
            if (can) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                InkButton(t("অনুমোদন", "Approve"), { confirm = true }, Modifier.weight(1f), loading = action.busy && confirm)
                DangerButton(t("বাতিল", "Reject"), { reject = true }, Modifier.weight(1f), enabled = !action.busy)
            } else T(t("পেমেন্ট অনুমোদনের অনুমতি আপনার নেই", "You don't have permission to approve payments"), 13.sp, color = Reso.colors.muted)
        }
    }
    if (confirm && !action.busy) ConfirmDialog(
        t("পেমেন্ট অনুমোদন করবেন?", "Approve this payment?"),
        t(
            "${o.shareholderName}-এর ${Bn.money(o.amountPaisa)} ${if (o.method == "cash") "ক্যাশ জমা" else "ব্যাংক ট্রান্সফার"} যাচাই করেছেন তো? অনুমোদন করলে ${Bn.num(o.quantity)}টি শেয়ার তাঁর প্রোফাইলে যুক্ত হবে।",
            "Have you verified ${o.shareholderName}'s ${Bn.money(o.amountPaisa)} ${if (o.method == "cash") "cash deposit" else "bank transfer"}? Approving adds ${Bn.num(o.quantity)} shares to their profile.",
        ),
        t("অনুমোদন করুন", "Approve"),
        {
            action.run({ Graph.adminApi.approveOrder(o.id) }, onErr = { Toaster.show(it.message); confirm = false }) {
                confirm = false
                Toaster.show(t("পেমেন্ট অনুমোদিত হয়েছে", "Payment approved"))
                onDecided()
            }
        },
        { confirm = false },
    )
    if (reject) ReasonDialog(t("পেমেন্ট বাতিল করবেন?", "Reject this payment?"), t("বাতিল করুন", "Reject"), { reason ->
        reject = false
        action.run({ Graph.adminApi.rejectOrder(o.id, Reason(reason)) }) { Toaster.show(t("পেমেন্ট বাতিল করা হয়েছে", "Payment rejected")); onDecided() }
    }, { reject = false }, placeholder = t("বাতিলের কারণ (শেয়ারহোল্ডার দেখতে পাবেন)", "Reason (the shareholder will see it)"))
}

@Composable
private fun KycCard(k: KycRequest, onView: (String, String) -> Unit, onDecided: () -> Unit) {
    val action = rememberAction()
    var confirm by remember { mutableStateOf(false) }
    var reject by remember { mutableStateOf(false) }
    val can = AdminSession.can(Perm.KYC)
    ResoCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column {
                T(k.fullName, 17.sp, FontWeight.Bold, maxLines = 1)
                T(k.shareholderNo, 13.sp, color = Reso.colors.muted)
            }
            DetailBox {
                KeyValue("NID", Bn.digits(k.nidMasked), size = 14.sp)
                KeyValue(t("জন্ম তারিখ", "Date of birth"), Bn.date(k.dob), size = 14.sp)
                KeyValue(t("জমা দিয়েছেন", "Submitted"), Bn.relative(k.submittedAt), size = 14.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                k.files.forEach { f ->
                    DocTile(kycLabel(f.kind), Modifier.weight(1f)) {
                        if (can) onView(f.id, f.kind) else Toaster.show(t("KYC নথি দেখার অনুমতি আপনার নেই", "You don't have permission to view KYC documents"))
                    }
                }
            }
            if (can) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                InkButton(t("অনুমোদন", "Approve"), { confirm = true }, Modifier.weight(1f), loading = action.busy && confirm)
                DangerButton(t("বাতিল", "Reject"), { reject = true }, Modifier.weight(1f), enabled = !action.busy)
            } else T(t("KYC অনুমোদনের অনুমতি আপনার নেই", "You don't have permission to approve KYC"), 13.sp, color = Reso.colors.muted)
        }
    }
    if (confirm && !action.busy) ConfirmDialog(
        t("KYC অনুমোদন করবেন?", "Approve this KYC?"),
        t("NID-এর ছবি, নম্বর ও জন্ম তারিখ মিলিয়ে দেখেছেন তো? অনুমোদন করলে ${k.fullName} শেয়ার কিনতে পারবেন।", "Have you matched the NID photos, number and date of birth? Once approved, ${k.fullName} can buy shares."),
        t("অনুমোদন করুন", "Approve"),
        {
            action.run({ Graph.adminApi.approveKyc(k.shareholderId) }, onErr = { Toaster.show(it.message); confirm = false }) {
                confirm = false
                Toaster.show(t("KYC অনুমোদিত হয়েছে", "KYC approved"))
                onDecided()
            }
        },
        { confirm = false },
    )
    if (reject) ReasonDialog(t("KYC বাতিল করবেন?", "Reject this KYC?"), t("বাতিল করুন", "Reject"), { reason ->
        reject = false
        action.run({ Graph.adminApi.rejectKyc(k.shareholderId, Reason(reason)) }) { Toaster.show(t("KYC বাতিল করা হয়েছে", "KYC rejected")); onDecided() }
    }, { reject = false }, placeholder = t("যেমন: NID-এর ছবি অস্পষ্ট", "e.g. NID photo is blurry"))
}
