package net.rapti.resoshare.admin

import androidx.compose.foundation.background
import net.rapti.resoshare.core.i18n.t
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import net.rapti.resoshare.Auth
import net.rapti.resoshare.BuildConfig
import net.rapti.resoshare.Graph
import net.rapti.resoshare.core.ui.headerBubble
import net.rapti.resoshare.MainActivity
import net.rapti.resoshare.core.format.Bn
import net.rapti.resoshare.core.ui.Avatar
import net.rapti.resoshare.core.ui.ConfirmDialog
import net.rapti.resoshare.core.ui.DangerButton
import net.rapti.resoshare.core.ui.DemoBadge
import net.rapti.resoshare.core.ui.MenuRow
import net.rapti.resoshare.core.ui.ResoIcons
import net.rapti.resoshare.core.ui.RowDivider
import net.rapti.resoshare.core.ui.SegmentedTabs
import net.rapti.resoshare.core.ui.T
import net.rapti.resoshare.core.ui.Tag
import net.rapti.resoshare.core.ui.ToggleRow
import net.rapti.resoshare.core.ui.theme.Brand
import net.rapti.resoshare.core.ui.theme.Reso
import net.rapti.resoshare.core.ui.theme.ThemeMode
import net.rapti.resoshare.data.api.Perm

private data class MenuEntry(val icon: ImageVector, val title: String, val route: String, val badge: String? = null)

@Composable
fun MoreScreen(an: AdminNav) {
    val me by AdminSession.me.collectAsState()
    val c = Reso.colors
    val perms = me?.permissions.orEmpty()
    var logout by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    AdminScreen(header = {
        Column(
            Modifier.fillMaxWidth().background(c.header).statusBarsPadding().headerBubble().padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            T(t("আরও", "More"), 22.sp, FontWeight.Bold, Color.White)
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.headerChip).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Avatar(me?.name ?: "?", 56.dp, Brand.Amber, Brand.Navy)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    T(me?.name ?: "—", 18.sp, FontWeight.Bold, Color.White, maxLines = 2)
                    Tag(roleLabel(me?.roleKey, me?.roleName), Brand.Navy, Brand.Gold, size = 13.sp)
                }
            }
        }
    }) {
        item("share") {
            Section(t("শেয়ার ব্যবস্থাপনা", "Share management"), listOfNotNull(
                MenuEntry(ResoIcons.List, t("লেনদেন", "Transactions"), Routes.TRANSACTIONS),
                MenuEntry(ResoIcons.Chart, t("শেয়ার ও রেট", "Shares & rate"), Routes.RATE),
                MenuEntry(ResoIcons.Dollar, t("ডিভিডেন্ড", "Dividend"), Routes.DIVIDEND),
                if (Perm.REPORTS in perms) MenuEntry(ResoIcons.Report, t("রিপোর্ট", "Reports"), Routes.REPORTS) else null,
            ), an)
        }
        item("comm") {
            Section(t("যোগাযোগ", "Communication"), listOf(
                MenuEntry(ResoIcons.Bell, t("নোটিশ ও বার্তা", "Notices & messages"), Routes.NOTICES),
                MenuEntry(ResoIcons.Shield, t("নিয়ম ও রেগুলেশন", "Rules & regulations"), Routes.RULES),
            ), an)
        }
        item("sys") {
            Section(t("নিরাপত্তা ও সিস্টেম", "Security & system"), listOfNotNull(
                if (Perm.SETTINGS in perms) MenuEntry(ResoIcons.User, t("ইউজার ও রোল", "Users & roles"), Routes.STAFF) else null,
                if (Perm.SETTINGS in perms) MenuEntry(ResoIcons.Clock, t("অডিট লগ", "Audit log"), Routes.AUDIT) else null,
                MenuEntry(ResoIcons.Sun, t("সেটিংস", "Settings"), Routes.SETTINGS),
                MenuEntry(ResoIcons.Key, t("পাসওয়ার্ড পরিবর্তন", "Change password"), Routes.PASSWORD),
            ), an)
        }
        item("app") { AppSection() }
        item("logout") { DangerButton(t("লগআউট", "Log out"), { logout = true }, height = 54.dp) }
        item("ver") {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (Graph.isDemo) DemoBadge()
                T(t("রেজোনেন্স অ্যাডমিন · সংস্করণ ${Bn.digits(BuildConfig.VERSION_NAME)}", "Resonance admin · version ${BuildConfig.VERSION_NAME}"), 13.sp, color = c.muted, align = TextAlign.Center)
            }
        }
    }
    if (logout) ConfirmDialog(t("লগআউট করবেন?", "Log out?"), t("আবার ঢুকতে পাসওয়ার্ড ও OTP লাগবে।", "You'll need your password and OTP to sign in again."), t("লগআউট", "Log out"), {
        logout = false
        scope.launch { Auth.logout(null) }
    }, { logout = false }, danger = true)
}

@Composable
private fun Section(title: String, entries: List<MenuEntry>, an: AdminNav) {
    if (entries.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        T(title, 14.sp, color = Reso.colors.muted, modifier = Modifier.padding(start = 4.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Reso.colors.surface)) {
            entries.forEachIndexed { i, e ->
                if (i > 0) RowDivider(Modifier.padding(start = 54.dp))
                MenuRow(e.icon, e.title, badge = e.badge, onClick = { an.go(e.route) })
            }
        }
    }
}

@Composable
private fun AppSection() {
    val c = Reso.colors
    val mode by Graph.themeMode.collectAsState()
    val activity = LocalContext.current as? MainActivity
    val canLock = remember { activity?.canUseDeviceAuth() == true }
    var lock by remember { mutableStateOf(Graph.prefs.appLock && canLock) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        T(t("অ্যাপ", "App"), 14.sp, color = c.muted, modifier = Modifier.padding(start = 4.dp))
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            T(t("থিম", "Theme"), 16.sp, FontWeight.Bold)
            SegmentedTabs(listOf(t("সিস্টেম", "System"), t("লাইট", "Light"), t("ডার্ক", "Dark")), mode.ordinal, { i ->
                val m = ThemeMode.entries[i]
                Graph.themeMode.value = m
                Graph.prefs.themeMode = m
            })
            RowDivider(Modifier.padding(vertical = 4.dp))
            T(t("ভাষা", "Language"), 16.sp, FontWeight.Bold)
            LangPills()
            RowDivider(Modifier.padding(vertical = 4.dp))
            ToggleRow(
                t("অ্যাপ লক", "App lock"),
                if (canLock) t("অ্যাপে ফিরলে ফিঙ্গারপ্রিন্ট/ফোনের পিন লাগবে", "Fingerprint/phone PIN needed when you return") else t("ফোনে স্ক্রিন লক চালু থাকলে ব্যবহার করা যাবে", "Available when the phone has a screen lock"),
                lock,
                { lock = it; Graph.prefs.appLock = it },
                enabled = canLock,
            )
        }
    }
}
