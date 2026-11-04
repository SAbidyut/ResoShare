package net.rapti.resoshare.user

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
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
import net.rapti.resoshare.core.i18n.t
import net.rapti.resoshare.core.net.AppJson
import net.rapti.resoshare.core.net.Res
import net.rapti.resoshare.core.net.call
import net.rapti.resoshare.core.ui.Field
import net.rapti.resoshare.core.ui.GradientCard
import net.rapti.resoshare.core.ui.IconDisc
import net.rapti.resoshare.core.ui.InfoBox
import net.rapti.resoshare.core.ui.LinkText
import net.rapti.resoshare.core.ui.PrimaryButton
import net.rapti.resoshare.core.ui.ProgressTrack
import net.rapti.resoshare.core.ui.ResoCard
import net.rapti.resoshare.core.ui.ResoIcons
import net.rapti.resoshare.core.ui.T
import net.rapti.resoshare.core.ui.Tone
import net.rapti.resoshare.core.ui.theme.Brand
import net.rapti.resoshare.core.ui.theme.Reso
import net.rapti.resoshare.data.api.RegisterData
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody

fun jpegPart(name: String, bytes: ByteArray): MultipartBody.Part =
    MultipartBody.Part.createFormData(name, "$name.jpg", bytes.toRequestBody("image/jpeg".toMediaType()))

private fun steps() = listOf(t("ব্যক্তিগত তথ্য", "Personal"), t("পরিচয় যাচাই", "Identity"), t("নমিনি", "Nominee"))

