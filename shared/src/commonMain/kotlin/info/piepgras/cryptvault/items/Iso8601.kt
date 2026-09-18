package info.piepgras.cryptvault.items

/**
 * UTC timestamps as `2026-09-18T09:12:44Z` in common code, without a date library. The
 * manifest is read by people on the desktop, so it carries readable time, not epoch millis.
 */
object Iso8601 {

    fun format(epochMillis: Long): String {
        val seconds = epochMillis.floorDiv(1000L)
        val days = seconds.floorDiv(86_400L)
        val secOfDay = seconds.mod(86_400L).toInt()
        val (y, m, d) = civilFromDays(days)
        return buildString {
            append(y.toString().padStart(4, '0')).append('-')
            append(m.toString().padStart(2, '0')).append('-')
            append(d.toString().padStart(2, '0')).append('T')
            append((secOfDay / 3600).toString().padStart(2, '0')).append(':')
            append((secOfDay % 3600 / 60).toString().padStart(2, '0')).append(':')
            append((secOfDay % 60).toString().padStart(2, '0')).append('Z')
        }
    }

    /** Parses what [format] writes (and a fractional-seconds suffix); null for anything else. */
    fun parse(text: String): Long? {
        val m = REGEX.matchEntire(text.trim()) ?: return null
        val (y, mo, d, h, mi, s) = m.destructured
        val days = daysFromCivil(y.toInt(), mo.toInt(), d.toInt())
        return (days * 86_400L + h.toInt() * 3600L + mi.toInt() * 60L + s.toInt()) * 1000L
    }

    private val REGEX = Regex("""(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.\d+)?Z""")

    // Howard Hinnant's algorithms: days since 1970-01-01 <-> proleptic Gregorian civil date.
    private fun civilFromDays(z0: Long): Triple<Int, Int, Int> {
        val z = z0 + 719_468
        val era = z.floorDiv(146_097L)
        val doe = z - era * 146_097
        val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
        val y = yoe + era * 400
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = (doy - (153 * mp + 2) / 5 + 1).toInt()
        val m = (if (mp < 10) mp + 3 else mp - 9).toInt()
        return Triple((if (m <= 2) y + 1 else y).toInt(), m, d)
    }

    private fun daysFromCivil(y0: Int, m: Int, d: Int): Long {
        val y = if (m <= 2) y0 - 1 else y0
        val era = y.floorDiv(400)
        val yoe = y - era * 400
        val doy = (153 * (if (m > 2) m - 3 else m + 9) + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097L + doe - 719_468
    }
}
