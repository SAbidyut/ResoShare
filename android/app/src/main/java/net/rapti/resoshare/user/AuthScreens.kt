package net.rapti.resoshare.user

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import kotlinx.coroutines.launch
import net.rapti.resoshare.Auth
import net.rapti.resoshare.Graph
import net.rapti.resoshare.LocalSwitchPortal
import net.rapti.resoshare.Toaster
import net.rapti.resoshare.core.format.Bn
import net.rapti.resoshare.core.format.V
import net.rapti.resoshare.core.i18n.AppLang
import net.rapti.resoshare.core.i18n.Lang
import net.rapti.resoshare.core.i18n.t
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
import net.rapti.resoshare.core.ui.TextDisc
import net.rapti.resoshare.core.ui.Tone
import net.rapti.resoshare.core.ui.headerBubble
import net.rapti.resoshare.core.ui.theme.Brand
import net.rapti.resoshare.core.ui.theme.Reso
import net.rapti.resoshare.data.api.LoginRequest
import net.rapti.resoshare.data.api.PasswordChange
import net.rapti.resoshare.data.api.PublicInfo

/** Compact বাংলা | English switch used on headers. */
@Composable
fun LangChip(modifier: Modifier = Modifier) {
    val en = Lang.isEn
    Row(modifier.clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.12f)).padding(3.dp)) {
        listOf(AppLang.BN to "বাংলা", AppLang.EN to "English").forEach { (l, label) ->
            val sel = (l == AppLang.EN) == en
            Box(
                Modifier.clip(RoundedCornerShape(13.dp)).background(if (sel) Brand.Amber else Color.Transparent)
                    .clickable(role = Role.Tab) { Lang.set(l) }.padding(horizontal = 10.dp, vertical = 4.dp),
            ) { T(label, 12.sp, FontWeight.Bold, if (sel) Brand.Navy else Color.White, maxLines = 1) }
        }
    }
}

