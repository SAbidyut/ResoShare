package net.rapti.resoshare.core.net

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import net.rapti.resoshare.core.i18n.Lang
import net.rapti.resoshare.core.i18n.t
import net.rapti.resoshare.core.session.Session
import net.rapti.resoshare.data.api.ErrorEnvelope
import retrofit2.HttpException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

val AppJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    isLenient = false
}

/** A user-presentable API failure. `code` follows docs/API.md (plus NETWORK/TLS/BAD_RESPONSE/UNKNOWN). */
class ApiException(
    val code: String,
    override val message: String,
    val http: Int = 0,
    val fields: Map<String, String> = emptyMap(),
) : Exception(message)

sealed interface Res<out T> {
    data class Ok<T>(val value: T) : Res<T>
    data class Err(val error: ApiException) : Res<Nothing>
}

inline fun <T> Res<T>.onOk(f: (T) -> Unit): Res<T> { if (this is Res.Ok) f(value); return this }
inline fun <T> Res<T>.onErr(f: (ApiException) -> Unit): Res<T> { if (this is Res.Err) f(error); return this }

private val SESSION_LOSS = setOf("TOKEN_EXPIRED", "SESSION_EXPIRED", "UNAUTHORIZED")

/**
 * Runs an API call and maps every failure to an [ApiException] with a Bangla message.
 * When an authenticated call reports a lost session, the global [Session] is expired.
 */
