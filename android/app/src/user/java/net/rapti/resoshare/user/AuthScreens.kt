package net.rapti.resoshare.user

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import kotlinx.coroutines.launch
import net.rapti.resoshare.Graph
import net.rapti.resoshare.Toaster
import net.rapti.resoshare.core.format.Bn
import net.rapti.resoshare.core.format.V
import net.rapti.resoshare.core.net.Res
import net.rapti.resoshare.core.net.call
import net.rapti.resoshare.core.session.Session
import net.rapti.resoshare.core.ui.BrandLogo
import net.rapti.resoshare.core.ui.DemoBadge
import net.rapti.resoshare.core.ui.Field
import net.rapti.resoshare.core.ui.IconDisc
import net.rapti.resoshare.core.ui.InfoBox
import net.rapti.resoshare.core.ui.LinkText
import net.rapti.resoshare.core.ui.PrimaryButton
import net.rapti.resoshare.core.ui.ResoCard
import net.rapti.resoshare.core.ui.ResoIcons
import net.rapti.resoshare.core.ui.ScreenHeader
import net.rapti.resoshare.core.ui.SecondaryButton
import net.rapti.resoshare.core.ui.SheetShape
import net.rapti.resoshare.core.ui.T
import net.rapti.resoshare.core.ui.Tone
import net.rapti.resoshare.core.ui.theme.Brand
import net.rapti.resoshare.core.ui.theme.Reso
import net.rapti.resoshare.data.api.LoginRequest
import net.rapti.resoshare.data.api.PasswordChange
import net.rapti.resoshare.data.api.PublicInfo

@Composable
fun LoginScreen(nav: NavHostController) {
    val c = Reso.colors
    val scope = rememberCoroutineScope()
    var id by rememberSaveable { mutableStateOf(nav.previousBackStackEntry?.savedStateHandle?.get<String>("mobile") ?: "") }
    var pass by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        nav.currentBackStackEntry?.savedStateHandle?.get<String>("mobile")?.let { id = it }
    }

    fun submit() {
        if (loading) return
        error = null
        if (id.isBlank() || pass.isEmpty()) { error = "মোবাইল/ইমেইল ও পাসওয়ার্ড দিন"; return }
        val identifier = if (id.contains('@')) id.trim() else V.normalizeMobile(id)
        loading = true
        scope.launch {
            when (val r = call { Graph.shareholderApi.login(LoginRequest(identifier, pass, Graph.prefs.deviceId)) }) {
                is Res.Ok -> {
                    Graph.tokens.set(r.value.tokens.accessToken, r.value.tokens.refreshToken)
                    pass = ""
                    Session.start()
                    nav.toLoggedIn(r.value.me.mustChangePassword)
                }
                is Res.Err -> error = r.error.message
            }
            loading = false
        }
    }

    Column(Modifier.fillMaxSize().background(c.surface).verticalScroll(rememberScrollState()).imePadding()) {
        Column(
            Modifier.fillMaxWidth().background(c.header).statusBarsPadding().padding(start = 28.dp, end = 28.dp, top = 40.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(26.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BrandLogo(44.dp)
                Column {
                    T("Resonance Automation", 19.sp, FontWeight.Bold, Color.White)
                    T("শেয়ারহোল্ডার পোর্টাল", 14.sp, color = Brand.HeaderMuted)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                T("আপনার শেয়ার,", 34.sp, FontWeight.Bold, Color.White, lineHeight = 44.sp)
                T("এক জায়গায়", 34.sp, FontWeight.Bold, Brand.Amber, lineHeight = 44.sp)
                T("রেজিস্ট্রেশন করুন, শেয়ার কিনুন এবং নিজের প্রোফাইলে সব হিসাব দেখুন।", 16.sp, color = Brand.HeaderMuted)
            }
            if (Graph.isDemo) DemoBadge()
        }
        Column(
            Modifier.fillMaxWidth()
                .drawBehind { drawRect(c.header, size = androidx.compose.ui.geometry.Size(size.width, 28.dp.toPx())) }
                .clip(SheetShape).background(c.surface)
                .padding(horizontal = 28.dp, vertical = 30.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            T("লগইন করুন", 22.sp, FontWeight.Bold)
            if (Graph.isDemo) {
                InfoBox("মোবাইল: ০১৯১১০০০১২৪ · পাসওয়ার্ড: Demo@1234\nঅথবা নতুন রেজিস্ট্রেশন করে দেখুন।", title = "ডেমো লগইন", tone = Tone.CASH)
            }
            Field("মোবাইল নম্বর বা ইমেইল", id, { id = it; error = null }, placeholder = "০১৭XXXXXXXX", keyboard = KeyboardType.Email, maxLength = 254)
            Field("পাসওয়ার্ড", pass, { pass = it; error = null }, placeholder = "আপনার পাসওয়ার্ড", password = true, ime = ImeAction.Done, onDone = ::submit, maxLength = 128)
            if (error != null) InfoBox(error!! + "\nবারবার ভুল দিলে নিরাপত্তার জন্য কিছুক্ষণ লগইন বন্ধ থাকে। পাসওয়ার্ড মনে না থাকলে হটলাইনে যোগাযোগ করুন।", tone = Tone.DANGER)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                LinkText("পাসওয়ার্ড ভুলে গেছেন?", { nav.navigate(Routes.FORGOT) })
            }
            PrimaryButton("লগইন করুন", ::submit, loading = loading)
            SecondaryButton("নতুন শেয়ারহোল্ডার? রেজিস্ট্রেশন করুন", { nav.navigate(Routes.REGISTER) })
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
fun ForgotPasswordScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    var info by remember { mutableStateOf<PublicInfo?>(null) }
    LaunchedEffect(Unit) { (call { Graph.shareholderApi.publicInfo() } as? Res.Ok)?.let { info = it.value } }
    val hotline = info?.hotline ?: "01617-345534"
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("পাসওয়ার্ড ভুলে গেছেন?", onBack = { nav.popBackStack() })
        LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                ResoCard {
                    IconDisc(ResoIcons.Shield, Reso.colors.warnBg, Reso.colors.brown, 52.dp, 26.dp)
                    Spacer(Modifier.height(12.dp))
                    T("নিরাপত্তার জন্য পাসওয়ার্ড রিসেট অফিস থেকে করা হয়", 18.sp, FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    T(
                        "আপনার শেয়ার ও টাকার নিরাপত্তার জন্য অ্যাপ থেকে নিজে নিজে পাসওয়ার্ড রিসেট করা যায় না। " +
                            "হটলাইনে ফোন করুন বা অফিসে আসুন। পরিচয় যাচাইয়ের পর অ্যাডমিন একটি অস্থায়ী পাসওয়ার্ড দেবেন, " +
                            "প্রথম লগইনে সেটি বদলে নিতে হবে।",
                        15.sp, color = Reso.colors.textSoft, lineHeight = 24.sp,
                    )
                }
            }
            item {
                ResoCard {
                    T("হটলাইন", 14.sp, color = Reso.colors.muted)
                    T(Bn.digits(hotline), 22.sp, FontWeight.Bold)
                    if (!info?.officeAddress.isNullOrBlank()) {
                        Spacer(Modifier.height(8.dp))
                        T("অফিস: ${info!!.officeAddress}", 15.sp, color = Reso.colors.textSoft)
                    }
                    Spacer(Modifier.height(14.dp))
                    PrimaryButton("হটলাইনে কল করুন", {
                        runCatching { ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + hotline.filter { it.isDigit() || it == '+' }))) }
                    }, icon = ResoIcons.Phone)
                }
            }
            item { InfoBox("কেউ ফোন করে আপনার পাসওয়ার্ড বা OTP চাইলে কখনো দেবেন না। কোম্পানি কখনো পাসওয়ার্ড চায় না।", tone = Tone.WARN, title = "সতর্কতা") }
        }
    }
}

