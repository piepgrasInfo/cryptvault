package info.piepgras.cryptvault.vault

import org.cryptomator.cryptofs.CryptoFileSystemProperties
import org.cryptomator.cryptofs.CryptoFileSystemProvider
import org.cryptomator.cryptolib.api.CryptorProvider
import org.cryptomator.cryptolib.api.Masterkey
import org.cryptomator.cryptolib.api.MasterkeyLoader
import org.cryptomator.cryptolib.common.MasterkeyFileAccess
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.URI
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.channels.Channels
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.util.Random

/**
 * The interop promise of BUILD_BRIEF.md §1, checked against Cryptomator's own file-system layer
 * (`org.cryptomator:cryptofs`, the code the desktop app runs): a vault this app writes is a
 * vault cryptofs reads, and the other way round. This is the automated half of the Phase 0
 * acceptance; the desktop GUI check is the developer's.
 */
class CryptofsInteropTest {

    private lateinit var dir: Path
    private val password = "interop passphrase 2026".toCharArray()
    private val rnd = Random(42)

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("cryptvault-interop")
    }

    @After
    fun tearDown() {
        PathVaultStorage(dir).deleteRecursively("")
    }

    private fun cryptofsProperties(): CryptoFileSystemProperties {
        val loader = MasterkeyLoader { _: URI ->
            MasterkeyFileAccess(ByteArray(0), SecureRandom()).load(dir.resolve("masterkey.cryptomator"), CharBuffer.wrap(password))
        }
        return CryptoFileSystemProperties.cryptoFileSystemProperties()
            .withKeyLoader(loader)
            .withCipherCombo(CryptorProvider.Scheme.SIV_GCM)
            .withShorteningThreshold(220)
            .build()
    }

    @Test
    fun `a vault written by CryptVault opens in cryptofs`() {
        val big = ByteArray(70_000).also { rnd.nextBytes(it) }
        val exact = ByteArray(3 * 32768).also { rnd.nextBytes(it) }
        val longName = "l".repeat(150) + ".bin"
        CryptomatorVault.create(PathVaultStorage(dir), password).use { vault ->
            vault.writeFile("", "hello.txt").use { it.write(ByteBuffer.wrap("hello from CryptVault".toByteArray())) }
            val docs = vault.createDirectory("", "Documents")
            vault.writeFile(docs, "big.bin").use { it.write(ByteBuffer.wrap(big)) }
            vault.writeFile(docs, longName).use { it.write(ByteBuffer.wrap("long".toByteArray())) }
            val sub = vault.createDirectory(docs, "Ünïcödé 🔐")
            vault.writeFile(sub, "note.md").use { it.write(ByteBuffer.wrap("# note".toByteArray())) }
            vault.writeFile("", "empty.bin").close()
            vault.writeFile("", "exact.bin").use { it.write(ByteBuffer.wrap(exact)) }
        }

        CryptoFileSystemProvider.newFileSystem(dir, cryptofsProperties()).use { fs ->
            val root = fs.getPath("/")
            val names = Files.list(root).use { s -> s.map { it.fileName.toString() }.sorted().toList() }
            assertEquals(listOf("Documents", "empty.bin", "exact.bin", "hello.txt"), names)
            assertEquals("hello from CryptVault", Files.readString(root.resolve("hello.txt")))
            // the two sizes cryptolib's own channel gets wrong: desktop must see them right
            assertEquals(0L, Files.size(root.resolve("empty.bin")))
            assertEquals(0, Files.readAllBytes(root.resolve("empty.bin")).size)
            assertEquals((3 * 32768).toLong(), Files.size(root.resolve("exact.bin")))
            assertArrayEquals(exact, Files.readAllBytes(root.resolve("exact.bin")))
            assertArrayEquals(big, Files.readAllBytes(root.resolve("Documents/big.bin")))
            assertEquals(70_000L, Files.size(root.resolve("Documents/big.bin")))
            assertEquals("long", Files.readString(root.resolve("Documents/$longName")))
            assertEquals("# note", Files.readString(root.resolve("Documents/Ünïcödé 🔐/note.md")))
            assertTrue(Files.isDirectory(root.resolve("Documents/Ünïcödé 🔐")))

            // cryptofs edits the vault; CryptVault sees the edits after re-opening (§2.2 reconciliation)
            Files.writeString(root.resolve("Documents/from-desktop.txt"), "written by cryptofs")
            Files.move(root.resolve("hello.txt"), root.resolve("renamed.txt"))
            Files.delete(root.resolve("Documents/$longName"))
        }

        CryptomatorVault.open(PathVaultStorage(dir), password).use { vault ->
            assertEquals(listOf("Documents", "empty.bin", "exact.bin", "renamed.txt"), vault.list("").map { it.name })
            val docs = vault.list("").first { it.name == "Documents" }.dirId!!
            assertEquals(listOf("big.bin", "from-desktop.txt", "Ünïcödé 🔐"), vault.list(docs).map { it.name })
            val fromDesktop = vault.list(docs).first { it.name == "from-desktop.txt" }
            assertEquals("written by cryptofs", String(Channels.newInputStream(vault.readFile(fromDesktop)).readBytes()))
        }
    }

    @Test
    fun `a vault created by cryptofs opens in CryptVault, including the recovery-key path`() {
        MasterkeyFileAccess(ByteArray(0), SecureRandom())
            .persist(Masterkey.generate(SecureRandom()), dir.resolve("masterkey.cryptomator"), CharBuffer.wrap(password))
        CryptoFileSystemProvider.initialize(dir, cryptofsProperties(), URI.create("masterkeyfile:masterkey.cryptomator"))
        val payload = ByteArray(40_000).also { rnd.nextBytes(it) }
        CryptoFileSystemProvider.newFileSystem(dir, cryptofsProperties()).use { fs ->
            val root = fs.getPath("/")
            Files.createDirectory(root.resolve("Photos"))
            Files.write(root.resolve("Photos/img.bin"), payload)
            Files.writeString(root.resolve("readme.txt"), "made by cryptofs")
        }

        CryptomatorVault.open(PathVaultStorage(dir), password).use { vault ->
            assertEquals(8, vault.config.format)
            assertEquals(listOf("Photos", "readme.txt"), vault.list("").map { it.name })
            val photos = vault.list("").first { it.name == "Photos" }
            assertEquals(EntryKind.DIRECTORY, photos.kind)
            val img = vault.list(photos.dirId!!).single()
            assertEquals(40_000L, img.size)
            assertArrayEquals(payload, Channels.newInputStream(vault.readFile(img)).readBytes())
            vault.openRandomAccess(img).use { r ->
                val buf = ByteBuffer.allocate(1000)
                assertEquals(1000, r.read(35_000, buf))
                assertArrayEquals(payload.copyOfRange(35_000, 36_000), buf.array())
            }
            // CryptVault writes into the cryptofs-made vault ...
            vault.writeFile(photos.dirId, "added.txt").use { it.write(ByteBuffer.wrap("added".toByteArray())) }
            vault.changePassword(password, "changed".toCharArray())
        }

        // ... and cryptofs reads it back with the changed password.
        val changed = "changed".toCharArray()
        val loader = MasterkeyLoader { _: URI ->
            MasterkeyFileAccess(ByteArray(0), SecureRandom()).load(dir.resolve("masterkey.cryptomator"), CharBuffer.wrap(changed))
        }
        val props = CryptoFileSystemProperties.cryptoFileSystemProperties().withKeyLoader(loader).build()
        CryptoFileSystemProvider.newFileSystem(dir, props).use { fs ->
            assertEquals("added", Files.readString(fs.getPath("/Photos/added.txt")))
            assertEquals("made by cryptofs", Files.readString(fs.getPath("/readme.txt")))
        }
    }
}
