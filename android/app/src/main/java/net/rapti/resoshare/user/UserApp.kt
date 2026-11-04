package net.rapti.resoshare.user

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import net.rapti.resoshare.Graph
import net.rapti.resoshare.USER_IDLE_MS
import net.rapti.resoshare.core.i18n.t
import net.rapti.resoshare.core.net.Res
import net.rapti.resoshare.core.net.call
import net.rapti.resoshare.core.session.Session
import net.rapti.resoshare.core.ui.BottomBar
import net.rapti.resoshare.core.ui.BrandLogo
import net.rapti.resoshare.core.ui.NavItem
import net.rapti.resoshare.core.ui.ResoIcons
import net.rapti.resoshare.core.ui.theme.Brand
import net.rapti.resoshare.core.ui.theme.Reso
import net.rapti.resoshare.data.api.RefreshRequest

object Routes {
    const val SPLASH = "splash"
    const val LOGIN = "login"
    const val FORGOT = "forgot"
    const val REGISTER = "register"
    const val FORCE_PASSWORD = "force_password"
    const val HOME = "home"
    const val BUY = "buy"
    const val CHART = "chart"
    const val NOTICES = "notices"
    const val PROFILE = "profile"
    const val CERTIFICATE = "certificate"
    const val RULES = "rules"
    const val ORDERS = "orders"
    const val NOMINEES = "nominees"
    const val KYC = "kyc"
    const val PASSWORD = "password"
    const val SETTINGS = "settings"
    val TABS = setOf(HOME, CHART, NOTICES, PROFILE)
    /** Screens that keep the bottom bar visible (v2 design). */
    val WITH_BAR = TABS + setOf(CERTIFICATE, RULES, ORDERS)
}

/** Navigate to a bottom tab, keeping each tab's state. */
fun NavHostController.tab(route: String) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}

fun NavHostController.toLoggedIn(mustChangePassword: Boolean) {
    Session.idleTimeoutMs = USER_IDLE_MS
    navigate(if (mustChangePassword) Routes.FORCE_PASSWORD else Routes.HOME) {
        popUpTo(0) { inclusive = true }
        launchSingleTop = true
    }
}

private const val ANIM = 240

private fun AnimatedContentTransitionScope<NavBackStackEntry>.isTabSwitch() =
    initialState.destination.route in Routes.TABS && targetState.destination.route in Routes.TABS

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UserApp() {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val unread by UserRepo.unread.collectAsState()
    val active by Session.active.collectAsState()

    // Session loss (logout, expiry, revoked) → back to login.
    LaunchedEffect(Unit) {
        var wasActive = Session.isActive
        Session.active.collect { a ->
            if (wasActive && !a) {
                UserRepo.clear()
                nav.navigate(Routes.LOGIN) { popUpTo(0) { inclusive = true } }
            }
            wasActive = a
        }
    }

    val showBar = active && route in Routes.WITH_BAR && !WindowInsets.isImeVisible
    Column(Modifier.fillMaxSize().background(Reso.colors.bg)) {
        NavHost(
            navController = nav,
            startDestination = Routes.SPLASH,
            modifier = Modifier.weight(1f),
            // Light, short transitions keep navigation smooth on low-end phones.
            enterTransition = {
                if (isTabSwitch()) fadeIn(tween(180))
                else slideInHorizontally(tween(ANIM)) { it / 10 } + fadeIn(tween(ANIM))
            },
            exitTransition = { fadeOut(tween(140)) },
            popEnterTransition = { fadeIn(tween(ANIM)) },
            popExitTransition = { slideOutHorizontally(tween(ANIM)) { it / 10 } + fadeOut(tween(ANIM)) },
        ) {
            composable(Routes.SPLASH) { Splash(nav) }
            composable(Routes.LOGIN) { LoginScreen(nav) }
            composable(Routes.FORGOT) { ForgotPasswordScreen(nav) }
            composable(Routes.REGISTER) { RegisterScreen(nav) }
            composable(Routes.FORCE_PASSWORD) { ChangePasswordScreen(nav, forced = true) }
            composable(Routes.HOME) { HomeScreen(nav) }
            composable(Routes.BUY) { BuyScreen(nav) }
            composable(Routes.CHART) { ChartScreen(nav) }
            composable(Routes.NOTICES) { NoticesScreen(nav) }
            composable(Routes.PROFILE) { ProfileScreen(nav) }
            composable(Routes.CERTIFICATE) { CertificateScreen(nav) }
            composable(Routes.RULES) { RulesScreen(nav) }
            composable(Routes.ORDERS) { OrdersScreen(nav) }
            composable(Routes.NOMINEES) { NomineeEditScreen(nav) }
            composable(Routes.KYC) { KycResubmitScreen(nav) }
            composable(Routes.PASSWORD) { ChangePasswordScreen(nav, forced = false) }
            composable(Routes.SETTINGS) { UserSettingsScreen(nav) }
        }
        AnimatedVisibility(
            visible = showBar,
            enter = slideInVertically(tween(220)) { it } + fadeIn(),
            exit = slideOutVertically(tween(180)) { it } + fadeOut(),
        ) {
            BottomBar(
                items = listOf(
                    NavItem(Routes.HOME, t("হোম", "Home"), ResoIcons.Home),
                    NavItem(Routes.CHART, t("চার্ট", "Chart"), ResoIcons.Chart),
                    NavItem(Routes.NOTICES, t("নোটিশ", "Notices"), ResoIcons.Bell, badge = unread),
                    NavItem(Routes.PROFILE, t("প্রোফাইল", "Profile"), ResoIcons.User),
                ),
                selected = route,
                onSelect = { nav.tab(it) },
                fab = NavItem(Routes.BUY, t("শেয়ার কিনুন", "Buy shares"), ResoIcons.Plus),
                onFab = { nav.navigate(Routes.BUY) { launchSingleTop = true } },
            )
        }
    }
}

/** Restores a saved session (refresh token) or shows login. */
@Composable
private fun Splash(nav: NavHostController) {
    LaunchedEffect(Unit) {
        val rt = Graph.tokens.refreshToken
        if (rt == null || Graph.tokens.role == "admin") {
            nav.navigate(Routes.LOGIN) { popUpTo(0) { inclusive = true } }
            return@LaunchedEffect
        }
        val api = Graph.shareholderApi
        when (val tk = call { api.refresh(RefreshRequest(rt)) }) {
            is Res.Ok -> {
                Graph.tokens.set(tk.value.accessToken, tk.value.refreshToken)
                when (val me = call { api.me() }) {
                    is Res.Ok -> {
                        Session.start()
                        nav.toLoggedIn(me.value.mustChangePassword)
                    }
                    is Res.Err -> {
                        if (me.error.code != "NETWORK") Graph.tokens.clear()
                        nav.navigate(Routes.LOGIN) { popUpTo(0) { inclusive = true } }
                    }
                }
            }
            is Res.Err -> {
                // Network trouble keeps the token for the next start; auth failures drop it.
                if (tk.error.code != "NETWORK") Graph.tokens.clear()
                nav.navigate(Routes.LOGIN) { popUpTo(0) { inclusive = true } }
            }
        }
    }
    Box(Modifier.fillMaxSize().background(Brand.Navy), contentAlignment = Alignment.Center) { BrandLogo(72.dp) }
}
