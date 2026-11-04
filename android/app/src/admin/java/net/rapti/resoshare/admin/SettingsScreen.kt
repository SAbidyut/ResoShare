package net.rapti.resoshare.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.rapti.resoshare.Graph
import net.rapti.resoshare.Toaster
import net.rapti.resoshare.core.format.Bn
import net.rapti.resoshare.core.format.V
import net.rapti.resoshare.core.session.Session
import net.rapti.resoshare.core.ui.ConfirmDialog
import net.rapti.resoshare.core.ui.Field
import net.rapti.resoshare.core.ui.InfoBox
import net.rapti.resoshare.core.ui.Load
import net.rapti.resoshare.core.ui.PrimaryButton
import net.rapti.resoshare.core.ui.ResoCard
import net.rapti.resoshare.core.ui.RowDivider
import net.rapti.resoshare.core.ui.ScreenHeader
import net.rapti.resoshare.core.ui.StepButton
import net.rapti.resoshare.core.ui.T
import net.rapti.resoshare.core.ui.ToggleRow
import net.rapti.resoshare.core.ui.Tone
import net.rapti.resoshare.core.ui.theme.Reso
import net.rapti.resoshare.data.api.CompanySettings
import net.rapti.resoshare.data.api.PaymentSettings
import net.rapti.resoshare.data.api.Perm
import net.rapti.resoshare.data.api.SecuritySettings
import net.rapti.resoshare.data.api.Settings

