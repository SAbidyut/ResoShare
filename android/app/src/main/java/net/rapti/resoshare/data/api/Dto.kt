package net.rapti.resoshare.data.api

import kotlinx.serialization.Serializable

// Mirrors docs/API.md. Money is Long paisa. Dates are "YYYY-MM-DD", timestamps ISO-8601 UTC.

@Serializable data class ErrorEnvelope(val error: ErrorBody)
@Serializable data class ErrorBody(val code: String, val message: String = "", val fields: Map<String, String>? = null)

@Serializable data class Tokens(val accessToken: String, val refreshToken: String, val expiresIn: Int = 900)

@Serializable
data class Order(
    val id: String,
    val orderNo: String,
    val quantity: Int,
    val ratePaisa: Long,
    val amountPaisa: Long,
    val method: String,
    val reference: String? = null,
    val status: String,
    val note: String? = null,
    val createdAt: String,
    val decidedAt: String? = null,
)

@Serializable
data class AdminOrder(
    val id: String,
    val orderNo: String,
    val quantity: Int,
    val ratePaisa: Long,
    val amountPaisa: Long,
    val method: String,
    val reference: String? = null,
    val status: String,
    val note: String? = null,
    val createdAt: String,
    val decidedAt: String? = null,
    val shareholderId: String,
    val shareholderNo: String,
    val shareholderName: String,
)

@Serializable
data class Nominee(
    val slot: Int,
    val name: String,
    val relation: String,
    val idType: String,
    val idNumberMasked: String,
    val sharePercent: Int,
)

@Serializable
data class NomineeInput(
    val name: String,
    val relation: String,
    val idType: String,
    val idNumber: String,
    val sharePercent: Int,
)

@Serializable
data class Me(
    val id: String,
    val shareholderNo: String,
    val fullName: String,
    val mobile: String,
    val email: String,
    val kycStatus: String,
    val kycNote: String? = null,
    val accountStatus: String,
    val mustChangePassword: Boolean = false,
    val joinedAt: String,
    val nominees: List<Nominee> = emptyList(),
)

@Serializable data class Pool(val totalShares: Int, val soldShares: Int, val remainingShares: Int, val saleEnabled: Boolean)

@Serializable
data class Notice(
    val id: String,
    val title: String,
    val body: String,
    val category: String,
    val createdAt: String,
    val read: Boolean = false,
)

@Serializable data class RuleSection(val title: String, val items: List<String>)
@Serializable data class Rules(val version: String, val effectiveDate: String, val sections: List<RuleSection>)

// ---------- shareholder requests / responses ----------

@Serializable
data class RegisterData(
    val fullName: String,
    val mobile: String,
    val email: String,
    val password: String,
    val nidNumber: String,
    val dob: String,
    val nominees: List<NomineeInput>,
    val acceptRules: Boolean,
)

@Serializable data class RegisterResult(val shareholderNo: String)
@Serializable data class LoginRequest(val identifier: String, val password: String, val deviceId: String)
@Serializable data class LoginResult(val tokens: Tokens, val me: Me)
@Serializable data class RefreshRequest(val refreshToken: String)
@Serializable data class PasswordChange(val currentPassword: String, val newPassword: String)

@Serializable data class DayRate(val date: String, val ratePaisa: Long, val prevRatePaisa: Long? = null)

@Serializable
data class Holding(
    val shares: Int,
    val paidPaisa: Long,
    val valuePaisa: Long,
    val profitPaisa: Long,
    val profitBp: Int,
)

@Serializable
data class Dashboard(
    val me: Me,
    val today: DayRate,
    val holding: Holding,
    val pool: Pool,
    val unreadNotices: Int,
    val recentOrders: List<Order>,
)

@Serializable data class OrderPage(val items: List<Order>, val nextCursor: String? = null)

@Serializable
data class OrderRequest(
    val quantity: Int,
    val method: String,
    val reference: String?,
    val expectedRatePaisa: Long,
    val acceptRules: Boolean,
)

@Serializable
data class Certificate(
    val certificateNo: String,
    val shareholderNo: String,
    val fullName: String,
    val shares: Int,
    val issuedOn: String,
    val verifyUrl: String,
    val companyName: String,
)

@Serializable data class NomineesUpdate(val nominees: List<NomineeInput>)
@Serializable data class KycData(val nidNumber: String, val dob: String)

@Serializable
data class PaymentInfo(
    val cashEnabled: Boolean,
    val bankEnabled: Boolean,
    val officeAddress: String,
    val bankName: String,
    val accountName: String,
    val accountNumber: String,
)

