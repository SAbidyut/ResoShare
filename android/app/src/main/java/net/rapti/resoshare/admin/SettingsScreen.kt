package net.rapti.resoshare.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.rapti.resoshare.Graph
import net.rapti.resoshare.Toaster
import net.rapti.resoshare.core.format.Bn
import net.rapti.resoshare.core.format.V
import net.rapti.resoshare.core.i18n.t
import net.rapti.resoshare.core.session.Session
import net.rapti.resoshare.core.ui.ConfirmDialog
import net.rapti.resoshare.core.ui.Field
import net.rapti.resoshare.core.ui.InfoBox
import net.rapti.resoshare.core.ui.Load
import net.rapti.resoshare.core.ui.MenuRow
import net.rapti.resoshare.core.ui.PrimaryButton
import net.rapti.resoshare.core.ui.ResoCard
import net.rapti.resoshare.core.ui.ResoIcons
import net.rapti.resoshare.core.ui.RowDivider
import net.rapti.resoshare.core.ui.ScreenHeader
import net.rapti.resoshare.core.ui.SecondaryButton
import net.rapti.resoshare.core.ui.StepButton
import net.rapti.resoshare.core.ui.T
import net.rapti.resoshare.core.ui.Tag
import net.rapti.resoshare.core.ui.ToggleRow
import net.rapti.resoshare.core.ui.Tone
import net.rapti.resoshare.core.ui.theme.Reso
import net.rapti.resoshare.data.api.BackupStatus
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
    var minutes by remember { mutableIntStateOf(10) }
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
            "name" to V.required(name, t("কোম্পানির নাম", "the company name"), 2, 100),
            "email" to if (email.isBlank()) null else V.email(email),
            "hotline" to if (Bn.ascii(hotline).count { it.isDigit() } in 5..15) null else t("সঠিক হটলাইন নম্বর দিন", "Enter a valid hotline number"),
            "office" to if (cash && office.isBlank()) t("ক্যাশ চালু থাকলে অফিসের ঠিকানা দিন", "Enter the office address when cash is on") else null,
            "bankName" to if (bank && bankName.isBlank()) t("ব্যাংক চালু থাকলে ব্যাংকের নাম দিন", "Enter the bank name when bank transfer is on") else null,
            "accName" to if (bank && accName.isBlank()) t("অ্যাকাউন্টের নাম দিন", "Enter the account name") else null,
            "accNo" to if (bank && accNo.isBlank()) t("অ্যাকাউন্ট নম্বর দিন", "Enter the account number") else null,
        )
        if (!cash && !bank) { Toaster.show(t("অন্তত একটি পেমেন্ট মাধ্যম চালু রাখুন", "Keep at least one payment method on")); return false }
        return errs.values.all { it == null }
    }

    val dirty = data != null && initFor != null && current() != data.copy(security = data.security.copy(mfaRequired = true))

    AdminScreen(
        header = { ScreenHeader(t("সেটিংস", "Settings"), onBack = an::back) },
        refreshing = loader.refreshing,
        onRefresh = { loader.refresh() },
    ) {
        loadItems(loader.state, { loader.load() }) {
            if (!can) item("ro") {
                InfoBox(t("শুধু দেখার অনুমতি আছে। পরিবর্তন করতে সুপার অ্যাডমিনের সাহায্য নিন।", "View-only access. Ask a super admin to make changes."), tone = Tone.WARN)
            }
            item("company") {
                ResoCard {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        T(t("কোম্পানির তথ্য", "Company details"), 17.sp, FontWeight.Bold)
                        Field(t("কোম্পানির নাম", "Company name"), name, { name = it; errs = errs - "name" }, enabled = can, error = errs["name"], maxLength = 100)
                        Field(t("যোগাযোগের ইমেইল", "Contact email"), email, { email = it.trim(); errs = errs - "email" }, enabled = can, error = errs["email"],
                            keyboard = KeyboardType.Email, placeholder = "info@example.com", maxLength = 254)
                        Field(t("হটলাইন নম্বর", "Hotline number"), hotline, { hotline = it; errs = errs - "hotline" }, enabled = can, error = errs["hotline"],
                            keyboard = KeyboardType.Phone, placeholder = t("০১৭XXXXXXXX", "017XXXXXXXX"), maxLength = 20)
                    }
                }
            }
            item("payment") {
                ResoCard {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        T(t("পেমেন্ট মাধ্যম", "Payment methods"), 17.sp, FontWeight.Bold)
                        ToggleRow(t("ক্যাশ", "Cash"), t("অফিসে নগদ টাকা গ্রহণ", "Cash received at the office"), cash, { cash = it }, enabled = can)
                        RowDivider()
                        ToggleRow(t("ব্যাংক ট্রান্সফার", "Bank transfer"), t("অ্যাকাউন্টের তথ্য", "Account details"), bank, { bank = it }, enabled = can)
                        RowDivider()
                        T(t("ইউজারকে যা দেখানো হবে", "Shown to shareholders"), 15.sp, FontWeight.Bold)
                        Field(t("অফিসের ঠিকানা (ক্যাশ জমার জন্য)", "Office address (for cash)"), office, { office = it; errs = errs - "office" }, enabled = can,
                            error = errs["office"], singleLine = false, minHeight = 70.dp, maxLength = 300, placeholder = t("[অফিসের ঠিকানা]", "[Office address]"))
                        Field(t("ব্যাংক ও শাখার নাম", "Bank and branch"), bankName, { bankName = it; errs = errs - "bankName" }, enabled = can, error = errs["bankName"],
                            maxLength = 100, placeholder = t("[ব্যাংকের নাম]", "[Bank name]"))
                        Field(t("অ্যাকাউন্টের নাম", "Account name"), accName, { accName = it; errs = errs - "accName" }, enabled = can, error = errs["accName"],
                            maxLength = 100, placeholder = t("[অ্যাকাউন্টের নাম]", "[Account name]"))
                        Field(t("অ্যাকাউন্ট নম্বর", "Account number"), accNo, { accNo = it; errs = errs - "accNo" }, enabled = can, error = errs["accNo"],
                            maxLength = 40, placeholder = t("[অ্যাকাউন্ট নম্বর]", "[Account number]"))
                        T(
                            t(
                                "ব্যাংক বা অফিসের তথ্য শুধু সুপার অ্যাডমিন বদলাতে পারেন, আর বদলালে সব শেয়ারহোল্ডারকে নোটিশ যায়।",
                                "Only a super admin can change bank or office details, and every shareholder is notified when they change.",
                            ),
                            13.sp, color = Reso.colors.muted,
                        )
                    }
                }
            }
            item("security") {
                ResoCard {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        T(t("নিরাপত্তা", "Security"), 17.sp, FontWeight.Bold)
                        AlwaysOnRow(t("২-ধাপ যাচাই (OTP)", "2-step verification (OTP)"), t("সব অ্যাডমিনের জন্য বাধ্যতামূলক", "Mandatory for all admins"))
                        RowDivider()
                        AlwaysOnRow(
                            t("অটো লগআউট", "Auto logout"),
                            t("${Bn.num(minutes)} মিনিট নিষ্ক্রিয় থাকলে", "After ${Bn.num(minutes)} minutes of inactivity"),
                        )
                        if (can) Row(verticalAlignment = Alignment.CenterVertically) {
                            T(t("সময় বদলান", "Change time"), 14.sp, color = Reso.colors.muted, modifier = Modifier.weight(1f))
                            StepButton(false, { minutes = (minutes - 1).coerceAtLeast(3) }, size = 40.dp, enabled = minutes > 3)
                            T(t("${Bn.num(minutes)} মিনিট", "${Bn.num(minutes)} min"), 15.sp, FontWeight.Bold, align = TextAlign.Center, modifier = Modifier.width(78.dp))
                            StepButton(true, { minutes = (minutes + 1).coerceAtMost(60) }, size = 40.dp, enabled = minutes < 60)
                        }
                        RowDivider()
                    }
                    MenuRow(ResoIcons.Key, t("পাসওয়ার্ড পরিবর্তন", "Change password"), onClick = { an.go(Routes.PASSWORD) })
                    T(
                        t(
                            "অন্যান্য সুরক্ষা: স্ক্রিনশট বন্ধ, রুট করা ফোনে অ্যাডমিন প্যানেল বন্ধ, প্রতিটি পরিবর্তন অডিট লগে রেকর্ড এবং সব সংযোগ HTTPS-এ এনক্রিপ্ট করা।",
                            "Also: screenshots blocked, admin panel disabled on rooted phones, every change recorded in the audit log, and all traffic encrypted with HTTPS.",
                        ),
                        13.sp, color = Reso.colors.muted, modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            if (can) item("backup") { BackupCard() }
            item("language") {
                ResoCard {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        T(t("ভাষা", "Language"), 17.sp, FontWeight.Bold)
                        LangPills()
                    }
                }
            }
            if (can) item("save") {
                PrimaryButton(t("পরিবর্তন সংরক্ষণ করুন", "Save changes"), {
                    if (!dirty) Toaster.show(t("কোনো পরিবর্তন নেই", "No changes")) else if (validate()) confirm = true
                }, loading = action.busy)
            }
        }
    }
    if (confirm) ConfirmDialog(
        t("সেটিংস সংরক্ষণ করবেন?", "Save settings?"),
        t(
            "পেমেন্টের তথ্য বদলালে শেয়ারহোল্ডাররা সাথে সাথে নতুন তথ্য দেখবেন। ভুল অ্যাকাউন্ট নম্বর দিলে টাকা ভুল জায়গায় যেতে পারে — আবার মিলিয়ে নিন।",
            "Shareholders see new payment details immediately. A wrong account number could send money to the wrong place — please double-check.",
        ),
        t("সংরক্ষণ করুন", "Save"),
        {
            confirm = false
            action.run({ Graph.adminApi.saveSettings(current()) }, onErr = { e ->
                if (e.fields.isNotEmpty()) errs = e.fields.mapValues { it.value }
                Toaster.show(e.message)
            }) { s ->
                loader.set(s)
                Session.idleTimeoutMs = s.security.autoLogoutMinutes.coerceIn(3, 60) * 60_000L
                AdminSession.companyName = s.company.name
                Toaster.show(t("সেটিংস সংরক্ষণ হয়েছে", "Settings saved"))
            }
        },
        { confirm = false },
    )
}

