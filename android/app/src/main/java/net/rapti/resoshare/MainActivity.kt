package net.rapti.resoshare

import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import net.rapti.resoshare.core.i18n.t
import net.rapti.resoshare.core.security.Integrity
import net.rapti.resoshare.core.session.Session
import net.rapti.resoshare.core.ui.BrandLogo
import net.rapti.resoshare.core.ui.IconDisc
import net.rapti.resoshare.core.ui.PrimaryButton
import net.rapti.resoshare.core.ui.ResoIcons
import net.rapti.resoshare.core.ui.SecondaryButton
import net.rapti.resoshare.core.ui.T
import net.rapti.resoshare.core.ui.theme.Brand
import net.rapti.resoshare.core.ui.theme.Reso
import net.rapti.resoshare.core.ui.theme.ResoTheme

class MainActivity : FragmentActivity() {

    private var backgroundedAt = 0L

    private val lifecycleWatcher = object : DefaultLifecycleObserver {
        override fun onStop(owner: LifecycleOwner) { backgroundedAt = SystemClock.elapsedRealtime() }
        override fun onStart(owner: LifecycleOwner) {
            if (backgroundedAt == 0L) return
            val away = SystemClock.elapsedRealtime() - backgroundedAt
            if (Session.isActive && Graph.prefs.appLock && canUseDeviceAuth() && away > LOCK_AFTER_MS) Session.lock()
            // Being away longer than the idle timeout ends the session as well.
            if (Session.isActive && away > Session.idleTimeoutMs) {
                Auth.logoutAsync(t("নিষ্ক্রিয় থাকায় নিরাপত্তার জন্য লগআউট করা হয়েছে।", "Logged out after inactivity, for your security."))
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        // No screenshots, screen recording or recents thumbnails of financial/KYC data.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) setRecentsScreenshotEnabled(false)
        // Tap-jacking protection: ignore touches while another window overlays ours.
        window.decorView.filterTouchesWhenObscured = true
        applySystemBars(dark = false)
        ProcessLifecycleOwner.get().lifecycle.addObserver(lifecycleWatcher)

        val verdict = Integrity.check(this)

        setContent {
            val mode by Graph.themeMode.collectAsState()
            ResoTheme(mode) {
                val snackbar = remember { SnackbarHostState() }
                LaunchedEffect(Unit) { merge(Toaster.messages, Session.expired).collect { snackbar.showSnackbar(it) } }
                // Idle watchdog.
                LaunchedEffect(Unit) {
                    while (true) {
                        delay(10_000)
                        if (Session.isActive && Session.idleFor() > Session.idleTimeoutMs) {
                            Auth.logout(t("নিষ্ক্রিয় থাকায় নিরাপত্তার জন্য লগআউট করা হয়েছে।", "Logged out after inactivity, for your security."))
                        }
                    }
                }
                // Every screen starts with a navy header → always light status-bar icons;
                // navigation-bar icons follow the app theme.
                val dark = Reso.colors.dark
                LaunchedEffect(dark) { applySystemBars(dark) }
                Box(Modifier.fillMaxSize().background(Reso.colors.bg)) {
                    val blocked = verdict == Integrity.Verdict.TAMPERED || verdict == Integrity.Verdict.DEBUGGER
                    if (blocked) {
                        BlockedScreen(verdict)
                    } else {
                        AppRoot(verdict)
                        var showRootWarning by remember { mutableStateOf(verdict == Integrity.Verdict.ROOTED && !Graph.prefs.rootWarned) }
                        if (showRootWarning) RootWarning { Graph.prefs.rootWarned = true; showRootWarning = false }
                        val locked by Session.locked.collectAsState()
                        AnimatedVisibility(locked, enter = fadeIn(), exit = fadeOut()) { LockOverlay(onUnlock = ::promptUnlock) }
                    }
                    // Solid strip behind the status bar so scrolled content never shows through it.
                    Box(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars).background(Reso.colors.header))
                    SnackbarHost(
                        snackbar,
                        Modifier.align(Alignment.BottomCenter).navigationBarsPadding().imePadding().padding(16.dp).padding(bottom = 64.dp),
                    ) { data ->
                        Box(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Brand.Navy).padding(horizontal = 18.dp, vertical = 14.dp),
                        ) { T(data.visuals.message, 15.sp, color = Color.White) }
                    }
                }
            }
        }
    }

