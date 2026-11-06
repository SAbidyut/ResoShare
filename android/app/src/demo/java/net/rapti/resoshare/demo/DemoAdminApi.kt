package net.rapti.resoshare.demo

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.rapti.resoshare.core.format.Bn
import net.rapti.resoshare.core.format.V
import net.rapti.resoshare.core.net.ApiException
import net.rapti.resoshare.data.api.AdminApi
import net.rapti.resoshare.data.api.AdminDashboard
import net.rapti.resoshare.data.api.AdminLoginRequest
import net.rapti.resoshare.data.api.AdminLoginResult
import net.rapti.resoshare.data.api.AdminOrder
import net.rapti.resoshare.data.api.AdminOrderPage
import net.rapti.resoshare.data.api.Approvals
import net.rapti.resoshare.data.api.AuditPage
import net.rapti.resoshare.data.api.Contact
import net.rapti.resoshare.data.api.Dividend
import net.rapti.resoshare.data.api.DividendRequest
import net.rapti.resoshare.data.api.Dividends
import net.rapti.resoshare.data.api.IssueShares
import net.rapti.resoshare.data.api.KycFile
import net.rapti.resoshare.data.api.KycRequest
import net.rapti.resoshare.data.api.MessageRequest
import net.rapti.resoshare.data.api.MfaRequest
import net.rapti.resoshare.data.api.MfaResult
import net.rapti.resoshare.data.api.NoticeRequest
import net.rapti.resoshare.data.api.PasswordChange
import net.rapti.resoshare.data.api.PendingCounts
import net.rapti.resoshare.data.api.Perm
import net.rapti.resoshare.data.api.Pool
import net.rapti.resoshare.data.api.RateHistoryItem
import net.rapti.resoshare.data.api.RatesAdmin
import net.rapti.resoshare.data.api.Reason
import net.rapti.resoshare.data.api.RefreshRequest
import net.rapti.resoshare.data.api.Report
import net.rapti.resoshare.data.api.Role
import net.rapti.resoshare.data.api.RoleCreate
import net.rapti.resoshare.data.api.RolePatch
import net.rapti.resoshare.data.api.RulesAdmin
import net.rapti.resoshare.data.api.RulesDraft
import net.rapti.resoshare.data.api.RulesPublish
import net.rapti.resoshare.data.api.RulesVersion
import net.rapti.resoshare.data.api.SaleToggle
import net.rapti.resoshare.data.api.SalesPoint
import net.rapti.resoshare.data.api.SalesStats
import net.rapti.resoshare.data.api.ScheduleItem
import net.rapti.resoshare.data.api.ScheduleUpdate
import net.rapti.resoshare.data.api.SentNotice
import net.rapti.resoshare.data.api.SentNoticePage
import net.rapti.resoshare.data.api.Settings
import net.rapti.resoshare.data.api.ShareholderDetail
import net.rapti.resoshare.data.api.ShareholderPage
import net.rapti.resoshare.data.api.ShareholderRow
import net.rapti.resoshare.data.api.SimpleRate
import net.rapti.resoshare.data.api.Staff
import net.rapti.resoshare.data.api.StaffCreate
import net.rapti.resoshare.data.api.StaffCreated
import net.rapti.resoshare.data.api.StaffList
import net.rapti.resoshare.data.api.StaffMe
import net.rapti.resoshare.data.api.StaffPatch
import net.rapti.resoshare.data.api.TempPassword
import net.rapti.resoshare.data.api.Tokens
import net.rapti.resoshare.demo.DemoStore.iso
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/** Offline admin API for the demo APK. Enforces the same permission model as the server. */
class DemoAdminApi : AdminApi {
    private val s = DemoStore
    private val lock = Mutex()
    @Volatile private var staffId: String? = null
    @Volatile private var pendingMfa: Pair<String, String>? = null // token -> staffId

    private suspend fun <T> op(block: () -> T): T {
        delay(200L + (0..250).random())
        return lock.withLock { block() }
    }

    private fun current(): Staff {
        val id = staffId ?: throw ApiException("UNAUTHORIZED", "আবার লগইন করুন", 401)
        return s.staff.firstOrNull { it.id == id && it.status == "active" } ?: throw ApiException("UNAUTHORIZED", "আবার লগইন করুন", 401)
    }

    private fun perms(st: Staff) = s.roles.firstOrNull { it.id == st.roleId }?.permissions ?: emptyList()

    private fun need(p: String): Staff {
        val st = current()
        if (p !in perms(st)) throw ApiException("FORBIDDEN", "এই কাজের অনুমতি আপনার নেই", 403)
        return st
    }

    private fun staffMe(st: Staff) = StaffMe(st.id, st.name, st.email, st.roleKey, st.roleName, perms(st), false)

    private fun tempPassword(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789"
        val r = SecureRandom()
        val symbols = "@#!%*"
        return (1..9).map { chars[r.nextInt(chars.length)] }.joinToString("") + symbols[r.nextInt(symbols.length)] + r.nextInt(10)
    }