@Serializable data class PublicInfo(val companyName: String, val hotline: String, val email: String, val officeAddress: String)

@Serializable data class NoticePage(val items: List<Notice>, val nextCursor: String? = null, val unread: Int = 0)

@Serializable data class ChartPoint(val date: String, val ratePaisa: Long)

@Serializable
data class Chart(
    val period: String,
    val points: List<ChartPoint>,
    val todayRatePaisa: Long,
    val dayChangePaisa: Long,
    val periodChangePaisa: Long,
    val periodChangeBp: Int,
    val highPaisa: Long,
    val lowPaisa: Long,
    val monthChangePaisa: Long,
    val myShares: Int,
)

// ---------- admin ----------

@Serializable data class AdminLoginRequest(val email: String, val password: String, val deviceId: String)
@Serializable data class Enrollment(val secret: String, val otpauthUri: String)
@Serializable data class AdminLoginResult(val mfaToken: String, val enrollment: Enrollment? = null)
@Serializable data class MfaRequest(val mfaToken: String, val code: String)

@Serializable
data class StaffMe(
    val id: String,
    val name: String,
    val email: String,
    val roleKey: String,
    val roleName: String,
    val permissions: List<String>,
    val mustChangePassword: Boolean = false,
)

@Serializable data class MfaResult(val tokens: Tokens, val staff: StaffMe)

@Serializable data class SimpleRate(val date: String, val ratePaisa: Long)
@Serializable data class PendingCounts(val payments: Int, val kyc: Int)

@Serializable
data class AdminDashboard(
    val today: SimpleRate,
    val pool: Pool,
    val shareholderCount: Int,
    val pending: PendingCounts,
    val recentOrders: List<AdminOrder>,
)

@Serializable data class SalesPoint(val date: String, val shares: Int, val amountPaisa: Long)

@Serializable
data class SalesStats(
    val period: String,
    val points: List<SalesPoint>,
    val totalShares: Int,
    val totalAmountPaisa: Long,
    val lastChangeShares: Int,
)

@Serializable
data class ShareholderRow(
    val id: String,
    val shareholderNo: String,
    val fullName: String,
    val shares: Int,
    val kycStatus: String,
    val accountStatus: String,
)

@Serializable data class ShareholderPage(val items: List<ShareholderRow>, val total: Int, val nextCursor: String? = null)
@Serializable data class KycFile(val id: String, val kind: String)

@Serializable
data class ShareholderDetail(
    val id: String,
    val shareholderNo: String,
    val fullName: String,
    val mobileMasked: String,
    val emailMasked: String,
    val kycStatus: String,
    val kycNote: String? = null,
    val accountStatus: String,
    val joinedAt: String,
    val dob: String,
    val nidMasked: String,
    val shares: Int,
    val paidPaisa: Long,
    val valuePaisa: Long,
    val nominees: List<Nominee>,
    val orders: List<Order>,
    val kycFiles: List<KycFile>,
)

@Serializable data class Contact(val mobile: String, val email: String)
@Serializable data class Reason(val reason: String)
@Serializable data class TempPassword(val tempPassword: String)
@Serializable data class MessageRequest(val title: String, val body: String)

@Serializable
data class KycRequest(
    val shareholderId: String,
    val shareholderNo: String,
    val fullName: String,
    val submittedAt: String,
    val nidMasked: String,
    val dob: String,
    val files: List<KycFile>,
)

@Serializable data class Approvals(val payments: List<AdminOrder>, val kyc: List<KycRequest>)

@Serializable
data class AdminOrderPage(
    val items: List<AdminOrder>,
    val nextCursor: String? = null,
    val todayTotalPaisa: Long = 0,
    val monthTotalPaisa: Long = 0,
)

@Serializable data class ScheduleItem(val date: String, val ratePaisa: Long? = null)
@Serializable data class RateHistoryItem(val date: String, val ratePaisa: Long, val changePaisa: Long, val setByName: String? = null)

@Serializable
data class RatesAdmin(
    val today: SimpleRate,
    val tomorrow: SimpleRate,
    val schedule: List<ScheduleItem>,
    val history: List<RateHistoryItem>,
)

@Serializable data class ScheduleSetItem(val date: String, val ratePaisa: Long)
@Serializable data class ScheduleUpdate(val items: List<ScheduleSetItem>, val notify: Boolean)
@Serializable data class SaleToggle(val saleEnabled: Boolean)
@Serializable data class IssueShares(val additionalShares: Int)

