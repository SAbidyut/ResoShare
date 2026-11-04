package net.rapti.resoshare.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.rapti.resoshare.Graph
import net.rapti.resoshare.Toaster
import net.rapti.resoshare.core.format.Bn
import net.rapti.resoshare.core.ui.CheckRow
import net.rapti.resoshare.core.ui.ConfirmDialog
import net.rapti.resoshare.core.ui.Field
import net.rapti.resoshare.core.ui.InfoBox
import net.rapti.resoshare.core.ui.Load
import net.rapti.resoshare.core.ui.PrimaryButton
import net.rapti.resoshare.core.ui.ProgressTrack
import net.rapti.resoshare.core.ui.ResoCard
import net.rapti.resoshare.core.ui.ResoIcons
import net.rapti.resoshare.core.ui.RowDivider
import net.rapti.resoshare.core.ui.ScreenHeader
import net.rapti.resoshare.core.ui.SecondaryButton
import net.rapti.resoshare.core.ui.StepButton
import net.rapti.resoshare.core.ui.T
import net.rapti.resoshare.core.ui.Tag
import net.rapti.resoshare.core.ui.ToggleRow
import net.rapti.resoshare.core.ui.Tone
import net.rapti.resoshare.core.ui.theme.Brand
import net.rapti.resoshare.core.ui.theme.Reso
import net.rapti.resoshare.data.api.IssueShares
import net.rapti.resoshare.data.api.Perm
import net.rapti.resoshare.data.api.Pool
import net.rapti.resoshare.data.api.RatesAdmin
import net.rapti.resoshare.data.api.SaleToggle
import net.rapti.resoshare.data.api.ScheduleSetItem
import net.rapti.resoshare.data.api.ScheduleUpdate
import java.time.LocalDate

private const val STEP = 500L // ৳৫.০০

