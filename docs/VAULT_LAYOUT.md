# CryptVault — vault, metadata and backup layout

Status: normative companion to `BUILD_BRIEF.md` (§2, §3, §6). Where this file and the brief
disagree, the brief wins and this file is corrected in the same commit. Items marked
`[ASSUMED]` were decided by the author of the brief, not by the developer; build on them and
record that in `handoff.md`.

Sources: the Cryptomator vault format 8 documentation
(https://docs.cryptomator.org/en/latest/security/vault/ and
https://docs.cryptomator.org/security/architecture/) and the verified library facts in
`docs/research/2026-09-17-cryptolib-verification.md`.

---

## 1. Principles

1. **A CryptVault vault is a Cryptomator format 8 vault, byte for byte.** Nothing CryptVault adds
   changes a single file that Cryptomator would read. CryptVault's own metadata is stored *inside*
   the vault as ordinary encrypted files, so it is protected by the same keys and is visible to a
   desktop Cryptomator user as a harmless `.cryptvault` folder.
2. **The vault tree is authoritative; the manifest is an index.** A file that exists in the tree
   but not in the manifest is an item (it may have been added with desktop Cryptomator). A manifest
   entry whose file is gone is stale and is dropped. Reconciliation runs on every unlock.
3. **The backup is a mirror of the ciphertext plus a sidecar.** The mirror is itself a valid
   Cryptomator vault at every commit point. The sidecar carries snapshot manifests and superseded
   versions and is ignored by Cryptomator.
4. **Nothing is ever decrypted for the sake of backup.** Backup, restore, garbage collection and
   snapshot listing operate on ciphertext files; only the snapshot manifests are decrypted, and
   only with the vault's own masterkey.

## 2. Local vault directory (Cryptomator format 8)

```
<vault>/
  vault.cryptomator          JWT: {"format":8,"cipherCombo":"SIV_GCM","shorteningThreshold":220,"jti":…}
                             header kid=masterkeyfile:masterkey.cryptomator, HS256, signed with enc‖mac key
  masterkey.cryptomator      {"version":999,"scryptSalt","scryptCostParam":32768,"scryptBlockSize":8,
                              "primaryMasterKey","hmacMasterKey","versionMac"}  (AES-KeyWrap under scrypt KEK)
  d/<2>/<30>/                one directory per hashed directory ID (root directory ID is "")
     <base64url>.c9r         a file: 68-byte header (12 nonce, 40 payload, 16 tag) + chunks of
                             32 KiB cleartext → 32 KiB + 28 B ciphertext, AES-256-GCM, chunk number and
                             header nonce as associated data
     <base64url>.c9r/dir.c9r a directory: dir.c9r holds the child directory's ID
     <base64url>.c9r/symlink.c9r  a symlink (never created by CryptVault; tolerated on read)
     <base64url>.c9s/        a name longer than 220 chars: name.c9s + contents.c9r (or dir.c9r)
     dirid.c9r               backup copy of this directory's ID (written by cryptofs; optional)
```

Library mapping (`org.cryptomator:cryptolib`, pin **2.2.2**; never 2.2.1 — the official Android
app marks it broken for file creation):

| Need | cryptolib API |
| --- | --- |
| Create a vault | `Masterkey.generate(SecureRandom)`; `MasterkeyFileAccess(pepper = byte[0], SecureRandom).persist(masterkey, out, passphrase, 999)`; write `vault.cryptomator` as an HS256 JWT signed with `encKey ‖ macKey` (use `com.nimbusds:nimbus-jose-jwt` or the ~40 lines of HMAC-SHA256 + base64url it takes `[ASSUMED: hand-written, no JOSE dependency]`) |
| Open a vault | `MasterkeyFileAccess.load(in, passphrase)` → `CryptorProvider.forScheme(SIV_GCM).provide(masterkey, SecureRandom)`; verify the JWT signature of `vault.cryptomator` with the same key and refuse any `format` other than 8 |
| Change password | `MasterkeyFileAccess.changePassphrase(...)` (re-wraps only; no file re-encryption) |
| Encrypt a file | `EncryptingWritableByteChannel(channel, cryptor)` (streams, constant memory) |
| Read sequentially | `DecryptingReadableByteChannel(src, cryptor, authenticate = true)` |
| Read at an offset | `DecryptingReadableByteChannel(src, cryptor, true, header, firstChunk)` or `fileContentCryptor().decryptChunk(buf, chunkNumber, header, true)`; chunk index = offset / 32768 |
| Names | `fileNameCryptor().encryptFilename(BaseEncoding.base64Url(), name, dirId.bytes)` + `.c9r`; `hashDirectoryId(dirId)` → `d/<2>/<30>` |
| Directory IDs | new directory: random UUID string, written to `dir.c9r`; root: empty string |

The pepper is empty, as in every Cryptomator client, so that any Cryptomator app can open the
vault. `scryptCostParam` stays at the library default 32768 for the same reason `[ASSUMED]`; the
password-strength gate (§4 of the brief) carries the extra safety instead.

## 3. Cleartext tree conventions

What a desktop Cryptomator user sees when they open the vault:

```
/                          the vault root: the user's folders and files ("items")
/.cryptvault/              CryptVault metadata — a normal folder; Cryptomator shows it, nothing breaks
/.cryptvault/manifest.json      item index (schema below), written atomically
/.cryptvault/manifest.json.bak  the previous generation, for recovery
/.cryptvault/thumbs/<itemId>.jpg   thumbnails (≤ 256 px, JPEG q70), generated at import [ASSUMED sizes]
/Documents/passport.pdf    an item of kind "file"
/Notes/Insurance numbers.md   an item of kind "note" — a UTF-8 Markdown file, so notes are readable elsewhere
```

Rules:

- Every item is a real file in the tree. The manifest adds `title`, `tags`, `note`, timestamps,
  a content hash and a thumbnail reference. Nothing that only exists in the manifest is user data.
- Notes are files named `<title>.md` under the folder the user chose (default `Notes/`). The
  item's `note` field is the free-text attached to a *file* item; for a note item the body *is*
  the file.
- Reconciliation on unlock: walk the tree (cheap: it is the ciphertext tree, decrypting names
  only); synthesise entries for unknown files (`title` = file name, `kind` by extension, `tags`
  empty); drop entries with no file; write the manifest if anything changed.
- The manifest is written as `manifest.json.tmp` → fsync → rename over `manifest.json`, after
  moving the previous file to `.bak`. All three are ordinary encrypted vault files.

### 3.1 Manifest schema (`.cryptvault/manifest.json`, schema 1)

```json
{
  "schema": 1,
  "vaultId": "8c5b2f0e-9c4d-4a56-9d1a-2f9a1c2b7e33",
  "generation": 42,
  "updatedAt": "2026-09-17T09:12:44Z",
  "items": [
    {
      "id": "3f9a1c2b-…",
      "path": "Documents/passport.pdf",
      "kind": "file",
      "title": "Passport",
      "tags": ["id", "travel"],
      "note": "Expires 2031-03",
      "mime": "application/pdf",
      "size": 1834211,
      "createdAt": "2026-09-01T10:00:00Z",
      "modifiedAt": "2026-09-01T10:00:00Z",
      "sha256": "…hex… or null",
      "thumb": ".cryptvault/thumbs/3f9a1c2b-….jpg"
    }
  ],
  "folders": [
    { "path": "Documents", "tags": [] }
  ]
}
```

- `generation` increases by one per write; it is what the backup snapshot records.
- `sha256` is the hash of the *cleartext* and is computed at import for files up to 64 MiB
  `[ASSUMED threshold]`; larger files carry `null` and rely on size + `modifiedAt`.
- Serialisation: kotlinx.serialization, unknown keys ignored, so a newer app can add fields
  without breaking an older one. `schema` is bumped only for incompatible changes, and a newer
  schema than the app knows is a read-only warning, never a refusal to unlock.

## 4. App-level state (outside any vault)

Lives in the app's private directory, excluded from Auto Backup and device transfer
(`dataExtractionRules`), and is never uploaded:

| File | Content |
| --- | --- |
| `vaults.json` | registry: `vaultId`, display name, location (`app-private:<dir>` or `saf:<tree URI>`), `createdAt`, `biometric: true/false`, `backup: { target, remoteFolder, retention, constraints }`, last successful snapshot `seq` and time |
| `secrets/<vaultId>.biometric` | the raw 64-byte masterkey wrapped by the Keystore key `cryptvault.biometric.<vaultId>` (AES-256-GCM, IV prepended). Wrapping the masterkey rather than the password means the wrap survives a password change and no password is kept in memory `[ASSUMED]`. Key parameters: `setUserAuthenticationRequired(true)`, `setInvalidatedByBiometricEnrollment(true)`, on API 30+ `setUserAuthenticationParameters(300, AUTH_BIOMETRIC_STRONG or AUTH_DEVICE_CREDENTIAL)`, on API 26–29 `setUserAuthenticationValidityDurationSeconds(300)`; StrongBox if available, TEE otherwise |
| `secrets/providers.enc` | provider credentials (Dropbox `DbxCredential`, WebDAV URL + user + app password, Drive account name) encrypted under the non-auth Keystore key `cryptvault.app` so nothing rests in plaintext. MSAL keeps its own token cache; note in the privacy policy that it may fall back to plaintext on devices without Keystore support |
| `cache/open/<session>/…` | decrypted temp files handed to external apps; deleted on lock, on timeout, on process start |
| `index/<vaultId>.json` | ciphertext hash cache for the backup planner: `path → (size, mtime, ciphertextSha256)`; purely a performance cache, rebuilt when missing |

## 5. Recovery key

Format identical to Cryptomator desktop's, so a vault whose password is lost can be reset either
in CryptVault or on a desktop:

- Payload: the 64 raw masterkey bytes followed by the 16 most significant bits of the CRC-32 of
  those bytes (66 bytes).
- Encoding: 12 bits per word, 3 bytes → 2 words, from Cryptomator's 4096-word English list →
  **44 words**. Port `WordEncoder` and `RecoveryKeyFactory` from cryptomator/cryptomator
  (GPL-3.0) into `:shared` with a file-level attribution comment; ship `4096words_en.txt` under
  `shared/src/main/resources/` with its origin in `NOTICE`.
- Reset flow: the user types the 44 words (with word-by-word validation against the list) → CRC
  check → `Masterkey` from the 64 bytes → `MasterkeyFileAccess.persist(masterkey, out, newPassword,
  999)` writes a new `masterkey.cryptomator`. `vault.cryptomator` needs no change. The biometric
  wrap is regenerated on the next password unlock.
- Shown once at vault creation with copy and share-as-text disabled, "I have written it down"
  confirmation, and re-displayable later only after a fresh password entry `[ASSUMED, as desktop]`.

## 6. Remote layout (per backup target)

Every target gets one folder per vault inside its app-scoped area. The folder name is the vault's
display name slugified plus the first 8 hex digits of `vaultId`, e.g. `personal-8c5b2f0e`
`[ASSUMED]` — readable for a desktop user browsing `Apps/CryptVault`, stable across renames.

| Target | Vault folder |
| --- | --- |
| Dropbox (App folder) | `/Apps/CryptVault/<slug>-<id8>/` (the API sees `/<slug>-<id8>/`) |
| OneDrive personal | `/me/drive/special/approot:/<slug>-<id8>` → shown as `Apps/CryptVault/<slug>-<id8>` |
| Google Drive | `appDataFolder/<slug>-<id8>/` (invisible to the user; Drive interop is restore-only) |
| WebDAV | `<base URL>/CryptVault/<slug>-<id8>/` |
| SAF folder | `<tree>/CryptVault/<slug>-<id8>/` |

Inside the vault folder:

```
vault.cryptomator            mirror of the local file
masterkey.cryptomator        mirror of the local file (re-uploaded after a password change or recovery)
d/…                          mirror of the local ciphertext tree = a valid Cryptomator vault
cryptvault/                  CryptVault sidecar; Cryptomator ignores it (verified in Phase 6, see §9)
  latest                     text: "<seq>\n<sha256 of snapshots/<seq>.json.enc>\n" — the commit point;
                             its rev/ETag is the concurrency token
  snapshots/00000012.json.enc   snapshot manifest, AES-256-GCM under a key derived from the masterkey
                             (HKDF-SHA256, info `cryptvault-snapshot-v1`; blob = `CVS1` ‖ IV ‖ ciphertext),
                             so a restore recovers it from the password alone; the app keeps that
                             derived key Keystore-wrapped so the worker can write snapshots while
                             the vault is locked (built this way in Phase 4 instead of the
                             Cryptomator file format, which needs the cryptor and hence an unlock)
  versions/<sha256>.c9r      superseded ciphertext files still referenced by a retained snapshot,
                             content-addressed by the SHA-256 of the ciphertext
```

### 6.1 Snapshot manifest schema (schema 1)

```json
{
  "schema": 1,
  "seq": 12,
  "vaultId": "8c5b2f0e-…",
  "vaultName": "Personal",
  "createdAt": "2026-09-17T09:15:02Z",
  "installation": "b7e1…",          // random per-install id; identifies the writer, not the person
  "manifestGeneration": 42,          // .cryptvault/manifest.json generation at snapshot time
  "meta": {
    "vault.cryptomator":     { "size": 512,  "sha256": "…" },
    "masterkey.cryptomator": { "size": 462,  "sha256": "…" }
  },
  "files": [
    { "path": "d/AB/CDEF…/xyz.c9r",         "size": 1834307, "sha256": "…", "stored": "mirror" },
    { "path": "d/AB/CDEF…/old.c9r",         "size": 90211,   "sha256": "…", "stored": "versions" }
  ]
}
```

`path` is the ciphertext path relative to the vault folder; `stored` says where the bytes are
*now*: at that path in the mirror, or at `cryptvault/versions/<sha256>.c9r`.

### 6.2 Backup algorithm (planner is pure Kotlin in `:shared`, executor is Android)

Inputs: the local ciphertext file list with sizes and hashes (from the hash cache, re-hashing only
files whose size or mtime changed), the last snapshot manifest this installation wrote (cached
locally), and the remote `latest`.

1. **Check the writer.** Read remote `latest`. If it is missing → first backup. If its `seq` is
   greater than the last `seq` this installation wrote → another installation has written here;
   **stop** and offer the user *Restore from it* or *Take over* (explicit confirmation; taking over
   makes this device the writer and the next snapshot supersedes) `[ASSUMED]`.
2. **Diff.** For each local file: unchanged (same path and hash in the last manifest) → keep its
   entry. Modified (same path, different hash) → server-side MOVE the mirror file to
   `versions/<oldSha>.c9r` (if that exists already, DELETE the mirror file instead), then upload the
   new bytes to the mirror path. New → upload. Missing locally → MOVE to `versions/<sha>.c9r`.
   Renames appear as missing + new; if a new file's hash equals a just-moved one, MOVE it back
   instead of uploading `[ASSUMED optimisation]`.
3. **Upload rules.** Resumable/chunked above the provider's simple-upload limit (Dropbox 150 MiB
   sessions, OneDrive 320 KiB-multiple fragments, Drive 256 KiB-multiple chunks, Nextcloud chunking
   v2 above 5 MB, SAF and plain WebDAV: single stream). Hash the ciphertext while streaming.
   Uploads before manifests, always.
