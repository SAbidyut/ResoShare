package net.rapti.resoshare.demo

import net.rapti.resoshare.core.format.Bn
import net.rapti.resoshare.core.format.V
import net.rapti.resoshare.core.net.ApiException
import net.rapti.resoshare.data.api.AdminOrder
import net.rapti.resoshare.data.api.AuditEntry
import net.rapti.resoshare.data.api.Dividend
import net.rapti.resoshare.data.api.Nominee
import net.rapti.resoshare.data.api.Order
import net.rapti.resoshare.data.api.Pool
import net.rapti.resoshare.data.api.Role
import net.rapti.resoshare.data.api.RuleSection
import net.rapti.resoshare.data.api.RulesVersion
import net.rapti.resoshare.data.api.Settings
import net.rapti.resoshare.data.api.CompanySettings
import net.rapti.resoshare.data.api.PaymentSettings
import net.rapti.resoshare.data.api.SecuritySettings
import net.rapti.resoshare.data.api.Staff
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.TreeMap
import java.util.UUID
import kotlin.random.Random

/**
 * In-memory backend used only by `demo` builds. It mirrors the server's business rules
 * closely enough to exercise every screen offline. Nothing here is used in `live` builds.
 */
object DemoStore {
    const val DEMO_PASSWORD = "Demo@1234"
    const val DEMO_USER_MOBILE = "01911000124"
    const val DEMO_ADMIN_EMAIL = "admin@rapti.net"
    const val DEMO_OTP = "123456"

    class DNominee(var name: String, var relation: String, var idType: String, var idNumber: String, var percent: Int)

    class DUser(
        val id: String, val no: Int, var name: String, var mobile: String, var email: String, var password: String,
        var nid: String, var dob: LocalDate, var kycStatus: String, var kycNote: String?, var accountStatus: String,
        val joinedAt: Instant, val nominees: MutableList<DNominee>, var submittedAt: Instant = joinedAt,
        var mustChange: Boolean = false,
    ) { val shareholderNo get() = "RA-SH-%05d".format(no) }

    class DOrder(
        val id: String, val no: Int, val userId: String, val qty: Int, val rate: Long, val method: String,
        val reference: String?, var status: String, var note: String?, val createdAt: Instant, var decidedAt: Instant?,
    )

    class DNotice(
        val id: String, val title: String, val body: String, val category: String, val createdAt: Instant,
        val audience: String, val userIds: Set<String>, val readBy: MutableSet<String> = mutableSetOf(), val byName: String,
    )

    val users = mutableListOf<DUser>()
    val orders = mutableListOf<DOrder>()
    val notices = mutableListOf<DNotice>()
    val rates = TreeMap<LocalDate, Long>()
    val rateSetBy = HashMap<LocalDate, String>()
    val rateNotify = HashSet<LocalDate>()
    var totalShares = 10_000
    var saleEnabled = true
    val dividends = mutableListOf<Dividend>()
    var rulesCurrent: RulesVersion? = null
    var rulesDraft: RulesVersion? = null
    val rulesHistory = mutableListOf<RulesVersion>()
    val roles = mutableListOf<Role>()
    val staff = mutableListOf<Staff>()
    val staffPasswords = HashMap<String, String>()
    val audit = mutableListOf<AuditEntry>()
    var settings = Settings(
        CompanySettings("Resonance Automation", "info@rapti.net", "01617-345534"),
        PaymentSettings(true, true, "ভূঁইঘর, নারায়ণগঞ্জ (RAPTI অফিস)", "[ব্যাংকের নাম]", "Resonance Automation", "[অ্যাকাউন্ট নম্বর]"),
        SecuritySettings(5, true),
    )
    private var orderSeq = 140
    private var cashSeq = 140
    private var userSeq = 160

    fun now(): Instant = Instant.now()
    fun uuid() = UUID.randomUUID().toString()
    fun today(): LocalDate = Bn.today()

