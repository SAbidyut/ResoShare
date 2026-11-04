package net.rapti.resoshare.user

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import kotlinx.coroutines.launch
import net.rapti.resoshare.Graph
import net.rapti.resoshare.core.format.Bn
import net.rapti.resoshare.core.format.V
import net.rapti.resoshare.core.net.AppJson
import net.rapti.resoshare.core.net.Res
import net.rapti.resoshare.core.net.call
import net.rapti.resoshare.core.ui.CheckRow
import net.rapti.resoshare.core.ui.Field
import net.rapti.resoshare.core.ui.IconDisc
import net.rapti.resoshare.core.ui.InfoBox
import net.rapti.resoshare.core.ui.LinkText
import net.rapti.resoshare.core.ui.PrimaryButton
import net.rapti.resoshare.core.ui.ResoCard
import net.rapti.resoshare.core.ui.ResoIcons
import net.rapti.resoshare.core.ui.ScreenHeader
import net.rapti.resoshare.core.ui.T
import net.rapti.resoshare.core.ui.Tone
import net.rapti.resoshare.core.ui.theme.Reso
import net.rapti.resoshare.data.api.RegisterData
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody

private val STEP_TITLES = listOf("ধাপ ১: ব্যক্তিগত তথ্য", "ধাপ ২: পরিচয় যাচাই (KYC)", "ধাপ ৩: নমিনি")

fun jpegPart(name: String, bytes: ByteArray): MultipartBody.Part =
    MultipartBody.Part.createFormData(name, "$name.jpg", bytes.toRequestBody("image/jpeg".toMediaType()))

