package info.piepgras.cryptvault.backup

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.net.toUri

/**
 * Android 17 (API 37) blocks apps that target it from the local network unless the user grants
 * `ACCESS_LOCAL_NETWORK` (a runtime permission). A WebDAV server at home — a Nextcloud on a NAS —
 * is exactly that case, so the app asks at the point of use: when the host of the URL is a local
 * address, and only on platforms that know the permission.
 */
object LocalNetwork {
    const val PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
    const val FIRST_SDK = 37

    fun platformRequires(): Boolean = Build.VERSION.SDK_INT >= FIRST_SDK

    fun isGranted(context: Context): Boolean =
        !platformRequires() || ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** True when [url]'s host is a private, link-local, loopback or unqualified name — a local-network destination. */
    fun isLocalHost(url: String): Boolean {
        val host = runCatching { url.toUri().host }.getOrNull()?.lowercase()?.trim('[', ']') ?: return false
        if (host == "localhost" || host.endsWith(".local") || host.endsWith(".home.arpa") || host.endsWith(".lan") || host.endsWith(".internal")) return true
        if ('.' !in host && ':' !in host) return true // a bare hostname resolves on the LAN
        val v4 = host.split('.').takeIf { it.size == 4 && it.all { p -> p.toIntOrNull() in 0..255 } }?.map { it.toInt() }
        if (v4 != null) {
            val (a, b) = v4
            return a == 10 || a == 127 || (a == 192 && b == 168) || (a == 172 && b in 16..31) || (a == 169 && b == 254) || (a == 100 && b in 64..127)
        }
        // IPv6: loopback, link-local, unique-local
        return host == "::1" || host.startsWith("fe80:") || host.startsWith("fc") || host.startsWith("fd")
    }

    /** Whether talking to [url] needs the permission and it has not been granted. */
    fun needsPermission(context: Context, url: String): Boolean = platformRequires() && isLocalHost(url) && !isGranted(context)
}
