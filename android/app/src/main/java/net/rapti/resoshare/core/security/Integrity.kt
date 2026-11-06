package net.rapti.resoshare.core.security

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Debug
import net.rapti.resoshare.BuildConfig
import java.io.File
import java.security.MessageDigest

/** Runtime environment checks. None of these replace server-side enforcement. */
object Integrity {
    enum class Verdict { OK, ROOTED, TAMPERED, DEBUGGER }

    fun check(context: Context): Verdict {
        if (!BuildConfig.DEBUG) {
            if (isRepackaged(context)) return Verdict.TAMPERED
            if (Debug.isDebuggerConnected() || Debug.waitingForDebugger()) return Verdict.DEBUGGER
        }
        if (isRooted()) return Verdict.ROOTED
        return Verdict.OK
    }

    private fun isRepackaged(context: Context): Boolean {
        // A release build must never be debuggable.
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) return true
        val expected = BuildConfig.RELEASE_CERT_SHA256
        if (expected.isEmpty()) return false
        val actual = signingCertSha256(context) ?: return true
        return !actual.equals(expected, ignoreCase = true)
    }

    @Suppress("DEPRECATION")
    private fun signingCertSha256(context: Context): String? = try {
        val pm = context.packageManager
        val sigs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            val si = info.signingInfo ?: return null
            if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
        } else {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures
        }
        val first = sigs?.firstOrNull() ?: return null
        MessageDigest.getInstance("SHA-256").digest(first.toByteArray()).joinToString("") { "%02X".format(it) }
    } catch (_: Exception) {
        null
    }

    private val SU_PATHS = listOf(
        "/system/app/Superuser.apk", "/sbin/su", "/system/bin/su", "/system/xbin/su", "/data/local/xbin/su",
        "/data/local/bin/su", "/system/sd/xbin/su", "/system/bin/failsafe/su", "/data/local/su", "/su/bin/su",
        "/system/xbin/daemonsu", "/sbin/.magisk", "/cache/.disable_magisk", "/dev/.magisk.unblock",
        "/system/bin/.ext/.su", "/system/usr/we-need-root/su-backup", "/system/xbin/mu",
    )

    fun isRooted(): Boolean {
        if (Build.TAGS?.contains("test-keys") == true) return true
        if (SU_PATHS.any { runCatching { File(it).exists() }.getOrDefault(false) }) return true
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("/system/xbin/which", "su"))
            val found = p.inputStream.bufferedReader().use { it.readLine() } != null
            p.destroy()
            found
        } catch (_: Exception) {
            false
        }
    }
}