@Serializable
data class Dividend(
    val id: String,
    val fiscalYear: String,
    val perSharePaisa: Long,
    val distributionDate: String,
    val totalShares: Int,
    val totalPaisa: Long,
    val status: String,
    val createdAt: String,
)

@Serializable data class Dividends(val items: List<Dividend>, val soldShares: Int)
@Serializable data class DividendRequest(val fiscalYear: String, val perSharePaisa: Long, val distributionDate: String)

@Serializable
data class Report(
    val type: String,
    val title: String,
    val columns: List<String>,
    val rows: List<List<String>>,
    val generatedAt: String,
)

@Serializable
data class SentNotice(
    val id: String,
    val title: String,
    val body: String,
    val category: String,
    val audience: String,
    val recipientCount: Int,
    val createdAt: String,
    val byName: String,
)

@Serializable data class SentNoticePage(val items: List<SentNotice>, val nextCursor: String? = null)
@Serializable data class NoticeRequest(val title: String, val body: String, val audience: String, val userIds: List<String>)

@Serializable
data class RulesVersion(
    val id: String,
    val version: String,
    val effectiveDate: String,
    val summary: String = "",
    val status: String,
    val sections: List<RuleSection> = emptyList(),
    val updatedAt: String,
    val byName: String = "",
)

@Serializable data class RulesAdmin(val current: RulesVersion? = null, val draft: RulesVersion? = null, val history: List<RulesVersion> = emptyList())
@Serializable data class RulesDraft(val sections: List<RuleSection>)

@Serializable
data class RulesPublish(
    val version: String,
    val effectiveDate: String,
    val summary: String,
    val sections: List<RuleSection>,
    val notify: Boolean,
)

@Serializable
data class Staff(
    val id: String,
    val name: String,
    val email: String,
    val mobile: String,
    val roleId: String,
    val roleKey: String,
    val roleName: String,
    val status: String,
    val lastActiveAt: String? = null,
    val mfaEnrolled: Boolean = false,
)

@Serializable data class Role(val id: String, val key: String, val name: String, val permissions: List<String>, val isSystem: Boolean)
@Serializable data class StaffList(val items: List<Staff>, val roles: List<Role>)
@Serializable data class StaffCreate(val name: String, val email: String, val mobile: String, val roleId: String)
@Serializable data class StaffCreated(val staff: Staff, val tempPassword: String)
@Serializable data class StaffPatch(val roleId: String? = null, val status: String? = null)
@Serializable data class RoleCreate(val name: String, val permissions: List<String>)
@Serializable data class RolePatch(val name: String? = null, val permissions: List<String>? = null)

@Serializable
data class AuditEntry(
    val id: String,
    val at: String,
    val actorName: String,
    val category: String,
    val message: String,
    val tone: String,
)

@Serializable data class AuditPage(val items: List<AuditEntry>, val nextCursor: String? = null)

@Serializable data class CompanySettings(val name: String, val email: String, val hotline: String)

@Serializable
data class PaymentSettings(
    val cashEnabled: Boolean,
    val bankEnabled: Boolean,
    val officeAddress: String,
    val bankName: String,
    val accountName: String,
    val accountNumber: String,
)

@Serializable data class SecuritySettings(val autoLogoutMinutes: Int, val mfaRequired: Boolean = true)
@Serializable data class Settings(val company: CompanySettings, val payment: PaymentSettings, val security: SecuritySettings)

object Perm {
    const val VIEW = "view"
    const val KYC = "kyc_approve"
    const val PAYMENT = "payment_approve"
    const val RATE = "rate_change"
    const val DIVIDEND = "dividend"
    const val NOTICES = "notices_rules"
    const val REPORTS = "reports"
    const val SETTINGS = "settings_staff"
    val ALL = listOf(VIEW, KYC, PAYMENT, RATE, DIVIDEND, NOTICES, REPORTS, SETTINGS)
    fun label(p: String) = when (p) {
        VIEW -> "তথ্য দেখা"
        KYC -> "KYC অনুমোদন"
        PAYMENT -> "পেমেন্ট অনুমোদন"
        RATE -> "রেট বদল"
        DIVIDEND -> "ডিভিডেন্ড"
        NOTICES -> "নোটিশ ও নিয়ম"
        REPORTS -> "রিপোর্ট"
        SETTINGS -> "সেটিংস ও স্টাফ"
        else -> p
    }
}
