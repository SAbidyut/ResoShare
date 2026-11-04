package net.rapti.resoshare.admin

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import net.rapti.resoshare.Graph
import net.rapti.resoshare.core.format.Bn
import net.rapti.resoshare.core.format.V
import net.rapti.resoshare.core.ui.BrandLogo
import net.rapti.resoshare.core.ui.DemoBadge
import net.rapti.resoshare.core.ui.Field
import net.rapti.resoshare.core.ui.InfoBox
import net.rapti.resoshare.core.ui.LinkText
import net.rapti.resoshare.core.ui.PrimaryButton
import net.rapti.resoshare.core.ui.QrCode
import net.rapti.resoshare.core.ui.ResoIcons
import net.rapti.resoshare.core.ui.SecondaryButton
import net.rapti.resoshare.core.ui.SheetShape
import net.rapti.resoshare.core.ui.T
import net.rapti.resoshare.core.ui.Tag
import net.rapti.resoshare.core.ui.Tone
import net.rapti.resoshare.core.ui.theme.Brand
import net.rapti.resoshare.core.ui.theme.Reso
import net.rapti.resoshare.data.api.AdminLoginRequest
import net.rapti.resoshare.data.api.AdminLoginResult
import net.rapti.resoshare.data.api.MfaRequest

@Composable
fun LoginScreen(onSignedIn: (forcedPasswordChange: Boolean) -> Unit) {
    val c = Reso.colors
    var mfa by remember { mutableStateOf<AdminLoginResult?>(null) }
    var email by rememberSaveable { mutableStateOf(if (Graph.isDemo) "admin@rapti.net" else "") }
    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(0f to c.header, 0.55f to c.header, 0.55f to c.surface, 1f to c.surface),
        ),
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Column(
                Modifier.fillMaxWidth().statusBarsPadding().padding(start = 28.dp, end = 28.dp, top = 40.dp, bottom = 36.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    BrandLogo(44.dp)
                    Column {
                        T("Resonance Automation", 19.sp, FontWeight.Bold, Color.White)
                        T("শেয়ার ম্যানেজমেন্ট", 14.sp, color = Brand.HeaderMuted)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Tag("অ্যাডমিন প্যানেল", c.headerChip, Brand.Gold, size = 13.sp)
                    if (Graph.isDemo) DemoBadge()
                }
                T("নিরাপদ অ্যাডমিন লগইন", 32.sp, FontWeight.Bold, Color.White, lineHeight = 40.sp)
                T("শুধু অনুমোদিত অ্যাডমিন ও স্টাফদের জন্য। প্রতিটি লগইন রেকর্ড করা হয়।", 16.sp, color = Brand.HeaderMuted, lineHeight = 25.sp)
            }
            Column(
                Modifier.fillMaxWidth().clip(SheetShape).background(c.surface).padding(start = 28.dp, end = 28.dp, top = 30.dp, bottom = 32.dp),
            ) {
                AnimatedContent(
                    mfa,
                    transitionSpec = {
                        val dir = if (targetState != null) 1 else -1
                        (slideInHorizontally(tween(280)) { dir * it / 3 } + fadeIn(tween(280))) togetherWith
                            (slideOutHorizontally(tween(280)) { -dir * it / 3 } + fadeOut(tween(180)))
                    },
                    label = "login-step",
                ) { step ->
                    if (step == null) {
                        PasswordStep(email, { email = it }, onMfa = { mfa = it })
                    } else {
                        OtpStep(step, onBack = { mfa = null }, onSignedIn = onSignedIn)
                    }
                }
            }
        }
    }
}

