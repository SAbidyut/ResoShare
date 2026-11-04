package net.rapti.resoshare.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import net.rapti.resoshare.Graph
import net.rapti.resoshare.Toaster
import net.rapti.resoshare.core.format.Bn
import net.rapti.resoshare.core.format.V
import net.rapti.resoshare.core.net.Res
import net.rapti.resoshare.core.net.call
import net.rapti.resoshare.core.ui.Avatar
import net.rapti.resoshare.core.ui.CheckRow
import net.rapti.resoshare.core.ui.ConfirmDialog
import net.rapti.resoshare.core.ui.EmptyBlock
import net.rapti.resoshare.core.ui.Field
import net.rapti.resoshare.core.ui.InfoBox
import net.rapti.resoshare.core.ui.Load
import net.rapti.resoshare.core.ui.PrimaryButton
import net.rapti.resoshare.core.ui.ResoCard
import net.rapti.resoshare.core.ui.ResoIcons
import net.rapti.resoshare.core.ui.RowDivider
import net.rapti.resoshare.core.ui.ScreenHeader
import net.rapti.resoshare.core.ui.SearchField
import net.rapti.resoshare.core.ui.T
import net.rapti.resoshare.core.ui.Tone
import net.rapti.resoshare.core.ui.theme.Reso
import net.rapti.resoshare.data.api.NoticeRequest
import net.rapti.resoshare.data.api.Perm
import net.rapti.resoshare.data.api.ShareholderRow

@Composable
fun NoticesScreen(an: AdminNav) {
    val c = Reso.colors
    val sent = rememberPager("sent", { cur -> Graph.adminApi.sentNotices(cur) }, { it.items }, { it.nextCursor })
    val count = rememberLoader("holder-count") { Graph.adminApi.shareholders(null, "all", null, 1).total }
    var title by rememberSaveable { mutableStateOf("") }
    var body by rememberSaveable { mutableStateOf("") }
    var selectedMode by rememberSaveable { mutableStateOf(false) }
    val selected = remember { mutableStateMapOf<String, String>() } // id -> name
    var picker by remember { mutableStateOf(false) }
    var errTitle by remember { mutableStateOf<String?>(null) }
    var errBody by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf(false) }
    val action = rememberAction()
    val total = (count.state as? Load.Ready)?.data
    val canSend = AdminSession.can(Perm.NOTICES)

    AdminScreen(
        header = { ScreenHeader("নোটিশ ও বার্তা", onBack = an::back) },
        refreshing = sent.refreshing,
        onRefresh = { sent.refresh(); count.refresh() },
    ) {
        if (canSend) item("form") {
            ResoCard {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    T("নতুন বার্তা", 17.sp, FontWeight.Bold)
                    Field("শিরোনাম", title, { title = it; errTitle = null }, placeholder = "যেমন: বার্ষিক সাধারণ সভার বিজ্ঞপ্তি", error = errTitle, maxLength = 120)
                    Field("বার্তা", body, { body = it; errBody = null }, placeholder = "শেয়ারহোল্ডারদের জন্য বার্তা লিখুন", singleLine = false,
                        minHeight = 120.dp, maxLength = 2000, ime = ImeAction.Default, error = errBody,
                        hint = "${Bn.num(body.length)} / ২,০০০ অক্ষর")
                    T("প্রাপক", 14.sp, FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ChoicePill("সবাই${total?.let { " (${Bn.num(it)})" } ?: ""}", !selectedMode, Modifier.weight(1f)) { selectedMode = false }
                        ChoicePill("নির্বাচিত${if (selected.isNotEmpty()) " (${Bn.num(selected.size)})" else ""}", selectedMode, Modifier.weight(1f)) {
                            selectedMode = true; picker = true
                        }
                    }
                    if (selectedMode) {
                        if (selected.isEmpty()) T("কাউকে বাছাই করা হয়নি", 13.sp, color = c.red)
                        else T(selected.values.take(4).joinToString(", ") + if (selected.size > 4) " ও আরও ${Bn.num(selected.size - 4)} জন" else "", 14.sp, color = c.textSoft)
                        net.rapti.resoshare.core.ui.LinkText("প্রাপক বাছাই করুন", { picker = true })
                    }
                    InfoBox(
                        "শেয়ারহোল্ডার হোমের ঘণ্টা আইকন ও প্রোফাইলের “নোটিশ” সারি থেকে দেখতে পাবেন। SMS ও ইমেইল বন্ধ করা আছে।",
                        title = "শুধু অ্যাপে পাঠানো হবে", icon = ResoIcons.Bell,
                    )
                    PrimaryButton("বার্তা পাঠান", {
                        errTitle = V.required(title, "শিরোনাম", 3, 120)
                        errBody = V.required(body, "বার্তা", 3, 2000)
                        if (errTitle == null && errBody == null) {
                            if (selectedMode && selected.isEmpty()) Toaster.show("অন্তত একজন প্রাপক বাছাই করুন") else confirm = true
                        }
                    }, loading = action.busy)
                }
            }
        } else item("np") { InfoBox("নোটিশ পাঠানোর অনুমতি আপনার নেই।", tone = Tone.WARN) }
        item("sent-title") { T("পাঠানো বার্তা", 17.sp, FontWeight.Bold) }
        pagedItems(sent, "এখনো কোনো বার্তা পাঠানো হয়নি", { it.id }) { n, first, last ->
            Column(Modifier.grouped(first, last)) {
                if (!first) RowDivider(Modifier.padding(horizontal = 16.dp))
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    T(n.title, 16.sp, FontWeight.Bold)
                    T(n.body, 14.sp, color = c.textSoft, maxLines = 2)
                    val who = when (n.audience) { "all" -> "সবাই"; "user" -> "একজন"; else -> "নির্বাচিত" }
                    T("${Bn.dateOf(n.createdAt)} · অ্যাপ · $who ${Bn.num(n.recipientCount)} জন · ${n.byName}", 13.sp, color = c.muted)
                }
            }
        }
    }

    if (picker) RecipientPicker(selected, onDismiss = { picker = false; if (selected.isEmpty()) selectedMode = false })
    if (confirm) ConfirmDialog(
        "বার্তা পাঠাবেন?",
        "“${title.trim()}” — ${if (selectedMode) "${Bn.num(selected.size)} জন নির্বাচিত" else "সব ${total?.let { Bn.num(it) } ?: ""} জন"} শেয়ারহোল্ডারের অ্যাপে যাবে। পাঠানোর পর ফেরত নেওয়া যাবে না।",
        "পাঠান",
        {
            confirm = false
            val req = NoticeRequest(title.trim(), body.trim(), if (selectedMode) "selected" else "all", if (selectedMode) selected.keys.toList() else emptyList())
            action.run({ Graph.adminApi.sendNotice(req) }) {
                Toaster.show("বার্তা পাঠানো হয়েছে")
                title = ""; body = ""; selected.clear(); selectedMode = false
                sent.load(silent = true)
            }
        },
        { confirm = false },
    )
}

