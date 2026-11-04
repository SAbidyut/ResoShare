package net.rapti.resoshare.demo

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.rapti.resoshare.core.format.Bn
import net.rapti.resoshare.core.format.V
import net.rapti.resoshare.core.net.ApiException
import net.rapti.resoshare.core.net.AppJson
import net.rapti.resoshare.data.api.Certificate
import net.rapti.resoshare.data.api.Chart
import net.rapti.resoshare.data.api.ChartPoint
import net.rapti.resoshare.data.api.Dashboard
import net.rapti.resoshare.data.api.DayRate
import net.rapti.resoshare.data.api.Holding
import net.rapti.resoshare.data.api.KycData
import net.rapti.resoshare.data.api.LoginRequest
import net.rapti.resoshare.data.api.LoginResult
import net.rapti.resoshare.data.api.Me
import net.rapti.resoshare.data.api.Nominee
import net.rapti.resoshare.data.api.NomineeInput
import net.rapti.resoshare.data.api.NomineesUpdate
import net.rapti.resoshare.data.api.Notice
import net.rapti.resoshare.data.api.NoticePage
import net.rapti.resoshare.data.api.Order
import net.rapti.resoshare.data.api.OrderPage
import net.rapti.resoshare.data.api.OrderRequest
import net.rapti.resoshare.data.api.PasswordChange
import net.rapti.resoshare.data.api.PaymentInfo
import net.rapti.resoshare.data.api.PublicInfo
import net.rapti.resoshare.data.api.RefreshRequest
import net.rapti.resoshare.data.api.RegisterData
import net.rapti.resoshare.data.api.RegisterResult
import net.rapti.resoshare.data.api.Rules
import net.rapti.resoshare.data.api.ShareholderApi
import net.rapti.resoshare.data.api.Tokens
import net.rapti.resoshare.demo.DemoStore.iso
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okio.Buffer
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/** Offline shareholder API for the demo APK. */
class DemoShareholderApi : ShareholderApi {
    private val s = DemoStore
    private val lock = Mutex()
    private val bg = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var currentUserId: String? = null

    private suspend fun <T> op(block: () -> T): T {
        delay(220L + (0..250).random())
        return lock.withLock { block() }
    }

    private fun cur(): DemoStore.DUser {
        val id = currentUserId ?: throw ApiException("UNAUTHORIZED", "আবার লগইন করুন", 401)
        val u = s.user(id)
        if (u.accountStatus == "suspended") throw ApiException("ACCOUNT_SUSPENDED", "আপনার অ্যাকাউন্ট স্থগিত করা হয়েছে। হটলাইনে যোগাযোগ করুন।", 403)
        return u
    }

    private fun meDto(u: DemoStore.DUser) = Me(
        u.id, u.shareholderNo, u.name, u.mobile, u.email, u.kycStatus, u.kycNote, u.accountStatus, u.mustChange,
        u.joinedAt.iso(), s.nomineesDto(u),
    )

    private fun tokens() = Tokens("demo-access-" + s.uuid(), "demo-refresh-" + s.uuid(), 900)

    override suspend fun publicInfo() = op {
        PublicInfo(s.settings.company.name, s.settings.company.hotline, s.settings.company.email, s.settings.payment.officeAddress,
            s.todayRate(), s.todayRate() - s.rateOn(s.today().minusDays(1)))
    }

    override suspend fun rules(): Rules = op {
        val r = s.rulesCurrent ?: throw ApiException("NOT_FOUND", "নিয়ম এখনো প্রকাশ হয়নি", 404)
        Rules(r.version, r.effectiveDate, r.sections)
    }

    private fun validateNominees(list: List<NomineeInput>) {
        if (list.size !in 1..2) throw ApiException("INVALID_INPUT", "১ বা ২ জন নমিনি দিন", 400)
        list.forEach {
            V.name(it.name)?.let { m -> throw ApiException("INVALID_INPUT", "নমিনি: $m", 400) }
            V.relation(it.relation)?.let { m -> throw ApiException("INVALID_INPUT", m, 400) }
            V.nomineeId(it.idType, it.idNumber)?.let { m -> throw ApiException("INVALID_INPUT", m, 400) }
        }
        if (list.sumOf { it.sharePercent } != 100 || list.any { it.sharePercent !in 1..100 }) {
            throw ApiException("INVALID_INPUT", "নমিনিদের ভাগ মিলিয়ে ১০০% হতে হবে", 400)
        }
    }

