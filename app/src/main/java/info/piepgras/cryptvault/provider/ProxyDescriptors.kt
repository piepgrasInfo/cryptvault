package info.piepgras.cryptvault.provider

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.system.ErrnoException
import android.system.OsConstants
import info.piepgras.cryptvault.vault.CryptomatorVault
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

/**
 * Seekable, read-only file descriptors that decrypt on demand (`StorageManager.openProxyFileDescriptor`,
 * API 26): the kernel's reads come back to [CryptomatorVault.RandomAccessReader] chunk by chunk,
 * so a PDF renderer, a media player or the system file picker can seek in a file that never
 * exists in the clear. The callbacks run on one dedicated thread.
 *
 * Every open reader is tracked by vault, and [closeAll] — called when a vault locks — closes
 * them, so a descriptor another app still holds fails on its next read (docs/THREAT_MODEL.md S8).
 */
object ProxyDescriptors {

    private val thread: HandlerThread by lazy { HandlerThread("cryptvault-proxy-fd").apply { start() } }
    private val handler: Handler by lazy { Handler(thread.looper) }
    private val open = ConcurrentHashMap<String, MutableSet<AutoCloseable>>()

    private fun track(vaultId: String?, closeable: AutoCloseable) {
        if (vaultId != null) open.getOrPut(vaultId) { ConcurrentHashMap.newKeySet() }.add(closeable)
    }

    private fun untrack(vaultId: String?, closeable: AutoCloseable) {
        if (vaultId != null) open[vaultId]?.remove(closeable)
    }

    /** Closes every descriptor's backing reader for a vault; their next read returns EIO. */
    fun closeAll(vaultId: String) {
        open.remove(vaultId)?.forEach { runCatching { it.close() } }
    }

    fun openCount(vaultId: String): Int = open[vaultId]?.size ?: 0

    fun readOnly(context: Context, reader: CryptomatorVault.RandomAccessReader, vaultId: String? = null): ParcelFileDescriptor {
        val storage = context.getSystemService(StorageManager::class.java)
            ?: throw IOException("no StorageManager")
        val callback = object : ProxyFileDescriptorCallback() {
            override fun onGetSize(): Long = reader.size

            override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
                try {
                    val buf = ByteBuffer.wrap(data, 0, size)
                    val n = reader.read(offset, buf)
                    return if (n < 0) 0 else n
                } catch (e: Exception) {
                    // A tampered chunk, a closed reader (vault locked) or a storage failure:
                    // report an I/O error, never garbage.
                    throw ErrnoException("read", OsConstants.EIO)
                }
            }

            override fun onRelease() {
                untrack(vaultId, reader)
                runCatching { reader.close() }
            }
        }
        track(vaultId, reader)
        return storage.openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY, callback, handler)
    }

    /** Registers a write-side resource (a pipe) so a lock can cut it off too. */
    fun trackWriter(vaultId: String, closeable: AutoCloseable) = track(vaultId, closeable)
    fun untrackWriter(vaultId: String, closeable: AutoCloseable) = untrack(vaultId, closeable)
}
