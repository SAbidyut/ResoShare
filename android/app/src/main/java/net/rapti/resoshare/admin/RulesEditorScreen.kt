package net.rapti.resoshare.admin

import androidx.compose.animation.AnimatedVisibility
import net.rapti.resoshare.core.i18n.t
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
        if (sections.isEmpty()) return t("অন্তত একটি ধারা রাখুন", "Keep at least one section")
        if (sections.size > 30) return t("সর্বোচ্চ ৩০টি ধারা রাখা যায়", "At most 30 sections are allowed")
        sections.forEachIndexed { i, s ->
            val n = Bn.num(i + 1)
            if (s.title.trim().length < 2) return t("ধারা $n-এর নাম লিখুন", "Enter a name for section $n")
            val items = s.items()
            if (items.isEmpty()) return t("ধারা $n-এ অন্তত একটি নিয়ম লিখুন", "Write at least one rule in section $n")
            if (items.size > 50) return t("ধারা $n-এ সর্বোচ্চ ৫০টি নিয়ম রাখা যায়", "Section $n can have at most 50 rules")
            if (items.any { it.length > 500 }) return t("ধারা $n-এর প্রতিটি নিয়ম ৫০০ অক্ষরের মধ্যে রাখুন", "Keep each rule in section $n under 500 characters")
        }
        return null
    }

    AdminScreen(
        header = {
            ScreenHeader(t("নিয়ম ও রেগুলেশন", "Rules & regulations"), onBack = an::back) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    HeaderStat(t("চালু সংস্করণ", "Current version"), data?.current?.version?.let { Bn.digits(it) } ?: "—", Modifier.weight(1f))
                    HeaderStat(t("কার্যকর হয়েছে", "Effective from"), data?.current?.effectiveDate?.let { Bn.date(it) } ?: "—", Modifier.weight(1.4f))
                }
            }
        },
        refreshing = loader.refreshing,
        onRefresh = { loader.refresh() },
    ) {
        loadItems(loader.state, { loader.load() }) { d ->
            item("info") {
                InfoBox(t("এখানে যা লিখে “প্রকাশ করুন” চাপবেন, ইউজারদের অ্যাপের “নিয়ম ও রেগুলেশন” স্ক্রিনে সেটাই দেখাবে। প্রতি লাইন ইউজারের কাছে একটি আলাদা পয়েন্ট হয়ে দেখাবে।", "Whatever you write here and publish appears on the shareholders' “Rules & regulations” screen. Each line shows as a separate point."))
            }
            item("sections") {
                ResoCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        T(t("ধারাসমূহ (${Bn.num(sections.size)}টি)", "Sections (${Bn.num(sections.size)})"), 17.sp, FontWeight.Bold, modifier = Modifier.weight(1f))
                        if (dirty) Tag(t("খসড়া সম্পাদনা", "Editing draft"), c.warnBg, c.brown)
                        else if (d.draft != null) Tag(t("খসড়া সংরক্ষিত", "Draft saved"), c.warnBg, c.brown)
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
                        SecondaryButton(t("নতুন ধারা যোগ করুন", "Add a section"), {
                            if (sections.size >= 30) Toaster.show(t("সর্বোচ্চ ৩০টি ধারা রাখা যায়", "At most 30 sections are allowed"))
                            else EditSection("", "").also { sections.add(it); expanded = it.key }
                        }, icon = ResoIcons.Plus, height = 50.dp)
                    }
                }
            }
            if (canEdit) item("publish") {
                ResoCard {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        T(t("প্রকাশ করুন", "Publish"), 17.sp, FontWeight.Bold)
                        Field(t("নতুন সংস্করণ নম্বর", "New version number"), version, { version = it.filter { ch -> ch.isDigit() || ch == '.' }.take(12) },
                            placeholder = t("যেমন ${Bn.digits(nextVersion(d.current?.version))}", "e.g. ${nextVersion(d.current?.version)}"), keyboard = KeyboardType.Decimal,
                            hint = d.current?.let { t("বর্তমান সংস্করণ ${Bn.digits(it.version)}; নতুনটি এর চেয়ে বড় হতে হবে", "Current version ${it.version}; the new one must be higher") })
                        DateField(t("কার্যকর হওয়ার তারিখ", "Effective date"), effective, { effective = it }, min = Bn.today())
                        Field(t("কী বদলেছে (সংক্ষেপে)", "What changed (short)"), summary, { summary = it }, placeholder = t("যেমন: বিক্রির নিয়ম যোগ হয়েছে", "e.g. Added selling rules"), maxLength = 300)
                        CheckRow(notify, { notify = it }) { T(t("অ্যাপের নোটিশে শেয়ারহোল্ডারদের জানান", "Tell shareholders via an app notice"), 15.sp, color = c.textSoft) }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            SecondaryButton(t("খসড়া রাখুন", "Save draft"), {
                                val err = validate()
                                if (err != null) Toaster.show(err)
                                else draftAction.run({ Graph.adminApi.saveRulesDraft(RulesDraft(sections.map { it.toSection() })) }) {
                                    loader.set(it)
                                    Toaster.show(t("খসড়া সংরক্ষণ হয়েছে", "Draft saved"))
                                }
                            }, Modifier.weight(1f), loading = draftAction.busy, enabled = !publishAction.busy)
                            PrimaryButton(t("প্রকাশ করুন", "Publish"), {
                                val v = Bn.ascii(version).trim()
                                val err = validate()
                                    ?: if (!Regex("^\\d+(\\.\\d+){0,2}$").matches(v)) t("সংস্করণ নম্বর যেমন ১.১ আকারে দিন", "Enter a version like 1.1") else null
                                    ?: if (effective == null) t("কার্যকর হওয়ার তারিখ দিন", "Pick the effective date") else null
                                if (err != null) Toaster.show(err) else confirmPublish = true
                            }, Modifier.weight(1f), loading = publishAction.busy, enabled = !draftAction.busy)
                        }
                    }
                }
            } else item("np") { InfoBox(t("নিয়ম সম্পাদনার অনুমতি আপনার নেই।", "You don't have permission to edit the rules."), tone = Tone.WARN) }
            item("history") {
                ResoCard {
                    T(t("সংস্করণের ইতিহাস", "Version history"), 17.sp, FontWeight.Bold)
                    if (d.history.isEmpty()) T(t("এখনো কোনো সংস্করণ নেই", "No versions yet"), 14.sp, color = c.muted, modifier = Modifier.padding(top = 8.dp))
                    d.history.forEach { h ->
                        RowDivider(Modifier.padding(vertical = 8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                val label = when (h.status) {
                                    "draft" -> t("খসড়া", "Draft")
                                    "published" -> t("সংস্করণ ${Bn.digits(h.version)} · চালু", "Version ${h.version} · live")
                                    else -> t("সংস্করণ ${Bn.digits(h.version)}", "Version ${h.version}")
                                }
                                T(label, 15.sp, FontWeight.Bold)
                                T(listOf(Bn.relative(h.updatedAt), h.byName).filter { it.isNotBlank() }.joinToString(" · "), 13.sp, color = c.muted)
                                if (h.summary.isNotBlank()) T(h.summary, 13.sp, color = c.textSoft, maxLines = 2)
                            }
                            when (h.status) {
                                "published" -> Tag(t("চালু", "Live"), c.successBg, c.green)
                                "draft" -> Tag(t("খসড়া", "Draft"), c.warnBg, c.brown)
                                else -> Tag(t("আর্কাইভ", "Archived"), c.track, c.muted)
                            }
                        }
                    }
                }
            }
        }
    }

    deleteKey?.let { key ->
        val s = sections.firstOrNull { it.key == key }
        ConfirmDialog(t("ধারাটি মুছবেন?", "Delete this section?"), t("“${s?.title?.ifBlank { "নামহীন ধারা" }}” ধারা ও এর সব নিয়ম মুছে যাবে। প্রকাশ না করা পর্যন্ত ইউজারদের অ্যাপে কিছু বদলাবে না।", "“${s?.title?.ifBlank { "Untitled section" }}” and all its rules will be removed. Nothing changes for shareholders until you publish."), t("মুছুন", "Delete"), {
            sections.removeAll { it.key == key }
            deleteKey = null
        }, { deleteKey = null }, danger = true)
    }
    if (confirmPublish) {
        val v = Bn.ascii(version).trim()
        ConfirmDialog(
            t("নিয়ম প্রকাশ করবেন?", "Publish the rules?"),
            t("সংস্করণ ${Bn.digits(v)} প্রকাশ হলে সব শেয়ারহোল্ডারের অ্যাপে নতুন নিয়ম দেখাবে${if (notify) " এবং নোটিশ যাবে" else ""}। আগের সংস্করণ আর্কাইভ হবে।", "Once version $v is published, all shareholders see the new rules${if (notify) " and get a notice" else ""}. The previous version is archived."),
            t("প্রকাশ করুন", "Publish"),
            {
                confirmPublish = false
                val eff = effective ?: return@ConfirmDialog
                publishAction.run({
                    Graph.adminApi.publishRules(RulesPublish(v, eff.toString(), summary.trim(), sections.map { it.toSection() }, notify))
                }) {
                    summary = ""
                    loader.set(it)
                    Toaster.show(t("নিয়ম প্রকাশ হয়েছে", "Rules published"))
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
                T(s.title.ifBlank { t("নামহীন ধারা", "Untitled section") }, 16.sp, FontWeight.Bold, if (s.title.isBlank()) c.muted else c.text, maxLines = 2)
                if (!open) T(t("${Bn.num(s.items().size)}টি নিয়ম", "${Bn.num(s.items().size)} rules"), 12.sp, color = c.muted)
            }
            if (canEdit) {
                val shape = RoundedCornerShape(22.dp)
                Box(
                    Modifier.heightIn(min = 40.dp).clip(shape)
                        .background(if (open) c.ink else c.surface)
                        .border(1.5.dp, c.ink, shape)
                        .pressable(onClick = onToggle).padding(horizontal = 16.dp, vertical = 9.dp),
                    contentAlignment = Alignment.Center,
                ) { T(if (open) t("বন্ধ করুন", "Close") else t("সম্পাদনা", "Edit"), 14.sp, FontWeight.Bold, if (open) c.onInk else c.ink) }
            }
        }
        AnimatedVisibility(open, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Field(t("ধারার নাম", "Section name"), s.title, { s.title = it }, maxLength = 100)
                Field(t("নিয়মগুলো (প্রতি লাইনে একটি)", "Rules (one per line)"), s.text, { s.text = it }, singleLine = false, minHeight = 160.dp,
                    maxLength = 20_000, ime = ImeAction.Default, hint = t("${Bn.num(s.items().size)}টি নিয়ম · খালি লাইন বাদ যাবে", "${Bn.num(s.items().size)} rules · blank lines are ignored"))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SecondaryButton(t("↑ উপরে", "↑ Up"), onUp, Modifier.weight(1f), enabled = !isFirst, height = 46.dp)
                    SecondaryButton(t("↓ নিচে", "↓ Down"), onDown, Modifier.weight(1f), enabled = !isLast, height = 46.dp)
                    DangerButton(t("মুছুন", "Delete"), onDelete, Modifier.weight(1f), height = 46.dp)
                }
            }
        }
    }
}
