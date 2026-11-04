package net.rapti.resoshare

import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.compose.setContent
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
import androidx.compose.foundation.layout.fillMaxSize
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
import net.rapti.resoshare.role.RoleRoot
import net.rapti.resoshare.role.RoleSpec

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
                Auth.logoutAsync("নিষ্ক্রিয় থাকায় নিরাপত্তার জন্য লগআউট করা হয়েছে।")
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
        enableEdgeToEdge()
        ProcessLifecycleOwner.get().lifecycle.addObserver(lifecycleWatcher)
        Session.idleTimeoutMs = RoleSpec.defaultIdleTimeoutMs

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
                            Auth.logout("নিষ্ক্রিয় থাকায় নিরাপত্তার জন্য লগআউট করা হয়েছে।")
                        }
                    }
                }
                Box(Modifier.fillMaxSize().background(Reso.colors.bg)) {
                    val blocked = verdict == Integrity.Verdict.TAMPERED || verdict == Integrity.Verdict.DEBUGGER ||
                        (verdict == Integrity.Verdict.ROOTED && RoleSpec.blockRootedDevices)
                    if (blocked) {
                        BlockedScreen(verdict)
                    } else {
                        RoleRoot()
                        var showRootWarning by remember { mutableStateOf(verdict == Integrity.Verdict.ROOTED && !Graph.prefs.rootWarned) }
                        if (showRootWarning) RootWarning { Graph.prefs.rootWarned = true; showRootWarning = false }
                        val locked by Session.locked.collectAsState()
                        AnimatedVisibility(locked, enter = fadeIn(), exit = fadeOut()) { LockOverlay(onUnlock = ::promptUnlock) }
                    }
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
                .setTitle("অ্যাপ আনলক করুন")
                .setSubtitle("ফিঙ্গারপ্রিন্ট, ফেস বা ফোনের পিন/প্যাটার্ন দিন")
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
        T("অ্যাপ লক করা আছে", 24.sp, FontWeight.Bold, Color.White)
        T("আপনার তথ্য সুরক্ষিত রাখতে আবার পরিচয় নিশ্চিত করুন।", 15.sp, color = Brand.HeaderMuted, align = TextAlign.Center)
        PrimaryButton("আনলক করুন", onUnlock, Modifier.widthIn(max = 320.dp), icon = ResoIcons.Fingerprint)
        SecondaryButton("লগআউট", { scope.launch { Auth.logout(null) } }, Modifier.widthIn(max = 320.dp), color = Color.White)
    }
}

@Composable
private fun BlockedScreen(verdict: Integrity.Verdict) {
    val (title, body) = when (verdict) {
        Integrity.Verdict.TAMPERED -> "অ্যাপটি পরিবর্তিত" to "এই অ্যাপটি আসল নয় বা পরিবর্তন করা হয়েছে। নিরাপত্তার জন্য এটি চালু হবে না। কোম্পানির দেওয়া আসল অ্যাপটি ইনস্টল করুন।"
        Integrity.Verdict.DEBUGGER -> "অনিরাপদ পরিবেশ" to "ডিবাগার সংযুক্ত থাকায় অ্যাপটি চালু করা যাচ্ছে না।"
        else -> "রুট করা ডিভাইস" to "নিরাপত্তার কারণে অ্যাডমিন অ্যাপ রুট করা ডিভাইসে চলে না। অন্য একটি নিরাপদ ফোন ব্যবহার করুন।"
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
        title = { T("নিরাপত্তা সতর্কতা", 19.sp, FontWeight.Bold) },
        text = { T("আপনার ফোনটি রুট করা মনে হচ্ছে। এতে অন্য অ্যাপ আপনার তথ্য চুরি করতে পারে। সম্ভব হলে রুট ছাড়া ফোনে অ্যাপটি ব্যবহার করুন।", 15.sp, color = c.textSoft) },
        confirmButton = { TextButton(onClick = onDismiss) { T("বুঝেছি", 16.sp, FontWeight.Bold, c.ink) } },
    )
}
