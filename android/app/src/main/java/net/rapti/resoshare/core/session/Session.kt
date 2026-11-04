package net.rapti.resoshare.core.session

import android.content.Context
import android.os.SystemClock
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import net.rapti.resoshare.core.i18n.AppLang
import net.rapti.resoshare.core.ui.theme.ThemeMode
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Small encrypted key/value store. Values are AES-256-GCM encrypted with a non-exportable
 * Android Keystore key (hardware backed where available); the entry name is bound as AAD.
 */
class SecureStore(context: Context) {
    private val prefs = context.getSharedPreferences("reso_secure", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build()
        gen.init(spec)
        return gen.generateKey()
    }

    @Synchronized
    fun put(name: String, value: String?) {
        if (value == null) {
            prefs.edit().remove(name).apply()
            return
        }
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.ENCRYPT_MODE, key())
        c.updateAAD(name.toByteArray())
        val ct = c.doFinal(value.toByteArray(Charsets.UTF_8))
        val blob = c.iv + ct
        prefs.edit().putString(name, Base64.encodeToString(blob, Base64.NO_WRAP)).apply()
    }

    @Synchronized
    fun get(name: String): String? {
        val b64 = prefs.getString(name, null) ?: return null
        return try {
            val blob = Base64.decode(b64, Base64.NO_WRAP)
            val c = Cipher.getInstance(TRANSFORM)
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob, 0, 12))
            c.updateAAD(name.toByteArray())
            String(c.doFinal(blob, 12, blob.size - 12), Charsets.UTF_8)
        } catch (_: Exception) {
            // Tampered value or key invalidated: drop it.
            prefs.edit().remove(name).apply()
            null
        }
    }

    @Synchronized
    fun clear() = prefs.edit().clear().apply()

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "reso_master_v1"
        const val TRANSFORM = "AES/GCM/NoPadding"
    }
}

/** Non-secret preferences. */
class Prefs(context: Context) {
    private val p = context.getSharedPreferences("reso_prefs", Context.MODE_PRIVATE)

    val deviceId: String
        get() = p.getString("device_id", null) ?: UUID.randomUUID().toString().also {
            p.edit().putString("device_id", it).apply()
        }

    var themeMode: ThemeMode
        get() = runCatching { ThemeMode.valueOf(p.getString("theme", "SYSTEM")!!) }.getOrDefault(ThemeMode.SYSTEM)
        set(v) = p.edit().putString("theme", v.name).apply()

    var appLock: Boolean
        get() = p.getBoolean("app_lock", false)
        set(v) = p.edit().putBoolean("app_lock", v).apply()

    var lastIdentifier: String?
        get() = p.getString("last_id", null)
        set(v) = p.edit().putString("last_id", v).apply()

    var lang: AppLang
        get() = if (p.getString("lang", "bn") == "en") AppLang.EN else AppLang.BN
        set(v) = p.edit().putString("lang", v.code).apply()

    /** Last used portal ("user" / "admin") so the right login screen opens. */
    var portal: String
        get() = if (p.getString("portal", "user") == "admin") "admin" else "user"
        set(v) = p.edit().putString("portal", v).apply()

    /** Shareholder's personal share goal (home "আমার লক্ষ্য" card). */
    var goalShares: Int
        get() = p.getInt("goal_shares", 100)
        set(v) = p.edit().putInt("goal_shares", v).apply()

    /** Hide money amounts on home/profile (privacy eye toggle). */
    var hideAmounts: Boolean
        get() = p.getBoolean("hide_amounts", false)
        set(v) = p.edit().putBoolean("hide_amounts", v).apply()

    var rootWarned: Boolean
        get() = p.getBoolean("root_warned", false)
        set(v) = p.edit().putBoolean("root_warned", v).apply()
}

/**
 * Token holder. The access token only lives in memory; the refresh token is persisted
 * encrypted so the user stays signed in across app restarts.
 */
class TokenStore(private val store: SecureStore) {
    @Volatile var accessToken: String? = null
        private set

    val refreshToken: String? get() = store.get(REFRESH)

    /** Portal the stored session belongs to ("user" / "admin"); decides the refresh endpoint. */
    val role: String? get() = store.get(ROLE)

    /** Portal currently shown; new sessions are tagged with it. */
    @Volatile var currentPortal: String = "user"

    fun set(access: String, refresh: String) {
        accessToken = access
        store.put(REFRESH, refresh)
        store.put(ROLE, currentPortal)
    }

    fun clear() {
        accessToken = null
        store.put(REFRESH, null)
        store.put(ROLE, null)
    }

    private companion object {
        const val REFRESH = "refresh_token"
        const val ROLE = "refresh_role"
    }
}

/** Global session state: emits when the session is lost so the UI can return to login. */
object Session {
    private val _active = MutableStateFlow(false)
    /** Observable login state; role roots navigate to login when it turns false. */
    val active: StateFlow<Boolean> = _active
    val isActive: Boolean get() = _active.value

    private val _expired = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val expired: SharedFlow<String> = _expired

    private val _locked = MutableStateFlow(false)
    /** True while the app-lock overlay must be shown. */
    val locked: StateFlow<Boolean> = _locked

    @Volatile private var lastInteraction = SystemClock.elapsedRealtime()
    @Volatile var idleTimeoutMs: Long = 15 * 60_000L

    lateinit var tokens: TokenStore

    fun start() {
        _active.value = true
        touch()
    }

    fun touch() { lastInteraction = SystemClock.elapsedRealtime() }

    fun idleFor(): Long = SystemClock.elapsedRealtime() - lastInteraction

    fun lock() { if (isActive) _locked.value = true }
    fun unlock() { _locked.value = false; touch() }

    /** Called on logout or when the server rejects the session. */
    fun expire(message: String?) {
        val wasActive = isActive
        _active.value = false
        _locked.value = false
        if (::tokens.isInitialized) tokens.clear()
        if (wasActive && message != null) _expired.tryEmit(message)
    }
}
