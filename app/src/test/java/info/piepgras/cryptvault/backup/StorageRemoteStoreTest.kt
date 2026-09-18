package info.piepgras.cryptvault.backup

import info.piepgras.cryptvault.vault.CryptomatorVault
import info.piepgras.cryptvault.vault.PathVaultStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.ByteBuffer

class StorageRemoteStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `a folder target round-trips a backup and a restore`() {
        val password = "correct horse battery staple".toCharArray()
        val storage = PathVaultStorage(tmp.newFolder("vault").toPath())
        val vault = CryptomatorVault.create(storage, password.clone())
        vault.writeFile("", "a.txt").use { it.write(ByteBuffer.wrap("alpha".toByteArray())) }
        val key = SnapshotCrypto.deriveKey(vault.rawKey())
        val remote = StorageRemoteStore(PathVaultStorage(tmp.newFolder("folder").toPath()))
        val runner = BackupRunner(storage, remote, BackupIndex(tmp.newFolder("index")), key, "v", "P", "i")
        assertEquals(1L, (runner.run() as BackupOutcome.Done).seq)
        vault.writeFile("", "a.txt").use { it.write(ByteBuffer.wrap("beta".toByteArray())) }
        assertEquals(2L, (runner.run() as BackupOutcome.Done).seq)
        assertEquals(1, remote.list(RemoteLayout.VERSIONS).size)
        assertEquals(2, remote.list(RemoteLayout.SNAPSHOTS).size)
        assertNull(remote.stat("nothing/here"))
        assertEquals(emptyList<RemoteEntry>(), remote.list("nothing"))

        val restored = PathVaultStorage(tmp.newFolder("restored").toPath())
        val r = RestoreRunner(remote, restored)
        val raw = r.fetchMetaAndUnlock(password.clone())
        val snaps = r.listSnapshots(SnapshotCrypto.deriveKey(raw))
        r.restore(snaps[0].snapshot, snaps[0].snapshot)
        CryptomatorVault.open(restored, password.clone()).use { v ->
            val e = v.list("").first { it.name == "a.txt" }
            assertEquals("beta", java.nio.channels.Channels.newInputStream(v.readFile(e)).use { it.readBytes().toString(Charsets.UTF_8) })
        }
    }
}
