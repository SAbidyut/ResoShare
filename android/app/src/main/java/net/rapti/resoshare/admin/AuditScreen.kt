package net.rapti.resoshare.admin

import androidx.compose.foundation.background
import net.rapti.resoshare.core.i18n.t
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.rapti.resoshare.Graph
import net.rapti.resoshare.core.format.Bn
import net.rapti.resoshare.core.ui.FilterPills
import net.rapti.resoshare.core.ui.InfoBox
import net.rapti.resoshare.core.ui.ResoIcons
import net.rapti.resoshare.core.ui.RowDivider
import net.rapti.resoshare.core.ui.ScreenHeader
import net.rapti.resoshare.core.ui.T
import net.rapti.resoshare.core.ui.Tone
import net.rapti.resoshare.core.ui.theme.Brand
import net.rapti.resoshare.core.ui.theme.Reso
import net.rapti.resoshare.data.api.Perm

private val CATS = listOf("", "rate", "approval", "login", "settings", "staff", "shareholder", "content")
private val CAT_LABELS: List<String>
    get() = listOf(
        t("সব", "All"), t("রেট", "Rate"), t("অনুমোদন", "Approvals"), t("লগইন", "Login"),
        t("সেটিংস", "Settings"), t("স্টাফ", "Staff"), t("শেয়ারহোল্ডার", "Shareholders"), t("কনটেন্ট", "Content"),
    )

@Composable
fun AuditScreen(an: AdminNav) {
    var cat by rememberSaveable { mutableIntStateOf(0) }
    val can = AdminSession.can(Perm.SETTINGS)
    val pager = rememberPager(
        "audit:${CATS[cat]}",
        { cursor -> Graph.adminApi.audit(CATS[cat].ifEmpty { null }, cursor) },
        { it.items }, { it.nextCursor },
    )
    AdminScreen(
        header = { ScreenHeader(t("অডিট লগ", "Audit log"), subtitle = t("কে কখন কী পরিবর্তন করেছে", "Who changed what, and when"), onBack = an::back) },
        refreshing = pager.refreshing,
        onRefresh = { pager.refresh() },
        spacing = 0.dp,
    ) {
        if (!can) {
            item("np") { InfoBox(t("অডিট লগ দেখার অনুমতি আপনার নেই।", "You don't have permission to view the audit log."), tone = Tone.WARN) }
            return@AdminScreen
        }
        item("filters") { FilterPills(CAT_LABELS, cat, { cat = it }, Modifier.padding(bottom = 14.dp)) }
        pagedItems(pager, t("এই ধরনের কোনো রেকর্ড নেই", "No records of this kind"), { it.id }) { e, first, last ->
            val c = Reso.colors
            Column(Modifier.grouped(first, last)) {
                if (!first) RowDivider(Modifier.padding(horizontal = 16.dp))
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val dot = when (e.tone) {
                        "warn" -> Brand.Amber
                        "ok" -> c.green
                        "bad" -> c.red
                        else -> c.ink
                    }
                    Box(Modifier.padding(top = 7.dp).size(10.dp).clip(CircleShape).background(dot))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        T(e.message, 15.sp, FontWeight.Bold)
                        T("${e.actorName} · ${Bn.relative(e.at)}", 13.sp, color = c.muted)
                    }
                }
            }
        }
        item("note") {
            Box(Modifier.padding(top = 16.dp)) {
                InfoBox(t("অডিট লগ কেউ পরিবর্তন বা মুছতে পারে না। প্রতিটি রেকর্ড আগেরটির সাথে হ্যাশ দিয়ে যুক্ত, তাই গোপনে বদলালে ধরা পড়ে।", "Nobody can edit or delete the audit log. Each record is hash-linked to the previous one, so any secret change is detected."), icon = ResoIcons.Shield)
            }
        }
    }
}