@Composable
fun RateScreen(an: AdminNav) {
    val rates = rememberLoader("rates") { Graph.adminApi.rates() }
    val pool = rememberLoader("pool") { Graph.adminApi.pool() }
    val data = (rates.state as? Load.Ready)?.data
    val canEdit = AdminSession.can(Perm.RATE)

    // Editable schedule state (text per row), initialised from the server data.
    val texts = remember { mutableStateListOf<String>() }
    val saved = remember { mutableStateListOf<Long?>() }
    var initFor by remember { mutableStateOf<RatesAdmin?>(null) }
    var notify by remember { mutableStateOf(true) }
    var confirmSave by remember { mutableStateOf(false) }
    val save = rememberAction()
    LaunchedEffect(data) {
        if (data != null && data !== initFor) {
            texts.clear(); saved.clear()
            data.schedule.forEach { s ->
                saved.add(s.ratePaisa)
                texts.add(s.ratePaisa?.let { Bn.moneyPlain(it) } ?: "")
            }
            initFor = data
        }
    }
    val parsed: List<Long?> = texts.map { Bn.parseMoney(it)?.takeIf { v -> v > 0 } }
    val dirty = texts.indices.any { i -> parsed[i] != saved.getOrNull(i) }
    val errors: List<String?> = texts.indices.map { i ->
        when {
            texts[i].isBlank() && saved.getOrNull(i) != null -> "সংরক্ষিত রেট মুছে ফেলা যায় না"
            texts[i].isNotBlank() && parsed[i] == null -> "সঠিক রেট লিখুন"
            else -> null
        }
    }

    AdminScreen(
        header = {
            ScreenHeader("শেয়ার ও রেট", onBack = an::back) {
                val c = Reso.colors
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.headerChip).padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        T("আজকের রেট · কেনা ও বিক্রয় একই", 13.sp, color = Brand.HeaderMuted, maxLines = 1)
                        T(data?.let { Bn.money(it.today.ratePaisa) } ?: "—", 28.sp, FontWeight.Bold, Brand.Gold, maxLines = 1)
                    }
                    if (data != null) Column(horizontalAlignment = Alignment.End) {
                        T("আগামীকাল", 13.sp, color = Brand.HeaderMuted)
                        T(Bn.money(data.tomorrow.ratePaisa), 17.sp, FontWeight.Bold, Color.White)
                        val ch = data.tomorrow.ratePaisa - data.today.ratePaisa
                        T(changeText(ch, short = true), 13.sp, FontWeight.Bold, changeColorOnNavy(ch))
                    }
                }
            }
        },
        refreshing = rates.refreshing,
        onRefresh = { rates.refresh(); pool.refresh() },
    ) {
        loadItems(rates.state, { rates.load() }) { r ->
            item("schedule") {
                val c = Reso.colors
                ResoCard {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            T("আগামী ১৫ দিনের রেট সূচি", 17.sp, FontWeight.Bold, modifier = Modifier.weight(1f))
                            if (dirty) Tag("সংরক্ষণ বাকি", c.warnBg, c.brown) else Tag("সংরক্ষিত", c.successBg, c.green)
                        }
                        InfoBox(
                            "যে দিনে যত টাকা-পয়সা বসাবেন, সেই দিন সেটাই চালু হবে। রেট প্রতিদিন রাত ১২টায় নিজে থেকে বদলাবে। কেনার রেট আর বিক্রয় রেট সবসময় একই থাকবে। কোনো দিন খালি রাখলে আগের দিনের রেট চলবে।",
                            icon = ResoIcons.Calendar,
                        )
                        // Today (locked)
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.bg).padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                T(Bn.weekdayDayMonth(Bn.parseDate(r.today.date) ?: Bn.today()), 16.sp, FontWeight.Bold)
                                T("আজ · চালু আছে", 13.sp, FontWeight.Bold, c.green)
                            }
                            T(Bn.money(r.today.ratePaisa), 18.sp, FontWeight.Bold)
                        }
                    }
                }
            }
            if (texts.size == r.schedule.size) {
                r.schedule.forEachIndexed { i, s ->
                    item("row:${s.date}") {
                        val prev = effective(parsed, i - 1, r.today.ratePaisa)
                        ScheduleRow(
                            date = Bn.parseDate(s.date) ?: Bn.today(),
                            text = texts[i],
                            value = parsed[i],
                            prev = prev,
                            error = errors[i],
                            canEdit = canEdit,
                            onText = { texts[i] = it },
                            onStep = { delta ->
                                val base = parsed[i] ?: prev
                                texts[i] = Bn.moneyPlain((base + delta).coerceAtLeast(100L))
                            },
                        )
                    }
                }
            }
            if (canEdit) item("save") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CheckRow(notify, { notify = it }) { T("রেট বদলালে অ্যাপের নোটিশে জানান", 15.sp, color = Reso.colors.textSoft) }
                    PrimaryButton(
                        "রেট সূচি সংরক্ষণ করুন",
                        {
                            when {
                                errors.any { it != null } -> Toaster.show("লাল চিহ্নিত ঘরগুলো ঠিক করুন")
                                !dirty -> Toaster.show("কোনো পরিবর্তন নেই")
                                else -> confirmSave = true
                            }
                        },
                        loading = save.busy,
                    )
                }
            }
            item("pool") { PoolCard(pool) }
            item("history") {
                ResoCard {
                    T("রেটের ইতিহাস", 17.sp, FontWeight.Bold)
                    r.history.forEach { h ->
                        RowDivider(Modifier.padding(vertical = 8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                T(Bn.date(h.date), 15.sp, FontWeight.Bold)
                                T(h.setByName ?: "সূচি অনুযায়ী", 13.sp, color = Reso.colors.muted, maxLines = 1)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                T(Bn.money(h.ratePaisa), 15.sp, FontWeight.Bold)
                                T(if (h.changePaisa == 0L) "— অপরিবর্তিত" else changeText(h.changePaisa, short = true), 13.sp, FontWeight.Bold, changeColor(h.changePaisa))
                            }
                        }
                    }
                }
            }
        }
    }

    if (confirmSave) {
        val changed = texts.indices.filter { parsed[it] != null && parsed[it] != saved.getOrNull(it) }
        ConfirmDialog(
            "রেট সূচি সংরক্ষণ করবেন?",
            "${Bn.num(changed.size)} দিনের রেট সংরক্ষণ হবে। নির্ধারিত দিনে রাত ১২টায় সেই রেট নিজে থেকে চালু হবে${if (notify) " এবং শেয়ারহোল্ডারদের অ্যাপে নোটিশ যাবে" else ""}।",
            "সংরক্ষণ করুন",
            {
                confirmSave = false
                val d = data ?: return@ConfirmDialog
                val items = d.schedule.indices.mapNotNull { i -> parsed[i]?.let { ScheduleSetItem(d.schedule[i].date, it) } }
                save.run({ Graph.adminApi.saveSchedule(ScheduleUpdate(items, notify)) }) {
                    rates.set(it)
                    Toaster.show("রেট সূচি সংরক্ষণ হয়েছে")
                }
            },
            { confirmSave = false },
        )
    }
}

