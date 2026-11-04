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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
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
import net.rapti.resoshare.core.net.Res
import net.rapti.resoshare.core.net.call
import net.rapti.resoshare.core.session.Session
import net.rapti.resoshare.core.ui.BottomBar
import net.rapti.resoshare.core.ui.BrandLogo
import net.rapti.resoshare.core.ui.NavItem
import net.rapti.resoshare.core.ui.ResoIcons
import net.rapti.resoshare.core.ui.theme.Brand
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
    const val PROFILE = "profile"
    const val CERTIFICATE = "certificate"
    const val NOTICES = "notices"
    const val RULES = "rules"
    const val ORDERS = "orders"
    const val NOMINEES = "nominees"
    const val KYC = "kyc"
    const val PASSWORD = "password"
    val TABS = setOf(HOME, BUY, CHART, PROFILE)
    val PUBLIC = setOf(SPLASH, LOGIN, FORGOT, REGISTER, RULES)
}

/** Navigate to a bottom tab, keeping each tab's state. */
fun NavHostController.tab(route: String) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}

fun NavHostController.toLoggedIn(mustChangePassword: Boolean) = navigate(if (mustChangePassword) Routes.FORCE_PASSWORD else Routes.HOME) {
    popUpTo(0) { inclusive = true }
    launchSingleTop = true
}

private const val ANIM = 320

private fun AnimatedContentTransitionScope<NavBackStackEntry>.isTabSwitch() =
    initialState.destination.route in Routes.TABS && targetState.destination.route in Routes.TABS

@Composable
fun UserApp() {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val unread by UserRepo.unread.collectAsState()

    // Session loss (logout, expiry, revoked) → back to login.
    LaunchedEffect(Unit) {
        var wasActive = Session.isActive
        Session.active.collect { active ->
            if (wasActive && !active) {
                UserRepo.clear()
                nav.navigate(Routes.LOGIN) { popUpTo(0) { inclusive = true } }
            }
            wasActive = active
        }
    }

    Column(Modifier.fillMaxSize()) {
        NavHost(
            navController = nav,
            startDestination = Routes.SPLASH,
            modifier = Modifier.weight(1f),
            enterTransition = {
                if (isTabSwitch()) fadeIn(tween(220))
                else slideInHorizontally(tween(ANIM)) { it / 3 } + fadeIn(tween(ANIM))
            },
            exitTransition = {
                if (isTabSwitch()) fadeOut(tween(160))
                else slideOutHorizontally(tween(ANIM)) { -it / 6 } + fadeOut(tween(ANIM / 2))
            },
            popEnterTransition = { slideInHorizontally(tween(ANIM)) { -it / 6 } + fadeIn(tween(ANIM)) },
            popExitTransition = { slideOutHorizontally(tween(ANIM)) { it / 3 } + fadeOut(tween(ANIM / 2)) },
        ) {
            composable(Routes.SPLASH) { Splash(nav) }
            composable(Routes.LOGIN) { LoginScreen(nav) }
            composable(Routes.FORGOT) { ForgotPasswordScreen(nav) }
            composable(Routes.REGISTER) { RegisterScreen(nav) }
            composable(Routes.FORCE_PASSWORD) { ChangePasswordScreen(nav, forced = true) }
            composable(Routes.HOME) { HomeScreen(nav) }
            composable(Routes.BUY) { BuyScreen(nav) }
            composable(Routes.CHART) { ChartScreen(nav) }
            composable(Routes.PROFILE) { ProfileScreen(nav) }
            composable(Routes.CERTIFICATE) { CertificateScreen(nav) }
            composable(Routes.NOTICES) { NoticesScreen(nav) }
            composable(Routes.RULES) { RulesScreen(nav) }
            composable(Routes.ORDERS) { OrdersScreen(nav) }
            composable(Routes.NOMINEES) { NomineeEditScreen(nav) }
            composable(Routes.KYC) { KycResubmitScreen(nav) }
            composable(Routes.PASSWORD) { ChangePasswordScreen(nav, forced = false) }
        }
        AnimatedVisibility(
            visible = route in Routes.TABS,
            enter = slideInVertically(tween(260)) { it } + fadeIn(),
            exit = slideOutVertically(tween(200)) { it } + fadeOut(),
        ) {
            BottomBar(
                items = listOf(
                    NavItem(Routes.HOME, "হোম", ResoIcons.Home, badge = 0),
                    NavItem(Routes.BUY, "শেয়ার কিনুন", ResoIcons.PlusCircle),
                    NavItem(Routes.CHART, "চার্ট", ResoIcons.Chart),
                    NavItem(Routes.PROFILE, "প্রোফাইল", ResoIcons.User, badge = unread),
                ),
                selected = route,
                onSelect = { nav.tab(it) },
            )
        }
    }
}

/** Restores a saved session (refresh token) or shows login. */
@Composable
private fun Splash(nav: NavHostController) {
    LaunchedEffect(Unit) {
        val rt = Graph.tokens.refreshToken
        if (rt == null) {
            nav.navigate(Routes.LOGIN) { popUpTo(0) { inclusive = true } }
            return@LaunchedEffect
        }
        val api = Graph.shareholderApi
        when (val t = call { api.refresh(RefreshRequest(rt)) }) {
            is Res.Ok -> {
                Graph.tokens.set(t.value.accessToken, t.value.refreshToken)
                when (val me = call { api.me() }) {
                    is Res.Ok -> {
                        Session.start()
                        nav.toLoggedIn(me.value.mustChangePassword)
                    }
                    is Res.Err -> {
                        Graph.tokens.clear()
                        nav.navigate(Routes.LOGIN) { popUpTo(0) { inclusive = true } }
                    }
                }
            }
            is Res.Err -> {
                // Network trouble keeps the token for the next start; auth failures drop it.
                if (t.error.code != "NETWORK") Graph.tokens.clear()
                nav.navigate(Routes.LOGIN) { popUpTo(0) { inclusive = true } }
            }
        }
    }
    Box(Modifier.fillMaxSize().background(Brand.Navy), contentAlignment = Alignment.Center) { BrandLogo(72.dp) }
}