4. **Meta files.** Upload `vault.cryptomator` and `masterkey.cryptomator` when their hash changed.
5. **Snapshot.** Encrypt and upload `snapshots/<seq>.json.enc` with `seq = previous + 1`.
6. **Commit.** Write `latest` with an `If-Match` on the rev/ETag read in step 1 (Dropbox `update`
   mode with `rev`; Graph `If-Match: <eTag>`; WebDAV `If-Match`; Drive has no conditional update —
   re-read `headRevisionId` immediately before writing and accept the small race `[ASSUMED]`; SAF:
   none). A precondition failure means a concurrent writer: stop, report, do not GC.
7. **Garbage collection.** Retained = the newest **5** snapshots (setting, 1–20 `[ASSUMED range]`).
   Delete `snapshots/*` older than those; delete every `versions/*` whose hash no retained snapshot
   references. GC runs only after step 6 succeeded.

Crash safety: any interrupted run leaves extra bytes, never a broken commit point. The next run
lists `versions/` and the mirror, reuses what is already there, and finishes. A half-uploaded
mirror file is unreadable by desktop Cryptomator for exactly that one file until the run completes.

### 6.3 Restore algorithm

1. Choose a target and a vault folder (list `Apps/CryptVault`-equivalent).
2. Download `masterkey.cryptomator`, ask for the password, validate locally (cryptolib). Nothing
   else is fetched before the password is right.