    fun rateOn(d: LocalDate): Long = rates.floorEntry(d)?.value ?: rates.firstEntry()?.value ?: 100_000L
    fun todayRate() = rateOn(today())

    fun sold() = orders.filter { it.status == "confirmed" }.sumOf { it.qty }
    fun pool() = Pool(totalShares, sold(), totalShares - sold(), saleEnabled)

    fun sharesOf(u: DUser) = orders.filter { it.userId == u.id && it.status == "confirmed" }.sumOf { it.qty }
    fun paidOf(u: DUser) = orders.filter { it.userId == u.id && it.status == "confirmed" }.sumOf { it.qty * it.rate }

    fun user(id: String) = users.firstOrNull { it.id == id } ?: throw ApiException("NOT_FOUND", "শেয়ারহোল্ডার পাওয়া যায়নি", 404)

    fun Instant.iso(): String = toString()

    fun orderDto(o: DOrder) = Order(
        o.id, "ORD-%06d".format(o.no), o.qty, o.rate, o.qty * o.rate, o.method, o.reference, o.status, o.note,
        o.createdAt.iso(), o.decidedAt?.iso(),
    )

    fun adminOrderDto(o: DOrder): AdminOrder {
        val u = user(o.userId)
        val d = orderDto(o)
        return AdminOrder(d.id, d.orderNo, d.quantity, d.ratePaisa, d.amountPaisa, d.method, d.reference, d.status, d.note,
            d.createdAt, d.decidedAt, u.id, u.shareholderNo, u.name)
    }

    fun nomineesDto(u: DUser) = u.nominees.mapIndexed { i, n ->
        Nominee(i + 1, n.name, n.relation, n.idType, V.maskId(n.idNumber), n.percent)
    }

    fun log(actor: String, category: String, message: String, tone: String) {
        audit.add(0, AuditEntry(uuid(), now().iso(), actor, category, message, tone))
    }

    fun notify(title: String, body: String, category: String, audience: String = "all", userIds: Set<String> = emptySet(), by: String = "সিস্টেম") {
        notices.add(0, DNotice(uuid(), title, body, category, now(), audience, userIds, byName = by))
    }

    fun newOrderNo() = ++orderSeq
    fun newCashRef() = "CASH-%04d".format(++cashSeq)
    fun newUserNo() = ++userSeq

    // ------------------------------------------------------------------ seed

    private val NAMES = listOf(
        "আবদুল করিম", "নাজমুল হাসান", "সুমাইয়া ইসলাম", "ফাতেমা আক্তার", "রুবিনা ইয়াসমিন", "তানভীর আহমেদ",
        "মাহমুদুল হক", "শাহানা পারভীন", "রাকিবুল ইসলাম", "জান্নাতুল ফেরদৌস", "মোঃ সাইফুল্লাহ", "নুসরাত জাহান",
        "আরিফুর রহমান", "মিজানুর রহমান", "সালমা বেগম", "কামরুল হাসান", "শারমিন সুলতানা", "ইমরান হোসেন",
    )

    init { seed() }

