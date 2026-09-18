package info.piepgras.cryptvault.ui

import android.content.Context
import android.text.format.DateUtils
import info.piepgras.cryptvault.items.Iso8601
import java.util.Locale

/** Display formatting only; nothing here decides anything. */
object Format {

    fun size(bytes: Long): String {
        if (bytes < 0) return "?"
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var v = bytes.toDouble() / 1024
        var i = 0
        while (v >= 1024 && i < units.lastIndex) {
            v /= 1024
            i++
        }
        return String.format(Locale.getDefault(), if (v >= 100) "%.0f %s" else "%.1f %s", v, units[i])
    }

    /** "2 hours ago" style for recent times, a short date otherwise. */
    fun date(context: Context, iso: String): String {
        val t = Iso8601.parse(iso) ?: return iso
        return DateUtils.getRelativeDateTimeString(context, t, DateUtils.MINUTE_IN_MILLIS, DateUtils.WEEK_IN_MILLIS, 0).toString()
    }

    fun shortDate(context: Context, iso: String): String {
        val t = Iso8601.parse(iso) ?: return iso
        return DateUtils.formatDateTime(context, t, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_ABBREV_MONTH)
    }
}
