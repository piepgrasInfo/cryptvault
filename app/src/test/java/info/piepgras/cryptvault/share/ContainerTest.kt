package info.piepgras.cryptvault.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File

/**
 * Round trips through the three writers, wrong-passphrase handling and sniffing. Every test
 * also leaves its container under `build/containers/` so the host can check it with the
 * real tools (`gpg --list-packets`, `bsdtar --passphrase`), which is the Phase 5 acceptance.
 */
class ContainerTest {
    @get:Rule val tmp = TemporaryFolder()
    private val samples = File("build/containers").apply { mkdirs() }
    private val pw = "abacus abdomen abdominal abide abiding ability".toCharArray()

    private fun entry(name: String, content: ByteArray) = ShareEntry(name, content.size.toLong(), { ByteArrayInputStream(content) })
    private val pdf = "%PDF-1.7 not really a pdf but ".toByteArray() + ByteArray(50_000) { (it % 7).toByte() }
    private val note = "a note that travels beside the file".toByteArray()

    private fun roundTrip(kind: ContainerKind, entries: List<ShareEntry>, title: String): Pair<File, List<File>> {
        val name = SharePolicy.containerName(kind, entries.map { it.name }, title)
        val out = File(tmp.newFolder(), name)
        ContainerWriter.write(kind, entries, pw.clone(), out, title)
        File(samples, name).let { out.copyTo(it, overwrite = true) }
        assertEquals(kind, ContainerReader.detect(out))
        val files = ContainerReader.open(kind, out, pw.clone(), tmp.newFolder("out-$kind"))
        return out to files
    }

    @Test
    fun `zip with two entries is aes-256 under one folder and opens with the passphrase only`() {
        val (out, files) = roundTrip(ContainerKind.ZIP, listOf(entry("report.pdf", pdf), entry(SharePolicy.noteFileName("report.pdf"), note)), "Taxes 2026")
        assertEquals(listOf("report.pdf", "report - note.txt"), files.map { it.name })
        assertTrue(files[0].readBytes().contentEquals(pdf)); assertTrue(files[1].readBytes().contentEquals(note))
        net.lingala.zip4j.ZipFile(out, pw).use { z ->
            assertTrue(z.isEncrypted)
            assertEquals(listOf("Taxes 2026/report.pdf", "Taxes 2026/report - note.txt"), z.fileHeaders.map { it.fileName })
            assertEquals(net.lingala.zip4j.model.enums.EncryptionMethod.AES, z.fileHeaders[0].encryptionMethod)
            assertEquals(net.lingala.zip4j.model.enums.AesKeyStrength.KEY_STRENGTH_256, z.fileHeaders[0].aesExtraDataRecord.aesKeyStrength)
            assertEquals(net.lingala.zip4j.model.enums.AesVersion.TWO, z.fileHeaders[0].aesExtraDataRecord.aesVersion)
        }
        assertThrows(WrongPassphraseException::class.java) { ContainerReader.open(ContainerKind.ZIP, out, "wrong".toCharArray(), tmp.newFolder()) }
    }

    @Test
    fun `single file as age and as pgp with a wrong passphrase refused`() {
        for (kind in listOf(ContainerKind.AGE, ContainerKind.PGP)) {
            val (out, files) = roundTrip(kind, listOf(entry("report.pdf", pdf)), "x")
            assertEquals("report.pdf", files.single().name)
            assertTrue(files.single().readBytes().contentEquals(pdf))
            assertThrows("$kind", WrongPassphraseException::class.java) { ContainerReader.open(kind, out, "wrong".toCharArray(), tmp.newFolder()) }
        }
    }

    @Test
    fun `several files as age and as pgp travel in an inner zip and come back flat`() {
        for (kind in listOf(ContainerKind.AGE, ContainerKind.PGP)) {
            val (out, files) = roundTrip(kind, listOf(entry("clip.mp4", pdf), entry("clip - note.txt", note)), "Holiday")
            assertEquals(if (kind == ContainerKind.AGE) "Holiday.zip.age" else "Holiday.zip.gpg", out.name)
            assertEquals(listOf("clip.mp4", "clip - note.txt"), files.map { it.name })
            assertTrue(files[0].readBytes().contentEquals(pdf))
        }
    }

    @Test
    fun `age output starts with the age header and pgp output with a v4 skesk`() {
        val (age, _) = roundTrip(ContainerKind.AGE, listOf(entry("report.pdf", pdf)), "x")
        val (gpg, _) = roundTrip(ContainerKind.PGP, listOf(entry("report.pdf", pdf)), "x")
        assertTrue(age.readBytes().copyOf(21).toString(Charsets.US_ASCII) == "age-encryption.org/v1")
        val head = gpg.readBytes()
        assertEquals(0xC3, head[0].toInt() and 0xFF) // new-format SKESK, tag 3
        assertEquals(4, head[2].toInt())              // SKESK version 4
        assertEquals(9, head[3].toInt())              // AES-256
        assertEquals(3, head[4].toInt())              // S2K iterated and salted
        assertEquals(8, head[5].toInt())              // SHA-256
    }
}