    override suspend fun register(data: RequestBody, nidFront: MultipartBody.Part, nidBack: MultipartBody.Part, photo: MultipartBody.Part): RegisterResult {
        val raw = Buffer().also { data.writeTo(it) }.readUtf8()
        val d = AppJson.decodeFromString(RegisterData.serializer(), raw)
        return op {
            val mobile = V.normalizeMobile(d.mobile)
            listOfNotNull(V.name(d.fullName), V.mobile(mobile), V.email(d.email), V.password(d.password), V.nid(d.nidNumber))
                .firstOrNull()?.let { throw ApiException("INVALID_INPUT", it, 400) }
            if (!d.acceptRules) throw ApiException("INVALID_INPUT", "নিয়ম ও রেগুলেশন মানতে হবে", 400)
            validateNominees(d.nominees)
            if (s.users.any { it.mobile == mobile || it.email.equals(d.email.trim(), true) || it.nid == V.digitsOnly(d.nidNumber) }) {
                throw ApiException("CONFLICT", "এই মোবাইল, ইমেইল বা NID দিয়ে আগেই রেজিস্ট্রেশন হয়েছে", 409)
            }
            val u = DemoStore.DUser(
                s.uuid(), s.newUserNo(), d.fullName.trim(), mobile, d.email.trim().lowercase(), d.password, V.digitsOnly(d.nidNumber),
                LocalDate.parse(d.dob), "pending", null, "active", s.now(),
                d.nominees.map { DemoStore.DNominee(it.name.trim(), it.relation.trim(), it.idType, V.digitsOnly(it.idNumber), it.sharePercent) }.toMutableList(),
            )
            s.users += u
            s.log(u.name, "shareholder", "নতুন রেজিস্ট্রেশন: ${u.name} (${u.shareholderNo})", "info")
            RegisterResult(u.shareholderNo)
        }
    }

    override suspend fun login(body: LoginRequest): LoginResult = op {
        val id = body.identifier.trim()
        val mobile = V.normalizeMobile(id)
        val u = s.users.firstOrNull { it.mobile == mobile || it.email.equals(id, ignoreCase = true) }
        if (u == null || u.password != body.password) throw ApiException("UNAUTHORIZED", "মোবাইল/ইমেইল বা পাসওয়ার্ড সঠিক নয়", 401)
        if (u.accountStatus == "suspended") throw ApiException("ACCOUNT_SUSPENDED", "আপনার অ্যাকাউন্ট স্থগিত করা হয়েছে। হটলাইনে যোগাযোগ করুন।", 403)
        currentUserId = u.id
        LoginResult(tokens(), meDto(u))
    }

    override suspend fun refresh(body: RefreshRequest): Tokens = op {
        if (currentUserId == null) {
            // Demo: restore the sample account after an app restart.
            currentUserId = s.users.firstOrNull { it.mobile == DemoStore.DEMO_USER_MOBILE }?.id
        }
        tokens()
    }

    override suspend fun logout(body: RefreshRequest) { op { currentUserId = null } }

    override suspend fun me(): Me = op { meDto(cur()) }

    override suspend fun changePassword(body: PasswordChange) = op {
        val u = cur()
        if (u.password != body.currentPassword) throw ApiException("INVALID_INPUT", "বর্তমান পাসওয়ার্ড সঠিক নয়", 400, mapOf("currentPassword" to "বর্তমান পাসওয়ার্ড সঠিক নয়"))
        V.password(body.newPassword)?.let { throw ApiException("INVALID_INPUT", it, 400, mapOf("newPassword" to it)) }
        u.password = body.newPassword
        u.mustChange = false
    }