/** Effective rate on schedule index i (null entries inherit the previous day). */
private fun effective(parsed: List<Long?>, i: Int, today: Long): Long {
    var v = today
    for (k in 0..i) parsed.getOrNull(k)?.let { v = it }
    return v
}

@Composable
private fun changeColor(ch: Long) = when {
    ch > 0 -> Reso.colors.green
    ch < 0 -> Reso.colors.red
    else -> Reso.colors.muted
}

private fun changeColorOnNavy(ch: Long) = when {
    ch > 0 -> Brand.Mint
    ch < 0 -> Color(0xFFFF9C94)
    else -> Brand.HeaderMuted
}

private fun changeText(ch: Long, short: Boolean = false): String = when {
    ch > 0 -> "▲ ${Bn.signedMoney(ch)}"
    ch < 0 -> "▼ ${Bn.signedMoney(ch)}"
    else -> if (short) "— সমান" else "— আগের দিনের সমান"
}

@Composable
private fun ScheduleRow(
    date: LocalDate,
    text: String,
    value: Long?,
    prev: Long,
    error: String?,
    canEdit: Boolean,
    onText: (String) -> Unit,
    onStep: (Long) -> Unit,
) {
    val c = Reso.colors
    ResoCard(padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                T(Bn.weekdayDayMonth(date), 15.sp, FontWeight.Bold, maxLines = 1)
                if (value == null) T("সেট করা নেই · আগের রেট চলবে", 12.sp, color = c.muted)
                else T(changeText(value - prev), 12.sp, FontWeight.Bold, changeColor(value - prev))
            }
            if (canEdit) {
                StepButton(false, { onStep(-STEP) }, size = 44.dp)
                Spacer(Modifier.width(8.dp))
                Field(null, text, { v -> onText(v.filter { it.isDigit() || it == '.' || it == ',' }) },
                    Modifier.width(118.dp), placeholder = Bn.moneyPlain(prev), keyboard = KeyboardType.Decimal, maxLength = 14, minHeight = 46.dp)
                Spacer(Modifier.width(8.dp))
                StepButton(true, { onStep(STEP) }, size = 44.dp)
            } else {
                T(value?.let { Bn.money(it) } ?: "—", 16.sp, FontWeight.Bold)
            }
        }
        if (error != null) T(error, 13.sp, color = c.red, align = TextAlign.End, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
    }
}