3. Download `latest` and the retained snapshot manifests; decrypt; show them with date, file count
   and total size; default to the newest.
4. Create a **new local vault** (`<name> (restored <date>)`) — never overwrite an existing one
   `[ASSUMED]` — and download every `files[]` entry from its `stored` location, verifying the SHA-256
   on arrival; then the two meta files. Resume on interruption by hash comparison.
5. Open it. Offer to make this device the writer for that remote folder (§6.2 step 1).

### 6.4 What the provider learns

Vault display-name slug, number of files, ciphertext sizes (cleartext size + 68 + 28 per 32 KiB
chunk), upload times and cadence, the CryptVault app name. Never: file names, content, tags,
notes, thumbnails, the password. Stated verbatim in the privacy policy.

## 7. Vaults in SAF folders

A vault opened from or created in a user-chosen tree URI uses the same layout through
`DocumentsContract`/`DocumentFile`. Additional rules:

- On selection, self-test: create a file, write 1 KiB, rename it, delete it. Any failure refuses
  the location with the provider's name in the message.
- Persist the URI permission; if it is gone at unlock time, ask the user to pick the folder again
  and verify `vault.cryptomator` is at the root before accepting.
- Warn once when the tree belongs to a cloud app's DocumentsProvider (Drive, Nextcloud, OneDrive):
  writes are slower and the provider may produce conflicted copies.
