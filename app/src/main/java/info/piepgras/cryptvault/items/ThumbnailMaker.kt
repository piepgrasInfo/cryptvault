package info.piepgras.cryptvault.items

import info.piepgras.cryptvault.vault.CryptomatorVault
import java.io.InputStream

/**
 * Produces a small JPEG for an item, or null when the type has no picture. The Android
 * implementation decodes images, grabs a video frame and renders a PDF's first page; tests use
 * a fake. Both read the item through the vault, never from a plaintext file.
 */
fun interface ThumbnailMaker {
    /**
     * @param openStream a fresh decrypting stream of the item, callable more than once
     * @param openRandom a fresh random-access reader of the item, for formats that seek
     * @return JPEG bytes, or null
     */
    fun make(
        mime: String,
        name: String,
        size: Long,
        openStream: () -> InputStream,
        openRandom: () -> CryptomatorVault.RandomAccessReader,
    ): ByteArray?
}