@Composable
private fun PoolCard(loader: Loader<Pool>) {
    val c = Reso.colors
    val action = rememberAction()
    var confirmToggle by remember { mutableStateOf<Boolean?>(null) }
    var issue by remember { mutableStateOf(false) }
    ResoCard {
        T("শেয়ারের হিসাব", 17.sp, FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        when (val s = loader.state) {
            is Load.Loading -> net.rapti.resoshare.core.ui.LoadingBlock()
            is Load.Failed -> net.rapti.resoshare.core.ui.ErrorBlock(s.message, { loader.load() })
            is Load.Ready -> {
                val p = s.data
                val frac = if (p.totalShares > 0) p.soldShares.toFloat() / p.totalShares else 0f
                ProgressTrack(frac, height = 10.dp)
                Spacer(Modifier.height(12.dp))
                Row {
                    PoolStat("মোট", Bn.num(p.totalShares), Modifier.weight(1f), Alignment.Start)
                    PoolStat("বিক্রিত (${Bn.num((frac * 100).toInt())}%)", Bn.num(p.soldShares), Modifier.weight(1f), Alignment.CenterHorizontally)
                    PoolStat("অবশিষ্ট", Bn.num(p.remainingShares), Modifier.weight(1f), Alignment.End)
                }
                RowDivider(Modifier.padding(vertical = 10.dp))
                ToggleRow(
                    if (p.saleEnabled) "শেয়ার বিক্রি চালু আছে" else "শেয়ার বিক্রি বন্ধ আছে",
                    "বন্ধ থাকলে কেউ নতুন অর্ডার দিতে পারবে না",
                    p.saleEnabled,
                    { confirmToggle = it },
                    enabled = AdminSession.can(Perm.RATE) && !action.busy,
                )
                if (AdminSession.can(Perm.SETTINGS)) {
                    Spacer(Modifier.height(8.dp))
                    SecondaryButton("নতুন শেয়ার ইস্যু করুন", { issue = true }, height = 50.dp, loading = action.busy && issue)
                }
            }
        }
    }
    confirmToggle?.let { on ->
        ConfirmDialog(
            if (on) "শেয়ার বিক্রি চালু করবেন?" else "শেয়ার বিক্রি বন্ধ করবেন?",
            if (on) "শেয়ারহোল্ডাররা আবার নতুন অর্ডার দিতে পারবেন।" else "বন্ধ থাকা অবস্থায় কেউ নতুন শেয়ার কেনার অর্ডার দিতে পারবেন না। অপেক্ষমাণ অর্ডার অনুমোদন করা যাবে।",
            if (on) "চালু করুন" else "বন্ধ করুন",
            {
                confirmToggle = null
                action.run({ Graph.adminApi.setSale(SaleToggle(on)) }) { loader.set(it); Toaster.show(if (on) "বিক্রি চালু হয়েছে" else "বিক্রি বন্ধ হয়েছে") }
            },
            { confirmToggle = null },
            danger = !on,
        )
    }
    if (issue) IssueDialog(onDismiss = { issue = false }) { n ->
        action.run({ Graph.adminApi.issueShares(IssueShares(n)) }) {
            issue = false
            loader.set(it)
            Toaster.show("${Bn.num(n)}টি নতুন শেয়ার ইস্যু হয়েছে")
        }
    }
}

@Composable
private fun PoolStat(label: String, value: String, modifier: Modifier, align: Alignment.Horizontal) {
    Column(modifier, horizontalAlignment = align) {
        T(label, 13.sp, color = Reso.colors.muted, maxLines = 1)
        T(value, 19.sp, FontWeight.Bold)
    }
}

@Composable
private fun IssueDialog(onDismiss: () -> Unit, onIssue: (Int) -> Unit) {
    val c = Reso.colors
    var text by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf(false) }
    val n = Bn.ascii(text).filter { it.isDigit() }.toIntOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        title = { T(if (confirm) "নিশ্চিত করুন" else "নতুন শেয়ার ইস্যু", 19.sp, FontWeight.Bold) },
        text = {
            if (!confirm) Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Field("কয়টি নতুন শেয়ার?", text, { text = it.take(8); err = null }, keyboard = KeyboardType.Number, error = err, placeholder = "যেমন ১,০০০")
                InfoBox("নতুন শেয়ার মোট শেয়ারের সাথে যোগ হবে এবং শেয়ারহোল্ডারদের অ্যাপে নোটিশ যাবে।", tone = Tone.WARN)
            } else T("মোট শেয়ারের সাথে ${Bn.num(n ?: 0)}টি নতুন শেয়ার যোগ হবে। এটি অডিট লগে রেকর্ড থাকবে।", 15.sp, color = c.textSoft)
        },
        confirmButton = {
            TextButton(onClick = {
                if (!confirm) {
                    if (n == null || n !in 1..10_000_000) err = "১ থেকে ১,০০,০০,০০০ এর মধ্যে সংখ্যা দিন" else confirm = true
                } else onIssue(n ?: return@TextButton)
            }) { T(if (confirm) "ইস্যু করুন" else "পরবর্তী", 16.sp, FontWeight.Bold, c.ink) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { T("বাতিল", 16.sp, color = c.muted) } },
    )
}
