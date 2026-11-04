package net.rapti.resoshare.admin

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.rapti.resoshare.Toaster
import net.rapti.resoshare.core.export.ExportFile
import net.rapti.resoshare.core.format.Bn
import net.rapti.resoshare.core.ui.CircleIconButton
import net.rapti.resoshare.core.ui.InfoBox
import net.rapti.resoshare.core.ui.PrimaryButton
import net.rapti.resoshare.core.ui.RadioCard
import net.rapti.resoshare.core.ui.ResoCard
import net.rapti.resoshare.core.ui.ResoIcons
import net.rapti.resoshare.core.ui.RowDivider
import net.rapti.resoshare.core.ui.ScreenHeader
import net.rapti.resoshare.core.ui.T
import net.rapti.resoshare.core.ui.Tone
import net.rapti.resoshare.core.ui.theme.Reso
import net.rapti.resoshare.data.api.Perm
import java.time.LocalDate

private data class ReportType(val key: String, val title: String, val subtitle: String, val dated: Boolean)

private val TYPES = listOf(
    ReportType("sales", "বিক্রয় রিপোর্ট", "শেয়ার বিক্রি ও আয়", true),
    ReportType("shareholders", "শেয়ারহোল্ডার তালিকা", "নাম, ID, শেয়ার সংখ্যা", false),
    ReportType("transactions", "লেনদেন বিবরণী", "পেমেন্ট ও ট্রানজেকশন", true),
    ReportType("dividends", "ডিভিডেন্ড রিপোর্ট", "কে কত পেয়েছে", false),
    ReportType("kyc", "KYC অবস্থা", "যাচাইকৃত ও অপেক্ষমাণ", false),
)

/** Reports generated in this app session (kept in memory only; never written to shared storage automatically). */
internal data class RecentReport(val title: String, val format: ExportFormat, val createdOn: LocalDate, val file: ExportFile)
internal val recent = mutableStateListOf<RecentReport>()

@Composable
fun ReportsScreen(an: AdminNav) {
    val ctx = LocalContext.current
    val c = Reso.colors
    var type by rememberSaveable { mutableIntStateOf(0) }
    var from by remember { mutableStateOf<LocalDate?>(Bn.today().withDayOfMonth(1)) }
    var to by remember { mutableStateOf<LocalDate?>(Bn.today()) }
    var format by rememberSaveable { mutableIntStateOf(0) }
    var ready by remember { mutableStateOf<ExportFile?>(null) }
    val saver = rememberFileSaver()
    val action = rememberAction()
    val can = AdminSession.can(Perm.REPORTS)
    val t = TYPES[type]

    AdminScreen(header = { ScreenHeader("রিপোর্ট", onBack = an::back) }) {
        if (!can) item("np") { InfoBox("রিপোর্ট তৈরির অনুমতি আপনার নেই। সুপার অ্যাডমিনের সঙ্গে যোগাযোগ করুন।", tone = Tone.WARN) }
        item("types") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                T("রিপোর্টের ধরন", 15.sp, FontWeight.Bold)
                TYPES.forEachIndexed { i, rt -> RadioCard(type == i, rt.title, null, { type = i }, subtitle = rt.subtitle) }
            }
        }
        if (t.dated) item("dates") {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DateField("শুরুর তারিখ", from, { from = it }, Modifier.weight(1f), max = to ?: Bn.today())
                DateField("শেষ তারিখ", to, { to = it }, Modifier.weight(1f), min = from, max = Bn.today())
            }
        }
        item("format") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                T("ফরম্যাট", 15.sp, FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ExportFormat.entries.forEachIndexed { i, f -> ChoicePill(f.label, format == i, Modifier.weight(1f)) { format = i } }
                }
            }
        }
        item("go") {
            PrimaryButton("রিপোর্ট তৈরি করুন", {
                if (t.dated && from != null && to != null && from!!.isAfter(to)) {
                    Toaster.show("শুরুর তারিখ শেষ তারিখের পরে হতে পারে না"); return@PrimaryButton
                }
                val f = ExportFormat.entries[format]
                action.run({ buildReport(ctx, t.key, if (t.dated) from else null, if (t.dated) to else null, f) }) { file ->
                    recent.add(0, RecentReport(t.title + if (t.dated && from != null) " · ${Bn.dayMonth(from!!)} – ${Bn.dayMonth(to ?: Bn.today())}" else "", f, Bn.today(), file))
                    if (recent.size > 10) recent.removeAt(recent.lastIndex)
                    ready = file
                }
            }, enabled = can, loading = action.busy)
        }
        item("recent") {
            ResoCard {
                T("সাম্প্রতিক রিপোর্ট", 17.sp, FontWeight.Bold)
                if (recent.isEmpty()) T("এই সেশনে এখনো কোনো রিপোর্ট তৈরি হয়নি", 14.sp, color = c.muted, modifier = Modifier.padding(top = 8.dp))
                recent.forEach { r ->
                    RowDivider(Modifier.padding(vertical = 8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            T(r.title, 15.sp, FontWeight.Bold, maxLines = 2)
                            T("${r.format.label} · ${Bn.dayMonth(r.createdOn)}", 13.sp, color = c.muted)
                        }
                        CircleIconButton(ResoIcons.Download, "আবার ডাউনলোড", { ready = r.file }, bg = c.tagBlueBg, fg = c.ink)
                    }
                }
            }
        }
        item("note") { InfoBox("রিপোর্টের ফাইল শুধু আপনি সংরক্ষণ বা শেয়ার করলে ফোনে থাকবে। শেয়ারহোল্ডারদের ব্যক্তিগত তথ্য সাবধানে রাখুন।", icon = ResoIcons.Lock) }
    }
    ready?.let { ExportReadyDialog(it, saver) { ready = null } }
}

/** Equal-width pill choice (PDF / Excel / CSV). */
@Composable
fun ChoicePill(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = Reso.colors
    val bg by animateColorAsState(if (selected) c.ink else c.surface, label = "pill")
    val shape = RoundedCornerShape(24.dp)
    Box(
        modifier.heightIn(min = 46.dp).clip(shape).background(bg).border(1.5.dp, c.ink, shape).clickable(role = Role.RadioButton, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { T(label, 15.sp, FontWeight.Bold, if (selected) c.onInk else c.text) }
}