@Composable
private fun RecipientPicker(selected: MutableMap<String, String>, onDismiss: () -> Unit) {
    val c = Reso.colors
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<Load<List<ShareholderRow>>>(Load.Loading) }
    LaunchedEffect(query) {
        delay(if (query.isEmpty()) 0 else 350)
        results = Load.Loading
        results = when (val r = call { Graph.adminApi.shareholders(query.trim().ifBlank { null }, "all", null, 50) }) {
            is Res.Ok -> Load.Ready(r.value.items)
            is Res.Err -> Load.Failed(r.error.message)
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = c.surface) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                T("প্রাপক বাছাই করুন", 18.sp, FontWeight.Bold, modifier = Modifier.weight(1f))
                T("${Bn.num(selected.size)} জন", 14.sp, FontWeight.Bold, c.ink)
            }
            SearchField(query, { query = it }, "নাম, ID বা পুরো মোবাইল নম্বর")
            Box(Modifier.fillMaxWidth().heightIn(min = 200.dp, max = 420.dp)) {
                when (val s = results) {
                    is Load.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center).size(28.dp), color = c.ink)
                    is Load.Failed -> T(s.message, 14.sp, color = c.red, modifier = Modifier.align(Alignment.Center))
                    is Load.Ready -> if (s.data.isEmpty()) EmptyBlock("কাউকে পাওয়া যায়নি") else LazyColumn {
                        items(s.data, key = { it.id }) { r ->
                            CheckRow(r.id in selected, { on -> if (on) selected[r.id] = r.fullName else selected.remove(r.id) }) {
                                Avatar(r.fullName, 36.dp)
                                Column(Modifier.weight(1f)) {
                                    T(r.fullName, 15.sp, FontWeight.Bold, maxLines = 1)
                                    T(r.shareholderNo, 12.sp, color = c.muted)
                                }
                            }
                        }
                    }
                }
            }
            PrimaryButton("ঠিক আছে", onDismiss, height = 50.dp)
            Box(Modifier.padding(bottom = 8.dp))
        }
    }
}