/** A security setting that is always on (shown checked, cannot be switched off). */
@Composable
private fun AlwaysOnRow(title: String, subtitle: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            T(title, 16.sp, FontWeight.Bold)
            T(subtitle, 13.sp, color = Reso.colors.muted)
        }
        net.rapti.resoshare.core.ui.CheckBox(true, {}, size = 26.dp, enabled = false)
    }
}

/** Human readable size, e.g. "1.8 MB" (digits follow the app language). */
private fun sizeText(bytes: Long?): String {
    if (bytes == null || bytes <= 0) return "—"
    val mb = bytes / (1024.0 * 1024.0)
    val text = if (mb >= 1) String.format(java.util.Locale.US, "%.1f MB", mb) else String.format(java.util.Locale.US, "%.0f KB", bytes / 1024.0)
    return Bn.digits(text)
}

@Composable
private fun BackupCard() {
    val c = Reso.colors
    val loader = rememberLoader("backup") { Graph.adminApi.backup() }
    val action = rememberAction()
    var ask by remember { mutableStateOf(false) }
    ResoCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            T(t("ডাটা ব্যাকআপ", "Data backup"), 17.sp, FontWeight.Bold)
            when (val s = loader.state) {
                is Load.Loading -> T(t("লোড হচ্ছে…", "Loading…"), 14.sp, color = c.muted)
                is Load.Failed -> T(s.message, 14.sp, color = c.red)
                is Load.Ready -> BackupInfo(s.data)
            }
            SecondaryButton(
                t("এখনই ব্যাকআপ নিন", "Back up now"), { ask = true }, height = 50.dp,
                loading = action.busy, enabled = loader.state is Load.Ready,
            )
            T(
                t(
                    "সার্ভার প্রতিদিন রাত ৩টায় নিজে থেকে ডাটাবেসের ব্যাকআপ নেয়। ব্যাকআপে শেয়ারহোল্ডারদের ব্যক্তিগত তথ্য থাকে — সার্ভারের বাইরে নিরাপদ জায়গায় কপি রাখুন।",
                    "The server backs up the database automatically every night at 3 AM. Backups contain personal data — keep an off-server copy somewhere safe.",
                ),
                13.sp, color = c.muted,
            )
        }
    }
    if (ask) ConfirmDialog(
        t("এখনই ব্যাকআপ নেবেন?", "Back up now?"),
        t("সার্ভার কিছুক্ষণের মধ্যে ডাটাবেসের একটি নতুন ব্যাকআপ নেবে। ১০ মিনিটে একবারই অনুরোধ করা যায়।", "The server will take a fresh database backup shortly. You can request this once every 10 minutes."),
        t("ব্যাকআপ নিন", "Back up"),
        {
            ask = false
            action.run({ Graph.adminApi.requestBackup() }) {
                loader.set(it)
                Toaster.show(t("ব্যাকআপের অনুরোধ পাঠানো হয়েছে", "Backup requested"))
            }
        },
        { ask = false },
    )
}