    override suspend fun login(body: AdminLoginRequest): AdminLoginResult = op {
        val st = s.staff.firstOrNull { it.email.equals(body.email.trim(), true) && it.status == "active" }
        if (st == null || s.staffPasswords[st.id] != body.password) {
            s.log(body.email.take(40), "login", "ব্যর্থ অ্যাডমিন লগইন চেষ্টা", "bad")
            throw ApiException("UNAUTHORIZED", "ইমেইল বা পাসওয়ার্ড সঠিক নয়", 401)
        }
        val token = "mfa-" + s.uuid()
        pendingMfa = token to st.id
        AdminLoginResult(token, null)
    }

    override suspend fun mfa(body: MfaRequest): MfaResult = op {
        val p = pendingMfa
        if (p == null || p.first != body.mfaToken) throw ApiException("MFA_INVALID", "সময় শেষ, আবার লগইন করুন", 401)
        if (body.code != DemoStore.DEMO_OTP) throw ApiException("MFA_INVALID", "OTP সঠিক নয়", 401)
        pendingMfa = null
        staffId = p.second
        val st = current()
        s.log(st.name, "login", "অ্যাডমিন লগইন (OTP যাচাইকৃত)", "info")
        MfaResult(Tokens("demo-a-" + s.uuid(), "demo-r-" + s.uuid(), 900), staffMe(st))
    }

    override suspend fun refresh(body: RefreshRequest): Tokens = op {
        if (staffId == null) staffId = "s1"
        Tokens("demo-a-" + s.uuid(), "demo-r-" + s.uuid(), 900)
    }

    override suspend fun logout(body: RefreshRequest) { op { staffId = null } }
    override suspend fun me(): StaffMe = op { staffMe(current()) }

    override suspend fun changePassword(body: PasswordChange) = op {
        val st = current()
        if (s.staffPasswords[st.id] != body.currentPassword) throw ApiException("INVALID_INPUT", "বর্তমান পাসওয়ার্ড সঠিক নয়", 400, mapOf("currentPassword" to "বর্তমান পাসওয়ার্ড সঠিক নয়"))
        V.password(body.newPassword)?.let { throw ApiException("INVALID_INPUT", it, 400, mapOf("newPassword" to it)) }
        s.staffPasswords[st.id] = body.newPassword
    }

    override suspend fun dashboard(): AdminDashboard = op {
        need(Perm.VIEW)
        AdminDashboard(
            SimpleRate(s.today().toString(), s.todayRate()), s.pool(), s.users.size,
            PendingCounts(s.orders.count { it.status == "pending" }, s.users.count { it.kycStatus == "pending" }),
            s.orders.sortedByDescending { it.createdAt }.take(5).map(s::adminOrderDto),
        )
    }

    override suspend fun sales(period: String): SalesStats = op {
        need(Perm.VIEW)
        val t = s.today()
        val buckets: List<Pair<LocalDate, LocalDate>> = when (period) {
            "daily" -> (6 downTo 0).map { t.minusDays(it.toLong()).let { d -> d to d } }
            "weekly" -> (7 downTo 0).map { t.minusWeeks(it.toLong()).let { d -> d.minusDays(6) to d } }
            "monthly" -> (11 downTo 0).map { t.minusMonths(it.toLong()).let { d -> d.withDayOfMonth(1) to d.with(TemporalAdjusters.lastDayOfMonth()) } }
            else -> (4 downTo 0).map { t.minusYears(it.toLong()).let { d -> d.withDayOfYear(1) to d.with(TemporalAdjusters.lastDayOfYear()) } }
        }
        val confirmed = s.orders.filter { it.status == "confirmed" }
        val pts = buckets.map { (a, b) ->
            val inB = confirmed.filter { val d = (it.decidedAt ?: it.createdAt).atZone(Bn.DHAKA).toLocalDate(); !d.isBefore(a) && !d.isAfter(b) }
            SalesPoint(a.toString(), inB.sumOf { it.qty }, inB.sumOf { it.qty * it.rate })
        }
        SalesStats(period, pts, pts.sumOf { it.shares }, pts.sumOf { it.amountPaisa },
            if (pts.size >= 2) pts.last().shares - pts[pts.size - 2].shares else 0)
    }

    private fun row(u: DemoStore.DUser) = ShareholderRow(u.id, u.shareholderNo, u.name, s.sharesOf(u), u.kycStatus, u.accountStatus)

    override suspend fun shareholders(q: String?, status: String, cursor: String?, limit: Int): ShareholderPage = op {
        need(Perm.VIEW)
        val query = q?.trim()?.lowercase().orEmpty()
        val asciiQ = Bn.ascii(query)
        val list = s.users.filter {
            when (status) {
                "verified" -> it.kycStatus == "verified" && it.accountStatus == "active"
                "pending" -> it.kycStatus != "verified" && it.accountStatus == "active"
                "suspended" -> it.accountStatus == "suspended"
                else -> true
            }
        }.filter {
            query.isEmpty() || it.name.lowercase().contains(query) || it.shareholderNo.lowercase().contains(asciiQ) ||
                it.mobile == V.normalizeMobile(asciiQ) || it.email.equals(query, ignoreCase = true)
        }.sortedByDescending { s.sharesOf(it) }
        ShareholderPage(list.map(::row), s.users.size, null, net.rapti.resoshare.data.api.StatusCounts(
            s.users.size,
            s.users.count { it.kycStatus == "verified" && it.accountStatus == "active" },
            s.users.count { it.kycStatus != "verified" && it.accountStatus == "active" },
            s.users.count { it.accountStatus == "suspended" },
        ))
    }