@Composable
fun LoginScreen(nav: NavHostController) {
    val c = Reso.colors
    val scope = rememberCoroutineScope()
    val switchPortal = LocalSwitchPortal.current
    var id by rememberSaveable { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<PublicInfo?>(null) }

    LaunchedEffect(Unit) {
        nav.currentBackStackEntry?.savedStateHandle?.get<String>("mobile")?.let { id = it }
        (call { Graph.shareholderApi.publicInfo() } as? Res.Ok)?.let { info = it.value }
    }

    fun submit() {
        if (loading) return
        error = null
        if (id.isBlank() || pass.isEmpty()) { error = t("মোবাইল/ইমেইল ও পাসওয়ার্ড দিন", "Enter mobile/email and password"); return }
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

    Column(Modifier.fillMaxSize().background(c.surface).imePadding().verticalScroll(rememberScrollState())) {
        Column(
            Modifier.fillMaxWidth().background(c.header).statusBarsPadding().headerBubble()
                .padding(start = 28.dp, end = 28.dp, top = 12.dp, bottom = 52.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { LangChip() }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BrandLogo(44.dp)
                Column(Modifier.weight(1f)) {
                    T("Resonance Automation", 19.sp, FontWeight.Bold, Color.White, maxLines = 1)
                    T(t("শেয়ারহোল্ডার পোর্টাল", "Shareholder portal"), 14.sp, color = Brand.HeaderMuted)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                T(t("আপনার শেয়ার,", "Your shares,"), 34.sp, FontWeight.Bold, Color.White, lineHeight = 44.sp)
                T(t("এক জায়গায়", "in one place"), 34.sp, FontWeight.Bold, Brand.Amber, lineHeight = 44.sp)
                T(t("রেজিস্ট্রেশন করুন, শেয়ার কিনুন এবং নিজের প্রোফাইলে সব হিসাব দেখুন।",
                    "Register, buy shares and see every account detail in your profile."), 16.sp, color = Brand.HeaderMuted)
            }
            info?.takeIf { it.todayRatePaisa > 0 }?.let { RatePill(it.todayRatePaisa, it.dayChangePaisa) }
            if (Graph.isDemo) DemoBadge()
        }
        Column(
            Modifier.fillMaxWidth()
                .drawBehind { drawRect(c.header, size = Size(size.width, 28.dp.toPx())) }
                .clip(SheetShape).background(c.surface)
                .padding(horizontal = 28.dp, vertical = 28.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            T(t("লগইন করুন", "Log in"), 22.sp, FontWeight.Bold)
            if (Graph.isDemo) {
                InfoBox(t("মোবাইল: ০১৯১১০০০১২৪ · পাসওয়ার্ড: Demo@1234\nঅথবা নতুন রেজিস্ট্রেশন করে দেখুন। অনুমোদন দিতে অ্যাডমিন প্যানেলে ঢুকুন।",
                    "Mobile: 01911000124 · Password: Demo@1234\nOr register a new account, then approve it from the admin panel."),
                    title = t("ডেমো লগইন", "Demo login"), tone = Tone.CASH)
            }
            Field(t("মোবাইল নম্বর বা ইমেইল", "Mobile number or email"), id, { id = it; error = null }, placeholder = t("০১৭XXXXXXXX", "017XXXXXXXX"),
                keyboard = KeyboardType.Email, maxLength = 254)
            Field(t("পাসওয়ার্ড", "Password"), pass, { pass = it; error = null }, placeholder = t("আপনার পাসওয়ার্ড", "Your password"), password = true,
                ime = ImeAction.Done, onDone = ::submit, maxLength = 128)
            if (error != null) InfoBox(error!! + t("\nবারবার ভুল দিলে নিরাপত্তার জন্য কিছুক্ষণ লগইন বন্ধ থাকে। পাসওয়ার্ড মনে না থাকলে হটলাইনে যোগাযোগ করুন।",
                "\nRepeated failures pause login for a while. If you forgot your password, call the hotline."), tone = Tone.DANGER)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                LinkText(t("পাসওয়ার্ড ভুলে গেছেন?", "Forgot password?"), { nav.navigate(Routes.FORGOT) })
            }
            Box(Modifier.shadow(10.dp, RoundedCornerShape(14.dp), spotColor = Brand.Amber.copy(alpha = 0.5f))) {
                PrimaryButton(t("লগইন করুন", "Log in"), ::submit, loading = loading)
            }
            SecondaryButton(t("নতুন শেয়ারহোল্ডার? রেজিস্ট্রেশন করুন", "New shareholder? Register"), { nav.navigate(Routes.REGISTER) })
            Row(
                Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(10.dp)).clickable { switchPortal("admin") },
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(ResoIcons.Shield, null, tint = c.muted, modifier = Modifier.size(16.dp))
                Spacer(Modifier.size(6.dp))
                T(t("অ্যাডমিন প্যানেল", "Admin panel"), 14.sp, FontWeight.Bold, c.muted)
            }
        }
    }
}

/** "আজকের শেয়ার রেট ৳১,০০০.০০ ▲ +৳২.০০" pill on the login header. */
@Composable
private fun RatePill(rate: Long, change: Long) {
    val up = change >= 0
    Row(
        Modifier.clip(RoundedCornerShape(28.dp)).background(Color.White.copy(alpha = 0.08f))
            .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(28.dp)).padding(start = 6.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        TextDisc("৳", Brand.Amber, Brand.Navy, 34.dp, 18.sp)
        Column {
            T(t("আজকের শেয়ার রেট", "Today's share rate"), 12.sp, color = Brand.HeaderMuted)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                T(Bn.money(rate), 16.sp, FontWeight.Bold, Color.White)
                if (change != 0L) T((if (up) "▲ " else "▼ ") + Bn.signedMoney(change), 13.sp, FontWeight.Bold, if (up) Brand.Mint else Color(0xFFF7837A))
            }
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
        ScreenHeader(t("পাসওয়ার্ড ভুলে গেছেন?", "Forgot password?"), onBack = { nav.popBackStack() })
        LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                ResoCard {
                    IconDisc(ResoIcons.Shield, Reso.colors.warnBg, Reso.colors.brown, 52.dp, 26.dp)
                    Spacer(Modifier.height(12.dp))
                    T(t("নিরাপত্তার জন্য পাসওয়ার্ড রিসেট অফিস থেকে করা হয়", "For security, passwords are reset by the office"), 18.sp, FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    T(
                        t(
                            "আপনার শেয়ার ও টাকার নিরাপত্তার জন্য অ্যাপ থেকে নিজে নিজে পাসওয়ার্ড রিসেট করা যায় না। হটলাইনে ফোন করুন বা অফিসে আসুন। পরিচয় যাচাইয়ের পর অ্যাডমিন একটি অস্থায়ী পাসওয়ার্ড দেবেন, প্রথম লগইনে সেটি বদলে নিতে হবে।",
                            "To protect your shares and money, passwords can't be reset from the app. Call the hotline or visit the office. After verifying your identity, an admin gives you a temporary password that you change at first login.",
                        ),
                        15.sp, color = Reso.colors.textSoft, lineHeight = 24.sp,
                    )
                }
            }
            item {
                ResoCard {
                    T(t("হটলাইন", "Hotline"), 14.sp, color = Reso.colors.muted)
                    T(Bn.digits(hotline), 22.sp, FontWeight.Bold)
                    if (!info?.officeAddress.isNullOrBlank()) {
                        Spacer(Modifier.height(8.dp))
                        T(t("অফিস: ", "Office: ") + info!!.officeAddress, 15.sp, color = Reso.colors.textSoft)
                    }
                    Spacer(Modifier.height(14.dp))
                    PrimaryButton(t("হটলাইনে কল করুন", "Call the hotline"), {
                        runCatching { ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + hotline.filter { it.isDigit() || it == '+' }))) }
                    }, icon = ResoIcons.Phone)
                }
            }
            item {
                InfoBox(t("কেউ ফোন করে আপনার পাসওয়ার্ড বা OTP চাইলে কখনো দেবেন না। কোম্পানি কখনো পাসওয়ার্ড চায় না।",
                    "Never share your password or OTP with anyone who calls you. The company never asks for it."), tone = Tone.WARN, title = t("সতর্কতা", "Warning"))
            }
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
        if (cur.isEmpty()) e["cur"] = t("বর্তমান পাসওয়ার্ড দিন", "Enter your current password")
        V.password(new)?.let { e["new"] = it }
        if (new.isNotEmpty() && new == cur) e["new"] = t("নতুন পাসওয়ার্ড আগেরটির থেকে আলাদা হতে হবে", "The new password must differ from the old one")
        V.confirm(new, conf)?.let { e["conf"] = it }
        errs = e
        if (e.isNotEmpty() || loading) return
        loading = true
        scope.launch {
            when (val r = call { Graph.shareholderApi.changePassword(PasswordChange(cur, new)) }) {
                is Res.Ok -> {
                    cur = ""; new = ""; conf = ""
                    Toaster.show(t("পাসওয়ার্ড বদলানো হয়েছে। অন্য সব ডিভাইস থেকে লগআউট হয়েছে।", "Password changed. All other devices were logged out."))
                    if (forced) nav.toLoggedIn(false) else nav.popBackStack()
                }
                is Res.Err -> errs = buildMap {
                    r.error.fields["currentPassword"]?.let { put("cur", it) }
                    (r.error.fields["newPassword"] ?: r.error.fields["password"])?.let { put("new", it) }
                    if (isEmpty()) put("form", r.error.message)
                }
            }
            loading = false
        }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        ScreenHeader(t("পাসওয়ার্ড পরিবর্তন", "Change password"), onBack = if (forced) null else ({ nav.popBackStack() }))
        LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (forced) item {
                InfoBox(t("আপনাকে একটি অস্থায়ী পাসওয়ার্ড দেওয়া হয়েছে। নিরাপত্তার জন্য এখনই নতুন পাসওয়ার্ড দিন।",
                    "You were given a temporary password. Set a new one now for your security."), tone = Tone.WARN)
            }
            item { Field(t("বর্তমান পাসওয়ার্ড", "Current password"), cur, { cur = it }, password = true, error = errs["cur"], maxLength = 128, leading = ResoIcons.Lock) }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field(t("নতুন পাসওয়ার্ড", "New password"), new, { new = it }, password = true, error = errs["new"],
                        placeholder = t("কমপক্ষে ৮ অক্ষর", "At least 8 characters"), maxLength = 128, leading = ResoIcons.Key)
                    PasswordStrength(new)
                }
            }
            item {
                Field(t("নতুন পাসওয়ার্ড আবার দিন", "Confirm new password"), conf, { conf = it }, password = true, error = errs["conf"],
                    ime = ImeAction.Done, onDone = ::submit, maxLength = 128, leading = ResoIcons.Key)
            }
            errs["form"]?.let { item { InfoBox(it, tone = Tone.DANGER) } }
            item { PrimaryButton(t("পাসওয়ার্ড বদলান", "Change password"), ::submit, loading = loading) }
            if (forced) item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    LinkText(t("লগআউট", "Log out"), { scope.launch { Auth.logout(null) } }, color = Reso.colors.red)
                }
            }
        }
    }
}
