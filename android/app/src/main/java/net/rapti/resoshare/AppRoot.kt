package net.rapti.resoshare

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.rapti.resoshare.admin.AdminRoot
import net.rapti.resoshare.core.i18n.t
import net.rapti.resoshare.core.security.Integrity
import net.rapti.resoshare.core.session.Session
import net.rapti.resoshare.core.ui.IconDisc
import net.rapti.resoshare.core.ui.ResoIcons
import net.rapti.resoshare.core.ui.SecondaryButton
import net.rapti.resoshare.core.ui.T
import net.rapti.resoshare.core.ui.theme.Brand
import net.rapti.resoshare.user.UserApp

/** Switch between the shareholder and admin portals (only offered on the login screens). */
val LocalSwitchPortal = staticCompositionLocalOf<(String) -> Unit> { {} }

/** Result of the device integrity check done at startup. */
val LocalIntegrity = staticCompositionLocalOf { Integrity.Verdict.OK }

const val USER_IDLE_MS = 15 * 60_000L
const val ADMIN_IDLE_MS = 10 * 60_000L

/**
 * One app, two portals. The shareholder portal is the default; the admin portal is reached
 * from a link on the login screen. A logged-in session always stays in its own portal.
 */
@Composable
fun AppRoot(verdict: Integrity.Verdict) {
    val portal by Graph.portal.collectAsState()
    LaunchedEffect(portal) {
        if (!Session.isActive) Session.idleTimeoutMs = if (portal == "admin") ADMIN_IDLE_MS else USER_IDLE_MS
    }
    CompositionLocalProvider(
        LocalSwitchPortal provides { Graph.switchPortal(it) },
        LocalIntegrity provides verdict,
    ) {
        Crossfade(portal, animationSpec = tween(260), label = "portal") { p ->
            if (p == "admin") {
                if (verdict == Integrity.Verdict.ROOTED) AdminBlockedOnRoot() else AdminRoot()
            } else {
                UserApp()
            }
        }
    }
}

/** The admin portal never runs on a rooted device; the shareholder portal still works. */
@Composable
private fun AdminBlockedOnRoot() {
    Column(
        Modifier.fillMaxSize().background(Brand.Navy).padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconDisc(ResoIcons.Shield, Brand.Amber, Brand.Navy, 72.dp, 36.dp)
        T(t("রুট করা ডিভাইস", "Rooted device"), 24.sp, FontWeight.Bold, Color.White, align = TextAlign.Center)
        T(
            t(
                "নিরাপত্তার কারণে অ্যাডমিন প্যানেল রুট করা ফোনে চলে না। অন্য একটি নিরাপদ ফোন ব্যবহার করুন।",
                "For security, the admin panel does not run on rooted phones. Please use another secure phone.",
            ),
            16.sp, color = Brand.HeaderMuted, align = TextAlign.Center,
        )
        SecondaryButton(t("শেয়ারহোল্ডার লগইনে ফিরুন", "Back to shareholder login"), { Graph.switchPortal("user") }, Modifier.widthIn(max = 320.dp), color = Color.White)
    }
}