    private fun mask(m: String) = if (m.length >= 11) Bn.digits(m.take(5)) + "-••••••" else "••••"
    private fun maskEmail(e: String) = e.substringBefore('@').take(1) + "•••@" + e.substringAfter('@')

    override suspend fun shareholder(id: String): ShareholderDetail = op {
        need(Perm.VIEW)
        val u = s.user(id)
        ShareholderDetail(
            u.id, u.shareholderNo, u.name, mask(u.mobile), maskEmail(u.email), u.kycStatus, u.kycNote, u.accountStatus,
            u.joinedAt.iso(), u.dob.toString(), V.maskId(u.nid), s.sharesOf(u), s.paidOf(u), s.sharesOf(u) * s.todayRate(),
            s.nomineesDto(u), s.orders.filter { it.userId == u.id }.sortedByDescending { it.createdAt }.map(s::orderDto),
            listOf(KycFile("${u.id}:nid_front", "nid_front"), KycFile("${u.id}:nid_back", "nid_back"), KycFile("${u.id}:photo", "photo")),
        )
    }

    override suspend fun contact(id: String): Contact = op {
        val st = need(Perm.VIEW)
        val u = s.user(id)
        s.log(st.name, "shareholder", "${u.name}-এর যোগাযোগের তথ্য দেখেছেন", "info")
        Contact(u.mobile, u.email)
    }

    override suspend fun shareholderCertificate(id: String): net.rapti.resoshare.data.api.Certificate = op {
        val st = need(Perm.VIEW)
        val u = s.user(id)
        val shares = s.sharesOf(u)
        if (shares == 0) throw ApiException("NOT_FOUND", "এই শেয়ারহোল্ডারের এখনো কোনো শেয়ার নেই", 404)
        val last = s.orders.filter { it.userId == u.id && it.status == "confirmed" }.maxOf { it.decidedAt ?: it.createdAt }
        val issued = last.atZone(Bn.DHAKA).toLocalDate().toString()
        val cert = "RA-CERT-%05d".format(u.no)
        s.log(st.name, "shareholder", "${u.name}-এর সার্টিফিকেট দেখা হয়েছে", "info")
        net.rapti.resoshare.data.api.Certificate(cert, u.shareholderNo, u.name, shares, issued, "https://share.rapti.net/verify/$cert?s=demo", s.settings.company.name)
    }

    private var lastBackup = java.time.Instant.now().minus(java.time.Duration.ofHours(17))
    private var backupRequested: java.time.Instant? = null

    override suspend fun backup(): net.rapti.resoshare.data.api.BackupStatus = op {
        need(Perm.SETTINGS)
        net.rapti.resoshare.data.api.BackupStatus(lastBackup.iso(), "ok", 1_843_200, 14, backupRequested?.iso())
    }

    override suspend fun requestBackup(): net.rapti.resoshare.data.api.BackupStatus = op {
        val st = need(Perm.SETTINGS)
        val req = backupRequested
        if (req != null && req.isAfter(s.now().minusSeconds(600))) throw ApiException("TOO_MANY_REQUESTS", "১০ মিনিটে একবারই ব্যাকআপ নেওয়া যায়", 429)
        backupRequested = s.now(); lastBackup = s.now()
        s.log(st.name, "settings", "ডাটাবেস ব্যাকআপের অনুরোধ", "info")
        net.rapti.resoshare.data.api.BackupStatus(lastBackup.iso(), "ok", 1_843_200, 14, backupRequested?.iso())
    }

    override suspend fun suspend(id: String, body: Reason) = op {
        val st = need(Perm.SETTINGS)
        val u = s.user(id)
        u.accountStatus = "suspended"
        s.log(st.name, "shareholder", "${u.name}-এর অ্যাকাউন্ট স্থগিত: ${body.reason}", "bad")
    }

    override suspend fun activate(id: String) = op {
        val st = need(Perm.SETTINGS)
        val u = s.user(id)
        u.accountStatus = "active"
        s.log(st.name, "shareholder", "${u.name}-এর অ্যাকাউন্ট আবার চালু", "ok")
    }

    override suspend fun resetUserPassword(id: String): TempPassword = op {
        val st = need(Perm.SETTINGS)
        val u = s.user(id)
        val p = tempPassword()
        u.password = p; u.mustChange = true
        s.log(st.name, "shareholder", "${u.name}-এর পাসওয়ার্ড রিসেট", "warn")
        TempPassword(p)
    }

    override suspend fun message(id: String, body: MessageRequest) = op {
        val st = need(Perm.NOTICES)
        val u = s.user(id)
        V.required(body.title, "শিরোনাম", 3, 120)?.let { throw ApiException("INVALID_INPUT", it, 400) }
        V.required(body.body, "বার্তা", 3, 2000)?.let { throw ApiException("INVALID_INPUT", it, 400) }
        s.notify(body.title.trim(), body.body.trim(), "message", "user", setOf(u.id), st.name)
        s.log(st.name, "content", "${u.name}-কে বার্তা পাঠানো হয়েছে", "info")
    }

