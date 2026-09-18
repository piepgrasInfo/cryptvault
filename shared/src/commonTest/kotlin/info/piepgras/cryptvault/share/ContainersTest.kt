package info.piepgras.cryptvault.share

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContainersTest {
    @Test
    fun `sniffs the three containers by magic and nothing else`() {
        assertEquals(ContainerKind.ZIP, ContainerSniff.sniff(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x14, 0x00)))
        assertEquals(ContainerKind.AGE, ContainerSniff.sniff("age-encryption.org/v1\n-> scrypt".encodeToByteArray()))
        assertEquals(ContainerKind.PGP, ContainerSniff.sniff(byteArrayOf(0xC3.toByte(), 0x04, 0x04, 0x09)))
        assertEquals(ContainerKind.PGP, ContainerSniff.sniff(byteArrayOf(0x8C.toByte(), 0x0D)))
        assertNull(ContainerSniff.sniff("age-encryption.org/v2\n".encodeToByteArray()))
        assertNull(ContainerSniff.sniff(byteArrayOf(0x50, 0x4B, 0x05, 0x06))) // an empty zip's EOCD: not an archive we can open
        assertNull(ContainerSniff.sniff("%PDF-1.7".encodeToByteArray()))
        assertNull(ContainerSniff.sniff(ByteArray(0)))
    }

    @Test
    fun `passphrase policy is stricter for zip`() {
        val six = "abacus abdomen abdominal abide abiding ability"
        assertEquals(SharePolicy.Verdict.Ok, SharePolicy.check(ContainerKind.ZIP, six, 0))
        assertEquals(SharePolicy.Verdict.Ok, SharePolicy.check(ContainerKind.AGE, "typed phrase", 3))
        assertEquals(SharePolicy.Verdict.TooWeak(4), SharePolicy.check(ContainerKind.ZIP, "typed phrase", 3))
        assertEquals(SharePolicy.Verdict.Ok, SharePolicy.check(ContainerKind.ZIP, "typed phrase", 4))
        assertEquals(SharePolicy.Verdict.TooWeak(3), SharePolicy.check(ContainerKind.PGP, "weak", 2))
        assertEquals(SharePolicy.Verdict.Empty, SharePolicy.check(ContainerKind.PGP, "  ", 4))
    }

    @Test
    fun `container names`() {
        assertEquals("report.zip", SharePolicy.containerName(ContainerKind.ZIP, listOf("report.pdf"), "x"))
        assertEquals("report.pdf.age", SharePolicy.containerName(ContainerKind.AGE, listOf("report.pdf"), "x"))
        assertEquals("report.pdf.gpg", SharePolicy.containerName(ContainerKind.PGP, listOf("report.pdf"), "x"))
        assertEquals("Taxes 2026.zip", SharePolicy.containerName(ContainerKind.ZIP, listOf("a.pdf", "b.pdf"), "Taxes 2026"))
        assertEquals("Taxes 2026.zip.age", SharePolicy.containerName(ContainerKind.AGE, listOf("a.pdf", "b.pdf"), "Taxes 2026"))
        assertEquals("cryptvault.zip.gpg", SharePolicy.containerName(ContainerKind.PGP, listOf("a", "b"), ""))
        assertEquals("report - note.txt", SharePolicy.noteFileName("report.pdf"))
        assertTrue(SharePolicy.isAlreadyCompressed("clip.MP4")); assertFalse(SharePolicy.isAlreadyCompressed("notes.txt"))
    }
}
