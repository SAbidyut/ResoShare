package net.rapti.resoshare.data.api

import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Shareholder API (docs/API.md). Implemented by Retrofit in `live` builds and by an
 * in-memory fake in `demo` builds. Methods throw on non-2xx; see [net.rapti.resoshare.core.net.call].
 */
interface ShareholderApi {
    @GET("api/v1/public/info") suspend fun publicInfo(): PublicInfo
    @GET("api/v1/rules") suspend fun rules(): Rules

    @Multipart
    @POST("api/v1/auth/register")
    suspend fun register(
        @Part("data") data: RequestBody,
        @Part nidFront: MultipartBody.Part,
        @Part nidBack: MultipartBody.Part,
        @Part photo: MultipartBody.Part,
    ): RegisterResult

    @POST("api/v1/auth/login") suspend fun login(@Body body: LoginRequest): LoginResult
    @POST("api/v1/auth/refresh") suspend fun refresh(@Body body: RefreshRequest): Tokens
    @POST("api/v1/auth/logout") suspend fun logout(@Body body: RefreshRequest)

    @GET("api/v1/me") suspend fun me(): Me
    @POST("api/v1/me/password") suspend fun changePassword(@Body body: PasswordChange)
    @GET("api/v1/me/dashboard") suspend fun dashboard(): Dashboard
    @GET("api/v1/me/orders") suspend fun orders(@Query("cursor") cursor: String? = null, @Query("limit") limit: Int = 20): OrderPage
    @POST("api/v1/me/orders") suspend fun createOrder(@Body body: OrderRequest): Order
    @POST("api/v1/me/orders/{id}/cancel") suspend fun cancelOrder(@Path("id") id: String): Order
    @GET("api/v1/me/certificate") suspend fun certificate(): Certificate
    @PUT("api/v1/me/nominees") suspend fun updateNominees(@Body body: NomineesUpdate): List<Nominee>

    @Multipart
    @POST("api/v1/me/kyc")
    suspend fun resubmitKyc(
        @Part("data") data: RequestBody,
        @Part nidFront: MultipartBody.Part,
        @Part nidBack: MultipartBody.Part,
        @Part photo: MultipartBody.Part,
    )

    @GET("api/v1/me/payment-info") suspend fun paymentInfo(): PaymentInfo
    @GET("api/v1/notices") suspend fun notices(@Query("cursor") cursor: String? = null, @Query("limit") limit: Int = 30): NoticePage
    @POST("api/v1/notices/{id}/read") suspend fun markRead(@Path("id") id: String)
    @POST("api/v1/notices/read-all") suspend fun markAllRead()
    @GET("api/v1/market/chart") suspend fun chart(@Query("period") period: String): Chart
}

interface AdminApi {
    @POST("api/v1/admin/auth/login") suspend fun login(@Body body: AdminLoginRequest): AdminLoginResult
    @POST("api/v1/admin/auth/mfa") suspend fun mfa(@Body body: MfaRequest): MfaResult
    @POST("api/v1/admin/auth/refresh") suspend fun refresh(@Body body: RefreshRequest): Tokens
    @POST("api/v1/admin/auth/logout") suspend fun logout(@Body body: RefreshRequest)
    @GET("api/v1/admin/me") suspend fun me(): StaffMe
    @POST("api/v1/admin/me/password") suspend fun changePassword(@Body body: PasswordChange)

    @GET("api/v1/admin/dashboard") suspend fun dashboard(): AdminDashboard
    @GET("api/v1/admin/stats/sales") suspend fun sales(@Query("period") period: String): SalesStats

    @GET("api/v1/admin/shareholders")
    suspend fun shareholders(
        @Query("q") q: String? = null,
        @Query("status") status: String = "all",
        @Query("cursor") cursor: String? = null,
        @Query("limit") limit: Int = 30,
    ): ShareholderPage