@Composable
private fun PasswordStep(email: String, onEmail: (String) -> Unit, onMfa: (AdminLoginResult) -> Unit) {
    val c = Reso.colors
    var password by remember { mutableStateOf(if (Graph.isDemo) "Demo@1234" else "") }
    var emailErr by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var forgot by remember { mutableStateOf(false) }
    val action = rememberAction()

    fun submit() {
        emailErr = V.email(email)
        if (emailErr != null) return
        if (password.isEmpty()) { error = "পাসওয়ার্ড দিন"; return }
        error = null
        action.run(
            { Graph.adminApi.login(AdminLoginRequest(email.trim().lowercase(), password, Graph.prefs.deviceId)) },
            onErr = { error = it.message },
        ) { password = ""; onMfa(it) }
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        T("লগইন করুন", 22.sp, FontWeight.Bold)
        Field("অ্যাডমিন ইমেইল", email, { onEmail(it.trim()); emailErr = null }, placeholder = "admin@example.com",
            keyboard = KeyboardType.Email, error = emailErr, maxLength = 254)
        Field("পাসওয়ার্ড", password, { password = it; error = null }, placeholder = "আপনার পাসওয়ার্ড", password = true,
            ime = ImeAction.Done, onDone = ::submit, maxLength = 128)
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.bg).padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(ResoIcons.Lock, null, tint = c.text, modifier = Modifier.size(22.dp))
            T("লগইনের পর আপনার মোবাইলের Authenticator অ্যাপ থেকে ৬ অঙ্কের OTP দিতে হবে (২-ধাপ যাচাই)।", 14.sp, color = c.textSoft, lineHeight = 22.sp)
        }
        AnimatedVisibility(error != null) { InfoBox(error ?: "", tone = Tone.DANGER) }
        if (Graph.isDemo) {
            InfoBox("ডেমো লগইন: admin@rapti.net · পাসওয়ার্ড Demo@1234 · OTP ১২৩৪৫৬", title = "ডেমো সংস্করণ", tone = Tone.CASH)
        }
        PrimaryButton("লগইন করুন", ::submit, loading = action.busy)
        LinkText("পাসওয়ার্ড ভুলে গেছেন?", { forgot = true }, Modifier.align(Alignment.CenterHorizontally))
    }
    if (forgot) {
        AlertDialog(
            onDismissRequest = { forgot = false },
            containerColor = c.surface,
            title = { T("পাসওয়ার্ড ভুলে গেছেন?", 19.sp, FontWeight.Bold) },
            text = {
                T("নিরাপত্তার জন্য অ্যাডমিন পাসওয়ার্ড অ্যাপ থেকে নিজে রিসেট করা যায় না। সুপার অ্যাডমিনের সঙ্গে যোগাযোগ করুন — তিনি “ইউজার ও রোল” থেকে আপনার পাসওয়ার্ড ও OTP রিসেট করে একটি অস্থায়ী পাসওয়ার্ড দেবেন।",
                    15.sp, color = c.textSoft, lineHeight = 23.sp)
            },
            confirmButton = { TextButton(onClick = { forgot = false }) { T("বুঝেছি", 16.sp, FontWeight.Bold, c.ink) } },
        )
    }
}

@Composable
private fun OtpStep(mfa: AdminLoginResult, onBack: () -> Unit, onSignedIn: (Boolean) -> Unit) {
    val c = Reso.colors
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var finishing by remember { mutableStateOf(false) }
    val action = rememberAction()

    fun verify() {
        val digits = Bn.ascii(code).filter { it.isDigit() }
        if (digits.length != 6) { error = "৬ অঙ্কের OTP দিন"; return }
        if (finishing) return
        error = null
        action.run(
            { Graph.adminApi.mfa(MfaRequest(mfa.mfaToken, digits)) },
            onErr = { error = it.message; code = "" },
        ) { r ->
            finishing = true
            Graph.tokens.set(r.tokens.accessToken, r.tokens.refreshToken)
            scope.launch {
                afterSignIn(r.staff)
                onSignedIn(r.staff.mustChangePassword)
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        T("২-ধাপ যাচাই", 22.sp, FontWeight.Bold)
        val enroll = mfa.enrollment
        if (enroll != null) {
            T("প্রথমবার লগইন: আপনার ফোনে Google Authenticator বা Microsoft Authenticator অ্যাপ খুলে “+” চেপে নিচের QR কোডটি স্ক্যান করুন। স্ক্যান করা না গেলে কোডটি হাতে লিখে দিন।",
                15.sp, color = c.textSoft, lineHeight = 23.sp)
            Box(
                Modifier.align(Alignment.CenterHorizontally).clip(RoundedCornerShape(16.dp)).background(Color.White)
                    .border(1.dp, c.border, RoundedCornerShape(16.dp)).padding(14.dp),
            ) { QrCode(enroll.otpauthUri, Modifier.size(200.dp)) }
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.infoBg).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                T("গোপন কোড (কাউকে দেখাবেন না)", 13.sp, color = c.muted)
                SelectionContainer {
                    Text(
                        enroll.secret.chunked(4).joinToString(" "),
                        style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = c.text, letterSpacing = 1.sp),
                    )
                }
            }
            T("তারপর অ্যাপে দেখানো ৬ অঙ্কের কোডটি নিচে দিন।", 15.sp, color = c.textSoft)
        } else {
            T("আপনার মোবাইলের Authenticator অ্যাপে “Resonance” এর পাশে দেখানো ৬ অঙ্কের কোডটি দিন। কোড প্রতি ৩০ সেকেন্ডে বদলায়।",
                15.sp, color = c.textSoft, lineHeight = 23.sp)
        }
        Field(
            "OTP কোড", code,
            { v ->
                val d = Bn.ascii(v).filter { it.isDigit() }.take(6)
                code = d
                error = null
                if (d.length == 6) verify()
            },
            placeholder = "৬ অঙ্ক", keyboard = KeyboardType.NumberPassword, ime = ImeAction.Done, onDone = ::verify, maxLength = 6, error = error,
        )
        if (Graph.isDemo) InfoBox("ডেমো OTP: ১২৩৪৫৬", tone = Tone.CASH)
        PrimaryButton("যাচাই করুন", ::verify, loading = action.busy || finishing)
        SecondaryButton("পেছনে যান", onBack, enabled = !action.busy && !finishing, height = 50.dp)
        T("OTP ৫ মিনিটের মধ্যে দিতে হবে। সময় পার হলে আবার লগইন করুন।", 13.sp, color = c.muted, align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}
