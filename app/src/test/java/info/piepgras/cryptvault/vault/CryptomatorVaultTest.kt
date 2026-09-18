package info.piepgras.cryptvault.vault

import info.piepgras.cryptvault.recovery.RecoveryKey
import org.cryptomator.cryptolib.api.AuthenticationFailedException
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.ReadableByteChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.Random

/**
 * The cryptolib round trip BUILD_BRIEF.md §12 Phase 0 asks for, plus the layout rules of
 * docs/VAULT_LAYOUT.md §2. Everything runs on a temp directory through [PathVaultStorage];
 * cryptolib is pure Java, so this is the real code path, not a fake.
 */
class CryptomatorVaultTest {

    private lateinit var dir: Path
    private lateinit var storage: PathVaultStorage
    private val password = "correct horse battery staple".toCharArray()
    private val rnd = Random(20260918)

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("cryptvault-test")
        storage = PathVaultStorage(dir)
    }

    @After
    fun tearDown() {
        storage.deleteRecursively("")
    }

    private fun CryptomatorVault.put(dirId: String, name: String, bytes: ByteArray) {
        writeFile(dirId, name).use { it.write(ByteBuffer.wrap(bytes)) }
    }

    private fun ReadableByteChannel.readAll(): ByteArray = Channels.newInputStream(this).use { it.readBytes() }

    private fun CryptomatorVault.entry(dirId: String, name: String): VaultEntry =
        list(dirId).firstOrNull { it.name == name } ?: throw AssertionError("no entry '$name' in $dirId: ${list(dirId).map { it.name }}")

    private fun CryptomatorVault.get(dirId: String, name: String): ByteArray = readFile(entry(dirId, name)).readAll()

    @Test
    fun `creates the format 8 files and the root directory`() {
        CryptomatorVault.create(storage, password).use { vault ->
            assertTrue(Files.isRegularFile(dir.resolve("vault.cryptomator")))
            assertTrue(Files.isRegularFile(dir.resolve("masterkey.cryptomator")))
            val masterkeyJson = Files.readString(dir.resolve("masterkey.cryptomator"))
            for (field in listOf("scryptSalt", "scryptCostParam", "scryptBlockSize", "primaryMasterKey", "hmacMasterKey", "versionMac")) {
                assertTrue("masterkey file has $field", masterkeyJson.contains("\"$field\""))
            }
            assertTrue(masterkeyJson.contains("\"version\": 999") || masterkeyJson.contains("\"version\":999"))
            assertEquals(8, vault.config.format)
            assertEquals("SIV_GCM", vault.config.cipherCombo)
            assertEquals(220, vault.config.shorteningThreshold)
            val root = dir.resolve(vault.dirPath(CryptomatorVault.ROOT_DIR_ID))
            assertTrue(Files.isDirectory(root))
            assertTrue(root.parent.fileName.toString().length == 2)
            assertTrue(root.fileName.toString().length == 30)
            assertTrue(Files.isRegularFile(root.resolve("dirid.c9r")))
            assertEquals(68, vault.headerSize)
            assertEquals(32768, vault.cleartextChunkSize)
            assertEquals(32768 + 28, vault.ciphertextChunkSize)
            assertTrue(CryptomatorVault.isVault(storage))
            assertTrue(vault.list(CryptomatorVault.ROOT_DIR_ID).isEmpty())
        }
    }

    @Test
    fun `writes and reads a multi-chunk file sequentially and by random access`() {
        val big = ByteArray(100_000).also { rnd.nextBytes(it) } // 3 full chunks + a partial one
        val small = "hello, vault".toByteArray()
        CryptomatorVault.create(storage, password).use { vault ->
            vault.put("", "big.bin", big)
            vault.put("", "hello.txt", small)

            val entries = vault.list("")
            assertEquals(listOf("big.bin", "hello.txt"), entries.map { it.name })
            assertEquals(EntryKind.FILE, entries[0].kind)
            assertEquals(100_000L, entries[0].size)
            assertEquals(small.size.toLong(), entries[1].size)
            assertEquals(68L + 3 * (32768 + 28) + (100_000 - 3 * 32768) + 28, Files.size(dir.resolve(entries[0].contentPath!!)))
            assertTrue(entries[0].entryPath.endsWith(".c9r"))
            assertTrue(Files.isRegularFile(dir.resolve(entries[0].entryPath)))

            assertArrayEquals(big, vault.get("", "big.bin"))
            assertArrayEquals(small, vault.get("", "hello.txt"))

            vault.openRandomAccess(entries[0]).use { reader ->
                assertEquals(100_000L, reader.size)
                fun readAt(offset: Long, n: Int): ByteArray {
                    val buf = ByteBuffer.allocate(n)
                    val got = reader.read(offset, buf)
                    return buf.array().copyOf(maxOf(got, 0))
                }
                assertArrayEquals(big.copyOfRange(0, 10), readAt(0, 10))
                assertArrayEquals(big.copyOfRange(32_700, 32_900), readAt(32_700, 200)) // across a chunk boundary
                assertArrayEquals(big.copyOfRange(65_536, 65_536 + 4096), readAt(65_536, 4096)) // exactly at a boundary
                assertArrayEquals(big.copyOfRange(99_900, 100_000), readAt(99_900, 500)) // tail, short read
                assertEquals(-1, reader.read(100_000, ByteBuffer.allocate(10)))
                assertArrayEquals(big, readAt(0, 100_000))
            }
        }
    }

    @Test
    fun `empty files and exact chunk multiples carry no surplus chunk`() {
        CryptomatorVault.create(storage, password).use { vault ->
            vault.put("", "empty.txt", ByteArray(0))
            val one = ByteArray(32768).also { rnd.nextBytes(it) }
            val two = ByteArray(2 * 32768).also { rnd.nextBytes(it) }
            vault.put("", "one.bin", one)
            vault.put("", "two.bin", two)
            val e = vault.entry("", "empty.txt")
            assertEquals(0L, e.size)
            assertEquals(68L, Files.size(dir.resolve(e.contentPath!!)))
            assertArrayEquals(ByteArray(0), vault.get("", "empty.txt"))
            assertEquals(68L + 32796, Files.size(dir.resolve(vault.entry("", "one.bin").contentPath!!)))
            assertEquals(68L + 2 * 32796, Files.size(dir.resolve(vault.entry("", "two.bin").contentPath!!)))
            assertEquals(32768L, vault.entry("", "one.bin").size)
            assertEquals(65536L, vault.entry("", "two.bin").size)
            assertArrayEquals(one, vault.get("", "one.bin"))
            assertArrayEquals(two, vault.get("", "two.bin"))
            vault.openRandomAccess(vault.entry("", "two.bin")).use { r ->
                assertEquals(65536L, r.size)
                val buf = ByteBuffer.allocate(100)
                assertEquals(100, r.read(65_436, buf))
                assertArrayEquals(two.copyOfRange(65_436, 65_536), buf.array())
                assertEquals(-1, r.read(65_536, ByteBuffer.allocate(1)))
            }
            vault.openRandomAccess(e).use { r ->
                assertEquals(0L, r.size)
                assertEquals(-1, r.read(0, ByteBuffer.allocate(1)))
            }
        }
    }

    @Test
    fun `directories nest, list, move and delete with their subtree`() {
        CryptomatorVault.create(storage, password).use { vault ->
            val docs = vault.createDirectory("", "Documents")
            val inner = vault.createDirectory(docs, "2026")
            vault.put(docs, "passport.pdf", ByteArray(10) { 1 })
            vault.put(inner, "tax.pdf", ByteArray(20) { 2 })
            assertTrue(docs.isNotEmpty() && docs != inner)

            val rootList = vault.list("")
            assertEquals(1, rootList.size)
            assertEquals(EntryKind.DIRECTORY, rootList[0].kind)
            assertEquals(docs, rootList[0].dirId)
            assertTrue(Files.isRegularFile(dir.resolve(rootList[0].entryPath).resolve("dir.c9r")))
            assertEquals(docs, Files.readString(dir.resolve(rootList[0].entryPath).resolve("dir.c9r")))
            assertTrue(Files.isRegularFile(dir.resolve(vault.dirPath(docs)).resolve("dirid.c9r")))

            assertEquals(listOf("2026", "passport.pdf"), vault.list(docs).map { it.name })
            assertEquals(listOf("tax.pdf"), vault.list(inner).map { it.name })
            assertTrue(vault.exists(docs, "passport.pdf"))
            assertFalse(vault.exists(docs, "nope"))

            // Rename the directory: its id and its d/ storage stay where they are.
            vault.move(vault.entry("", "Documents"), "", "Papers")
            assertEquals(listOf("Papers"), vault.list("").map { it.name })
            assertEquals(docs, vault.entry("", "Papers").dirId)
            assertArrayEquals(ByteArray(20) { 2 }, vault.get(inner, "tax.pdf"))

            // Move a file across directories: the ciphertext name changes with the parent id.
            val before = vault.entry(docs, "passport.pdf").entryPath
            vault.move(vault.entry(docs, "passport.pdf"), inner, "passport.pdf")
            assertFalse(Files.exists(dir.resolve(before)))
            assertEquals(listOf("passport.pdf", "tax.pdf"), vault.list(inner).map { it.name })
            assertArrayEquals(ByteArray(10) { 1 }, vault.get(inner, "passport.pdf"))

            // Delete the whole tree: both hashed directories disappear.
            vault.delete(vault.entry("", "Papers"))
            assertTrue(vault.list("").isEmpty())
            assertFalse(Files.exists(dir.resolve(vault.dirPath(docs))))
            assertFalse(Files.exists(dir.resolve(vault.dirPath(inner))))
            assertTrue(Files.exists(dir.resolve(vault.dirPath(""))))
        }
    }

    @Test
    fun `refuses duplicate names and bad names`() {
        CryptomatorVault.create(storage, password).use { vault ->
            vault.createDirectory("", "A")
            assertThrows(IOException::class.java) { vault.createDirectory("", "A") }
            vault.put("", "f", ByteArray(1))
            assertThrows(IOException::class.java) { vault.move(vault.entry("", "f"), "", "A") }
            assertThrows(IllegalArgumentException::class.java) { vault.createDirectory("", "a/b") }
            assertThrows(IllegalArgumentException::class.java) { vault.createDirectory("", "..") }
        }
    }

    @Test
    fun `long names are shortened into c9s directories and back`() {
        val longName = "a".repeat(160) + ".jpg" // 164 chars → ciphertext well above 220
        val content = ByteArray(40_000).also { rnd.nextBytes(it) }
        CryptomatorVault.create(storage, password).use { vault ->
            vault.put("", longName, content)
            val e = vault.entry("", longName)
            assertTrue(e.isShortened)
            assertTrue(e.entryPath.endsWith(".c9s"))
            assertTrue(Files.isDirectory(dir.resolve(e.entryPath)))
            assertTrue(Files.isRegularFile(dir.resolve(e.entryPath).resolve("name.c9s")))
            assertTrue(Files.isRegularFile(dir.resolve(e.entryPath).resolve("contents.c9r")))
            val stored = Files.readString(dir.resolve(e.entryPath).resolve("name.c9s"))
            assertTrue(stored.endsWith(".c9r") && stored.length > 220)
            assertEquals(40_000L, e.size)
            assertArrayEquals(content, vault.get("", longName))

            // shortened → plain, plain → shortened, shortened → shortened
            vault.move(e, "", "short.jpg")
            val s = vault.entry("", "short.jpg")
            assertFalse(s.isShortened)
            assertFalse(Files.exists(dir.resolve(e.entryPath)))
            assertArrayEquals(content, vault.get("", "short.jpg"))
            vault.move(s, "", longName)
            assertTrue(vault.entry("", longName).isShortened)
            vault.move(vault.entry("", longName), "", "b".repeat(170))
            val b = vault.entry("", "b".repeat(170))
            assertTrue(b.isShortened)
            assertArrayEquals(content, vault.get("", "b".repeat(170)))

            // a long directory name too
            val longDir = "d".repeat(180)
            val id = vault.createDirectory("", longDir)
            val de = vault.entry("", longDir)
            assertEquals(EntryKind.DIRECTORY, de.kind)
            assertTrue(de.isShortened)
            assertEquals(id, de.dirId)
            vault.move(de, "", "short dir")
            assertFalse(vault.entry("", "short dir").isShortened)
            assertFalse(Files.exists(dir.resolve(vault.entry("", "short dir").entryPath).resolve("name.c9s")))
            vault.delete(vault.entry("", "short dir"))
            vault.delete(b)
            assertTrue(vault.list("").isEmpty())
        }
    }

    @Test
    fun `reopens with the password, refuses a wrong one, and changes it`() {
        CryptomatorVault.create(storage, password).use { it.put("", "x.txt", "x".toByteArray()) }
        assertThrows(WrongPasswordException::class.java) { CryptomatorVault.open(storage, "wrong".toCharArray()) }
        val newPassword = "a different passphrase".toCharArray()
        CryptomatorVault.open(storage, password).use { vault ->
            assertEquals("x", String(vault.get("", "x.txt")))
            assertThrows(WrongPasswordException::class.java) { vault.changePassword("wrong".toCharArray(), newPassword) }
            vault.changePassword(password, newPassword)
            assertFalse(Files.exists(dir.resolve("masterkey.cryptomator.tmp")))
        }
        assertThrows(WrongPasswordException::class.java) { CryptomatorVault.open(storage, password) }
        CryptomatorVault.open(storage, newPassword).use { vault ->
            assertEquals("x", String(vault.get("", "x.txt")))
        }
    }

    @Test
    fun `recovery key resets the password and a foreign key is refused`() {
        val words: String
        CryptomatorVault.create(storage, password).use { vault ->
            vault.put("", "keep.txt", "keep".toByteArray())
            val raw = vault.rawKey()
            assertEquals(64, raw.size)
            words = RecoveryKey.encode(raw)
            raw.fill(0)
        }
        assertEquals(44, words.split(" ").size)

        val foreign = ByteArray(64).also { rnd.nextBytes(it) }
        assertThrows(VaultFormatException::class.java) {
            CryptomatorVault.resetPassword(storage, foreign, "whatever".toCharArray())
        }
        assertNotNull(CryptomatorVault.open(storage, password).also { it.close() })

        val decoded = RecoveryKey.decode(words)!!
        val reset = "reset by recovery key".toCharArray()
        CryptomatorVault.resetPassword(storage, decoded, reset)
        assertThrows(WrongPasswordException::class.java) { CryptomatorVault.open(storage, password) }
        CryptomatorVault.open(storage, reset).use { vault ->
            assertEquals("keep", String(vault.get("", "keep.txt")))
            assertArrayEquals(decoded, vault.rawKey())
        }
    }

    @Test
    fun `refuses storage without a vault, a second create, and a tampered config`() {
        assertThrows(VaultFormatException::class.java) { CryptomatorVault.open(storage, password) }
        assertFalse(CryptomatorVault.isVault(storage))
        CryptomatorVault.create(storage, password).close()
        assertThrows(IOException::class.java) { CryptomatorVault.create(storage, password) }

        val config = dir.resolve("vault.cryptomator")
        val token = Files.readString(config)
        val parts = token.split('.')
        val payload = String(java.util.Base64.getUrlDecoder().decode(parts[1]))
        val downgraded = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(payload.replace("\"format\":8", "\"format\":7").toByteArray())
        Files.writeString(config, parts[0] + "." + downgraded + "." + parts[2])
        val e = assertThrows(VaultFormatException::class.java) { CryptomatorVault.open(storage, password) }
        assertTrue(e.message!!.contains("signature"))
        Files.writeString(config, token)
        CryptomatorVault.open(storage, password).close()
    }

    @Test
    fun `detects a tampered ciphertext chunk`() {
        val content = ByteArray(50_000).also { rnd.nextBytes(it) }
        CryptomatorVault.create(storage, password).use { vault ->
            vault.put("", "t.bin", content)
            val path = dir.resolve(vault.entry("", "t.bin").contentPath!!)
            val bytes = Files.readAllBytes(path)
            bytes[68 + 1000] = (bytes[68 + 1000].toInt() xor 0x01).toByte()
            Files.write(path, bytes)
            // The stream wraps cryptolib's failure in an IOException; either way nothing is returned.
            val e = assertThrows(IOException::class.java) { vault.get("", "t.bin") }
            assertTrue(e.cause is AuthenticationFailedException)
            vault.openRandomAccess(vault.entry("", "t.bin")).use { r ->
                // the second chunk is intact
                val buf = ByteBuffer.allocate(100)
                assertEquals(100, r.read(40_000, buf))
                assertArrayEquals(content.copyOfRange(40_000, 40_100), buf.array())
                assertThrows(AuthenticationFailedException::class.java) { r.read(0, ByteBuffer.allocate(10)) }
            }
        }
    }

    @Test
    fun `unknown ciphertext entries are ignored, not fatal`() {
        CryptomatorVault.create(storage, password).use { vault ->
            val root = dir.resolve(vault.dirPath(""))
            Files.writeString(root.resolve("garbage.c9r"), "not a valid siv name")
            Files.writeString(root.resolve("README.txt"), "someone put this here")
            Files.createDirectory(root.resolve("empty.c9r"))
            vault.put("", "real.txt", "r".toByteArray())
            assertEquals(listOf("real.txt"), vault.list("").map { it.name })
        }
    }

    @Test
    fun `cleartext size of an impossible ciphertext length is -1`() {
        CryptomatorVault.create(storage, password).use { vault ->
            assertEquals(0L, vault.cleartextSize(68))
            assertEquals(-1L, vault.cleartextSize(10))
            assertEquals(-1L, vault.cleartextSize(68 + 5)) // fewer bytes than one chunk's overhead
            assertEquals(0L, vault.cleartextSize(68 + 28)) // a trailing empty chunk, tolerated
            assertEquals(1L, vault.cleartextSize(68 + 28 + 1))
            assertEquals(32768L, vault.cleartextSize(68 + 32796))
            assertEquals(32768L, vault.cleartextSize(68 + 32796 + 28))
            assertNull(vault.list("").firstOrNull())
        }
    }
}