    override suspend fun dashboard(): Dashboard = op {
        val u = cur()
        val rate = s.todayRate()
        val shares = s.sharesOf(u)
        val paid = s.paidOf(u)
        val value = shares * rate
        val profit = value - paid
        Dashboard(
            meDto(u),
            DayRate(s.today().toString(), rate, s.rateOn(s.today().minusDays(1))),
            Holding(shares, paid, value, profit, if (paid > 0) (profit * 10_000 / paid).toInt() else 0),
            s.pool(),
            visibleNotices(u).count { u.id !in it.readBy },
            s.orders.filter { it.userId == u.id }.sortedByDescending { it.createdAt }.take(3).map(s::orderDto),
        )
    }

    override suspend fun orders(cursor: String?, limit: Int): OrderPage = op {
        val u = cur()
        OrderPage(s.orders.filter { it.userId == u.id }.sortedByDescending { it.createdAt }.map(s::orderDto), null)
    }

    override suspend fun createOrder(body: OrderRequest): Order = op {
        val u = cur()
        if (u.kycStatus != "verified") throw ApiException("KYC_REQUIRED", "KYC যাচাই সম্পন্ন হলে শেয়ার কিনতে পারবেন", 403)
        if (!body.acceptRules) throw ApiException("INVALID_INPUT", "নিয়ম ও রেগুলেশন মানতে টিক দিন", 400)
        if (!s.saleEnabled) throw ApiException("SALE_CLOSED", "এখন শেয়ার বিক্রি বন্ধ আছে", 409)
        if (body.quantity !in 1..10_000) throw ApiException("INVALID_INPUT", "শেয়ারের সংখ্যা সঠিক নয়", 400)
        val rate = s.todayRate()
        if (body.expectedRatePaisa != rate) throw ApiException("RATE_CHANGED", "শেয়ারের রেট বদলেছে। নতুন রেট দেখে আবার নিশ্চিত করুন।", 409, mapOf("currentRatePaisa" to rate.toString()))
        if (body.quantity > s.pool().remainingShares) throw ApiException("INSUFFICIENT_SHARES", "এত শেয়ার অবশিষ্ট নেই", 409)
        if (s.orders.count { it.userId == u.id && it.status == "pending" } >= 3) throw ApiException("TOO_MANY_PENDING", "আপনার ৩টি অর্ডার যাচাইয়ের অপেক্ষায় আছে। আগে সেগুলো নিষ্পত্তি হোক।", 409)
        val settings = s.settings.payment
        if (body.method == "cash" && !settings.cashEnabled || body.method == "bank" && !settings.bankEnabled || body.method !in setOf("cash", "bank")) {
            throw ApiException("INVALID_INPUT", "এই পেমেন্ট মাধ্যম এখন বন্ধ", 400)
        }
        val ref = if (body.method == "cash") s.newCashRef() else {
            V.reference(body.reference ?: "")?.let { throw ApiException("INVALID_INPUT", it, 400, mapOf("reference" to it)) }
            body.reference!!.trim()
        }
        val o = DemoStore.DOrder(s.uuid(), s.newOrderNo(), u.id, body.quantity, rate, body.method, ref, "pending", null, s.now(), null)
        s.orders += o
        s.log(u.name, "approval", "${u.name} ${Bn.num(o.qty)}টি শেয়ারের অর্ডার দিয়েছেন (${Bn.money(o.qty * o.rate)})", "info")
        s.orderDto(o)
    }

    override suspend fun cancelOrder(id: String): Order = op {
        val u = cur()
        val o = s.orders.firstOrNull { it.id == id && it.userId == u.id } ?: throw ApiException("NOT_FOUND", "অর্ডার পাওয়া যায়নি", 404)
        if (o.status != "pending") throw ApiException("CONFLICT", "শুধু অপেক্ষমাণ অর্ডার বাতিল করা যায়", 409)
        o.status = "cancelled"; o.note = "গ্রাহক বাতিল করেছেন"; o.decidedAt = s.now()
        s.orderDto(o)
    }

    override suspend fun certificate(): Certificate = op {
        val u = cur()
        val shares = s.sharesOf(u)
        if (shares == 0) throw ApiException("NOT_FOUND", "শেয়ার কেনা নিশ্চিত হলে সার্টিফিকেট পাবেন", 404)
        val last = s.orders.filter { it.userId == u.id && it.status == "confirmed" }.maxOf { it.decidedAt ?: it.createdAt }
        val issued = last.atZone(Bn.DHAKA).toLocalDate().toString()
        val cert = "RA-CERT-%05d".format(u.no)
        Certificate(cert, u.shareholderNo, u.name, shares, issued, "https://share.rapti.net/verify/$cert?s=demo${shares}x$issued", s.settings.company.name)
    }