    private fun seed() {
        val rnd = Random(2026)
        val t = today()
        // Rate history: walk backwards ~800 days from ৳১,০০০.০০ with an upward drift.
        var r = 100_000L
        rates[t] = r
        for (i in 1..800) {
            val step = listOf(-1500L, -1000L, -500L, -500L, 0L, 0L, 500L, 1000L, 1500L, 2500L)[rnd.nextInt(10)]
            r = (r - step / 2 - if (rnd.nextInt(3) == 0) 500 else 0).coerceAtLeast(55_000L)
            rates[t.minusDays(i.toLong())] = r
        }
        rateSetBy[t.minusDays(3)] = "ডি এম সাইফুল ইসলাম"
        // Schedule for the next days (some empty, to show the editor).
        var s = 100_000L
        for (i in 1..10) {
            s += listOf(1500L, -500L, 0L, 2000L, -1000L, 2000L, 0L, -500L, 0L, 1500L)[i - 1]
            rates[t.plusDays(i.toLong())] = s
            rateNotify.add(t.plusDays(i.toLong()))
        }

        roles += Role("r1", "super_admin", "সুপার অ্যাডমিন", net.rapti.resoshare.data.api.Perm.ALL, true)
        roles += Role("r2", "accounts", "অ্যাকাউন্টস", listOf("view", "payment_approve", "reports"), true)
        roles += Role("r3", "kyc_officer", "KYC অফিসার", listOf("view", "kyc_approve"), true)
        roles += Role("r4", "support", "সাপোর্ট", listOf("view"), true)

        staff += Staff("s1", "ডি এম সাইফুল ইসলাম", DEMO_ADMIN_EMAIL, "01617345534", "r1", "super_admin", "সুপার অ্যাডমিন", "active", now().iso(), true)
        staff += Staff("s2", "শারমিন আক্তার", "accounts@rapti.net", "01711000002", "r2", "accounts", "অ্যাকাউন্টস", "active", now().minus(1, ChronoUnit.DAYS).iso(), true)
        staff += Staff("s3", "কামরুল হাসান", "kyc@rapti.net", "01711000003", "r3", "kyc_officer", "KYC অফিসার", "active", now().iso(), true)
        staff += Staff("s4", "মিম আক্তার", "support@rapti.net", "01711000004", "r4", "support", "সাপোর্ট", "active", now().minus(3, ChronoUnit.DAYS).iso(), false)
        staffPasswords["s1"] = DEMO_PASSWORD

        // The demo shareholder from the design.
        val rahim = DUser(
            "u-rahim", 124, "মোঃ রহিম উদ্দিন", DEMO_USER_MOBILE, "rahim@example.com", DEMO_PASSWORD, "19901234567890123",
            LocalDate.of(1988, 3, 14), "verified", null, "active", t.minusDays(60).atStartOfDay(Bn.DHAKA).toInstant(),
            mutableListOf(DNominee("ফাতেমা আক্তার", "স্ত্রী", "nid", "1992123456789", 60), DNominee("মোঃ আরিফ উদ্দিন", "পুত্র", "birth_reg", "20151234567890123", 40)),
        )
        users += rahim
        addOrder(rahim, 20, rateOn(t.minusDays(60)), "bank", "TRF-0905-221", "confirmed", t.minusDays(60))
        addOrder(rahim, 20, rateOn(t.minusDays(23)), "bank", "TRF-1012-118", "confirmed", t.minusDays(23))
        addOrder(rahim, 10, rateOn(t.minusDays(7)), "cash", newCashRef(), "confirmed", t.minusDays(7))

        NAMES.forEachIndexed { i, n ->
            val u = DUser(
                "u-$i", 98 + i * 3, n, "01${listOf(7, 8, 9, 6, 5, 3)[i % 6]}${(10_000_000 + i * 731_337) % 100_000_000}".padEnd(11, '0').take(11),
                "user$i@example.com", DEMO_PASSWORD, "${1985 + i}0${(1000000000L + i * 7919L)}".take(13), LocalDate.of(1975 + i, 1 + i % 12, 1 + i),
                if (i % 7 == 3) "pending" else "verified", null, if (i == 16) "suspended" else "active",
                t.minusDays((300 - i * 12).toLong()).atStartOfDay(Bn.DHAKA).toInstant(),
                mutableListOf(DNominee("নমিনি ${i + 1}", "স্ত্রী", "nid", "1990123456${100 + i}", 100)),
            )
            users += u
            if (u.kycStatus == "verified") {
                val buys = 1 + i % 3
                repeat(buys) { k ->
                    val day = t.minusDays((250 - i * 10 - k * 30).toLong().coerceAtLeast(1))
                    addOrder(u, listOf(10, 20, 25, 30, 50, 100, 120)[(i + k) % 7], rateOn(day), if ((i + k) % 2 == 0) "cash" else "bank",
                        null, "confirmed", day)
                }
            }
        }
        // Pending approvals like in the design.
        val fatema = users.first { it.name == "ফাতেমা আক্তার" }.apply { kycStatus = "verified" }
        val tanvir = users.first { it.name == "তানভীর আহমেদ" }.apply { kycStatus = "verified" }
        addOrder(fatema, 5, todayRate(), "cash", newCashRef(), "pending", t, hoursAgo = 2)
        addOrder(tanvir, 20, todayRate(), "bank", "TRF-${t.toString().replace("-", "")}-0457", "pending", t.minusDays(1))
        val nazmul = users.first { it.name == "নাজমুল হাসান" }
        addOrder(nazmul, 10, rateOn(t.minusDays(1)), "cash", newCashRef(), "cancelled", t.minusDays(1)).note = "রসিদ পাওয়া যায়নি"

        // Notices.
        notices += DNotice(uuid(), "ডিভিডেন্ড বিতরণ সম্পন্ন", "গত অর্থবছরের ডিভিডেন্ড দেওয়া হয়েছে।", "dividend", t.minusDays(290).atStartOfDay(Bn.DHAKA).toInstant(), "all", emptySet(), byName = "ডি এম সাইফুল ইসলাম")
        notices.add(0, DNotice(uuid(), "নতুন শেয়ার বিক্রি শুরু", "নতুন ইস্যুর শেয়ার কেনার জন্য এখন উন্মুক্ত।", "company", t.minusDays(23).atStartOfDay(Bn.DHAKA).toInstant(), "all", emptySet(), byName = "ডি এম সাইফুল ইসলাম"))
        notices.add(0, DNotice(uuid(), "আপনার ক্যাশ জমা যাচাই হয়েছে", "১০টি শেয়ার আপনার প্রোফাইলে যুক্ত হয়েছে।", "payment", t.minusDays(7).atStartOfDay(Bn.DHAKA).toInstant().plusSeconds(40_000), "user", setOf(rahim.id), byName = "শারমিন আক্তার"))
        notices.add(0, DNotice(uuid(), "শেয়ারের রেট পরিবর্তন", "আজ থেকে প্রতি শেয়ারের রেট ${Bn.money(todayRate())} (কেনা ও বিক্রয় একই)।", "rate", t.atStartOfDay(Bn.DHAKA).toInstant(), "all", emptySet(), byName = "সিস্টেম"))

        dividends += Dividend("d1", "2025", 800, t.minusDays(290).toString(), 7_600, 6_080_000, "distributed", t.minusDays(300).atStartOfDay(Bn.DHAKA).toInstant().toString())
        dividends += Dividend("d2", "2024", 600, t.minusDays(650).toString(), 6_000, 3_600_000, "distributed", t.minusDays(660).atStartOfDay(Bn.DHAKA).toInstant().toString())

        val sections = listOf(
            RuleSection("শেয়ার কেনার নিয়ম", listOf(
                "শেয়ার কিনতে আগে রেজিস্ট্রেশন ও KYC (NID ও ছবি) শেষ করতে হবে।",
                "প্রতি শেয়ারের দাম কোম্পানি ঠিক করে। কেনার সময় যে রেট চালু থাকবে, সেই রেটেই কেনা হবে।",
                "সর্বনিম্ন কেনার পরিমাণ: [সর্বনিম্ন শেয়ার সংখ্যা]টি।",
            )),
            RuleSection("পেমেন্টের নিয়ম", listOf(
                "পেমেন্ট শুধু ক্যাশ (অফিসে জমা) অথবা ব্যাংক ট্রান্সফারে করা যাবে।",
                "ক্যাশ জমার পর অবশ্যই রসিদ নিন। ব্যাংক ট্রান্সফারে রেফারেন্স নম্বর অ্যাপে দিন।",
                "অ্যাডমিন পেমেন্ট যাচাই করার পরই শেয়ার আপনার প্রোফাইলে যুক্ত হবে। যাচাইয়ের সময়: [সময়]।",
            )),
            RuleSection("রেট ও শেয়ারের দাম", listOf(
                "শেয়ারের রেট প্রতিদিন রাত ১২টায় নির্ধারিত সূচি অনুযায়ী বদলায়।",
                "কেনার রেট ও বিক্রয়ের রেট সবসময় একই থাকবে।",
                "কোম্পানি আগামী ১৫ দিন পর্যন্ত রেটের সূচি আগে থেকে ঠিক করে রাখতে পারে।",
            )),
            RuleSection("শেয়ার বিক্রি", listOf(
                "শেয়ার বিক্রির আবেদন করলে সেদিনের রেটে হিসাব হবে।",
                "বিক্রির নিয়ম ও সময়সীমা: [বিক্রির নিয়ম]।",
            )),
            RuleSection("নমিনি", listOf(
                "সর্বোচ্চ ২ জন নমিনি রাখা যাবে।",
                "প্রতিটি নমিনির মালিকানার ভাগ (%) উল্লেখ করতে হবে; মোট ১০০% হতে হবে।",
                "নমিনির NID অথবা জন্ম নিবন্ধন নম্বর দিতে হবে।",
            )),
            RuleSection("ডিভিডেন্ড", listOf(
                "ডিভিডেন্ড ঘোষণা হলে প্রতি শেয়ার অনুযায়ী সবার ভাগ হিসাব হবে।",
                "ঘোষণার তথ্য অ্যাপের নোটিশে জানানো হবে।",
            )),
            RuleSection("নোটিশ ও যোগাযোগ", listOf(
                "কোম্পানির সব বার্তা শুধু এই অ্যাপের নোটিশে আসবে। SMS বা ইমেইল পাঠানো হয় না।",
                "যেকোনো প্রয়োজনে হটলাইন: ০১৬১৭-৩৪৫৫৩৪।",
            )),
        )
        rulesCurrent = RulesVersion("rv1", "1.0", t.minusDays(3).toString(), "প্রথম সংস্করণ", "published", sections, t.minusDays(3).atStartOfDay(Bn.DHAKA).toInstant().toString(), "ডি এম সাইফুল ইসলাম")

        log("ডি এম সাইফুল ইসলাম", "rate", "শেয়ারের রেট সূচি হালনাগাদ (১০ দিন)", "warn")
        log("শারমিন আক্তার", "approval", "আবদুল করিমের ${Bn.money(3_000_000)} পেমেন্ট অনুমোদিত", "ok")
        log("শারমিন আক্তার", "approval", "নাজমুল হাসানের ${Bn.money(1_000_000)} পেমেন্ট বাতিল", "bad")
        log("কামরুল হাসান", "approval", "সুমাইয়া ইসলামের KYC যাচাই সম্পন্ন", "ok")
        log("ডি এম সাইফুল ইসলাম", "staff", "নতুন স্টাফ “মিম আক্তার” যোগ (সাপোর্ট রোল)", "info")
        log("ডি এম সাইফুল ইসলাম", "login", "অ্যাডমিন লগইন (OTP যাচাইকৃত)", "info")
    }

    private fun addOrder(u: DUser, qty: Int, rate: Long, method: String, ref: String?, status: String, day: LocalDate, hoursAgo: Long = 0): DOrder {
        val at = if (day == today()) now().minus(hoursAgo, ChronoUnit.HOURS) else day.atStartOfDay(Bn.DHAKA).toInstant().plus(11, ChronoUnit.HOURS)
        val o = DOrder(uuid(), newOrderNo(), u.id, qty, rate, method, ref ?: if (method == "cash") newCashRef() else "TRF-${day.toString().replace("-", "")}-${100 + qty}",
            status, null, at, if (status == "pending") null else at.plus(3, ChronoUnit.HOURS))
        orders += o
        return o
    }
}