- Backup of a SAF-located vault works the same (the planner reads ciphertext via the tree URI).

## 8. Mail container detection (receiving)

CryptVault accepts `application/octet-stream` and the specific types and decides by content:

| Container | Magic |
| --- | --- |
| ZIP | `50 4B 03 04` |
| age | ASCII `age-encryption.org/v1` followed by LF |
| OpenPGP binary | first byte `0xC3` (new-format SKESK, tag 3) or `0x8C` (old format) |
| OpenPGP armored | `-----BEGIN PGP MESSAGE-----` |

Anything else is rejected with a plain message. Filenames are not trusted for detection.

## 9. Interop invariants (tested in Phase 6 against Cryptomator desktop)

1. Every file under `d/` is written exactly as cryptolib produces it; CryptVault never appends,
   patches or re-chunks in place.
2. The `cryptvault/` sidecar sits next to `d/`. If desktop Cryptomator's health check or vault
   listing objects to unknown root entries, the fallback is a sibling folder
   `<slug>-<id8>.cryptvault-meta/` outside the vault, and this file changes `[ASSUMED fallback]`.
3. `.cryptvault/` inside the cleartext tree is visible on the desktop as a folder with JSON and
   JPEG files. Deleting it on the desktop loses titles, tags and notes, not files; CryptVault
   rebuilds a bare manifest on the next unlock.
4. A vault edited on the desktop (files added, renamed, deleted) is picked up by reconciliation on
   the next CryptVault unlock, and the next backup snapshot reflects it.
5. A recovery key produced by CryptVault resets the password in Cryptomator desktop and vice versa.