    override suspend fun updateNominees(body: NomineesUpdate): List<Nominee> = op {
        val u = cur()
        validateNominees(body.nominees)
        u.nominees.clear()
        u.nominees += body.nominees.map { DemoStore.DNominee(it.name.trim(), it.relation.trim(), it.idType, V.digitsOnly(it.idNumber), it.sharePercent) }
        s.log(u.name, "shareholder", "${u.name} নমিনির তথ্য হালনাগাদ করেছেন", "info")
        s.nomineesDto(u)
    }

    override suspend fun resubmitKyc(data: RequestBody, nidFront: MultipartBody.Part, nidBack: MultipartBody.Part, photo: MultipartBody.Part) {
        val raw = Buffer().also { data.writeTo(it) }.readUtf8()
        val d = AppJson.decodeFromString(KycData.serializer(), raw)
        op {
            val u = cur()
            if (u.kycStatus != "rejected") throw ApiException("CONFLICT", "KYC আবার জমা দেওয়ার প্রয়োজন নেই", 409)
            u.nid = V.digitsOnly(d.nidNumber); u.dob = LocalDate.parse(d.dob)
            u.kycStatus = "pending"; u.kycNote = null; u.submittedAt = s.now()
        }
    }

    override suspend fun paymentInfo(): PaymentInfo = op {
        cur()
        val p = s.settings.payment
        PaymentInfo(p.cashEnabled, p.bankEnabled, p.officeAddress, p.bankName, p.accountName, p.accountNumber)
    }

    private fun visibleNotices(u: DemoStore.DUser) = s.notices.filter { it.audience == "all" || u.id in it.userIds }

    override suspend fun notices(cursor: String?, limit: Int): NoticePage = op {
        val u = cur()
        val list = visibleNotices(u)
        NoticePage(list.map { Notice(it.id, it.title, it.body, it.category, it.createdAt.iso(), u.id in it.readBy) }, null, list.count { u.id !in it.readBy })
    }

    override suspend fun markRead(id: String) = op {
        val u = cur()
        s.notices.firstOrNull { it.id == id }?.readBy?.add(u.id)
        Unit
    }

    override suspend fun markAllRead() = op {
        val u = cur()
        visibleNotices(u).forEach { it.readBy.add(u.id) }
    }

    override suspend fun chart(period: String): Chart = op {
        val u = cur()
        val t = s.today()
        val dates: List<LocalDate> = when (period) {
            "daily" -> (6 downTo 0).map { t.minusDays(it.toLong()) }
            "weekly" -> (7 downTo 0).map { if (it == 0) t else t.minusWeeks(it.toLong()).with(TemporalAdjusters.nextOrSame(java.time.DayOfWeek.FRIDAY)).let { d -> if (d.isAfter(t)) t else d } }
            "monthly" -> (11 downTo 0).map { if (it == 0) t else t.minusMonths(it.toLong()).with(TemporalAdjusters.lastDayOfMonth()) }
            "yearly" -> (4 downTo 0).map { if (it == 0) t else t.minusYears(it.toLong()).with(TemporalAdjusters.lastDayOfYear()) }
            else -> throw ApiException("INVALID_INPUT", "সময়কাল সঠিক নয়", 400)
        }
        val pts = dates.map { ChartPoint(it.toString(), s.rateOn(it)) }
        val first = pts.first().ratePaisa
        val last = pts.last().ratePaisa
        val prevMonthEnd = t.withDayOfMonth(1).minusDays(1)
        Chart(
            period, pts, last, last - s.rateOn(t.minusDays(1)), last - first,
            if (first > 0) ((last - first) * 10_000 / first).toInt() else 0,
            pts.maxOf { it.ratePaisa }, pts.minOf { it.ratePaisa }, last - s.rateOn(prevMonthEnd), s.sharesOf(u),
        )
    }
}
