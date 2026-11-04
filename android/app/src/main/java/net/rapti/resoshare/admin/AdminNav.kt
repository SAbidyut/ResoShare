package net.rapti.resoshare.admin

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.launch
import net.rapti.resoshare.Graph
import net.rapti.resoshare.core.net.Res
import net.rapti.resoshare.core.net.call
import net.rapti.resoshare.core.session.Session
import net.rapti.resoshare.core.ui.BottomBar
import net.rapti.resoshare.core.ui.BrandLogo
import net.rapti.resoshare.core.ui.ErrorBlock
import net.rapti.resoshare.core.ui.NavItem
import net.rapti.resoshare.core.ui.ResoIcons
import net.rapti.resoshare.core.ui.SecondaryButton
import net.rapti.resoshare.core.ui.T
import net.rapti.resoshare.core.ui.theme.Brand
import net.rapti.resoshare.core.ui.theme.Reso
import net.rapti.resoshare.data.api.Perm
import net.rapti.resoshare.data.api.RefreshRequest
import net.rapti.resoshare.data.api.StaffMe

object Routes {
    const val LOGIN = "login"
    const val FORCE_PW = "force_pw"
    const val DASH = "dash"
    const val HOLDERS = "holders"
    const val APPROVALS = "approvals"
    const val MORE = "more"
    const val HOLDER = "holder/{id}"
    fun holder(id: String) = "holder/$id"
    const val TRANSACTIONS = "transactions"
    const val RATE = "rate"
    const val DIVIDEND = "dividend"
    const val REPORTS = "reports"
    const val NOTICES = "notices"
    const val RULES = "rules"
    const val STAFF = "staff"
    const val AUDIT = "audit"
    const val SETTINGS = "settings"
    const val PASSWORD = "password"
    val TABS = setOf(DASH, HOLDERS, APPROVALS, MORE)
}