    private fun applySystemBars(dark: Boolean) {
        val clear = android.graphics.Color.TRANSPARENT
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(clear),
            navigationBarStyle = if (dark) SystemBarStyle.dark(clear) else SystemBarStyle.light(clear, clear),
        )
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        Session.touch()
    }

    override fun onDestroy() {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(lifecycleWatcher)
        super.onDestroy()
    }

    fun canUseDeviceAuth(): Boolean =
        BiometricManager.from(this).canAuthenticate(BIOMETRIC_WEAK or DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS

    private fun promptUnlock() {
        if (!canUseDeviceAuth()) { Session.unlock(); return }
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { Session.unlock() }
        })
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(t("অ্যাপ আনলক করুন", "Unlock the app"))
                .setSubtitle(t("ফিঙ্গারপ্রিন্ট, ফেস বা ফোনের পিন/প্যাটার্ন দিন", "Use fingerprint, face or your phone PIN/pattern"))
                .setAllowedAuthenticators(BIOMETRIC_WEAK or DEVICE_CREDENTIAL)
                .build(),
        )
    }

    private companion object { const val LOCK_AFTER_MS = 20_000L }
}

@Composable
private fun LockOverlay(onUnlock: () -> Unit) {
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { onUnlock() }
    Column(
        Modifier.fillMaxSize().background(Brand.Navy).padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BrandLogo(64.dp)
        T(t("অ্যাপ লক করা আছে", "App is locked"), 24.sp, FontWeight.Bold, Color.White)
        T(t("আপনার তথ্য সুরক্ষিত রাখতে আবার পরিচয় নিশ্চিত করুন।", "Confirm it's you to keep your information safe."), 15.sp, color = Brand.HeaderMuted, align = TextAlign.Center)
        PrimaryButton(t("আনলক করুন", "Unlock"), onUnlock, Modifier.widthIn(max = 320.dp), icon = ResoIcons.Fingerprint)
        SecondaryButton(t("লগআউট", "Log out"), { scope.launch { Auth.logout(null) } }, Modifier.widthIn(max = 320.dp), color = Color.White)
    }
}

@Composable
private fun BlockedScreen(verdict: Integrity.Verdict) {
    val (title, body) = when (verdict) {
        Integrity.Verdict.TAMPERED -> t("অ্যাপটি পরিবর্তিত", "App modified") to t("এই অ্যাপটি আসল নয় বা পরিবর্তন করা হয়েছে। নিরাপত্তার জন্য এটি চালু হবে না। কোম্পানির দেওয়া আসল অ্যাপটি ইনস্টল করুন।", "This app is not genuine or has been modified, so it will not run. Install the original app from the company.")
        Integrity.Verdict.DEBUGGER -> t("অনিরাপদ পরিবেশ", "Unsafe environment") to t("ডিবাগার সংযুক্ত থাকায় অ্যাপটি চালু করা যাচ্ছে না।", "The app cannot run while a debugger is attached.")
        else -> t("অনিরাপদ ডিভাইস", "Unsafe device") to t("এই ডিভাইসে অ্যাপটি চালানো নিরাপদ নয়।", "It is not safe to run the app on this device.")
    }
    Column(
        Modifier.fillMaxSize().background(Brand.Navy).padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconDisc(ResoIcons.Shield, Brand.Amber, Brand.Navy, 72.dp, 36.dp)
        T(title, 24.sp, FontWeight.Bold, Color.White, align = TextAlign.Center)
        T(body, 16.sp, color = Brand.HeaderMuted, align = TextAlign.Center)
    }
}

@Composable
private fun RootWarning(onDismiss: () -> Unit) {
    val c = Reso.colors
    AlertDialog(
        onDismissRequest = {},
        containerColor = c.surface,
        icon = { IconDisc(ResoIcons.Shield, c.warnBg, c.brown, 48.dp, 24.dp) },
        title = { T(t("নিরাপত্তা সতর্কতা", "Security warning"), 19.sp, FontWeight.Bold) },
        text = { T(t("আপনার ফোনটি রুট করা মনে হচ্ছে। এতে অন্য অ্যাপ আপনার তথ্য চুরি করতে পারে। সম্ভব হলে রুট ছাড়া ফোনে অ্যাপটি ব্যবহার করুন। (অ্যাডমিন প্যানেল রুট করা ফোনে চলবে না।)", "Your phone appears to be rooted, which lets other apps steal your data. Use an unrooted phone if possible. (The admin panel will not run on rooted phones.)"), 15.sp, color = c.textSoft) },
        confirmButton = { TextButton(onClick = onDismiss) { T(t("বুঝেছি", "I understand"), 16.sp, FontWeight.Bold, c.ink) } },
    )
}