    @GET("api/v1/admin/shareholders/{id}") suspend fun shareholder(@Path("id") id: String): ShareholderDetail
    @GET("api/v1/admin/shareholders/{id}/contact") suspend fun contact(@Path("id") id: String): Contact
    @GET("api/v1/admin/shareholders/{id}/certificate") suspend fun shareholderCertificate(@Path("id") id: String): Certificate
    @POST("api/v1/admin/shareholders/{id}/suspend") suspend fun suspend(@Path("id") id: String, @Body body: Reason)
    @POST("api/v1/admin/shareholders/{id}/activate") suspend fun activate(@Path("id") id: String)
    @POST("api/v1/admin/shareholders/{id}/reset-password") suspend fun resetUserPassword(@Path("id") id: String): TempPassword
    @POST("api/v1/admin/shareholders/{id}/message") suspend fun message(@Path("id") id: String, @Body body: MessageRequest)
    @GET("api/v1/admin/kyc-files/{fileId}") suspend fun kycFile(@Path("fileId") fileId: String): ResponseBody

    @GET("api/v1/admin/approvals") suspend fun approvals(): Approvals
    @POST("api/v1/admin/orders/{id}/approve") suspend fun approveOrder(@Path("id") id: String): AdminOrder
    @POST("api/v1/admin/orders/{id}/reject") suspend fun rejectOrder(@Path("id") id: String, @Body body: Reason): AdminOrder
    @POST("api/v1/admin/kyc/{id}/approve") suspend fun approveKyc(@Path("id") shareholderId: String)
    @POST("api/v1/admin/kyc/{id}/reject") suspend fun rejectKyc(@Path("id") shareholderId: String, @Body body: Reason)

    @GET("api/v1/admin/orders")
    suspend fun orders(
        @Query("status") status: String = "all",
        @Query("q") q: String? = null,
        @Query("cursor") cursor: String? = null,
        @Query("limit") limit: Int = 30,
    ): AdminOrderPage

    @GET("api/v1/admin/rates") suspend fun rates(): RatesAdmin
    @PUT("api/v1/admin/rates/schedule") suspend fun saveSchedule(@Body body: ScheduleUpdate): RatesAdmin
    @GET("api/v1/admin/pool") suspend fun pool(): Pool
    @PUT("api/v1/admin/pool") suspend fun setSale(@Body body: SaleToggle): Pool
    @POST("api/v1/admin/pool/issue") suspend fun issueShares(@Body body: IssueShares): Pool

    @GET("api/v1/admin/dividends") suspend fun dividends(): Dividends
    @POST("api/v1/admin/dividends") suspend fun declareDividend(@Body body: DividendRequest): Dividend

    @GET("api/v1/admin/reports/{type}")
    suspend fun report(@Path("type") type: String, @Query("from") from: String?, @Query("to") to: String?): Report

    @GET("api/v1/admin/notices") suspend fun sentNotices(@Query("cursor") cursor: String? = null): SentNoticePage
    @POST("api/v1/admin/notices") suspend fun sendNotice(@Body body: NoticeRequest): SentNotice

    @GET("api/v1/admin/rules") suspend fun rules(): RulesAdmin
    @PUT("api/v1/admin/rules/draft") suspend fun saveRulesDraft(@Body body: RulesDraft): RulesAdmin
    @POST("api/v1/admin/rules/publish") suspend fun publishRules(@Body body: RulesPublish): RulesAdmin

    @GET("api/v1/admin/staff") suspend fun staff(): StaffList
    @POST("api/v1/admin/staff") suspend fun createStaff(@Body body: StaffCreate): StaffCreated
    @PATCH("api/v1/admin/staff/{id}") suspend fun patchStaff(@Path("id") id: String, @Body body: StaffPatch): Staff
    @POST("api/v1/admin/staff/{id}/reset") suspend fun resetStaff(@Path("id") id: String): TempPassword
    @POST("api/v1/admin/staff/{id}/unlock") suspend fun unlockStaff(@Path("id") id: String)
    @POST("api/v1/admin/roles") suspend fun createRole(@Body body: RoleCreate): Role
    @PATCH("api/v1/admin/roles/{id}") suspend fun patchRole(@Path("id") id: String, @Body body: RolePatch): Role

    @GET("api/v1/admin/audit") suspend fun audit(@Query("category") category: String? = null, @Query("cursor") cursor: String? = null): AuditPage
    @GET("api/v1/admin/backup") suspend fun backup(): BackupStatus
    @POST("api/v1/admin/backup") suspend fun requestBackup(): BackupStatus
    @GET("api/v1/admin/settings") suspend fun settings(): Settings
    @PUT("api/v1/admin/settings") suspend fun saveSettings(@Body body: Settings): Settings
}