@Composable
fun RegisterScreen(nav: NavHostController) {
    val c = Reso.colors
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()
    var step by rememberSaveable { mutableIntStateOf(0) }
    var done by rememberSaveable { mutableStateOf<String?>(null) }

    // Step 1
    var name by rememberSaveable { mutableStateOf("") }
    var mobile by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var pass2 by remember { mutableStateOf("") }
    // Step 2
    var nid by rememberSaveable { mutableStateOf("") }
    var dob by rememberSaveable { mutableStateOf("") }
    var front by remember { mutableStateOf<ByteArray?>(null) }
    var back by remember { mutableStateOf<ByteArray?>(null) }
    var photo by remember { mutableStateOf<ByteArray?>(null) }
    // Step 3
    val nominees = remember { NomineesState() }
    var accept by rememberSaveable { mutableStateOf(false) }

    var errs by remember { mutableStateOf(mapOf<String, String>()) }
    var loading by remember { mutableStateOf(false) }
    var formError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(step) { list.animateScrollToItem(0) }
    BackHandler(enabled = step > 0 && done == null) { step--; errs = emptyMap() }

    fun validateStep(): Boolean {
        val e = mutableMapOf<String, String>()
        when (step) {
            0 -> {
                V.name(name)?.let { e["name"] = it }
                V.mobile(mobile)?.let { e["mobile"] = it }
                V.email(email)?.let { e["email"] = it }
                V.password(pass)?.let { e["pass"] = it }
                V.confirm(pass, pass2)?.let { e["pass2"] = it }
            }
            1 -> {
                V.nid(nid)?.let { e["nid"] = it }
                if (dob.isBlank()) e["dob"] = "জন্ম তারিখ দিন" else Bn.parseDate(dob)?.let { d ->
                    V.dob("${d.dayOfMonth}/${d.monthValue}/${d.year}")?.let { e["dob"] = it }
                }
                if (front == null) e["front"] = "NID এর সামনের ছবি দিন"
                if (back == null) e["back"] = "NID এর পেছনের ছবি দিন"
                if (photo == null) e["photo"] = "আপনার সাম্প্রতিক ছবি দিন"
            }
            2 -> {
                if (!nominees.validate()) e["nominee"] = "নমিনির তথ্য ঠিক করে দিন"
                if (!accept) e["accept"] = "নিয়ম ও রেগুলেশন মানতে টিক দিন"
            }
        }
        errs = e
        return e.isEmpty()
    }

    fun submit() {
        if (loading) return
        formError = null
        loading = true
        val data = RegisterData(name.trim(), V.normalizeMobile(mobile), email.trim().lowercase(), pass, V.digitsOnly(nid), dob, nominees.inputs(), accept)
        val json = AppJson.encodeToString(RegisterData.serializer(), data).toRequestBody("application/json; charset=utf-8".toMediaType())
        scope.launch {
            when (val r = call { Graph.shareholderApi.register(json, jpegPart("nidFront", front!!), jpegPart("nidBack", back!!), jpegPart("photo", photo!!)) }) {
                is Res.Ok -> { pass = ""; pass2 = ""; done = r.value.shareholderNo }
                is Res.Err -> {
                    formError = r.error.message
                    val f = r.error.fields
                    // Send the user back to the step that holds the offending field.
                    when {
                        f.keys.any { it in setOf("fullName", "mobile", "email", "password") } -> { step = 0; errs = mapOf("mobile" to (f["mobile"] ?: ""), "email" to (f["email"] ?: "")).filterValues { it.isNotEmpty() } }
                        f.keys.any { it in setOf("nidNumber", "dob") } -> step = 1
                    }
                }
            }
            loading = false
        }
    }

    if (done != null) {
        RegisterDone(done!!) {
            nav.previousBackStackEntry?.savedStateHandle?.set("mobile", V.normalizeMobile(mobile))
            nav.popBackStack()
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("রেজিস্ট্রেশন", onBack = { if (step > 0) { step--; errs = emptyMap() } else nav.popBackStack() })
        LazyColumn(
            Modifier.weight(1f).imePadding(),
            state = list,
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    StepBars(step)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        T(STEP_TITLES[step], 17.sp, FontWeight.Bold, modifier = Modifier.weight(1f))
                        T("${Bn.num(step + 1)} / ${Bn.num(3)}", 14.sp, color = c.muted)
                    }
                }
            }
            item {
                AnimatedContent(
                    targetState = step,
                    transitionSpec = {
                        val dir = if (targetState > initialState) 1 else -1
                        (slideInHorizontally(tween(300)) { it * dir / 3 } + fadeIn(tween(300))) togetherWith
                            (slideOutHorizontally(tween(250)) { -it * dir / 3 } + fadeOut(tween(200)))
                    },
                    label = "wizard",
                ) { s ->
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        when (s) {
                            0 -> {
                                Field("পুরো নাম (NID অনুযায়ী)", name, { name = it }, placeholder = "আপনার পুরো নাম", error = errs["name"], maxLength = 100)
                                Field("মোবাইল নম্বর", mobile, { mobile = it }, placeholder = "০১৭XXXXXXXX", keyboard = KeyboardType.Phone, error = errs["mobile"], maxLength = 16)
                                Field("ইমেইল", email, { email = it }, placeholder = "name@example.com", keyboard = KeyboardType.Email, error = errs["email"], maxLength = 254)
                                Field("পাসওয়ার্ড", pass, { pass = it }, placeholder = "কমপক্ষে ৮ অক্ষর", password = true, error = errs["pass"], maxLength = 128,
                                    hint = "অক্ষর ও সংখ্যা দুটোই রাখুন। অন্য কোথাও ব্যবহার করা পাসওয়ার্ড দেবেন না।")
                                Field("পাসওয়ার্ড আবার দিন", pass2, { pass2 = it }, placeholder = "পাসওয়ার্ড নিশ্চিত করুন", password = true, error = errs["pass2"], ime = ImeAction.Done, maxLength = 128)
                            }
                            1 -> {
                                Field("NID নম্বর", nid, { nid = it }, placeholder = "আপনার NID নম্বর", keyboard = KeyboardType.Number, error = errs["nid"], maxLength = 20)
                                DateField("জন্ম তারিখ", dob, { dob = it; errs = errs - "dob" }, error = errs["dob"], maxDate = Bn.today().minusYears(18))
                                T("নথি আপলোড করুন", 14.sp, FontWeight.Bold)
                                ImagePickTile("NID এর সামনের ছবি", front, { front = it; errs = errs - "front" }, error = "front" in errs)
                                ImagePickTile("NID এর পেছনের ছবি", back, { back = it; errs = errs - "back" }, error = "back" in errs)
                                ImagePickTile("আপনার সাম্প্রতিক ছবি", photo, { photo = it; errs = errs - "photo" }, error = "photo" in errs)
                                listOf("front", "back", "photo").firstNotNullOfOrNull { errs[it] }?.let { T(it, 13.sp, color = c.red) }
                                InfoBox("ছবিগুলো এনক্রিপ্ট করে সংরক্ষণ করা হয় এবং শুধু অনুমোদিত KYC অফিসার দেখতে পারেন।", icon = ResoIcons.Lock)
                            }
                            else -> {
                                NomineeEditor(nominees)
                                CheckRow(accept, { accept = it; errs = errs - "accept" }) {
                                    Column {
                                        T("আমি শেয়ারের নিয়ম ও রেগুলেশন পড়েছি এবং মানছি।", 14.sp, color = c.textSoft)
                                        LinkText("নিয়ম ও রেগুলেশন পড়ুন", { nav.navigate(Routes.RULES) })
                                    }
                                }
                                errs["accept"]?.let { T(it, 13.sp, color = c.red) }
                            }
                        }
                    }
                }
            }
            if (formError != null) item { InfoBox(formError!!, tone = Tone.DANGER) }
        }
        Column(
            Modifier.fillMaxWidth().background(c.bg).navigationBarsPadding().imePadding().padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            T(
                when (step) {
                    0 -> "পরের ধাপে NID যাচাই ও নমিনির তথ্য দিতে হবে।"
                    1 -> "অ্যাডমিন যাচাই করার পর আপনার অ্যাকাউন্টে শেয়ার কেনা চালু হবে।"
                    else -> "জমা দেওয়ার পর অ্যাডমিন আপনার তথ্য যাচাই করবেন।"
                },
                13.sp, color = c.muted, align = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
            )
            PrimaryButton(
                if (step < 2) "পরবর্তী ধাপ" else "রেজিস্ট্রেশন সম্পন্ন করুন",
                { if (validateStep()) { if (step < 2) step++ else submit() } },
                loading = loading,
            )
        }
    }
}

@Composable
private fun RegisterDone(shareholderNo: String, onLogin: () -> Unit) {
    val c = Reso.colors
    Column(
        Modifier.fillMaxSize().background(c.bg).padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconDisc(ResoIcons.CheckCircle, c.successBg, c.green, 88.dp, 46.dp)
        T("রেজিস্ট্রেশন সম্পন্ন হয়েছে!", 24.sp, FontWeight.Bold, align = TextAlign.Center)
        ResoCard {
            T("আপনার শেয়ারহোল্ডার ID", 14.sp, color = c.muted, align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            T(shareholderNo, 26.sp, FontWeight.Bold, align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
        T("অ্যাডমিন আপনার KYC যাচাই করলে নোটিশে জানানো হবে এবং শেয়ার কেনা চালু হবে।", 15.sp, color = c.textSoft, align = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        PrimaryButton("লগইন করুন", onLogin)
    }
}
