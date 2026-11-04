package net.rapti.resoshare.admin

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.rapti.resoshare.Graph
import net.rapti.resoshare.Toaster
import net.rapti.resoshare.core.format.Bn
import net.rapti.resoshare.core.ui.CheckRow
import net.rapti.resoshare.core.ui.ConfirmDialog
import net.rapti.resoshare.core.ui.DangerButton
import net.rapti.resoshare.core.ui.Field
import net.rapti.resoshare.core.ui.HeaderStat
import net.rapti.resoshare.core.ui.InfoBox
import net.rapti.resoshare.core.ui.Load
import net.rapti.resoshare.core.ui.PrimaryButton
import net.rapti.resoshare.core.ui.ResoCard
import net.rapti.resoshare.core.ui.ResoIcons
import net.rapti.resoshare.core.ui.RowDivider
import net.rapti.resoshare.core.ui.ScreenHeader
import net.rapti.resoshare.core.ui.SecondaryButton
import net.rapti.resoshare.core.ui.T
import net.rapti.resoshare.core.ui.Tag
import net.rapti.resoshare.core.ui.TextDisc
import net.rapti.resoshare.core.ui.Tone
import net.rapti.resoshare.core.ui.pressable
import net.rapti.resoshare.core.ui.theme.Reso
import net.rapti.resoshare.data.api.Perm
import net.rapti.resoshare.data.api.RuleSection
import net.rapti.resoshare.data.api.RulesAdmin
import net.rapti.resoshare.data.api.RulesDraft
import net.rapti.resoshare.data.api.RulesPublish
import java.time.LocalDate

/** One editable section: the rules are edited as plain text, one rule per line. */
private class EditSection(title: String, text: String) {
    var title by mutableStateOf(title)
    var text by mutableStateOf(text)
    val key = java.util.UUID.randomUUID().toString()
    fun items(): List<String> = text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
    fun toSection() = RuleSection(title.trim(), items())
}

private fun nextVersion(v: String?): String {
    if (v == null) return "1.0"
    val parts = v.split('.').map { it.toIntOrNull() ?: 0 }.toMutableList()
    if (parts.size == 1) parts.add(0)
    parts[parts.lastIndex] = parts.last() + 1
    return parts.joinToString(".")
}

