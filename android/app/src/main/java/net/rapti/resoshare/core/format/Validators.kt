package net.rapti.resoshare.core.format

import net.rapti.resoshare.core.i18n.t
import java.time.LocalDate
import java.time.Period

/**
 * Client-side validation mirroring docs/API.md. The server re-validates everything;
 * these exist for fast, friendly feedback. Each returns null when valid, else a message.
 */
object V {
    private val NAME = Regex("^[\\p{L}\\p{M} .\\-]{3,100}$")
    private val EMAIL = Regex("^[A-Za-z0-9._%+\\-]{1,64}@[A-Za-z0-9.\\-]{1,190}\\.[A-Za-z]{2,24}$")
    private val MOBILE = Regex("^01[3-9]\\d{8}$")
    private val REF = Regex("^[A-Za-z0-9\\-/]{3,40}$")
    private val COMMON = setOf(
        "password", "password1", "12345678", "123456789", "1234567890", "qwerty123", "abc12345",
        "iloveyou1", "admin123", "11111111", "00000000", "password123", "bangladesh1", "a1234567",
        "password@1", "p@ssw0rd", "admin@123", "abc@1234",
    )

    /** Normalise a Bangladeshi mobile: Bangla digits, spaces, dashes, +88/88 prefix. */
    fun normalizeMobile(input: String): String {
        var s = Bn.ascii(input).filter { it.isDigit() || it == '+' }
        s = s.removePrefix("+")
        if (s.startsWith("88") && s.length == 13) s = s.substring(2)
        return s
    }

    fun digitsOnly(input: String) = Bn.ascii(input).filter { it.isDigit() }

    fun name(s: String): String? = when {
        s.trim().length < 3 -> t("পুরো নাম লিখুন (কমপক্ষে ৩ অক্ষর)", "Enter the full name (at least 3 letters)")
        !NAME.matches(s.trim()) -> t("নামে শুধু অক্ষর, স্পেস, ডট বা হাইফেন দিন", "Use only letters, spaces, dots or hyphens")
        else -> null
    }

    fun mobile(s: String): String? =
        if (MOBILE.matches(normalizeMobile(s))) null
        else t("সঠিক ১১ অঙ্কের মোবাইল নম্বর দিন (যেমন ০১৭XXXXXXXX)", "Enter a valid 11-digit mobile number (e.g. 017XXXXXXXX)")

    fun email(s: String): String? = when {
        s.isBlank() -> t("ইমেইল দিন", "Enter your email")
        s.length > 254 || !EMAIL.matches(s.trim()) -> t("সঠিক ইমেইল ঠিকানা দিন", "Enter a valid email address")
        else -> null
    }

    private fun hasSymbol(s: String) = s.any { !it.isLetterOrDigit() && !it.isWhitespace() }

    /** 0–3 strength used by the registration meter. */
    fun strength(s: String): Int {
        if (s.isEmpty()) return 0
        var score = 0
        if (s.length >= 8) score++
        if (s.any { it.isLetter() } && s.any { it.isDigit() }) score++
        if (hasSymbol(s)) score++
        return score.coerceIn(1, 3)
    }

    fun password(s: String): String? = when {
        s.length < 8 -> t("পাসওয়ার্ড কমপক্ষে ৮ অক্ষরের হতে হবে", "Password must be at least 8 characters")
        s.length > 128 -> t("পাসওয়ার্ড অনেক লম্বা", "Password is too long")
        !s.any { it.isLetter() } || !s.any { it.isDigit() } || !hasSymbol(s) ->
            t("পাসওয়ার্ডে অক্ষর, সংখ্যা ও একটি চিহ্ন (যেমন @ # !) রাখুন", "Use letters, a number and a symbol (e.g. @ # !)")
        s.lowercase() in COMMON -> t("এই পাসওয়ার্ডটি খুব সহজ, অন্য একটি দিন", "This password is too common, choose another")
        else -> null
    }

    fun confirm(p: String, c: String): String? = if (p == c) null else t("দুটি পাসওয়ার্ড মেলেনি", "Passwords do not match")

    fun nid(s: String): String? {
        val d = digitsOnly(s)
        return if (d.length in setOf(10, 13, 17)) null else t("NID নম্বর ১০, ১৩ বা ১৭ অঙ্কের হয়", "NID number has 10, 13 or 17 digits")
    }

    fun nomineeId(type: String, s: String): String? {
        val d = digitsOnly(s)
        return if (type == "birth_reg") {
            if (d.length == 17) null else t("জন্ম নিবন্ধন নম্বর ১৭ অঙ্কের হয়", "Birth registration number has 17 digits")
        } else nid(s)
    }

    fun relation(s: String): String? =
        if (s.trim().length in 2..30) null else t("সম্পর্ক লিখুন (যেমন: স্ত্রী, পুত্র)", "Enter the relationship (e.g. wife, son)")

    /** Accepts dd/mm/yyyy (Bangla or ASCII digits). Returns ISO date or null. */
    fun parseDob(s: String): LocalDate? {
        val parts = Bn.ascii(s).split('/', '-', '.', ' ').filter { it.isNotBlank() }
        if (parts.size != 3) return null
        return try {
            val (d, m, y) = parts.map { it.toInt() }
            if (y < 1900) null else LocalDate.of(y, m, d)
        } catch (_: Exception) {
            null
        }
    }

    fun dob(d: LocalDate?): String? {
        if (d == null) return t("জন্ম তারিখ দিন", "Enter your date of birth")
        val age = Period.between(d, Bn.today()).years
        return when {
            d.isAfter(Bn.today()) -> t("ভবিষ্যতের তারিখ হতে পারে না", "Date cannot be in the future")
            age < 18 -> t("শেয়ারহোল্ডারের বয়স কমপক্ষে ১৮ বছর হতে হবে", "Shareholder must be at least 18 years old")
            age > 120 -> t("সঠিক জন্ম তারিখ দিন", "Enter a valid date of birth")
            else -> null
        }
    }

    fun dob(s: String): String? = dob(parseDob(s))

    fun reference(s: String): String? =
        if (REF.matches(s.trim())) null
        else t("রসিদের রেফারেন্স নম্বর দিন (৩-৪০ অক্ষর, ইংরেজি অক্ষর/সংখ্যা)", "Enter the receipt reference (3–40 letters/digits)")

    fun required(s: String, what: String, min: Int = 1, max: Int = 1000): String? = when {
        s.trim().length < min -> t("$what লিখুন", "Enter $what")
        s.trim().length > max -> t("$what অনেক লম্বা (সর্বোচ্চ ${Bn.num(max)} অক্ষর)", "$what is too long (max ${Bn.num(max)} characters)")
        else -> null
    }

    /** Mask all but the last 3 digits of an id number. */
    fun maskId(s: String): String {
        val d = digitsOnly(s)
        return if (d.length <= 3) d else "•".repeat(d.length - 3) + d.takeLast(3)
    }
}