@Composable
fun SettingsScreen(an: AdminNav) {
    val loader = rememberLoader("settings") { Graph.adminApi.settings() }
    val data = (loader.state as? Load.Ready)?.data
    val can = AdminSession.can(Perm.SETTINGS)

    var initFor by remember { mutableStateOf<Settings?>(null) }
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var hotline by remember { mutableStateOf("") }
    var cash by remember { mutableStateOf(true) }
    var bank by remember { mutableStateOf(true) }
    var office by remember { mutableStateOf("") }
    var bankName by remember { mutableStateOf("") }
    var accName by remember { mutableStateOf("") }
    var accNo by remember { mutableStateOf("") }
    var minutes by remember { mutableIntStateOf(5) }
    var errs by remember { mutableStateOf<Map<String, String?>>(emptyMap()) }
    var confirm by remember { mutableStateOf(false) }
    val action = rememberAction()

    LaunchedEffect(data) {
        if (data != null && data !== initFor) {
            name = data.company.name; email = data.company.email; hotline = data.company.hotline
            cash = data.payment.cashEnabled; bank = data.payment.bankEnabled
            office = data.payment.officeAddress; bankName = data.payment.bankName
            accName = data.payment.accountName; accNo = data.payment.accountNumber
            minutes = data.security.autoLogoutMinutes
            initFor = data
        }
    }

    fun current() = Settings(
        CompanySettings(name.trim(), email.trim(), hotline.trim()),
        PaymentSettings(cash, bank, office.trim(), bankName.trim(), accName.trim(), accNo.trim()),
        SecuritySettings(minutes, true),
    )

    fun validate(): Boolean {
        errs = mapOf(
            "name" to V.required(name, "কোম্পানির নাম", 2, 100),
            "email" to if (email.isBlank()) null else V.email(email),
            "hotline" to if (Bn.ascii(hotline).count { it.isDigit() } in 5..15) null else "সঠিক হটলাইন নম্বর দিন",
            "office" to if (cash && office.isBlank()) "ক্যাশ চালু থাকলে অফিসের ঠিকানা দিন" else null,
            "bankName" to if (bank && bankName.isBlank()) "ব্যাংক চালু থাকলে ব্যাংকের নাম দিন" else null,
            "accName" to if (bank && accName.isBlank()) "অ্যাকাউন্টের নাম দিন" else null,
            "accNo" to if (bank && accNo.isBlank()) "অ্যাকাউন্ট নম্বর দিন" else null,
        )
        if (!cash && !bank) { Toaster.show("অন্তত একটি পেমেন্ট মাধ্যম চালু রাখুন"); return false }
        return errs.values.all { it == null }
    }

    val dirty = data != null && initFor != null && current() != data.copy(security = data.security.copy(mfaRequired = true))

    AdminScreen(
        header = { ScreenHeader("সেটিংস", onBack = an::back) },
        refreshing = loader.refreshing,
        onRefresh = { loader.refresh() },
    ) {
        loadItems(loader.state, { loader.load() }) {
            if (!can) item("ro") { InfoBox("শুধু দেখার অনুমতি আছে। পরিবর্তন করতে সুপার অ্যাডমিনের সাহায্য নিন।", tone = Tone.WARN) }
            item("company") {
                ResoCard {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        T("কোম্পানির তথ্য", 17.sp, FontWeight.Bold)
                        Field("কোম্পানির নাম", name, { name = it; errs = errs - "name" }, enabled = can, error = errs["name"], maxLength = 100)
                        Field("যোগাযোগের ইমেইল", email, { email = it.trim(); errs = errs - "email" }, enabled = can, error = errs["email"],
                            keyboard = KeyboardType.Email, placeholder = "info@example.com", maxLength = 254)
                        Field("হটলাইন নম্বর", hotline, { hotline = it; errs = errs - "hotline" }, enabled = can, error = errs["hotline"],
                            keyboard = KeyboardType.Phone, placeholder = "০১৭XXXXXXXX", maxLength = 20)
                    }
                }
            }
            item("payment") {
                ResoCard {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        T("পেমেন্ট মাধ্যম", 17.sp, FontWeight.Bold)
                        ToggleRow("ক্যাশ", "অফিসে নগদ টাকা গ্রহণ", cash, { cash = it }, enabled = can)
                        RowDivider()
                        ToggleRow("ব্যাংক ট্রান্সফার", "অ্যাকাউন্টের তথ্য", bank, { bank = it }, enabled = can)
                        RowDivider()
                        T("ইউজারকে যা দেখানো হবে", 15.sp, FontWeight.Bold)
                        Field("অফিসের ঠিকানা (ক্যাশ জমার জন্য)", office, { office = it; errs = errs - "office" }, enabled = can,
                            error = errs["office"], singleLine = false, minHeight = 70.dp, maxLength = 300, placeholder = "[অফিসের ঠিকানা]")
                        Field("ব্যাংক ও শাখার নাম", bankName, { bankName = it; errs = errs - "bankName" }, enabled = can, error = errs["bankName"],
                            maxLength = 100, placeholder = "[ব্যাংকের নাম]")
                        Field("অ্যাকাউন্টের নাম", accName, { accName = it; errs = errs - "accName" }, enabled = can, error = errs["accName"],
                            maxLength = 100, placeholder = "[অ্যাকাউন্টের নাম]")
                        Field("অ্যাকাউন্ট নম্বর", accNo, { accNo = it; errs = errs - "accNo" }, enabled = can, error = errs["accNo"],
                            maxLength = 40, placeholder = "[অ্যাকাউন্ট নম্বর]")
                    }
                }
            }
            item("security") {
                ResoCard {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        T("নিরাপত্তা", 17.sp, FontWeight.Bold)
                        ToggleRow("২-ধাপ যাচাই (OTP)", "সব অ্যাডমিনের জন্য বাধ্যতামূলক — বন্ধ করা যায় না", true, {}, enabled = false)
                        RowDivider()
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                T("অটো লগআউট", 16.sp, FontWeight.Bold)
                                T("কিছু না করলে এত মিনিট পর অ্যাডমিন লগআউট হবে", 13.sp, color = Reso.colors.muted)
                            }
                            if (can) StepButton(false, { minutes = (minutes - 1).coerceAtLeast(3) }, size = 40.dp, enabled = minutes > 3)
                            T("${Bn.num(minutes)} মিনিট", 15.sp, FontWeight.Bold, align = TextAlign.Center, modifier = Modifier.width(78.dp))
                            if (can) StepButton(true, { minutes = (minutes + 1).coerceAtMost(60) }, size = 40.dp, enabled = minutes < 60)
                        }
                        RowDivider()
                        T("অন্যান্য সুরক্ষা: স্ক্রিনশট বন্ধ, রুট করা ফোনে অ্যাডমিন অ্যাপ বন্ধ, প্রতিটি পরিবর্তন অডিট লগে রেকর্ড এবং সব সংযোগ HTTPS-এ এনক্রিপ্ট করা।",
                            13.sp, color = Reso.colors.muted, modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }
            if (can) item("save") {
                PrimaryButton("সেটিংস সংরক্ষণ করুন", {
                    if (!dirty) Toaster.show("কোনো পরিবর্তন নেই") else if (validate()) confirm = true
                }, loading = action.busy)
            }
        }
    }
    if (confirm) ConfirmDialog(
        "সেটিংস সংরক্ষণ করবেন?",
        "পেমেন্টের তথ্য বদলালে শেয়ারহোল্ডাররা সাথে সাথে নতুন তথ্য দেখবেন। ভুল অ্যাকাউন্ট নম্বর দিলে টাকা ভুল জায়গায় যেতে পারে — আবার মিলিয়ে নিন।",
        "সংরক্ষণ করুন",
        {
            confirm = false
            action.run({ Graph.adminApi.saveSettings(current()) }, onErr = { e ->
                if (e.fields.isNotEmpty()) errs = e.fields.mapValues { it.value }
                Toaster.show(e.message)
            }) { s ->
                loader.set(s)
                Session.idleTimeoutMs = s.security.autoLogoutMinutes.coerceIn(3, 60) * 60_000L
                AdminSession.companyName = s.company.name
                Toaster.show("সেটিংস সংরক্ষণ হয়েছে")
            }
        },
        { confirm = false },
    )
}