@Composable
fun RulesEditorScreen(an: AdminNav) {
    val c = Reso.colors
    val loader = rememberLoader("rules-admin") { Graph.adminApi.rules() }
    val data = (loader.state as? Load.Ready)?.data
    val canEdit = AdminSession.can(Perm.NOTICES)

    val sections = remember { mutableStateListOf<EditSection>() }
    var baseline by remember { mutableStateOf<List<RuleSection>>(emptyList()) }
    var initFor by remember { mutableStateOf<RulesAdmin?>(null) }
    var expanded by remember { mutableStateOf<String?>(null) }
    var version by remember { mutableStateOf("") }
    var effective by remember { mutableStateOf<LocalDate?>(Bn.today()) }
    var summary by remember { mutableStateOf("") }
    var notify by remember { mutableStateOf(true) }
    var confirmPublish by remember { mutableStateOf(false) }
    var deleteKey by remember { mutableStateOf<String?>(null) }
    val draftAction = rememberAction()
    val publishAction = rememberAction()

    LaunchedEffect(data) {
        if (data != null && data !== initFor) {
            val src = data.draft?.sections?.takeIf { it.isNotEmpty() } ?: data.current?.sections.orEmpty()
            sections.clear()
            sections.addAll(src.map { EditSection(it.title, it.items.joinToString("\n")) })
            baseline = src
            version = Bn.digits(nextVersion(data.current?.version))
            initFor = data
        }
    }
    val dirty = sections.map { it.toSection() } != baseline

    fun validate(): String? {
        if (sections.isEmpty()) return "অন্তত একটি ধারা রাখুন"
        if (sections.size > 30) return "সর্বোচ্চ ৩০টি ধারা রাখা যায়"
        sections.forEachIndexed { i, s ->
            val n = Bn.num(i + 1)
            if (s.title.trim().length < 2) return "ধারা $n-এর নাম লিখুন"
            val items = s.items()
            if (items.isEmpty()) return "ধারা $n-এ অন্তত একটি নিয়ম লিখুন"
            if (items.size > 50) return "ধারা $n-এ সর্বোচ্চ ৫০টি নিয়ম রাখা যায়"
            if (items.any { it.length > 500 }) return "ধারা $n-এর প্রতিটি নিয়ম ৫০০ অক্ষরের মধ্যে রাখুন"
        }
        return null
    }

    AdminScreen(
        header = {
            ScreenHeader("নিয়ম ও রেগুলেশন", onBack = an::back) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    HeaderStat("চালু সংস্করণ", data?.current?.version?.let { Bn.digits(it) } ?: "—", Modifier.weight(1f))
                    HeaderStat("কার্যকর হয়েছে", data?.current?.effectiveDate?.let { Bn.date(it) } ?: "—", Modifier.weight(1.4f))
                }
            }
        },
        refreshing = loader.refreshing,
        onRefresh = { loader.refresh() },
    ) {
        loadItems(loader.state, { loader.load() }) { d ->
            item("info") {
                InfoBox("এখানে যা লিখে “প্রকাশ করুন” চাপবেন, ইউজারদের অ্যাপের “নিয়ম ও রেগুলেশন” স্ক্রিনে সেটাই দেখাবে। প্রতি লাইন ইউজারের কাছে একটি আলাদা পয়েন্ট হয়ে দেখাবে।")
            }
            item("sections") {
                ResoCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        T("ধারাসমূহ (${Bn.num(sections.size)}টি)", 17.sp, FontWeight.Bold, modifier = Modifier.weight(1f))
                        if (dirty) Tag("খসড়া সম্পাদনা", c.warnBg, c.brown)
                        else if (d.draft != null) Tag("খসড়া সংরক্ষিত", c.warnBg, c.brown)
                    }
                    sections.forEachIndexed { i, s ->
                        RowDivider(Modifier.padding(vertical = 10.dp))
                        SectionEditor(
                            index = i, s = s, open = expanded == s.key, canEdit = canEdit,
                            isFirst = i == 0, isLast = i == sections.lastIndex,
                            onToggle = { expanded = if (expanded == s.key) null else s.key },
                            onUp = { if (i > 0) { sections.removeAt(i); sections.add(i - 1, s) } },
                            onDown = { if (i < sections.lastIndex) { sections.removeAt(i); sections.add(i + 1, s) } },
                            onDelete = { deleteKey = s.key },
                        )
                    }
                    if (canEdit) {
                        RowDivider(Modifier.padding(vertical = 10.dp))
                        SecondaryButton("নতুন ধারা যোগ করুন", {
                            if (sections.size >= 30) Toaster.show("সর্বোচ্চ ৩০টি ধারা রাখা যায়")
                            else EditSection("", "").also { sections.add(it); expanded = it.key }
                        }, icon = ResoIcons.Plus, height = 50.dp)
                    }
                }
            }
            if (canEdit) item("publish") {
                ResoCard {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        T("প্রকাশ করুন", 17.sp, FontWeight.Bold)
                        Field("নতুন সংস্করণ নম্বর", version, { version = it.filter { ch -> ch.isDigit() || ch == '.' }.take(12) },
                            placeholder = "যেমন ${Bn.digits(nextVersion(d.current?.version))}", keyboard = KeyboardType.Decimal,
                            hint = d.current?.let { "বর্তমান সংস্করণ ${Bn.digits(it.version)}; নতুনটি এর চেয়ে বড় হতে হবে" })
                        DateField("কার্যকর হওয়ার তারিখ", effective, { effective = it }, min = Bn.today())
                        Field("কী বদলেছে (সংক্ষেপে)", summary, { summary = it }, placeholder = "যেমন: বিক্রির নিয়ম যোগ হয়েছে", maxLength = 300)
                        CheckRow(notify, { notify = it }) { T("অ্যাপের নোটিশে শেয়ারহোল্ডারদের জানান", 15.sp, color = c.textSoft) }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            SecondaryButton("খসড়া রাখুন", {
                                val err = validate()
                                if (err != null) Toaster.show(err)
                                else draftAction.run({ Graph.adminApi.saveRulesDraft(RulesDraft(sections.map { it.toSection() })) }) {
                                    loader.set(it)
                                    Toaster.show("খসড়া সংরক্ষণ হয়েছে")
                                }
                            }, Modifier.weight(1f), loading = draftAction.busy, enabled = !publishAction.busy)
                            PrimaryButton("প্রকাশ করুন", {
                                val v = Bn.ascii(version).trim()
                                val err = validate()
                                    ?: if (!Regex("^\\d+(\\.\\d+){0,2}$").matches(v)) "সংস্করণ নম্বর যেমন ১.১ আকারে দিন" else null
                                    ?: if (effective == null) "কার্যকর হওয়ার তারিখ দিন" else null
                                if (err != null) Toaster.show(err) else confirmPublish = true
                            }, Modifier.weight(1f), loading = publishAction.busy, enabled = !draftAction.busy)
                        }
                    }
                }
            } else item("np") { InfoBox("নিয়ম সম্পাদনার অনুমতি আপনার নেই।", tone = Tone.WARN) }
            item("history") {
                ResoCard {
                    T("সংস্করণের ইতিহাস", 17.sp, FontWeight.Bold)
                    if (d.history.isEmpty()) T("এখনো কোনো সংস্করণ নেই", 14.sp, color = c.muted, modifier = Modifier.padding(top = 8.dp))
                    d.history.forEach { h ->
                        RowDivider(Modifier.padding(vertical = 8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                val label = when (h.status) {
                                    "draft" -> "খসড়া"
                                    "published" -> "সংস্করণ ${Bn.digits(h.version)} · চালু"
                                    else -> "সংস্করণ ${Bn.digits(h.version)}"
                                }
                                T(label, 15.sp, FontWeight.Bold)
                                T(listOf(Bn.relative(h.updatedAt), h.byName).filter { it.isNotBlank() }.joinToString(" · "), 13.sp, color = c.muted)
                                if (h.summary.isNotBlank()) T(h.summary, 13.sp, color = c.textSoft, maxLines = 2)
                            }
                            when (h.status) {
                                "published" -> Tag("চালু", c.successBg, c.green)
                                "draft" -> Tag("খসড়া", c.warnBg, c.brown)
                                else -> Tag("আর্কাইভ", c.track, c.muted)
                            }
                        }
                    }
                }
            }
        }
    }

    deleteKey?.let { key ->
        val s = sections.firstOrNull { it.key == key }
        ConfirmDialog("ধারাটি মুছবেন?", "“${s?.title?.ifBlank { "নামহীন ধারা" }}” ধারা ও এর সব নিয়ম মুছে যাবে। প্রকাশ না করা পর্যন্ত ইউজারদের অ্যাপে কিছু বদলাবে না।", "মুছুন", {
            sections.removeAll { it.key == key }
            deleteKey = null
        }, { deleteKey = null }, danger = true)
    }
    if (confirmPublish) {
        val v = Bn.ascii(version).trim()
        ConfirmDialog(
            "নিয়ম প্রকাশ করবেন?",
            "সংস্করণ ${Bn.digits(v)} প্রকাশ হলে সব শেয়ারহোল্ডারের অ্যাপে নতুন নিয়ম দেখাবে${if (notify) " এবং নোটিশ যাবে" else ""}। আগের সংস্করণ আর্কাইভ হবে।",
            "প্রকাশ করুন",
            {
                confirmPublish = false
                val eff = effective ?: return@ConfirmDialog
                publishAction.run({
                    Graph.adminApi.publishRules(RulesPublish(v, eff.toString(), summary.trim(), sections.map { it.toSection() }, notify))
                }) {
                    summary = ""
                    loader.set(it)
                    Toaster.show("নিয়ম প্রকাশ হয়েছে")
                }
            },
            { confirmPublish = false },
        )
    }
}

