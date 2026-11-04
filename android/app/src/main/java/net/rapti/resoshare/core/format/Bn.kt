package net.rapti.resoshare.core.format

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeParseException
import kotlin.math.abs

/** Bangla formatting helpers. All money is integer paisa; never floating point. */
object Bn {
    val DHAKA: ZoneId = ZoneId.of("Asia/Dhaka")
    private const val DIGITS = "০১২৩৪৫৬৭৮৯"

    private val MONTHS = arrayOf(
        "জানুয়ারি", "ফেব্রুয়ারি", "মার্চ", "এপ্রিল", "মে", "জুন",
        "জুলাই", "আগস্ট", "সেপ্টেম্বর", "অক্টোবর", "নভেম্বর", "ডিসেম্বর",
    )
    private val MONTHS_SHORT = arrayOf(
        "জানু", "ফেব", "মার্চ", "এপ্রি", "মে", "জুন", "জুলা", "আগ", "সেপ্টে", "অক্টো", "নভে", "ডিসে",
    )
    // java.time DayOfWeek: MONDAY=1 … SUNDAY=7
    private val WEEKDAYS_SHORT = arrayOf("সোম", "মঙ্গল", "বুধ", "বৃহ", "শুক্র", "শনি", "রবি")

    /** Replace ASCII digits with Bangla digits. */
    fun digits(s: String): String = buildString(s.length) {
        for (c in s) append(if (c in '0'..'9') DIGITS[c - '0'] else c)
    }

    /** Replace Bangla digits with ASCII digits (for parsing user input). */
    fun ascii(s: String): String = buildString(s.length) {
        for (c in s) {
            val i = DIGITS.indexOf(c)
            append(if (i >= 0) ('0' + i) else c)
        }
    }

    /** Indian digit grouping: 2600000 → 26,00,000 */
    fun groupIndian(n: Long): String {
        val s = abs(n).toString()
        if (s.length <= 3) return (if (n < 0) "-" else "") + s
        val last3 = s.takeLast(3)
        var rest = s.dropLast(3)
        val parts = ArrayList<String>()
        while (rest.length > 2) {
            parts.add(0, rest.takeLast(2))
            rest = rest.dropLast(2)
        }
        if (rest.isNotEmpty()) parts.add(0, rest)
        return (if (n < 0) "-" else "") + parts.joinToString(",") + "," + last3
    }

    fun num(n: Long): String = digits(groupIndian(n))
    fun num(n: Int): String = num(n.toLong())

    /** 100000 paisa → ৳১,০০০.০০ */
    fun money(paisa: Long, symbol: Boolean = true): String {
        val a = abs(paisa)
        val taka = a / 100
        val p = (a % 100).toInt()
        val body = groupIndian(taka) + "." + (if (p < 10) "0$p" else "$p")
        return (if (paisa < 0) "-" else "") + (if (symbol) "৳" else "") + digits(body)
    }

    /** Money without the ৳ sign, used inside editable rate fields: 1,015.00 */
    fun moneyPlain(paisa: Long): String = money(paisa, symbol = false)

    /** +৳১৫.০০ / −৳৫.০০ / ৳০.০০ */
    fun signedMoney(paisa: Long): String = when {
        paisa > 0 -> "+" + money(paisa)
        paisa < 0 -> "−" + money(-paisa)
        else -> money(0)
    }

    /** Basis points → "+৪.২%" (one decimal). */
    fun percentBp(bp: Int, signed: Boolean = true): String {
        val a = abs(bp)
        val whole = a / 100
        val dec = (a % 100) / 10
        val sign = if (!signed) "" else if (bp > 0) "+" else if (bp < 0) "−" else ""
        return sign + digits("$whole.$dec") + "%"
    }

    /** Parse a user typed money string ("১,০১২.৫০" / "1012.5") into paisa, or null. */
    fun parseMoney(input: String): Long? {
        val s = ascii(input).replace(",", "").replace("৳", "").trim()
        if (s.isEmpty() || !Regex("^\\d{1,10}(\\.\\d{0,2})?$").matches(s)) return null
        val parts = s.split(".")
        val taka = parts[0].toLongOrNull() ?: return null
        val paisa = if (parts.size > 1 && parts[1].isNotEmpty()) parts[1].padEnd(2, '0').toLong() else 0L
        return taka * 100 + paisa
    }

    fun parseDate(s: String?): LocalDate? = try {
        if (s.isNullOrBlank()) null else LocalDate.parse(s.take(10))
    } catch (_: DateTimeParseException) {
        null
    }

    fun parseInstant(s: String?): ZonedDateTime? = try {
        if (s.isNullOrBlank()) null else Instant.parse(s).atZone(DHAKA)
    } catch (_: DateTimeParseException) {
        null
    }

    fun today(): LocalDate = LocalDate.now(DHAKA)

    private fun two(n: Int) = digits(if (n < 10) "0$n" else "$n")

    fun monthName(month: Int): String = MONTHS[month - 1]
    fun monthShort(month: Int): String = MONTHS_SHORT[month - 1]

    /** ২৮ অক্টোবর ২০২৬ */
    fun date(d: LocalDate): String = "${two(d.dayOfMonth)} ${MONTHS[d.monthValue - 1]} ${digits(d.year.toString())}"
    fun date(s: String?): String = parseDate(s)?.let { date(it) } ?: "—"

    /** ০৪ নভেম্বর */
    fun dayMonth(d: LocalDate): String = "${two(d.dayOfMonth)} ${MONTHS[d.monthValue - 1]}"

    /** বুধ, ০৫ নভে */
    fun weekdayDayMonth(d: LocalDate): String =
        "${WEEKDAYS_SHORT[d.dayOfWeek.value - 1]}, ${two(d.dayOfMonth)} ${MONTHS_SHORT[d.monthValue - 1]}"

    /** ১১:৪২ AM */
    fun time(z: ZonedDateTime): String {
        val h12 = if (z.hour % 12 == 0) 12 else z.hour % 12
        return "${two(h12)}:${two(z.minute)} ${if (z.hour < 12) "AM" else "PM"}"
    }

    /** "আজ, ১১:৪২ AM" / "গতকাল, ০৪:১৫ PM" / "২৮ অক্টোবর ২০২৬" */
    fun relative(s: String?, withTime: Boolean = true): String {
        val z = parseInstant(s) ?: return "—"
        val d = z.toLocalDate()
        val t = today()
        return when (d) {
            t -> if (withTime) "আজ, ${time(z)}" else "আজ"
            t.minusDays(1) -> if (withTime) "গতকাল, ${time(z)}" else "গতকাল"
            else -> date(d)
        }
    }

    /** Date part of an ISO timestamp in Dhaka. */
    fun dateOf(s: String?): String = parseInstant(s)?.let { date(it.toLocalDate()) } ?: "—"

    /** Chart axis label for a period bucket. */
    fun axisLabel(d: LocalDate, period: String): String = when (period) {
        "daily" -> two(d.dayOfMonth)
        "weekly" -> "${two(d.dayOfMonth)}/${digits(d.monthValue.toString())}"
        "monthly" -> MONTHS_SHORT[d.monthValue - 1]
        else -> digits(d.year.toString())
    }

    /** First grapheme of a name for avatars (handles Bangla vowel signs / conjunct joiners). */
    fun initial(name: String): String {
        val t = name.trim().removePrefix("মোঃ").removePrefix("মো.").trim()
        if (t.isEmpty()) return "?"
        val it = java.text.BreakIterator.getCharacterInstance()
        it.setText(t)
        val end = it.next()
        return if (end > 0) t.substring(0, end) else t.take(1)
    }
}
