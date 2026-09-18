package info.piepgras.cryptvault.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentIdTest {
    private val v = "8c5b2f0e-9c4d-4a56-9d1a-2f9a1c2b7e33"

    @Test
    fun `round trips ids with colons in paths`() {
        val id = DocumentId(v, "Docs/12:30 notes.md")
        assertEquals("$v:Docs/12:30 notes.md", id.toString())
        assertEquals(id, DocumentId.parse(id.toString()))
        assertEquals("12:30 notes.md", id.name)
        assertEquals("Docs", id.parentPath)
        assertFalse(id.isRoot)
    }

    @Test
    fun `root, children and containment`() {
        val root = DocumentId.root(v)
        assertTrue(root.isRoot)
        assertEquals("$v:", root.toString())
        assertEquals(root, DocumentId.parse("$v:"))
        val docs = root.child("Docs")
        assertEquals("Docs", docs.path)
        assertEquals("Docs/a.pdf", docs.child("a.pdf").path)
        assertTrue(root.contains(docs.child("a.pdf")))
        assertTrue(docs.contains(docs))
        assertTrue(docs.contains(docs.child("a.pdf")))
        assertFalse(docs.contains(DocumentId(v, "Docsx")))
        assertFalse(docs.contains(DocumentId("other", "Docs/a.pdf")))
        assertThrows(IllegalArgumentException::class.java) { DocumentId.parse("no-colon") }
    }
}