@Composable
private fun SectionEditor(
    index: Int,
    s: EditSection,
    open: Boolean,
    canEdit: Boolean,
    isFirst: Boolean,
    isLast: Boolean,
    onToggle: () -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onDelete: () -> Unit,
) {
    val c = Reso.colors
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextDisc(Bn.num(index + 1), c.ink, c.onInk, 30.dp, 14.sp)
            Column(Modifier.weight(1f)) {
                T(s.title.ifBlank { "নামহীন ধারা" }, 16.sp, FontWeight.Bold, if (s.title.isBlank()) c.muted else c.text, maxLines = 2)
                if (!open) T("${Bn.num(s.items().size)}টি নিয়ম", 12.sp, color = c.muted)
            }
            if (canEdit) {
                val shape = RoundedCornerShape(22.dp)
                Box(
                    Modifier.heightIn(min = 40.dp).clip(shape)
                        .background(if (open) c.ink else c.surface)
                        .border(1.5.dp, c.ink, shape)
                        .pressable(onClick = onToggle).padding(horizontal = 16.dp, vertical = 9.dp),
                    contentAlignment = Alignment.Center,
                ) { T(if (open) "বন্ধ করুন" else "সম্পাদনা", 14.sp, FontWeight.Bold, if (open) c.onInk else c.ink) }
            }
        }
        AnimatedVisibility(open, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Field("ধারার নাম", s.title, { s.title = it }, maxLength = 100)
                Field("নিয়মগুলো (প্রতি লাইনে একটি)", s.text, { s.text = it }, singleLine = false, minHeight = 160.dp,
                    maxLength = 20_000, ime = ImeAction.Default, hint = "${Bn.num(s.items().size)}টি নিয়ম · খালি লাইন বাদ যাবে")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SecondaryButton("↑ উপরে", onUp, Modifier.weight(1f), enabled = !isFirst, height = 46.dp)
                    SecondaryButton("↓ নিচে", onDown, Modifier.weight(1f), enabled = !isLast, height = 46.dp)
                    DangerButton("মুছুন", onDelete, Modifier.weight(1f), height = 46.dp)
                }
            }
        }
    }
}
