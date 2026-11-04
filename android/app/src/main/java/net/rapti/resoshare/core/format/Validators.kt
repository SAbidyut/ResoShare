package net.rapti.resoshare.core.format

import java.time.LocalDate
import java.time.Period

/**
 * Client-side validation mirroring docs/API.md. The server re-validates everything;
 * these exist for fast, friendly feedback. Each returns null when valid, else a Bangla message.
 */
object V {
    private val NAME = Regex("^[\\p{L}\\p{M} .\\-]{3,100}$")
    private val EMAIL = Regex("^[A-Za-z0-9._%+\\-]{1,64}@[A-Za-z0-9.\\-]{1,190}\\.[A-Za-z]{2,24}$")
    private val MOBILE = Regex("^01[3-9]\\d{8}$")
    private val REF = Regex("^[A-Za-z0-9\\-/]{3,40}$")
    private val COMMON = setOf(
        "password", "password1", "12345678", "123456789", "1234567890", "qwerty123", "abc12345",
        "iloveyou1", "admin123", "11111111", "00000000", "password123", "bangladesh1", "a1234567",
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
        s.trim().length < 3 -> "পুরো নাম লিখুন (কমপক্ষে ৩ অক্ষর)"
        !NAME.matches(s.trim()) -> "নামে শুধু অক্ষর, স্পেস, ডট বা হাইফেন দিন"
        else -> null
    }

    fun mobile(s: String): String? = if (MOBILE.matches(normalizeMobile(s))) null else "সঠিক ১১ অঙ্কের মোবাইল নম্বর দিন (যেমন ০১৭XXXXXXXX)"

    fun email(s: String): String? = when {
        s.isBlank() -> "ইমেইল দিন"
        s.length > 254 || !EMAIL.matches(s.trim()) -> "সঠিক ইমেইল ঠিকানা দিন"
        else -> null
    }

    fun password(s: String): String? = when {
        s.length < 8 -> "পাসওয়ার্ড কমপক্ষে ৮ অক্ষরের হতে হবে"
        s.length > 128 -> "পাসওয়ার্ড অনেক লম্বা"
        !s.any { it.isLetter() } || !s.any { it.isDigit() } -> "পাসওয়ার্ডে অক্ষর ও সংখ্যা দুটোই রাখুন"
        s.lowercase() in COMMON -> "এই পাসওয়ার্ডটি খুব সহজ, অন্য একটি দিন"
        else -> null
    }

    fun confirm(p: String, c: String): String? = if (p == c) null else "দুটি পাসওয়ার্ড মেলেনি"

    fun nid(s: String): String? {
        val d = digitsOnly(s)
        return if (d.length in setOf(10, 13, 17)) null else "NID নম্বর ১০, ১৩ বা ১৭ অঙ্কের হয়"
    }

    fun nomineeId(type: String, s: String): String? {
        val d = digitsOnly(s)
        return if (type == "birth_reg") {
            if (d.length == 17) null else "জন্ম নিবন্ধন নম্বর ১৭ অঙ্কের হয়"
        } else nid(s)
    }

    fun relation(s: String): String? = if (s.trim().length in 2..30) null else "সম্পর্ক লিখুন (যেমন: স্ত্রী, পুত্র)"

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

    fun dob(s: String): String? {
        val d = parseDob(s) ?: return "জন্ম তারিখ দিন / মাস / বছর আকারে লিখুন"
        val age = Period.between(d, Bn.today()).years
        return when {
            d.isAfter(Bn.today()) -> "ভবিষ্যতের তারিখ হতে পারে না"
            age < 18 -> "শেয়ারহোল্ডারের বয়স কমপক্ষে ১৮ বছর হতে হবে"
            age > 120 -> "সঠিক জন্ম তারিখ দিন"
            else -> null
        }
    }

    fun reference(s: String): String? = if (REF.matches(s.trim())) null else "রসিদের রেফারেন্স নম্বর দিন (৩-৪০ অক্ষর, ইংরেজি অক্ষর/সংখ্যা)"

    fun required(s: String, what: String, min: Int = 1, max: Int = 1000): String? = when {
        s.trim().length < min -> "$what লিখুন"
        s.trim().length > max -> "$what অনেক লম্বা (সর্বোচ্চ ${Bn.num(max)} অক্ষর)"
        else -> null
    }

    /** Mask all but the last 3 digits of an id number. */
    fun maskId(s: String): String {
        val d = digitsOnly(s)
        return if (d.length <= 3) d else "•".repeat(d.length - 3) + d.takeLast(3)
    }
}