suspend fun <T> call(block: suspend () -> T): Res<T> = try {
    Res.Ok(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    val ex = e.toApiException()
    if (ex.code in SESSION_LOSS && Session.isActive) {
        Session.expire(
            if (ex.code == "UNAUTHORIZED") t("আপনার সেশন বাতিল হয়েছে। আবার লগইন করুন।", "Your session was ended. Please log in again.")
            else t("সেশনের মেয়াদ শেষ। নিরাপত্তার জন্য আবার লগইন করুন।", "Session expired. Please log in again for security."),
        )
    }
    Res.Err(ex)
}

fun Throwable.toApiException(): ApiException = when (this) {
    is ApiException -> this
    is HttpException -> {
        val raw = try { response()?.errorBody()?.string() } catch (_: Exception) { null }
        val env = raw?.let { runCatching { AppJson.decodeFromString(ErrorEnvelope.serializer(), it) }.getOrNull() }
        if (env != null) {
            val fields = env.error.fields ?: emptyMap()
            if (Lang.isEn) {
                // The server speaks Bangla; show an English equivalent for the error code.
                ApiException(env.error.code, englishFor(env.error.code, code()), code(), fields.mapValues { (k, v) -> englishField(k, v) })
            } else {
                ApiException(env.error.code, env.error.message.ifBlank { fallbackFor(code()) }, code(), fields)
            }
        } else {
            ApiException(codeFor(code()), fallbackFor(code()), code())
        }
    }
    is SSLException -> ApiException("TLS", t("নিরাপদ সংযোগ যাচাই করা যায়নি। অনিরাপদ বা পাবলিক Wi-Fi হলে মোবাইল ডেটা ব্যবহার করুন।", "Could not verify a secure connection. On public Wi-Fi, try mobile data."))
    is SocketTimeoutException, is InterruptedIOException -> ApiException("NETWORK", t("সার্ভার সাড়া দিচ্ছে না। একটু পরে আবার চেষ্টা করুন।", "The server is not responding. Please try again shortly."))
    is UnknownHostException, is ConnectException -> ApiException("NETWORK", t("ইন্টারনেট সংযোগ পাওয়া যাচ্ছে না। সংযোগ দেখে আবার চেষ্টা করুন।", "No internet connection. Check your connection and try again."))
    is IOException -> ApiException("NETWORK", t("নেটওয়ার্কে সমস্যা হয়েছে। আবার চেষ্টা করুন।", "Network problem. Please try again."))
    is SerializationException, is IllegalArgumentException -> ApiException("BAD_RESPONSE", t("সার্ভার থেকে অপ্রত্যাশিত উত্তর এসেছে।", "Unexpected response from the server."))
    else -> ApiException("UNKNOWN", t("কিছু একটা ভুল হয়েছে। আবার চেষ্টা করুন।", "Something went wrong. Please try again."))
}

private fun codeFor(http: Int) = when (http) {
    400 -> "INVALID_INPUT"
    401 -> "UNAUTHORIZED"
    403 -> "FORBIDDEN"
    404 -> "NOT_FOUND"
    409 -> "CONFLICT"
    413 -> "PAYLOAD_TOO_LARGE"
    423 -> "LOCKED"
    429 -> "TOO_MANY_REQUESTS"
    else -> "SERVER_ERROR"
}

private fun fallbackFor(http: Int) = when (http) {
    400 -> t("তথ্যগুলো আবার দেখে নিন।", "Please check the information.")
    401 -> t("লগইন তথ্য সঠিক নয়।", "Incorrect login details.")
    403 -> t("এই কাজের অনুমতি আপনার নেই।", "You don't have permission for this.")
    404 -> t("তথ্য পাওয়া যায়নি।", "Not found.")
    409 -> t("তথ্যটি ইতোমধ্যে আছে বা পরিবর্তিত হয়েছে।", "This already exists or has changed.")
    413 -> t("ফাইলটি অনেক বড়। ৫ MB এর ছোট ছবি দিন।", "File too large. Use an image under 5 MB.")
    423 -> t("অনেকবার ভুল চেষ্টা হয়েছে। কিছুক্ষণ পরে চেষ্টা করুন।", "Too many failed attempts. Try again later.")
    429 -> t("অনেক বেশি অনুরোধ। একটু পরে চেষ্টা করুন।", "Too many requests. Please wait a moment.")
    else -> t("সার্ভারে সমস্যা হয়েছে। একটু পরে চেষ্টা করুন।", "Server problem. Please try again later.")
}

/** English text for server error codes (the server's own messages are Bangla). */
private fun englishFor(code: String, http: Int): String = when (code) {
    "INVALID_INPUT" -> "Please check the information you entered."
    "UNAUTHORIZED" -> "Incorrect login details. Repeated failures pause login for a while."
    "TOKEN_EXPIRED", "SESSION_EXPIRED" -> "Session expired. Please log in again."
    "MFA_INVALID" -> "The code is incorrect or has expired."
    "FORBIDDEN" -> "You don't have permission for this."
    "KYC_REQUIRED" -> "You can buy shares after your KYC is verified."
    "ACCOUNT_SUSPENDED" -> "Your account is suspended. Please call the hotline."
    "PASSWORD_CHANGE_REQUIRED" -> "Please set a new password first."
    "NOT_FOUND" -> "Not found."
    "CONFLICT" -> "This already exists or has already been processed."
    "RATE_CHANGED" -> "The share rate has changed. Please review and confirm again."
    "INSUFFICIENT_SHARES" -> "Not enough shares are left."
    "SALE_CLOSED" -> "Share sale is closed right now."
    "TOO_MANY_PENDING" -> "You already have 3 orders awaiting verification."
    "PAYLOAD_TOO_LARGE" -> "File too large. Use an image under 5 MB."
    "LOCKED" -> "Too many failed attempts. Try again later."
    "TOO_MANY_REQUESTS" -> "Too many requests. Please wait a moment."
    "SERVICE_UNAVAILABLE" -> "This service is not available on the server right now."
    else -> if (http >= 500) "Server problem. Please try again later." else "Something went wrong. Please try again."
}

private fun englishField(key: String, value: String): String = when (key) {
    "currentRatePaisa", "retryAfterSec" -> value
    "currentPassword" -> "Current password is incorrect"
    "newPassword" -> "Use letters, a number and a symbol (e.g. @ # !)"
    "reference" -> "This reference is invalid or already used"
    "mobile" -> "Check the mobile number"
    "email" -> "Check the email address"
    else -> "Please check this field"
}
