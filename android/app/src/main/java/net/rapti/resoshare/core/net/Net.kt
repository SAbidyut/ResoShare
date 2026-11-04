package net.rapti.resoshare.core.net

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
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
            if (ex.code == "UNAUTHORIZED") "আপনার সেশন বাতিল হয়েছে। আবার লগইন করুন।"
            else "সেশনের মেয়াদ শেষ। নিরাপত্তার জন্য আবার লগইন করুন।",
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
            ApiException(env.error.code, env.error.message.ifBlank { fallbackFor(code()) }, code(), env.error.fields ?: emptyMap())
        } else {
            ApiException(codeFor(code()), fallbackFor(code()), code())
        }
    }
    is SSLException -> ApiException("TLS", "নিরাপদ সংযোগ যাচাই করা যায়নি। অনিরাপদ বা পাবলিক Wi-Fi হলে মোবাইল ডেটা ব্যবহার করুন।")
    is SocketTimeoutException, is InterruptedIOException -> ApiException("NETWORK", "সার্ভার সাড়া দিচ্ছে না। একটু পরে আবার চেষ্টা করুন।")
    is UnknownHostException, is ConnectException -> ApiException("NETWORK", "ইন্টারনেট সংযোগ পাওয়া যাচ্ছে না। সংযোগ দেখে আবার চেষ্টা করুন।")
    is IOException -> ApiException("NETWORK", "নেটওয়ার্কে সমস্যা হয়েছে। আবার চেষ্টা করুন।")
    is SerializationException, is IllegalArgumentException -> ApiException("BAD_RESPONSE", "সার্ভার থেকে অপ্রত্যাশিত উত্তর এসেছে।")
    else -> ApiException("UNKNOWN", "কিছু একটা ভুল হয়েছে। আবার চেষ্টা করুন।")
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
    400 -> "তথ্যগুলো আবার দেখে নিন।"
    401 -> "লগইন তথ্য সঠিক নয়।"
    403 -> "এই কাজের অনুমতি আপনার নেই।"
    404 -> "তথ্য পাওয়া যায়নি।"
    409 -> "তথ্যটি ইতোমধ্যে আছে বা পরিবর্তিত হয়েছে।"
    413 -> "ফাইলটি অনেক বড়। ৫ MB এর ছোট ছবি দিন।"
    423 -> "অনেকবার ভুল চেষ্টা হয়েছে। কিছুক্ষণ পরে চেষ্টা করুন।"
    429 -> "অনেক বেশি অনুরোধ। একটু পরে চেষ্টা করুন।"
    else -> "সার্ভারে সমস্যা হয়েছে। একটু পরে চেষ্টা করুন।"
}