@Composable
private fun BackupInfo(b: BackupStatus) {
    val c = Reso.colors
    val (label, bg, fg) = when (b.status) {
        "ok" -> Triple(t("সফল", "Successful"), c.successBg, c.green)
        "failed" -> Triple(t("ব্যর্থ", "Failed"), c.dangerBg, c.red)
        "running" -> Triple(t("চলছে", "Running"), c.warnBg, c.brown)
        else -> Triple(t("হয়নি", "None yet"), c.track, c.muted)
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.bg).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                T(t("শেষ ব্যাকআপ", "Last backup"), 13.sp, color = c.muted)
                T(b.lastAt?.let { Bn.relative(it) } ?: "—", 16.sp, FontWeight.Bold)
            }
            Tag(label, bg, fg)
        }
        T(
            t("আকার ${sizeText(b.sizeBytes)} · শেষ ${Bn.num(b.keepDays)} দিনের ব্যাকআপ রাখা হয়", "Size ${sizeText(b.sizeBytes)} · last ${Bn.num(b.keepDays)} days kept"),
            13.sp, color = c.muted,
        )
        if (b.requestedAt != null) {
            T(t("অনুরোধ পাঠানো হয়েছে · কিছুক্ষণের মধ্যে ব্যাকআপ হবে", "Requested · the backup will run shortly"), 13.sp, color = c.brown)
        }
    }
}
