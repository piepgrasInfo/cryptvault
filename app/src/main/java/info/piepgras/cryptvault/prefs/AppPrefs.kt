package info.piepgras.cryptvault.prefs

import android.content.Context
import androidx.core.content.edit
import info.piepgras.cryptvault.items.SortKey

/** Small app-wide settings; one object per concern is the house pattern, this is the first. */
object AppPrefs {
    private const val FILE = "app"
    private const val KEY_LEGAL_ACCEPTED = "legal_accepted"
    private const val KEY_SORT = "sort"
    private const val KEY_SORT_ASC = "sort_asc"
    private const val KEY_GRID = "grid"
    private const val KEY_OPEN_WITH_NOTICE = "open_with_notice_shown"
    private const val KEY_DEFAULT_AUTO_LOCK = "default_auto_lock"
    private const val KEY_CONTAINER_KIND = "container_kind"
    private const val KEY_DELIVERABILITY_NOTICE = "deliverability_notice_shown"

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun legalAccepted(context: Context): Boolean = prefs(context).getBoolean(KEY_LEGAL_ACCEPTED, false)
    fun setLegalAccepted(context: Context) = prefs(context).edit { putBoolean(KEY_LEGAL_ACCEPTED, true) }

    fun sortKey(context: Context): SortKey = runCatching { SortKey.valueOf(prefs(context).getString(KEY_SORT, null) ?: "NAME") }.getOrDefault(SortKey.NAME)
    fun sortAscending(context: Context): Boolean = prefs(context).getBoolean(KEY_SORT_ASC, true)
    fun setSort(context: Context, key: SortKey, ascending: Boolean) = prefs(context).edit { putString(KEY_SORT, key.name); putBoolean(KEY_SORT_ASC, ascending) }

    fun grid(context: Context): Boolean = prefs(context).getBoolean(KEY_GRID, false)
    fun setGrid(context: Context, grid: Boolean) = prefs(context).edit { putBoolean(KEY_GRID, grid) }

    /** The container format the user shared with last (BUILD_BRIEF.md §7.2: "the default is remembered"). */
    fun containerKind(context: Context): info.piepgras.cryptvault.share.ContainerKind =
        runCatching { info.piepgras.cryptvault.share.ContainerKind.valueOf(prefs(context).getString(KEY_CONTAINER_KIND, null) ?: "ZIP") }.getOrDefault(info.piepgras.cryptvault.share.ContainerKind.ZIP)
    fun setContainerKind(context: Context, kind: info.piepgras.cryptvault.share.ContainerKind) = prefs(context).edit { putString(KEY_CONTAINER_KIND, kind.name) }

    fun deliverabilityNoticeShown(context: Context): Boolean = prefs(context).getBoolean(KEY_DELIVERABILITY_NOTICE, false)
    fun setDeliverabilityNoticeShown(context: Context) = prefs(context).edit { putBoolean(KEY_DELIVERABILITY_NOTICE, true) }

    fun openWithNoticeShown(context: Context): Boolean = prefs(context).getBoolean(KEY_OPEN_WITH_NOTICE, false)
    fun setOpenWithNoticeShown(context: Context) = prefs(context).edit { putBoolean(KEY_OPEN_WITH_NOTICE, true) }

    fun defaultAutoLockSeconds(context: Context): Int = prefs(context).getInt(KEY_DEFAULT_AUTO_LOCK, 60)
    fun setDefaultAutoLockSeconds(context: Context, seconds: Int) = prefs(context).edit { putInt(KEY_DEFAULT_AUTO_LOCK, seconds) }
}