    override suspend fun kycFile(fileId: String): ResponseBody = op {
        need(Perm.KYC)
        val kind = fileId.substringAfter(':')
        val label = when (kind) { "nid_front" -> "NID সামনে"; "nid_back" -> "NID পেছনে"; else -> "ছবি" }
        val bmp = Bitmap.createBitmap(640, 400, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.rgb(230, 236, 248))
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(11, 29, 58); textSize = 40f; textAlign = Paint.Align.CENTER }
        c.drawText("$label (ডেমো ছবি)", 320f, 210f, p)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
        out.toByteArray().toResponseBody("image/jpeg".toMediaType())
    }

    override suspend fun approvals(): Approvals = op {
        need(Perm.VIEW)
        Approvals(
            s.orders.filter { it.status == "pending" }.sortedByDescending { it.createdAt }.map(s::adminOrderDto),
            s.users.filter { it.kycStatus == "pending" }.map {
                KycRequest(it.id, it.shareholderNo, it.name, it.submittedAt.iso(), V.maskId(it.nid), it.dob.toString(),
                    listOf(KycFile("${it.id}:nid_front", "nid_front"), KycFile("${it.id}:nid_back", "nid_back"), KycFile("${it.id}:photo", "photo")))
            },
        )
    }

    private fun pendingOrder(id: String) = s.orders.firstOrNull { it.id == id }?.also {
        if (it.status != "pending") throw ApiException("CONFLICT", "এই অর্ডারটি ইতোমধ্যে নিষ্পত্তি হয়েছে", 409)
    } ?: throw ApiException("NOT_FOUND", "অর্ডার পাওয়া যায়নি", 404)

    override suspend fun approveOrder(id: String): AdminOrder = op {
        val st = need(Perm.PAYMENT)
        val o = pendingOrder(id)
        if (s.pool().remainingShares < o.qty) throw ApiException("INSUFFICIENT_SHARES", "অবশিষ্ট শেয়ার যথেষ্ট নয়", 409)
        o.status = "confirmed"; o.decidedAt = s.now()
        val u = s.user(o.userId)
        s.notify(if (o.method == "cash") "আপনার ক্যাশ জমা যাচাই হয়েছে" else "আপনার ব্যাংক ট্রান্সফার যাচাই হয়েছে",
            "${Bn.num(o.qty)}টি শেয়ার আপনার প্রোফাইলে যুক্ত হয়েছে।", "payment", "user", setOf(u.id), st.name)
        s.log(st.name, "approval", "${u.name}-এর ${Bn.money(o.qty * o.rate)} পেমেন্ট অনুমোদিত", "ok")
        s.adminOrderDto(o)
    }

    override suspend fun rejectOrder(id: String, body: Reason): AdminOrder = op {
        val st = need(Perm.PAYMENT)
        val o = pendingOrder(id)
        V.required(body.reason, "কারণ", 3, 300)?.let { throw ApiException("INVALID_INPUT", it, 400) }
        o.status = "cancelled"; o.note = body.reason.trim(); o.decidedAt = s.now()
        val u = s.user(o.userId)
        s.notify("আপনার পেমেন্ট বাতিল হয়েছে", "কারণ: ${body.reason.trim()}", "payment", "user", setOf(u.id), st.name)
        s.log(st.name, "approval", "${u.name}-এর ${Bn.money(o.qty * o.rate)} পেমেন্ট বাতিল", "bad")
        s.adminOrderDto(o)
    }

    override suspend fun approveKyc(shareholderId: String) = op {
        val st = need(Perm.KYC)
        val u = s.user(shareholderId)
        if (u.kycStatus != "pending") throw ApiException("CONFLICT", "এই KYC ইতোমধ্যে নিষ্পত্তি হয়েছে", 409)
        u.kycStatus = "verified"; u.kycNote = null
        s.notify("KYC যাচাই সম্পন্ন", "আপনার পরিচয় যাচাই হয়েছে। এখন শেয়ার কিনতে পারবেন।", "kyc", "user", setOf(u.id), st.name)
        s.log(st.name, "approval", "${u.name}-এর KYC যাচাই সম্পন্ন", "ok")
    }

    override suspend fun rejectKyc(shareholderId: String, body: Reason) = op {
        val st = need(Perm.KYC)
        val u = s.user(shareholderId)
        if (u.kycStatus != "pending") throw ApiException("CONFLICT", "এই KYC ইতোমধ্যে নিষ্পত্তি হয়েছে", 409)
        u.kycStatus = "rejected"; u.kycNote = body.reason.trim()
        s.notify("KYC বাতিল হয়েছে", "কারণ: ${body.reason.trim()}। সঠিক তথ্য দিয়ে আবার জমা দিন।", "kyc", "user", setOf(u.id), st.name)
        s.log(st.name, "approval", "${u.name}-এর KYC বাতিল", "bad")
    }

    override suspend fun orders(status: String, q: String?, cursor: String?, limit: Int): AdminOrderPage = op {
        need(Perm.VIEW)
        val query = q?.trim()?.lowercase().orEmpty()
        val list = s.orders.filter { status == "all" || it.status == status }
            .map(s::adminOrderDto)
            .filter { query.isEmpty() || it.shareholderName.lowercase().contains(query) || it.orderNo.lowercase().contains(Bn.ascii(query)) || (it.reference ?: "").lowercase().contains(Bn.ascii(query)) }
            .sortedByDescending { it.createdAt }
        val t = s.today()
        val confirmed = s.orders.filter { it.status == "confirmed" }
        AdminOrderPage(
            list, null,
            confirmed.filter { (it.decidedAt ?: it.createdAt).atZone(Bn.DHAKA).toLocalDate() == t }.sumOf { it.qty * it.rate },
            confirmed.filter { val d = (it.decidedAt ?: it.createdAt).atZone(Bn.DHAKA).toLocalDate(); d.year == t.year && d.month == t.month }.sumOf { it.qty * it.rate },
        )
    }

    private fun ratesDto(): RatesAdmin {
        val t = s.today()
        val hist = (0 until 30).map { t.minusDays(it.toLong()) }.map { d ->
            RateHistoryItem(d.toString(), s.rateOn(d), s.rateOn(d) - s.rateOn(d.minusDays(1)), s.rateSetBy[d])
        }
        return RatesAdmin(
            SimpleRate(t.toString(), s.todayRate()),
            SimpleRate(t.plusDays(1).toString(), s.rateOn(t.plusDays(1))),
            (1..15).map { t.plusDays(it.toLong()) }.map { d -> ScheduleItem(d.toString(), s.rates[d]) },
            hist,
        )
    }

    override suspend fun rates(): RatesAdmin = op { need(Perm.VIEW); ratesDto() }

    override suspend fun saveSchedule(body: ScheduleUpdate): RatesAdmin = op {
        val st = need(Perm.RATE)
        val t = s.today()
        body.items.forEach {
            val d = runCatching { LocalDate.parse(it.date) }.getOrNull() ?: throw ApiException("INVALID_INPUT", "তারিখ সঠিক নয়", 400)
            if (!d.isAfter(t) || d.isAfter(t.plusDays(15))) throw ApiException("INVALID_INPUT", "শুধু আগামী ১৫ দিনের রেট বসানো যায়", 400)
            if (it.ratePaisa !in 1..10_000_000_000L) throw ApiException("INVALID_INPUT", "রেট সঠিক নয়", 400)
        }
        body.items.forEach {
            val d = LocalDate.parse(it.date)
            s.rates[d] = it.ratePaisa; s.rateSetBy[d] = st.name
            if (body.notify) s.rateNotify.add(d) else s.rateNotify.remove(d)
        }
        s.log(st.name, "rate", "শেয়ারের রেট সূচি হালনাগাদ (${Bn.num(body.items.size)} দিন)", "warn")
        ratesDto()
    }

    override suspend fun pool(): Pool = op { need(Perm.VIEW); s.pool() }

    override suspend fun setSale(body: SaleToggle): Pool = op {
        val st = need(Perm.RATE)
        s.saleEnabled = body.saleEnabled
        s.log(st.name, "settings", if (body.saleEnabled) "শেয়ার বিক্রি চালু করা হয়েছে" else "শেয়ার বিক্রি বন্ধ করা হয়েছে", "warn")
        s.pool()
    }

    override suspend fun issueShares(body: IssueShares): Pool = op {
        val st = need(Perm.SETTINGS)
        if (body.additionalShares !in 1..10_000_000) throw ApiException("INVALID_INPUT", "শেয়ারের সংখ্যা সঠিক নয়", 400)
        s.totalShares += body.additionalShares
        s.log(st.name, "settings", "নতুন ${Bn.num(body.additionalShares)}টি শেয়ার ইস্যু", "warn")
        s.notify("নতুন শেয়ার বিক্রি শুরু", "নতুন ইস্যুর শেয়ার কেনার জন্য এখন উন্মুক্ত।", "company", by = st.name)
        s.pool()
    }

    override suspend fun dividends(): Dividends = op { need(Perm.VIEW); Dividends(s.dividends.toList(), s.sold()) }

    override suspend fun declareDividend(body: DividendRequest): Dividend = op {
        val st = need(Perm.DIVIDEND)
        if (!Regex("^\\d{4}$").matches(body.fiscalYear)) throw ApiException("INVALID_INPUT", "অর্থবছর ৪ অঙ্কে লিখুন", 400)
        if (s.dividends.any { it.fiscalYear == body.fiscalYear }) throw ApiException("CONFLICT", "এই অর্থবছরের ডিভিডেন্ড আগেই ঘোষণা হয়েছে", 409)
        if (body.perSharePaisa <= 0) throw ApiException("INVALID_INPUT", "প্রতি শেয়ারে ডিভিডেন্ড দিন", 400)
        val date = runCatching { LocalDate.parse(body.distributionDate) }.getOrNull() ?: throw ApiException("INVALID_INPUT", "বিতরণের তারিখ সঠিক নয়", 400)
        if (date.isBefore(s.today())) throw ApiException("INVALID_INPUT", "বিতরণের তারিখ আজ বা পরে হতে হবে", 400)
        val sold = s.sold()
        val d = Dividend(s.uuid(), body.fiscalYear, body.perSharePaisa, body.distributionDate, sold, sold * body.perSharePaisa,
            if (date.isAfter(s.today())) "declared" else "distributed", s.now().iso())
        s.dividends.add(0, d)
        s.notify("${Bn.digits(body.fiscalYear)} অর্থবছরের ডিভিডেন্ড ঘোষণা", "প্রতি শেয়ারে ${Bn.money(body.perSharePaisa)}। বিতরণের তারিখ: ${Bn.date(date)}।", "dividend", by = st.name)
        s.log(st.name, "content", "${Bn.digits(body.fiscalYear)} অর্থবছরের ডিভিডেন্ড ঘোষণা (মোট ${Bn.money(d.totalPaisa)})", "warn")
        d
    }

    override suspend fun report(type: String, from: String?, to: String?): Report = op {
        val st = need(Perm.REPORTS)
        val a = from?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: LocalDate.of(2000, 1, 1)
        val b = to?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: s.today()
        fun inRange(o: DemoStore.DOrder) = o.createdAt.atZone(Bn.DHAKA).toLocalDate().let { !it.isBefore(a) && !it.isAfter(b) }
        val status = mapOf("confirmed" to "নিশ্চিত", "pending" to "যাচাই চলছে", "cancelled" to "বাতিল")
        val kyc = mapOf("verified" to "যাচাইকৃত", "rejected" to "বাতিল", "pending" to "অপেক্ষমাণ")
        // Same shape as the server: ASCII money "1000.00" and ISO dates (spreadsheet friendly).
        fun plain(p: Long) = "%d.%02d".format(p / 100, p % 100)
        fun day(i: java.time.Instant) = i.atZone(Bn.DHAKA).toLocalDate().toString()
        fun method(m: String) = if (m == "cash") "ক্যাশ" else "ব্যাংক ট্রান্সফার"
        val r = when (type) {
            "sales" -> Report(type, "বিক্রয় রিপোর্ট", listOf("তারিখ", "অর্ডার নম্বর", "শেয়ারহোল্ডার ID", "নাম", "শেয়ার", "রেট (৳)", "মোট (৳)", "মাধ্যম"),
                s.orders.filter { it.status == "confirmed" && inRange(it) }.sortedBy { it.createdAt }.map { o ->
                    val u = s.user(o.userId)
                    listOf(day(o.decidedAt ?: o.createdAt), "ORD-%06d".format(o.no), u.shareholderNo, u.name, o.qty.toString(), plain(o.rate), plain(o.qty * o.rate), method(o.method))
                }, s.now().iso())
            "shareholders" -> Report(type, "শেয়ারহোল্ডার তালিকা", listOf("শেয়ারহোল্ডার ID", "নাম", "মোবাইল", "KYC", "অবস্থা", "শেয়ার", "মোট পরিশোধ (৳)", "যোগদান"),
                s.users.sortedBy { it.no }.map { u -> listOf(u.shareholderNo, u.name, u.mobile.take(5) + "-••••••", kyc[u.kycStatus] ?: u.kycStatus,
                    if (u.accountStatus == "active") "সক্রিয়" else "স্থগিত", s.sharesOf(u).toString(), plain(s.paidOf(u)), day(u.joinedAt)) }, s.now().iso())
            "transactions" -> Report(type, "লেনদেন বিবরণী", listOf("তারিখ", "অর্ডার নম্বর", "শেয়ারহোল্ডার ID", "নাম", "শেয়ার", "মোট (৳)", "মাধ্যম", "রেফারেন্স", "অবস্থা"),
                s.orders.filter { inRange(it) }.sortedBy { it.createdAt }.map { o ->
                    val u = s.user(o.userId)
                    listOf(day(o.createdAt), "ORD-%06d".format(o.no), u.shareholderNo, u.name, o.qty.toString(), plain(o.qty * o.rate), method(o.method), o.reference ?: "", status[o.status] ?: o.status)
                }, s.now().iso())
            "dividends" -> Report(type, "ডিভিডেন্ড রিপোর্ট", listOf("অর্থবছর", "বিতরণের তারিখ", "শেয়ার", "প্রতি শেয়ার (৳)", "মোট বিতরণ (৳)"),
                s.dividends.map { listOf(it.fiscalYear, it.distributionDate, it.totalShares.toString(), plain(it.perSharePaisa), plain(it.totalPaisa)) }, s.now().iso())
            "kyc" -> Report(type, "KYC অবস্থা", listOf("শেয়ারহোল্ডার ID", "নাম", "KYC অবস্থা", "জমার তারিখ", "মন্তব্য"),
                s.users.sortedBy { it.no }.map { listOf(it.shareholderNo, it.name, kyc[it.kycStatus] ?: it.kycStatus, day(it.submittedAt), it.kycNote ?: "") }, s.now().iso())
            else -> throw ApiException("INVALID_INPUT", "রিপোর্টের ধরন সঠিক নয়", 400)
        }
        s.log(st.name, "content", "${r.title} তৈরি করা হয়েছে", "info")
        r
    }

    override suspend fun sentNotices(cursor: String?): SentNoticePage = op {
        need(Perm.VIEW)
        SentNoticePage(s.notices.filter { it.category != "payment" && it.category != "kyc" }.map {
            SentNotice(it.id, it.title, it.body, it.category, it.audience, if (it.audience == "all") s.users.size else it.userIds.size, it.createdAt.iso(), it.byName)
        }, null)
    }

    override suspend fun sendNotice(body: NoticeRequest): SentNotice = op {
        val st = need(Perm.NOTICES)
        V.required(body.title, "শিরোনাম", 3, 120)?.let { throw ApiException("INVALID_INPUT", it, 400) }
        V.required(body.body, "বার্তা", 3, 2000)?.let { throw ApiException("INVALID_INPUT", it, 400) }
        if (body.audience == "selected" && body.userIds.isEmpty()) throw ApiException("INVALID_INPUT", "অন্তত একজন প্রাপক বাছাই করুন", 400)
        val ids = if (body.audience == "selected") body.userIds.filter { id -> s.users.any { it.id == id } }.toSet() else emptySet()
        s.notify(body.title.trim(), body.body.trim(), "company", if (body.audience == "selected") "selected" else "all", ids, st.name)
        s.log(st.name, "content", "নোটিশ পাঠানো: “${body.title.trim().take(40)}”", "info")
        val n = s.notices.first()
        SentNotice(n.id, n.title, n.body, n.category, n.audience, if (n.audience == "all") s.users.size else ids.size, n.createdAt.iso(), st.name)
    }

    private fun rulesDto() = RulesAdmin(s.rulesCurrent, s.rulesDraft, listOfNotNull(s.rulesDraft, s.rulesCurrent) + s.rulesHistory)

    override suspend fun rules(): RulesAdmin = op { need(Perm.VIEW); rulesDto() }

    private fun checkSections(list: List<net.rapti.resoshare.data.api.RuleSection>) {
        if (list.isEmpty() || list.size > 30) throw ApiException("INVALID_INPUT", "১ থেকে ৩০টি ধারা রাখুন", 400)
        list.forEach { sec ->
            V.required(sec.title, "ধারার নাম", 2, 100)?.let { throw ApiException("INVALID_INPUT", it, 400) }
            if (sec.items.isEmpty() || sec.items.size > 50) throw ApiException("INVALID_INPUT", "“${sec.title}” ধারায় অন্তত একটি নিয়ম দিন", 400)
            if (sec.items.any { it.isBlank() || it.length > 500 }) throw ApiException("INVALID_INPUT", "প্রতিটি নিয়ম ৫০০ অক্ষরের মধ্যে রাখুন", 400)
        }
    }

    override suspend fun saveRulesDraft(body: RulesDraft): RulesAdmin = op {
        val st = need(Perm.NOTICES)
        checkSections(body.sections)
        s.rulesDraft = RulesVersion(s.rulesDraft?.id ?: s.uuid(), "খসড়া", s.today().toString(), "", "draft", body.sections, s.now().iso(), st.name)
        s.log(st.name, "content", "নিয়ম ও রেগুলেশনের খসড়া সংরক্ষণ", "info")
        rulesDto()
    }

    override suspend fun publishRules(body: RulesPublish): RulesAdmin = op {
        val st = need(Perm.NOTICES)
        checkSections(body.sections)
        if (!Regex("^\\d+(\\.\\d+){0,2}$").matches(body.version)) throw ApiException("INVALID_INPUT", "সংস্করণ নম্বর যেমন ১.১ আকারে দিন", 400)
        val cur = s.rulesCurrent
        if (cur != null && compareVersion(body.version, cur.version) <= 0) throw ApiException("CONFLICT", "নতুন সংস্করণ নম্বর বর্তমান (${Bn.digits(cur.version)}) থেকে বড় হতে হবে", 409)
        val eff = runCatching { LocalDate.parse(body.effectiveDate) }.getOrNull() ?: throw ApiException("INVALID_INPUT", "কার্যকর তারিখ সঠিক নয়", 400)
        if (eff.isBefore(s.today())) throw ApiException("INVALID_INPUT", "কার্যকর তারিখ আজ বা পরে হতে হবে", 400)
        cur?.let { s.rulesHistory.add(0, it.copy(status = "archived")) }
        s.rulesCurrent = RulesVersion(s.uuid(), body.version, body.effectiveDate, body.summary.trim(), "published", body.sections, s.now().iso(), st.name)
        s.rulesDraft = null
        if (body.notify) s.notify("নিয়ম ও রেগুলেশন হালনাগাদ (সংস্করণ ${Bn.digits(body.version)})", body.summary.ifBlank { "নতুন নিয়ম প্রকাশিত হয়েছে।" }, "rules", by = st.name)
        s.log(st.name, "content", "নিয়ম ও রেগুলেশন সংস্করণ ${Bn.digits(body.version)} প্রকাশ", "warn")
        rulesDto()
    }

    private fun compareVersion(a: String, b: String): Int {
        val x = a.split('.').map { it.toIntOrNull() ?: 0 }
        val y = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = (x.getOrElse(i) { 0 }).compareTo(y.getOrElse(i) { 0 })
            if (d != 0) return d
        }
        return 0
    }

    override suspend fun staff(): StaffList = op { need(Perm.SETTINGS); StaffList(s.staff.toList(), s.roles.toList()) }

    override suspend fun createStaff(body: StaffCreate): StaffCreated = op {
        val st = need(Perm.SETTINGS)
        V.name(body.name)?.let { throw ApiException("INVALID_INPUT", it, 400) }
        V.email(body.email)?.let { throw ApiException("INVALID_INPUT", it, 400) }
        V.mobile(body.mobile)?.let { throw ApiException("INVALID_INPUT", it, 400) }
        if (s.staff.any { it.email.equals(body.email.trim(), true) }) throw ApiException("CONFLICT", "এই ইমেইলে আগেই স্টাফ আছে", 409)
        val role = s.roles.firstOrNull { it.id == body.roleId } ?: throw ApiException("INVALID_INPUT", "রোল বাছাই করুন", 400)
        val new = Staff(s.uuid(), body.name.trim(), body.email.trim().lowercase(), V.normalizeMobile(body.mobile), role.id, role.key, role.name, "active", null, false)
        s.staff += new
        val p = tempPassword()
        s.staffPasswords[new.id] = p
        s.log(st.name, "staff", "নতুন স্টাফ “${new.name}” যোগ (${role.name} রোল)", "info")
        StaffCreated(new, p)
    }

    override suspend fun patchStaff(id: String, body: StaffPatch): Staff = op {
        val st = need(Perm.SETTINGS)
        if (id == st.id) throw ApiException("FORBIDDEN", "নিজের রোল বা অবস্থা নিজে বদলানো যায় না", 403)
        val i = s.staff.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: throw ApiException("NOT_FOUND", "স্টাফ পাওয়া যায়নি", 404)
        var x = s.staff[i]
        body.roleId?.let { rid ->
            val r = s.roles.firstOrNull { it.id == rid } ?: throw ApiException("INVALID_INPUT", "রোল পাওয়া যায়নি", 400)
            x = x.copy(roleId = r.id, roleKey = r.key, roleName = r.name)
        }
        body.status?.let { x = x.copy(status = it) }
        val superAdmins = s.staff.mapIndexed { j, v -> if (j == i) x else v }.count { it.roleKey == "super_admin" && it.status == "active" }
        if (superAdmins == 0) throw ApiException("CONFLICT", "অন্তত একজন সক্রিয় সুপার অ্যাডমিন থাকতে হবে", 409)
        s.staff[i] = x
        s.log(st.name, "staff", "স্টাফ “${x.name}” হালনাগাদ (${x.roleName}, ${if (x.status == "active") "সক্রিয়" else "নিষ্ক্রিয়"})", "warn")
        x
    }

    override suspend fun resetStaff(id: String): TempPassword = op {
        val st = need(Perm.SETTINGS)
        val i = s.staff.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: throw ApiException("NOT_FOUND", "স্টাফ পাওয়া যায়নি", 404)
        val p = tempPassword()
        s.staffPasswords[id] = p
        s.staff[i] = s.staff[i].copy(mfaEnrolled = false)
        s.log(st.name, "staff", "স্টাফ “${s.staff[i].name}”-এর পাসওয়ার্ড ও OTP রিসেট", "warn")
        TempPassword(p)
    }

    override suspend fun unlockStaff(id: String) = op {
        val st = need(Perm.SETTINGS)
        val x = s.staff.firstOrNull { it.id == id } ?: throw ApiException("NOT_FOUND", "স্টাফ পাওয়া যায়নি", 404)
        s.log(st.name, "staff", "স্টাফ “${x.name}”-এর লগইন লক খোলা হয়েছে", "warn")
    }

    override suspend fun createRole(body: RoleCreate): Role = op {
        val st = need(Perm.SETTINGS)
        V.required(body.name, "রোলের নাম", 2, 40)?.let { throw ApiException("INVALID_INPUT", it, 400) }
        if (body.permissions.any { it !in Perm.ALL }) throw ApiException("INVALID_INPUT", "অনুমতি সঠিক নয়", 400)
        val r = Role(s.uuid(), "custom_" + s.roles.size, body.name.trim(), (body.permissions + Perm.VIEW).distinct(), false)
        s.roles += r
        s.log(st.name, "staff", "নতুন রোল “${r.name}” তৈরি", "info")
        r
    }

    override suspend fun patchRole(id: String, body: RolePatch): Role = op {
        val st = need(Perm.SETTINGS)
        val i = s.roles.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: throw ApiException("NOT_FOUND", "রোল পাওয়া যায়নি", 404)
        if (s.roles[i].isSystem) throw ApiException("FORBIDDEN", "সিস্টেম রোল বদলানো যায় না", 403)
        val r = s.roles[i].copy(name = body.name?.trim() ?: s.roles[i].name, permissions = (body.permissions ?: s.roles[i].permissions).filter { it in Perm.ALL })
        s.roles[i] = r
        s.log(st.name, "staff", "রোল “${r.name}” হালনাগাদ", "warn")
        r
    }

    override suspend fun audit(category: String?, cursor: String?): AuditPage = op {
        need(Perm.SETTINGS)
        AuditPage(s.audit.filter { category.isNullOrBlank() || it.category == category }, null)
    }

    override suspend fun settings(): Settings = op { need(Perm.VIEW); s.settings }

    override suspend fun saveSettings(body: Settings): Settings = op {
        val st = need(Perm.SETTINGS)
        V.required(body.company.name, "কোম্পানির নাম", 2, 100)?.let { throw ApiException("INVALID_INPUT", it, 400) }
        if (body.security.autoLogoutMinutes !in 3..60) throw ApiException("INVALID_INPUT", "অটো লগআউট ৩-৬০ মিনিটের মধ্যে দিন", 400)
        if (!body.payment.cashEnabled && !body.payment.bankEnabled) throw ApiException("INVALID_INPUT", "অন্তত একটি পেমেন্ট মাধ্যম চালু রাখুন", 400)
        s.settings = body.copy(security = body.security.copy(mfaRequired = true))
        s.log(st.name, "settings", "সেটিংস হালনাগাদ", "warn")
        s.settings
    }

    @Suppress("unused") private val keep = ChronoUnit.DAYS
}
