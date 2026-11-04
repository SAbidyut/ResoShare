package net.rapti.resoshare

import android.content.Context
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import net.rapti.resoshare.core.net.AppJson
import net.rapti.resoshare.core.session.TokenStore
import net.rapti.resoshare.data.api.AdminApi
import net.rapti.resoshare.data.api.ShareholderApi
import okhttp3.Authenticator
import okhttp3.CertificatePinner
import okhttp3.ConnectionSpec
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.Route
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

/** Live backend wiring: HTTPS-only OkHttp with optional pinning and transparent token refresh. */
object ApiFactory {
    const val IS_DEMO = false

    private lateinit var tokens: TokenStore
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val baseUrl = BuildConfig.API_BASE_URL.toHttpUrl()

    private val baseClient: OkHttpClient by lazy {
        val builder = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(true)
            .connectionSpecs(listOf(ConnectionSpec.RESTRICTED_TLS, ConnectionSpec.MODERN_TLS))
            .cache(null)
        val pins = BuildConfig.CERT_PINS.split(",").map { it.trim() }.filter { it.startsWith("sha256/") }
        if (pins.isNotEmpty()) {
            builder.certificatePinner(CertificatePinner.Builder().add(baseUrl.host, *pins.toTypedArray()).build())
        }
        builder.addInterceptor { chain ->
            val req = chain.request()
            // Never send credentials anywhere but our own API host.
            require(req.url.host == baseUrl.host && req.url.isHttps) { "Blocked request to unexpected host" }
            chain.proceed(
                req.newBuilder()
                    .header("User-Agent", "ResoShare/${BuildConfig.VERSION_NAME} (Android; ${if (BuildConfig.IS_ADMIN) "admin" else "user"})")
                    .header("Accept", "application/json")
                    .header("Accept-Language", "bn")
                    .build(),
            )
        }
        builder.build()
    }

    private val authedClient: OkHttpClient by lazy {
        baseClient.newBuilder()
            .addInterceptor { chain ->
                val t = tokens.accessToken
                val req = if (t != null) chain.request().newBuilder().header("Authorization", "Bearer $t").build() else chain.request()
                chain.proceed(req)
            }
            .authenticator(RefreshAuthenticator())
            .build()
    }

    private val retrofit: Retrofit by lazy {
        Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(authedClient)
            .addConverterFactory(AppJson.asConverterFactory(jsonType))
            .build()
    }

    fun init(@Suppress("UNUSED_PARAMETER") context: Context, tokenStore: TokenStore) { tokens = tokenStore }
    fun shareholder(): ShareholderApi = retrofit.create(ShareholderApi::class.java)
    fun admin(): AdminApi = retrofit.create(AdminApi::class.java)

    /** On a 401 TOKEN_EXPIRED, rotate the refresh token once and retry the request. */
    private class RefreshAuthenticator : Authenticator {
        override fun authenticate(route: Route?, response: Response): Request? {
            if (response.request.header("X-Retry-Auth") != null) return null
            val code = runCatching {
                val body = response.peekBody(4096).string()
                AppJson.parseToJsonElement(body).jsonObject["error"]?.jsonObject?.get("code")?.jsonPrimitive?.contentOrNull
            }.getOrNull()
            if (code != "TOKEN_EXPIRED") return null
            val sentToken = response.request.header("Authorization")?.removePrefix("Bearer ")
            synchronized(this) {
                val current = tokens.accessToken
                if (current != null && current != sentToken) {
                    return response.request.newBuilder().header("Authorization", "Bearer $current").header("X-Retry-Auth", "1").build()
                }
                val refresh = tokens.refreshToken ?: return null
                val path = if (BuildConfig.IS_ADMIN) "api/v1/admin/auth/refresh" else "api/v1/auth/refresh"
                val payload = AppJson.encodeToString(
                    net.rapti.resoshare.data.api.RefreshRequest.serializer(),
                    net.rapti.resoshare.data.api.RefreshRequest(refresh),
                )
                val req = Request.Builder().url(baseUrl.resolve(path)!!).post(payload.toRequestBody(jsonType)).build()
                val newTokens = runCatching {
                    baseClient.newCall(req).execute().use { r ->
                        if (!r.isSuccessful) null
                        else AppJson.decodeFromString(net.rapti.resoshare.data.api.Tokens.serializer(), r.body!!.string())
                    }
                }.getOrNull() ?: return null
                tokens.set(newTokens.accessToken, newTokens.refreshToken)
                return response.request.newBuilder()
                    .header("Authorization", "Bearer ${newTokens.accessToken}")
                    .header("X-Retry-Auth", "1")
                    .build()
            }
        }
    }
}