/** Small navigation facade handed to screens. */
class AdminNav(private val nav: NavHostController) {
    fun go(route: String) = nav.navigate(route) { launchSingleTop = true }
    fun back() { if (!nav.popBackStack()) tab(Routes.DASH) }
    fun tab(route: String) = nav.navigate(route) {
        popUpTo(Routes.DASH) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
    fun enterApp() = nav.navigate(Routes.DASH) { popUpTo(nav.graph.id) { inclusive = true } }
    fun toLogin() = nav.navigate(Routes.LOGIN) { popUpTo(nav.graph.id) { inclusive = true } }
    fun toForcedPassword() = nav.navigate(Routes.FORCE_PW) { popUpTo(nav.graph.id) { inclusive = true } }
}

private sealed interface Boot {
    data object Checking : Boot
    data object Login : Boot
    data object Main : Boot
    data object ForcePw : Boot
    data class Offline(val message: String) : Boot
}

/** Called after a successful sign-in or session restore. */
suspend fun afterSignIn(staff: StaffMe) {
    AdminSession.me.value = staff
    Session.start()
    if (staff.mustChangePassword) return
    if (Perm.VIEW in staff.permissions) {
        (call { Graph.adminApi.settings() } as? Res.Ok)?.value?.let {
            Session.idleTimeoutMs = it.security.autoLogoutMinutes.coerceIn(3, 60) * 60_000L
            AdminSession.companyName = it.company.name
        }
        refreshPending()
    }
}

suspend fun refreshPending() {
    (call { Graph.adminApi.dashboard() } as? Res.Ok)?.value?.let { AdminSession.pending.value = it.pending.payments + it.pending.kyc }
}

private suspend fun restore(): Boot {
    val rt = Graph.tokens.refreshToken ?: return Boot.Login
    return when (val r = call { Graph.adminApi.refresh(RefreshRequest(rt)) }) {
        is Res.Ok -> {
            Graph.tokens.set(r.value.accessToken, r.value.refreshToken)
            when (val m = call { Graph.adminApi.me() }) {
                is Res.Ok -> {
                    afterSignIn(m.value)
                    if (m.value.mustChangePassword) Boot.ForcePw else Boot.Main
                }
                is Res.Err -> if (m.error.code in OFFLINE) Boot.Offline(m.error.message) else { Graph.tokens.clear(); Boot.Login }
            }
        }
        is Res.Err -> if (r.error.code in OFFLINE) Boot.Offline(r.error.message) else { Graph.tokens.clear(); Boot.Login }
    }
}

private val OFFLINE = setOf("NETWORK", "TLS", "SERVER_ERROR", "TOO_MANY_REQUESTS")

@Composable
fun AdminRoot() {
    var boot by remember { mutableStateOf<Boot>(Boot.Checking) }
    var attempt by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(attempt) { boot = Boot.Checking; boot = restore() }
    when (val b = boot) {
        Boot.Checking -> Splash()
        is Boot.Offline -> Column(
            Modifier.fillMaxSize().background(Reso.colors.bg).padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BrandLogo(56.dp)
            ErrorBlock(b.message, { attempt++ })
            SecondaryButton("আবার লগইন করুন", {
                scope.launch { Graph.tokens.clear(); boot = Boot.Login }
            }, Modifier.widthIn(max = 260.dp), height = 48.dp)
        }
        else -> AdminNavHost(
            when (b) {
                Boot.Main -> Routes.DASH
                Boot.ForcePw -> Routes.FORCE_PW
                else -> Routes.LOGIN
            },
        )
    }
}

@Composable
private fun Splash() {
    Column(
        Modifier.fillMaxSize().background(Brand.Navy),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BrandLogo(72.dp)
        T("Resonance Automation", 20.sp, FontWeight.Bold, androidx.compose.ui.graphics.Color.White, align = TextAlign.Center)
        T("অ্যাডমিন প্যানেল", 14.sp, color = Brand.HeaderMuted)
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun AdminNavHost(start: String) {
    val nav = rememberNavController()
    val an = remember(nav) { AdminNav(nav) }
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val pending by AdminSession.pending.collectAsState()

    // Return to login whenever the session ends (logout, idle timeout, revoked on server).
    val active by Session.active.collectAsState()
    var wasActive by remember { mutableStateOf(Session.isActive) }
    LaunchedEffect(active) {
        if (wasActive && !active) {
            AdminSession.clear()
            an.toLogin()
        }
        wasActive = active
    }

    val showBar = route in Routes.TABS && !WindowInsets.isImeVisible
    val insets = WindowInsets.ime.union(WindowInsets.navigationBars).only(WindowInsetsSides.Bottom)
    Column(Modifier.fillMaxSize().background(if (showBar) Reso.colors.surface else Reso.colors.bg).windowInsetsPadding(insets)) {
        Box(Modifier.weight(1f)) {
            NavHost(
                navController = nav,
                startDestination = start,
                modifier = Modifier.fillMaxSize().background(Reso.colors.bg),
                enterTransition = { slideInHorizontally(tween(300)) { it / 4 } + fadeIn(tween(300)) },
                exitTransition = { slideOutHorizontally(tween(300)) { -it / 10 } + fadeOut(tween(200)) },
                popEnterTransition = { slideInHorizontally(tween(300)) { -it / 10 } + fadeIn(tween(300)) },
                popExitTransition = { slideOutHorizontally(tween(300)) { it / 4 } + fadeOut(tween(200)) },
            ) { graph(an) }
        }
        AnimatedVisibility(showBar, enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
            BottomBar(
                listOf(
                    NavItem(Routes.DASH, "ড্যাশবোর্ড", ResoIcons.Grid),
                    NavItem(Routes.HOLDERS, "শেয়ারহোল্ডার", ResoIcons.Users),
                    NavItem(Routes.APPROVALS, "অনুমোদন", ResoIcons.CheckCircle, pending),
                    NavItem(Routes.MORE, "আরও", ResoIcons.More),
                ),
                route,
                onSelect = { an.tab(it) },
                adminStyle = true,
            )
        }
    }
}

private fun NavGraphBuilder.tab(route: String, content: @Composable () -> Unit) =
    composable(
        route,
        enterTransition = { fadeIn(tween(220)) },
        exitTransition = { fadeOut(tween(160)) },
        popEnterTransition = { fadeIn(tween(220)) },
        popExitTransition = { fadeOut(tween(160)) },
    ) { content() }

private fun NavGraphBuilder.graph(an: AdminNav) {
    composable(Routes.LOGIN) { LoginScreen(onSignedIn = { forced -> if (forced) an.toForcedPassword() else an.enterApp() }) }
    composable(Routes.FORCE_PW) { ChangePasswordScreen(forced = true, onDone = { an.enterApp() }, onBack = {}) }
    tab(Routes.DASH) { DashboardScreen(an) }
    tab(Routes.HOLDERS) { ShareholdersScreen(an) }
    tab(Routes.APPROVALS) { ApprovalsScreen() }
    tab(Routes.MORE) { MoreScreen(an) }
    composable(Routes.HOLDER, arguments = listOf(navArgument("id") { type = NavType.StringType })) {
        ShareholderDetailScreen(it.arguments?.getString("id").orEmpty(), an)
    }
    composable(Routes.TRANSACTIONS) { TransactionsScreen(an) }
    composable(Routes.RATE) { RateScreen(an) }
    composable(Routes.DIVIDEND) { DividendScreen(an) }
    composable(Routes.REPORTS) { ReportsScreen(an) }
    composable(Routes.NOTICES) { NoticesScreen(an) }
    composable(Routes.RULES) { RulesEditorScreen(an) }
    composable(Routes.STAFF) { StaffScreen(an) }
    composable(Routes.AUDIT) { AuditScreen(an) }
    composable(Routes.SETTINGS) { SettingsScreen(an) }
    composable(Routes.PASSWORD) { ChangePasswordScreen(forced = false, onDone = { an.back() }, onBack = { an.back() }) }
}
