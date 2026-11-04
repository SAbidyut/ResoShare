package net.rapti.resoshare.admin

import androidx.compose.foundation.background
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
                "অনুমোদন",
                subtitle = if (data == null) "লোড হচ্ছে…" else "${Bn.num(payments.size + kyc.size)}টি আপনার সিদ্ধান্তের অপেক্ষায়",
            )
        },
        refreshing = loader.refreshing,
        onRefresh = { done.clear(); loader.refresh() },
    ) {
        item("tabs") { SegmentedTabs(listOf("পেমেন্ট (${Bn.num(payments.size)})", "KYC (${Bn.num(kyc.size)})"), tab, { tab = it }) }
        loadItems(loader.state, { loader.load() }) {
            if (tab == 0) {
                if (payments.isEmpty()) item("pe") { EmptyBlock("কোনো পেমেন্ট অনুমোদনের অপেক্ষায় নেই", ResoIcons.CheckCircle) }
                items(payments, key = { it.id }) { o ->
                    Box(Modifier.animateItem()) { PaymentCard(o) { decided(o.id) } }
                }
            } else {
                if (kyc.isEmpty()) item("ke") { EmptyBlock("কোনো KYC যাচাইয়ের অপেক্ষায় নেই", ResoIcons.Shield) }
                items(kyc, key = { it.shareholderId }) { k ->
                    Box(Modifier.animateItem()) {
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
                    T("${Bn.num(o.quantity)}টি শেয়ার", 13.sp, color = Reso.colors.muted)
                }
            }
            DetailBox {
                KeyValue("মাধ্যম", if (o.method == "cash") "ক্যাশ (অফিসে জমা)" else "ব্যাংক ট্রান্সফার", size = 14.sp)
                KeyValue(if (o.method == "cash") "রসিদ নম্বর" else "রেফারেন্স", o.reference ?: "—", size = 14.sp)
                KeyValue("রেট", Bn.money(o.ratePaisa), size = 14.sp)
                KeyValue("সময়", Bn.relative(o.createdAt), size = 14.sp)
            }
            if (can) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                InkButton("অনুমোদন", { confirm = true }, Modifier.weight(1f), loading = action.busy && confirm)
                DangerButton("বাতিল", { reject = true }, Modifier.weight(1f), enabled = !action.busy)
            } else T("পেমেন্ট অনুমোদনের অনুমতি আপনার নেই", 13.sp, color = Reso.colors.muted)
        }
    }
    if (confirm && !action.busy) ConfirmDialog(
        "পেমেন্ট অনুমোদন করবেন?",
        "${o.shareholderName}-এর ${Bn.money(o.amountPaisa)} ${if (o.method == "cash") "ক্যাশ জমা" else "ব্যাংক ট্রান্সফার"} যাচাই করেছেন তো? অনুমোদন করলে ${Bn.num(o.quantity)}টি শেয়ার তাঁর প্রোফাইলে যুক্ত হবে।",
        "অনুমোদন করুন",
        {
            action.run({ Graph.adminApi.approveOrder(o.id) }, onErr = { Toaster.show(it.message); confirm = false }) {
                confirm = false
                Toaster.show("পেমেন্ট অনুমোদিত হয়েছে")
                onDecided()
            }
        },
        { confirm = false },
    )
    if (reject) ReasonDialog("পেমেন্ট বাতিল করবেন?", "বাতিল করুন", { reason ->
        reject = false
        action.run({ Graph.adminApi.rejectOrder(o.id, Reason(reason)) }) { Toaster.show("পেমেন্ট বাতিল করা হয়েছে"); onDecided() }
    }, { reject = false }, placeholder = "বাতিলের কারণ (শেয়ারহোল্ডার দেখতে পাবেন)")
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
                KeyValue("জন্ম তারিখ", Bn.date(k.dob), size = 14.sp)
                KeyValue("জমা দিয়েছেন", Bn.relative(k.submittedAt), size = 14.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                k.files.forEach { f ->
                    DocTile(kycLabel(f.kind), Modifier.weight(1f)) {
                        if (can) onView(f.id, f.kind) else Toaster.show("KYC নথি দেখার অনুমতি আপনার নেই")
                    }
                }
            }
            if (can) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                InkButton("অনুমোদন", { confirm = true }, Modifier.weight(1f), loading = action.busy && confirm)
                DangerButton("বাতিল", { reject = true }, Modifier.weight(1f), enabled = !action.busy)
            } else T("KYC অনুমোদনের অনুমতি আপনার নেই", 13.sp, color = Reso.colors.muted)
        }
    }
    if (confirm && !action.busy) ConfirmDialog(
        "KYC অনুমোদন করবেন?",
        "NID-এর ছবি, নম্বর ও জন্ম তারিখ মিলিয়ে দেখেছেন তো? অনুমোদন করলে ${k.fullName} শেয়ার কিনতে পারবেন।",
        "অনুমোদন করুন",
        {
            action.run({ Graph.adminApi.approveKyc(k.shareholderId) }, onErr = { Toaster.show(it.message); confirm = false }) {
                confirm = false
                Toaster.show("KYC অনুমোদিত হয়েছে")
                onDecided()
            }
        },
        { confirm = false },
    )
    if (reject) ReasonDialog("KYC বাতিল করবেন?", "বাতিল করুন", { reason ->
        reject = false
        action.run({ Graph.adminApi.rejectKyc(k.shareholderId, Reason(reason)) }) { Toaster.show("KYC বাতিল করা হয়েছে"); onDecided() }
    }, { reject = false }, placeholder = "যেমন: NID-এর ছবি অস্পষ্ট")
}