@Composable
fun ChangePasswordScreen(nav: NavHostController, forced: Boolean) {
    val scope = rememberCoroutineScope()
    var cur by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var conf by remember { mutableStateOf("") }
    var errs by remember { mutableStateOf(mapOf<String, String>()) }
    var loading by remember { mutableStateOf(false) }

    fun submit() {
        val e = mutableMapOf<String, String>()
        if (cur.isEmpty()) e["cur"] = "বর্তমান পাসওয়ার্ড দিন"
        V.password(new)?.let { e["new"] = it }
        if (new.isNotEmpty() && new == cur) e["new"] = "নতুন পাসওয়ার্ড আগেরটির থেকে আলাদা হতে হবে"
        V.confirm(new, conf)?.let { e["conf"] = it }
        errs = e
        if (e.isNotEmpty() || loading) return
        loading = true
        scope.launch {
            when (val r = call { Graph.shareholderApi.changePassword(PasswordChange(cur, new)) }) {
                is Res.Ok -> {
                    cur = ""; new = ""; conf = ""
                    Toaster.show("পাসওয়ার্ড বদলানো হয়েছে। অন্য সব ডিভাইস থেকে লগআউট হয়েছে।")
                    if (forced) nav.toLoggedIn(false) else nav.popBackStack()
                }
                is Res.Err -> errs = buildMap {
                    r.error.fields["currentPassword"]?.let { put("cur", it) }
                    r.error.fields["newPassword"]?.let { put("new", it) }
                    if (isEmpty()) put("form", r.error.message)
                }
            }
            loading = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("পাসওয়ার্ড পরিবর্তন", onBack = if (forced) null else ({ nav.popBackStack() }))
        LazyColumn(
            Modifier.imePadding(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (forced) item { InfoBox("আপনাকে একটি অস্থায়ী পাসওয়ার্ড দেওয়া হয়েছে। নিরাপত্তার জন্য এখনই নতুন পাসওয়ার্ড দিন।", tone = Tone.WARN) }
            item { Field("বর্তমান পাসওয়ার্ড", cur, { cur = it }, password = true, error = errs["cur"], maxLength = 128) }
            item { Field("নতুন পাসওয়ার্ড", new, { new = it }, password = true, error = errs["new"], placeholder = "কমপক্ষে ৮ অক্ষর, অক্ষর ও সংখ্যা", maxLength = 128) }
            item { Field("নতুন পাসওয়ার্ড আবার দিন", conf, { conf = it }, password = true, error = errs["conf"], ime = ImeAction.Done, onDone = ::submit, maxLength = 128) }
            errs["form"]?.let { item { InfoBox(it, tone = Tone.DANGER) } }
            item { PrimaryButton("পাসওয়ার্ড বদলান", ::submit, loading = loading) }
            if (forced) item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    LinkText("লগআউট", { scope.launch { net.rapti.resoshare.Auth.logout(null) } }, color = Reso.colors.red)
                }
            }
        }
    }
}