@Composable
fun RegisterScreen(nav: NavHostController) {
    val c = Reso.colors
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()
    var step by rememberSaveable { mutableIntStateOf(0) }
    var done by rememberSaveable { mutableStateOf<String?>(null) }

    var name by rememberSaveable { mutableStateOf("") }
    var mobile by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var pass2 by remember { mutableStateOf("") }
    var nid by rememberSaveable { mutableStateOf("") }
    var dob by rememberSaveable { mutableStateOf("") }
    var front by remember { mutableStateOf<ByteArray?>(null) }
    var back by remember { mutableStateOf<ByteArray?>(null) }
    var photo by remember { mutableStateOf<ByteArray?>(null) }
    val nominees = remember { NomineesState() }

    var errs by remember { mutableStateOf(mapOf<String, String>()) }
    var loading by remember { mutableStateOf(false) }
    var formError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(step) { list.scrollToItem(0) }
    fun back() { if (step > 0) { step--; errs = emptyMap(); formError = null } else nav.popBackStack() }
    BackHandler(enabled = step > 0 && done == null) { back() }

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
                V.dob(Bn.parseDate(dob))?.let { e["dob"] = it }
                if (front == null || back == null || photo == null) e["files"] = t("তিনটি ছবিই দিন", "Add all three photos")
            }
            2 -> if (!nominees.validate()) e["nominee"] = t("নমিনির তথ্য ঠিক করে দিন", "Please fix the nominee details")
        }
        errs = e
        return e.isEmpty()
    }

    fun submit() {
        if (loading) return
        formError = null
        loading = true
        val data = RegisterData(name.trim(), V.normalizeMobile(mobile), email.trim().lowercase(), pass, V.digitsOnly(nid), dob, nominees.inputs(), true)
        val json = AppJson.encodeToString(RegisterData.serializer(), data).toRequestBody("application/json; charset=utf-8".toMediaType())
        scope.launch {
            when (val r = call { Graph.shareholderApi.register(json, jpegPart("nidFront", front!!), jpegPart("nidBack", back!!), jpegPart("photo", photo!!)) }) {
                is Res.Ok -> { pass = ""; pass2 = ""; done = r.value.shareholderNo }
                is Res.Err -> {
                    formError = r.error.message
                    val f = r.error.fields
                    when {
                        f.keys.any { it in setOf("fullName", "mobile", "email", "password") } -> step = 0
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

    // imePadding is applied exactly once, here. The form (including its buttons) scrolls,
    // so the field being typed in always stays visible above the keyboard.
    Column(Modifier.fillMaxSize().background(c.bg).imePadding()) {
        StepHeader(t("রেজিস্ট্রেশন", "Registration"), steps(), step, ::back)
        LazyColumn(
            Modifier.fillMaxSize(),
            state = list,
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (step) {
                0 -> {
                    item(key = "intro") {
                        GradientCard(c.rules, Modifier.fillMaxWidth(), radius = 14.dp, padding = PaddingValues(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                IconDisc(ResoIcons.Layers, Brand.Amber, Brand.Navy, 40.dp, 20.dp)
                                Column {
                                    T(t("মাত্র ৩ ধাপে শেয়ারহোল্ডার হন", "Become a shareholder in 3 steps"), 16.sp, FontWeight.Bold, c.rules.value)
                                    T(t("প্রায় ৫ মিনিট লাগবে", "Takes about 5 minutes"), 13.sp, color = c.rules.label)
                                }
                            }
                        }
                    }
                    item(key = "form1") {
                        ResoCard(padding = PaddingValues(16.dp)) {
                            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                Field(t("পুরো নাম (NID অনুযায়ী)", "Full name (as on NID)"), name, { name = it; errs = errs - "name" }, placeholder = t("আপনার পুরো নাম", "Your full name"),
                                    error = errs["name"], maxLength = 100, leading = ResoIcons.User)
                                Field(t("মোবাইল নম্বর", "Mobile number"), mobile, { mobile = it; errs = errs - "mobile" }, placeholder = t("০১৭XXXXXXXX", "017XXXXXXXX"),
                                    keyboard = KeyboardType.Phone, error = errs["mobile"], maxLength = 16, leading = ResoIcons.Phone)
                                Field(t("ইমেইল", "Email"), email, { email = it; errs = errs - "email" }, placeholder = "name@example.com",
                                    keyboard = KeyboardType.Email, error = errs["email"], maxLength = 254, leading = ResoIcons.Mail)
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Field(t("পাসওয়ার্ড", "Password"), pass, { pass = it; errs = errs - "pass" }, placeholder = t("কমপক্ষে ৮ অক্ষর", "At least 8 characters"),
                                        password = true, error = errs["pass"], maxLength = 128, leading = ResoIcons.Lock)
                                    PasswordStrength(pass)
                                }
                                Field(t("পাসওয়ার্ড আবার দিন", "Confirm password"), pass2, { pass2 = it; errs = errs - "pass2" }, placeholder = t("পাসওয়ার্ড নিশ্চিত করুন", "Re-enter the password"),
                                    password = true, error = errs["pass2"], ime = ImeAction.Done, maxLength = 128, leading = ResoIcons.Lock)
                            }
                        }
                    }
                    item(key = "consent") {
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            T(t("পরের ধাপে NID যাচাই ও নমিনির তথ্য দিতে হবে। রেজিস্ট্রেশন করলে আপনি শেয়ারের",
                                "Next you'll verify your NID and add nominees. By registering you agree to the"),
                                13.sp, color = c.muted, align = TextAlign.Center)
                            LinkText(t("নিয়ম ও রেগুলেশন মানতে রাজি হচ্ছেন", "share rules & regulations"), { nav.navigate(Routes.RULES) }, size = 13.sp)
                        }
                    }
                }
                1 -> {
                    item(key = "identity") {
                        ResoCard(padding = PaddingValues(16.dp)) {
                            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                T(t("আপনার পরিচয়", "Your identity"), 17.sp, FontWeight.Bold)
                                Field(t("NID নম্বর", "NID number"), nid, { nid = it; errs = errs - "nid" }, placeholder = t("আপনার NID নম্বর", "Your NID number"),
                                    keyboard = KeyboardType.Number, error = errs["nid"], maxLength = 20, leading = ResoIcons.IdCard)
                                DateField(t("জন্ম তারিখ", "Date of birth"), dob, { dob = it; errs = errs - "dob" }, error = errs["dob"], maxDate = Bn.today().minusYears(18))
                            }
                        }
                    }
                    item(key = "docs") {
                        val count = listOf(front, back, photo).count { it != null }
                        ResoCard(padding = PaddingValues(16.dp)) {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    T(t("নথি আপলোড করুন", "Upload documents"), 17.sp, FontWeight.Bold, modifier = Modifier.weight(1f))
                                    T(t("${Bn.num(count)} / ${Bn.num(3)} সম্পন্ন", "$count / 3 done"), 14.sp, FontWeight.Bold, if (count == 3) c.green else c.brown)
                                }
                                ProgressTrack(count / 3f, color = c.green)
                                ImagePickTile(t("NID এর সামনের ছবি", "NID front"), front, { front = it; errs = errs - "files" }, error = "files" in errs && front == null)
                                ImagePickTile(t("NID এর পেছনের ছবি", "NID back"), back, { back = it; errs = errs - "files" }, error = "files" in errs && back == null)
                                ImagePickTile(t("আপনার সাম্প্রতিক ছবি", "Your recent photo"), photo, { photo = it; errs = errs - "files" }, error = "files" in errs && photo == null)
                                errs["files"]?.let { T(it, 13.sp, color = c.red) }
                                InfoBox(t("ছবি যেন পরিষ্কার হয়, চার কোণা দেখা যায় এবং আলো ঠিক থাকে।",
                                    "Make sure the photo is sharp, all four corners are visible and the lighting is good."))
                            }
                        }
                    }
                    item(key = "privacy") {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            Icon(ResoIcons.Lock, null, tint = c.muted, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.size(6.dp))
                            T(t("আপনার তথ্য শুধু যাচাইয়ের কাজে ব্যবহার হবে", "Your data is used only for verification"), 13.sp, color = c.muted)
                        }
                    }
                }
                else -> {
                    item(key = "nomInfo") {
                        InfoBox(t("আপনার অবর্তমানে শেয়ার যাঁরা পাবেন, তাঁদের তথ্য দিন। সর্বোচ্চ ২ জন।",
                            "Who receives your shares in your absence? Up to 2 people."), icon = ResoIcons.Users)
                    }
                    item(key = "nominees") { NomineeEditor(nominees) }
                    item(key = "nomNote") {
                        T(t("পরে প্রোফাইল থেকে নমিনি ও ভাগ বদলাতে পারবেন। মোট ভাগ সবসময় ১০০% হতে হবে।",
                            "You can change nominees and shares later from your profile. The total must always be 100%."),
                            13.sp, color = c.muted, align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
            if (formError != null) item(key = "err") { InfoBox(formError!!, tone = Tone.DANGER) }
            item(key = "cta") {
                Column(Modifier.fillMaxWidth().navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    PrimaryButton(
                        when (step) {
                            0 -> t("পরবর্তী ধাপ: পরিচয় যাচাই  ›", "Next: Identity  ›")
                            1 -> t("পরবর্তী ধাপ: নমিনি  ›", "Next: Nominee  ›")
                            else -> t("রেজিস্ট্রেশন সম্পন্ন করুন", "Complete registration")
                        },
                        { if (validateStep()) { if (step < 2) step++ else submit() } },
                        loading = loading,
                    )
                    when (step) {
                        0 -> LinkText(t("আগে থেকেই অ্যাকাউন্ট আছে? লগইন করুন", "Already have an account? Log in"), { nav.popBackStack() })
                        1 -> T(t("অ্যাডমিন যাচাই করার পর আপনার অ্যাকাউন্টে শেয়ার কেনা চালু হবে।", "Share buying opens after an admin verifies your account."),
                            13.sp, color = c.muted, align = TextAlign.Center)
                        else -> Unit
                    }
                }
            }
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
        T(t("রেজিস্ট্রেশন সম্পন্ন হয়েছে!", "Registration complete!"), 24.sp, FontWeight.Bold, align = TextAlign.Center)
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surface).border(1.dp, c.border, RoundedCornerShape(16.dp)).padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            T(t("আপনার শেয়ারহোল্ডার ID", "Your shareholder ID"), 14.sp, color = c.muted)
            T(shareholderNo, 26.sp, FontWeight.Bold)
        }
        T(t("অ্যাডমিন আপনার KYC যাচাই করলে নোটিশে জানানো হবে এবং শেয়ার কেনা চালু হবে।",
            "You'll get a notice when an admin verifies your KYC, and share buying will open."), 15.sp, color = c.textSoft, align = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        PrimaryButton(t("লগইন করুন", "Log in"), onLogin)
    }
}
