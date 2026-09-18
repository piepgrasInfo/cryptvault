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

/**
 * Seekable, read-only file descriptors that decrypt on demand (`StorageManager.openProxyFileDescriptor`,
 * API 26): the kernel's reads come back to [CryptomatorVault.RandomAccessReader] chunk by chunk,
 * so a PDF renderer, a media player or the system file picker can seek in a file that never
 * exists in the clear. The callbacks run on one dedicated thread.
 */
object ProxyDescriptors {

    private val thread: HandlerThread by lazy { HandlerThread("cryptvault-proxy-fd").apply { start() } }
    private val handler: Handler by lazy { Handler(thread.looper) }

    fun readOnly(context: Context, reader: CryptomatorVault.RandomAccessReader): ParcelFileDescriptor {
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
                    // A tampered chunk or a storage failure: report an I/O error, never garbage.
                    throw ErrnoException("read", OsConstants.EIO)
                }
            }

            override fun onRelease() {
                runCatching { reader.close() }
            }
        }
        return storage.openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY, callback, handler)
    }
}
