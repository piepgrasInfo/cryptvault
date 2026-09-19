# CryptVault — Build Brief

Status: **design target**, 2026-09-17.
Refined from the developer's stub (Appendix C) through a 28-question interview on 2026-09-16 and
2026-09-17 (Appendix B) and four research reports (`docs/research/`). The plan that produced this
document was approved by the developer on 2026-09-17. This is the document a building agent
starts from.

---

## 0. How to use this brief

- **Read order.** This brief; then `docs/VAULT_LAYOUT.md` (the on-disk and remote formats,
  normative), `docs/THREAT_MODEL.md` (what must hold at each phase gate) and
  `docs/PROVIDER_SETUP.md` (what the developer has to register, with lead times). After the
  scaffold exists, `CLAUDE.md` comes first in every session: it carries the build commands and the
  house conventions (three-language strings, plain-file logic with unit tests, `Fake*` +
  `MainDispatcherRule`, lint baseline, the four-places permission rule, the `handoff.md` log).
  `CLAUDE.md` describes the repository as it *is*; this brief describes what it *becomes*. Where
  they disagree about the target, this brief wins, and `CLAUDE.md` is updated in the commit that
  moves reality.
- **Settled versus open.** Everything here that is not marked `[OPEN: …]` was decided by the
  developer in the interview and is not to be re-litigated. Items marked `[ASSUMED]` were decided
  by the author of this brief because the interview did not cover them; build on them and record
  in `handoff.md` that you did. `[OPEN: …]` items need the developer's answer; where a phase can
  proceed under a stated placeholder, proceed and say so. Never ship a placeholder.
- **Phases (§12) are the build order.** Each phase ends with the `CLAUDE.md` verification commands
  green, `:shared` tests green, a dated `handoff.md` entry with the real output, a commit, and a
  push to `origin/master`. Phase 0 starts by repointing `origin` (§12, Phase 0): today it points at
  the `skills` repository by mistake.
- **Rules live in plain Kotlin with tests.** Anything with a decision in it — the backup planner,
  garbage collection, retention, the recovery-key encoding, the passphrase policy, container
  detection, path and slug rules — is a pure function in `:shared`, unit-tested on the JVM, never
  inside a Composable and never dependent on an Android class.
- **Permissions, legal documents, Permissions screen, Play declarations agree at all times.**
  Every permission this brief adds goes into all four in the commit that adds it.
- **Licence rule.** The app is **GPL-3.0-or-later**. Dependencies must be GPL-compatible
  (Apache-2.0, MIT, BSD, MPL-2.0, LGPL, GPL, AGPL). AGPL components (cryptolib) are named in
  `NOTICE` and in the About screen. Code ported from cryptomator/cryptomator or
  cryptomator/android (both GPL-3.0) carries a file-level attribution comment naming the origin
  file and commit. Nothing may be copied from a non-GPL-compatible source.
- **Scaffold with the house skill.** `spec.json` at the repository root is the scaffolder input,
  filled from the interview. Run `app-generate-android-project` with it (Phase 0); do not hand-roll
  the Gradle setup.

---

## 1. Product summary

| | |
| --- | --- |
| Name | CryptVault |
| Package | `info.piepgras.cryptvault` (permanent after the first Play upload) |
| One-liner | An encrypted vault for the files and notes that matter, in the open Cryptomator format: open them in any app, back them up to your own cloud, mail them in a container only the recipient can open. |
| Category | Productivity / tools (security) |
| Platform | Android 8.0+ (minSdk 26), Kotlin, Jetpack Compose, Material 3 |
| UI languages | English, German, Russian — every string in all three (`CLAUDE.md`) |
| Audience | Adults and general audience; not directed at children |
| Price | v1 is free: no ads, no purchases. A one-time purchase comes later (§10) |
| Licence | GPL-3.0-or-later, source published (§13 for where) |
| Brand colour | Navy `#1E3A8A` |
| Icon | A cog as a heavy vault door, slightly ajar, a keyhole in the centre of the cog |
| Website entry | `website/content/apps/crypt-vault.toml`, slug `crypt-vault` (the developer already renamed the "Data Vault" placeholder; it currently says "Crypt Vault" — see §13) |

**What it is.** A local vault app. A vault is a Cryptomator format 8 directory — a published,
audited, cross-platform format — holding the user's files of any type and their notes, each with a
title, tags and a free-text note. Vaults unlock with a master password, optionally with biometrics,
and can be reset with a 44-word recovery key. Decrypted items open in any external app, either
through a temporary hand-off or through a system storage root the unlocked vault exposes. Each
vault can be backed up, encrypted, to Dropbox, OneDrive, Google Drive, a WebDAV server or any
folder, with five snapshots kept, and restored on a new phone with the password. Small items can
be mailed in an encrypted container (ZIP AES-256, age, or OpenPGP) that the recipient opens with
free tools, the passphrase travelling separately. A vault folder, whether on the phone or in a
Dropbox/OneDrive app folder, opens in Cryptomator's desktop app — this is a promised feature.

**What it is not.** Not a password manager: no autofill, no typed login records. Not a sync
service: one device writes each backup; two phones editing one vault is refused, not merged. Not a
cloud: CryptVault operates no server, has no accounts and never sees a user's data. Not a mail
client: mail is composed and sent by the user's own mail app.

**Long term.** Multi-device sync with conflict copies (§6.8), in-app sending via Graph and Gmail
API (§7.6), S3 targets, and a purchase that funds it (§10). The vault format is Cryptomator's,
so nothing stored today needs conversion for any of that. When the cross-vendor Unified Vault
Format ships in cryptolib, CryptVault follows it in a later phase; format 8 stays readable.

---

## 2. Vaults and items

### 2.1 Vaults

- Several named vaults per install (e.g. *Personal*, *Work*), each with its own password, its own
  biometric setting and its own backup target. The vault list is the app's home screen.
- **Location.** Default: the app's private directory (`filesDir/vaults/<vaultId>/`), invisible to
  other apps and file managers. Alternative, chosen at creation or by "Open existing vault": a
  user-picked folder through the Storage Access Framework — an SD card, a local folder, or a cloud
  app's folder. A picked folder is self-tested (create, write, rename, delete) before it is
  accepted, and a cloud-app folder gets a one-time warning that writes are slower and the provider
  may make conflicted copies (`docs/VAULT_LAYOUT.md` §7).
- **Open existing.** Any Cryptomator format 8 vault can be opened by picking its folder. Formats
  other than 8 are refused with a message naming the version. CryptVault never migrates a vault to
  another format.
- **Registry.** `vaults.json` (app-private, excluded from Auto Backup) records id, name, location,
  biometric flag, backup configuration and the last snapshot. Renaming a vault changes the display
  name only; the remote folder slug keeps the id suffix (`docs/VAULT_LAYOUT.md` §6).
- **Delete vault.** Asks for the password, then deletes the local directory (or, for a SAF vault,
  offers to delete the folder or just forget it). Remote backups are never deleted by this action;
  a separate "Delete remote backups" exists on the backup screen.
- **Export vault folder.** Copies the ciphertext directory to a user-picked folder as-is — the
  manual way to hand a vault to a desktop Cryptomator or to another phone.

### 2.2 Items

- An item is a file in the vault's cleartext tree, organised in folders the user creates. Each has
  an optional **title** (defaults to the file name), **tags**, and a free-text **note**. A **note
  item** is a Markdown file the user writes in-app (`Notes/<title>.md` by default), so notes are
  plain files on the desktop too.
- Item metadata lives in `.cryptvault/manifest.json` inside the vault (`docs/VAULT_LAYOUT.md`
  §3) and is rebuilt from the tree if it is missing. Files added, renamed or deleted on the desktop
  appear after the next unlock.
- **Thumbnails** for images, videos (first frame) and PDFs (first page) are generated at import,
  stored encrypted under `.cryptvault/thumbs/`, and shown in the browser; nothing is cached in
  plaintext.
- **Search** over title, file name, tags and note, in memory; **sort** by name, date, size, kind.
  Multi-select for move, tag, delete, share, mail.
- **Delete** is immediate (no trash `[ASSUMED]`); the five backup snapshots are the undo.
- **Sizes.** Anything: multi-GB videos import and open through streaming (the format's 32 KiB
  chunks); nothing is ever fully held in memory. The only limit is free space, checked before an
  import or a temp-file hand-off with a clear message.

### 2.3 Ways into a vault

| Path | Mechanics | Permission |
| --- | --- | --- |
| File picker | `ACTION_OPEN_DOCUMENT` (multiple), any type | none |
| Photo picker | Android Photo Picker, images and video, multiple | none |
| Share to CryptVault | `ACTION_SEND` / `ACTION_SEND_MULTIPLE` receiver, `*/*`; the user picks the vault (and unlocks it) and the folder | none |
| Capture | `ACTION_IMAGE_CAPTURE` into a FileProvider URI under `cache/import/`, encrypted and wiped immediately; a "Scan" entry uses the same intent and offers a document crop `[ASSUMED: crop only, no OCR]` | none (the camera app has the permission) |
| Move into vault | after any import: "Delete the original?" — SAF `deleteDocument` where `FLAG_SUPPORTS_DELETE` is set; `MediaStore.createDeleteRequest` (system dialog) for picked photos and videos; for shared content, only if the source URI is deletable | none |

Import runs as a foreground operation with progress; large imports continue as a WorkManager job
if the app is backgrounded `[ASSUMED]`.

---

## 3. Cryptography and vault format

The format is **Cryptomator vault format 8**, produced and consumed by
`org.cryptomator:cryptolib` **2.2.2** (AGPL-3.0; pure Java 8; no native code; pin 2.2.2, never
2.2.1). `docs/VAULT_LAYOUT.md` §2 maps every operation to its API. In short:

- **Key hierarchy.** Master password → scrypt (N = 32768, r = 8, p = 1, 8-byte salt, empty pepper —
  the Cryptomator defaults, kept for interoperability `[ASSUMED]`) → KEK → AES-KeyWrap of the two
  256-bit masterkey halves (`primaryMasterKey`, `hmacMasterKey`) in `masterkey.cryptomator`.
  `vault.cryptomator` is a JWT signed with the masterkey and pins `format: 8`, `cipherCombo:
  SIV_GCM`, `shorteningThreshold: 220`.
- **Files.** 68-byte header with a random per-file content key; content in 32 KiB chunks of
  AES-256-GCM with the chunk number and header nonce as associated data — seekable, tamper-evident,
  streamable. **Names** AES-SIV with the parent directory ID as associated data. Directories are
  flattened under `d/<2>/<30>` by hashed directory ID.
- **Biometric unlock** (§4.3) stores a copy of the 64-byte masterkey wrapped by a hardware-backed
  Android Keystore AES-GCM key that requires user authentication. The password path never depends
  on the Keystore, so a vault survives reinstalls, device changes and biometric re-enrolment; only
  the biometric shortcut is lost and re-created after the next password unlock.
- **Recovery key.** The Cryptomator scheme: 64 masterkey bytes + 16 CRC-32 bits, encoded as 44
  words from Cryptomator's 4096-word English list (`docs/VAULT_LAYOUT.md` §5). Ported from
  cryptomator/cryptomator (GPL-3.0) into `:shared` with attribution. Interoperable both ways with
  the desktop app; the official Android app does not have it yet.
- **Password change** re-wraps the masterkey (`MasterkeyFileAccess.changePassphrase`); no file is
  re-encrypted. A password change or a recovery-key reset re-uploads `masterkey.cryptomator` on the
  next backup.
- **Randomness** from `SecureRandom` only. **Memory**: passwords are `CharArray`s zeroed after
  use; the masterkey is `destroy()`ed on lock; no secret is ever a `String` or a log line.
- **What the Keystore may hold**: the biometric wrap of the masterkey (per vault) and the wrapping
  key for provider credentials. **What it may never be**: the only copy of anything.
- **Not used, and why**: Jetpack Security (deprecated), Tink and argon2kt (superseded by the
  format decision), SQLCipher (no database), Bouncy Castle as a JCA provider for the vault
  (cryptolib shades what it needs; PGPainless and kage bring `bcprov` for the mail containers and
  register it once at startup with `removeProvider("BC")` + `insertProviderAt(…, 1)`).

---

## 4. Unlock, locking and hardening

### 4.1 Passwords

- Minimum: zxcvbn4j score **3** and at least 10 characters `[ASSUMED length]`; the meter shows the
  score and one concrete suggestion; a "Suggest a passphrase" button offers six words from the EFF
  long list. The rule is a pure function in `:shared` with tests.
- The unlock field is `importantForAutofill = no` and `sensitiveData` for accessibility; no
  password manager fills it, no accessibility service other than a real one reads it.
- Wrong password: exponential backoff starting at 2 s, doubling, capped at 5 minutes, per vault,
  surviving process restarts (stored timestamp); the screen shows the wait. No wipe, ever.

### 4.2 Locking

- A vault locks when the app has been in the background for the vault's timeout (options 30 s,
  **1 min default**, 5 min, 15 min, 1 h `[ASSUMED options]`), on screen-off, on "Lock" in the UI,
  and when the process dies. Locking destroys the masterkey in memory, closes every open
  DocumentsProvider descriptor, and wipes `cache/open/`.
- A backup or restore in progress holds its own reference to the masterkey until the job ends
  `[ASSUMED]`; the UI shows the vault as locked meanwhile.

### 4.3 Biometrics

- Per-vault opt-in, offered after a successful password unlock. `BiometricPrompt` with
  `BIOMETRIC_STRONG`; on API 30+ the key allows `DEVICE_CREDENTIAL` as well and the prompt offers
  it; on API 26–29 the prompt is biometric-only with a "Use password" button. Auth window 300 s
  (`setUserAuthenticationParameters` / `…ValidityDurationSeconds`), so a second unlock within five
  minutes does not prompt again `[ASSUMED window]`.
- New enrolment or key invalidation: the app says so in one line and asks for the password; the
  wrap is silently re-created. Library: `androidx.biometric` 1.1.0 (stable) `[ASSUMED over the 1.4
  alpha]`.

### 4.4 Screen and data hygiene

- `FLAG_SECURE` on the Activity window, `SecureFlagPolicy.SecureOn` on every Compose dialog and
  popup; recents thumbnail blanked; `HIDE_OVERLAY_WINDOWS` and `filterTouchesWhenObscured` on the
  unlock screen.
- Clipboard: copies made by the app (a note, a passphrase) carry `EXTRA_IS_SENSITIVE` and are
  cleared after 60 s by a scheduled job if the clip is still ours.
- No Advanced Protection Mode handling in v1; no root detection; no Play Integrity.
- The threat model's S1–S7 are the acceptance checks for this section (Phase 2).

---

## 5. Getting data out

### 5.1 Open with (temporary hand-off) — Phase 1

1. Decrypt the item to `cache/open/<session>/<random>/<original name>` (the name is kept so the
   receiving app sees the right extension; the random parent prevents collisions and guessing).
   Free-space check first.
2. `ACTION_VIEW` (or `ACTION_EDIT` when the user chooses "Edit") with a FileProvider URI,
   `FLAG_GRANT_READ_URI_PERMISSION` (+ write for edit) set explicitly on the intent **and** in
   `ClipData`; never rely on implicit grants (Android 18 removes them).
3. On return to the foreground: compare size, mtime and hash; if changed, re-encrypt into the
   vault as a new version of the item and bump the manifest. Editors that "save as" elsewhere
   produce nothing to detect; the UI says so on the Edit path.
4. Wipe on lock, timeout, app start, and 10 minutes after the hand-off if the vault is still open
   `[ASSUMED]`. A first-time notice explains that the other app may keep a copy.

### 5.2 DocumentsProvider (vault as a storage root) — Phase 3

- One root per **unlocked** vault; locked vaults contribute no root. The provider is declared with
  `android:permission="android.permission.MANAGE_DOCUMENTS"`, `exported`, `grantUriPermissions`,
  the `DOCUMENTS_PROVIDER` intent filter; `FLAG_SUPPORTS_IS_CHILD` so folders can be picked.
- **Read**: `openDocument` returns `StorageManager.openProxyFileDescriptor` with a
  `ProxyFileDescriptorCallback` on a dedicated `HandlerThread`; `onRead(offset, size)` maps to
  chunk indices and decrypts through cryptolib's chunk API — seeking in a video works, a PDF viewer
  reads pages on demand, nothing touches disk in plaintext.
- **Write**: `createDocument` + `openDocument("w")` through a pipe that encrypts on the fly and
  commits the file on close (whole-file writes only; "rwt"/random-access write modes are refused
  with `UnsupportedOperationException`) `[ASSUMED]`. Rename, delete, move and create-directory map
  to vault tree operations; each bumps the manifest.
- **Thumbnails** via `openDocumentThumbnail` from `.cryptvault/thumbs/`.
- Locking a vault revokes the root; open descriptors fail on the next read (S8). The provider
  shows no UI; every prompt happens in the app.

### 5.3 Share and export

- **Share (plaintext)** to any app via the share sheet, using the same temp-file path as §5.1 with
  the same first-time notice. **Mail (encrypted)** is §7.
- **Export**: `ACTION_CREATE_DOCUMENT` for a single decrypted file to a user-picked location;
  `ACTION_OPEN_DOCUMENT_TREE` for several. Deliberately no storage permission — say so in the
  permissions document.

---

## 6. Backup and restore

### 6.1 Targets (v1)

| Target | Integration | Scope | Registration (`docs/PROVIDER_SETUP.md`) |
| --- | --- | --- | --- |
| Dropbox | `dropbox-android-sdk` 8.0.2 (MIT), PKCE, short-lived tokens + refresh | App folder `Apps/CryptVault` — `files.metadata.*`, `files.content.*`, `sharing.write` | App Console; Production approval when 50 users have linked |
| OneDrive (personal; M365 best-effort) | MSAL 8.4.2 (MIT) for sign-in; Graph **REST** via Ktor for the ~8 calls needed | `Files.ReadWrite.AppFolder` → `special/approot` = `Apps/CryptVault` | Entra app registration with personal accounts; `msauth://` redirect per signing key |
| Google Drive | Credential Manager + `AuthorizationClient` for the token; Drive v3 **REST** via Ktor | `drive.appdata` (non-sensitive) → `appDataFolder`, invisible to the user | GCP project, Android OAuth client per SHA-1, consent screen with the legal URLs |
| WebDAV (Nextcloud, ownCloud, Synology, Hetzner, …) | ~400 lines of Ktor: PROPFIND, PUT (`If-Match`), GET (`Range`), MKCOL, MOVE, DELETE; Nextcloud chunking v2 above 5 MB | user-entered base URL + app password | none |
| Any folder | SAF tree URI through `DocumentFile`; no ETags, no delta, no server-side move → copy+delete | user-chosen | none |

Google Drive requires Google Play services; a Play-services-free build hides the target
(§13). OneDrive business tenants may need `Files.ReadWrite` and admin consent; v1 tries with the
AppFolder scope, shows the tenant's error verbatim, and does not promise M365.

### 6.2 Abstraction

`RemoteStore` (interface in `:app`'s `backup` package; a `FakeRemoteStore` with failure injection
in tests) exposes `caps` (conditional write, server-side move, range get, resumable, max simple
upload), `list`, `stat`, `upload(ifMatch)`, `download(range)`, `delete(ifMatch)`, `move`,
`createLink` (optional). Every provider quirk lives inside its implementation; the planner never
sees a provider.

### 6.3 Semantics

One-way backup from the writing installation, as `docs/VAULT_LAYOUT.md` §6 specifies: the remote
vault folder is a **mirror of the ciphertext** (so it is a valid Cryptomator vault at every commit
point) plus a `cryptvault/` sidecar with encrypted snapshot manifests and superseded file versions.
Modified and deleted files move server-side into `versions/` before being replaced, so the newest
**five** snapshots (setting, 1–20) are fully restorable; garbage collection removes what no
retained snapshot references, only after a successful commit. The commit point is the `latest`
file, written with the provider's conditional-write primitive; a mismatch means another
installation wrote here, and the run stops with *Restore from it* / *Take over*. Nothing is ever
merged.

### 6.4 Triggers and constraints

- **After changes**: a WorkManager job enqueued 3 minutes after the last vault change `[ASSUMED
  debounce]`, unique per vault, replaced on each change; constraints: network **unmetered** (per
  vault setting, default on), battery not low, storage not low. Deferred while the vault is in a
  locked private space (platform behaviour).
- **Back up now** and **Restore**: a user-initiated data-transfer job on API 34+
  (`RUN_USER_INITIATED_JOBS`), a foreground `dataSync` service under WorkManager's
  `setForeground` on API 26–33 — with a progress notification either way (`POST_NOTIFICATIONS`
  requested at that moment, never at startup). Respect the 6 h/24 h dataSync budget on Android
  15+: a run that hits it pauses and resumes on the next trigger.
- Resumable uploads above each provider's simple-upload limit; ciphertext hashed while streaming;
  a hash cache avoids re-reading unchanged files.
- Failure: retry with backoff inside the job; after three failed runs, one notification with the
  provider's message; the vault list shows the last successful snapshot time in amber after 7 days
  and red after 30 `[ASSUMED]`.

### 6.5 Restore

The wizard in `docs/VAULT_LAYOUT.md` §6.3: pick target and folder → download `masterkey.cryptomator`
→ password (validated locally, nothing else downloaded before) → choose a snapshot → a **new**
local vault is created and filled, hashes verified → offer to make this device the writer.

### 6.6 What the provider learns, and the user is told

Vault slug, file count, ciphertext sizes, cadence, the app name. Never names, content, tags,
notes, thumbnails or the password. The privacy policy says exactly this, per provider, with the
provider's own policy linked.

### 6.7 Credentials

Provider tokens and WebDAV app passwords are stored encrypted under a Keystore key
(`docs/VAULT_LAYOUT.md` §4); MSAL keeps its own cache and the policy says so. "Disconnect" deletes
the local credential and, for Dropbox (`auth/token/revoke`), revokes it server-side.

### 6.8 Later, not v1

Multi-device sync (per-item version vectors, conflict copies), S3-compatible targets (SigV4
client), pCloud, a second target per vault ("also copy to").

---

## 7. Mail and sharing of items

### 7.1 Transport

The user's own mail app, through the share sheet: `ACTION_SEND` (one container) or
`ACTION_SEND_MULTIPLE`, FileProvider URI(s) from `cache/share/`, `FLAG_GRANT_READ_URI_PERMISSION`
in the intent and in `ClipData`, `EXTRA_SUBJECT` and `EXTRA_TEXT` prefilled. The chooser is the
system one (not `ACTION_SENDTO`, whose attachment handling is unreliable), so Signal, Quick Share
or a file manager work as well. CryptVault cannot know whether the mail was sent; the UI says
"handed to <app>", not "sent". No SMTP, no Gmail API, no Graph `sendMail` in v1 (§7.6).

### 7.2 Containers

The sender picks one per share; the default is remembered. All three are pure Java/Kotlin, no
native code.

| Container | Library | Parameters | Who can open it |
| --- | --- | --- | --- |
| **ZIP AES-256** (default) | zip4j 2.11.6 (Apache-2.0) | `EncryptionMethod.AES`, `KEY_STRENGTH_256`, AE-2; DEFLATE, or STORE for already-compressed media `[ASSUMED]`; entries under one top-level folder; ZipCrypto impossible to produce | 7-Zip, WinRAR, PeaZip, Keka, The Unarchiver, iZip; **not** Windows Explorer, macOS Archive Utility or iOS Files — the mail body says which tools |
| **age** | kage 0.7.0 (Apache-2.0; minSdk 26) | passphrase (`scrypt`) recipient at age's default work factor; a single file becomes `<name>.age`; several files or a file plus its note are first stored in an uncompressed ZIP → `<title>.zip.age` `[ASSUMED]` | `age`/`rage` CLI, AgePony (iOS/Android), Mage (Android) |
| **OpenPGP** | PGPainless 2.0.4 (Apache-2.0) | symmetric only: v4 SKESK, S2K iterated-and-salted SHA-256 at the maximum count, AES-256, SEIPDv1 with MDC, binary output `<name>.gpg`, literal packet carrying the file name; **never** v6/SEIPDv2/Argon2-S2K (GnuPG rejects them); verified with `gpg --list-packets` in Phase 5 | GnuPG, Kleopatra/Gpg4win, GPG Suite, Thunderbird, Proton Mail |

- **Passphrase**: generated by default — six words from the EFF long list (~77 bits) with a
  "regenerate" button — or typed by the user; typed passphrases must pass zxcvbn score 3, and for
  ZIP (PBKDF2-SHA1 × 1000) at least six words or score 4 `[ASSUMED policy]`. Shown once with a copy
  button (sensitive clip, 60 s), never placed in the mail body, never stored after the share.
- **Contents**: the selected item(s); a file item's note, if any, as `<title> - note.txt` alongside
  `[ASSUMED]`; a note item as its Markdown file.
- **Body template** (en/de/ru): what is attached, which format, a one-line list of free openers
  for that format, and "the passphrase comes separately". Nothing else.
- **Size**: warn above **15 MB** of container size (mail providers cap at 20–25 MB after base64
  overhead) and offer the link fallback (§7.3) or a smaller selection.

### 7.3 Link fallback (large items)

Upload the container to the vault's connected provider under a `cryptvault-share/<random>/`
folder in the app-scoped area and mail only the link; the passphrase still travels separately.

| Provider | Link | Expiry / password |
| --- | --- | --- |
| Dropbox | `sharing/create_shared_link_with_settings` (`sharing.write`) | expiry and link password need a paid plan; on Basic the app creates a permanent link and lists it under "Active links" with **Revoke** (`revoke_shared_link`) |
| OneDrive personal | `driveItem/createLink` (`type: view`, `scope: anonymous`) | expiry only on Microsoft 365 plans; same "Active links / Revoke" UI |
| Nextcloud | OCS share API, `shareType=3`, `expireDate` = +7 days, share password = a second generated secret `[ASSUMED]` | free, reliable |
| WebDAV (non-Nextcloud), SAF, Google Drive (`appdata` cannot share) | not available; the UI says so | — |

### 7.4 Receiving

- Intent filters: `ACTION_VIEW` for `application/zip`, `application/pgp-encrypted`,
  `application/octet-stream`, and a custom `application/vnd.age`; content is identified by magic
  bytes (`docs/VAULT_LAYOUT.md` §8), never by file name; anything unrecognised is declined in one
  line. Mail apps hand over `content://` URIs with generic types, which is why `octet-stream` is
  accepted.
- Flow: copy the attachment to `cache/import/` → detect → ask for the passphrase → decrypt (an
  inner ZIP is unpacked) → pick vault and folder → import as items (a `- note.txt` becomes the
  item's note) → wipe `cache/import/`.
- "Share to CryptVault" (§2.3) is the general path for any file, encrypted or not.

### 7.5 Deliverability, said once

A first-time notice: Gmail warns recipients about encrypted archives; strict Exchange tenants may
quarantine them; the link fallback avoids both. Phase 5 tests actual delivery to Gmail,
Outlook.com, Proton and iCloud and records the matrix in `handoff.md`.

### 7.6 Later, not v1

In-app sending through Microsoft Graph `Mail.Send` and the Gmail API `gmail.send` (each needs its
own OAuth registration and verification); recipient public keys (age and OpenPGP) with an address
book; self-decrypting HTML (declined for v1 — phishing look-alike).

---

## 8. Android architecture

Everything in `CLAUDE.md`'s architecture section will hold, with these specifics:

**Modules.** `:app` (Android) and `:shared` (plain Kotlin JVM, no Android imports, tests in
`./gradlew :shared:test`): the manifest and snapshot models with their serialisation, the
backup planner (`plan(local, lastSnapshot, remoteLatest) → Plan`), garbage collection and
retention, the recovery-key `WordEncoder`/`RecoveryKey` port, the passphrase generator and
policy, the password-strength gate, container sniffing, slug/path/name rules, and size/date
formatting. `:shared` does **not** depend on cryptolib; everything that needs a `Cryptor` lives in
`:app`'s `vault` package behind a small interface so the planner can be tested with fakes.

**Packages in `:app`** (`info.piepgras.cryptvault.…`):

| Package | Owns |
| --- | --- |
| `vault` | `VaultRepository` (registry, create/open/lock, SAF vs private location), the cryptolib adapter (`CryptomatorVault`: tree walk, name (de)cryption, streaming read/write, chunk reads), manifest I/O and reconciliation, thumbnails |
| `items` | import pipeline (picker, photo picker, share receiver, capture, move-original), note editor model, search and sort |
| `unlock` | password unlock with backoff, biometric wrap and prompt, recovery key create/show/reset, password change, `LockManager` (timeouts, screen-off, cache wipe) |
| `openwith` | temp-file hand-off, FileProvider paths, save-back detection, share-out, export |
| `provider` | `VaultDocumentsProvider`, proxy-descriptor callbacks, write pipe |
| `backup` | `RemoteStore` and its five implementations, credential store, planner executor, workers and the user-initiated job, restore wizard logic |
| `share` | container writers (zip4j, kage, PGPainless), passphrase UI model, mail intent, links, receive/import of containers |
| `security` | `FLAG_SECURE` helpers, clipboard, sensitive semantics, BC provider registration |
| `ui` | one file per screen, plus `theme` |
| `legal`, `prefs` | as the scaffold provides |

**Single Activity**, Navigation Compose (the app clears six screens easily). Startup gates: legal
disclaimer → crash-reporting opt-in → app; there is no database, so no upgrade gate — the registry
carries a `schema` field and migrates in place. The vault list is the first screen; every vault
starts locked.

**State**: no Room. The registry and the hash cache are kotlinx.serialization JSON files;
preferences are `object` helpers over `SharedPreferences`. **No DI framework**; manual
construction in `MainActivity`, ViewModels per area exposing `StateFlow`.

**WorkManager**: backup-after-change (debounced, unique per vault), manual backup/restore
(user-initiated data-transfer job on 34+, `setForeground` dataSync below), temp-cache sweep,
clipboard clear.

**Dependencies to add** (the scaffold provides Compose, navigation, Ktor, serialization,
Sentry, WorkManager is added here):

| Library | Version | Licence | Purpose |
| --- | --- | --- | --- |
| `org.cryptomator:cryptolib` | 2.2.2 | AGPL-3.0 | vault format (brings siv-mode, guava, gson, slf4j-api) |
| `org.slf4j:slf4j-nop` (release) / `slf4j-simple` (debug) | 2.0.17 | MIT | cryptolib's logging `[ASSUMED]` |
| `net.lingala.zip4j:zip4j` | 2.11.6 | Apache-2.0 | ZIP AES-256 containers, inner archives |
| `com.github.android-password-store:kage` | 0.7.0 | Apache-2.0 | age containers |
| `org.pgpainless:pgpainless-core` | 2.0.4 | Apache-2.0 | OpenPGP containers |
| `org.bouncycastle:bcprov-jdk18on` | 1.86 | MIT-style | pinned once for kage and PGPainless |
| `com.dropbox.core:dropbox-android-sdk` | 8.0.2 | MIT | Dropbox |
| `com.microsoft.identity.client:msal` | 8.4.2 | MIT | OneDrive sign-in (extra Maven repo, Lombok plugin) |
| `com.google.android.gms:play-services-auth` (+ Credential Manager, `googleid`) | current | Android SDK licence | Google Drive authorisation; used under GPLv3's system-library reading `[ASSUMED]`, absent from a Play-services-free build |
| `androidx.biometric:biometric` | 1.1.0 | Apache-2.0 | biometric prompt |
| `androidx.work:work-runtime-ktx` | 2.10.x | Apache-2.0 | jobs |
| `androidx.documentfile:documentfile` | 1.1.x | Apache-2.0 | SAF trees |
| `com.nulab-inc:zxcvbn` | 1.9.0 | MIT | password strength |
| EFF long wordlist (resource) | 2016 | CC-BY-3.0 | passphrases (attributed in `NOTICE`) |
| Cryptomator `4096words_en.txt` (resource) | — | GPL-3.0 | recovery keys (attributed in `NOTICE`) |

Toolchain pins follow the siblings (AGP 9.3.1, Compose BOM 2026.06.01, Gradle 9.7.0, JDK 25 —
which satisfies the Dropbox SDK's Java 21 build requirement — Kotlin/Compose compiler 2.4.10,
minSdk 26 / target 37 / compile 37, Room absent). Bump deliberately and together. No native
libraries are added, so the 16 KB page-size requirement is met by construction; re-check the
merged APK if any dependency ever brings an `.so`.

**Backup rules**: `android:allowBackup="false"` `[ASSUMED]` — nothing in the app's private storage
is restorable without the masterkey, and Keystore wraps never transfer; the privacy policy says
that device backups do not include vaults and that CryptVault's own backup is the way.

**Permission table — the v1 target** (the four-places rule applies to every row):

| Declared | For | Notes |
| --- | --- | --- |
| `USE_BIOMETRIC` | biometric unlock | normal permission; the biometric prompt itself is the disclosure |
| `INTERNET`, `ACCESS_NETWORK_STATE` | backup providers, link fallback, opt-in crash reporting | nothing else; no analytics |
| `POST_NOTIFICATIONS` | backup/restore progress and failure notices | requested at the first manual backup, never at startup |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC` | long backups and restores on API 26–33 (WorkManager `setForeground`) | service element with `foregroundServiceType="dataSync"` |
| `RUN_USER_INITIATED_JOBS` | "Back up now" / restore on API 34+ | normal permission |
| `HIDE_OVERLAY_WINDOWS` | hiding other apps' overlays above the unlock screen (§4.4) | normal permission, granted at install; `setHideOverlayWindows` throws without it |
| `MANAGE_DOCUMENTS` (on the provider element, not requested) | the DocumentsProvider | system-only; lets the Files app and pickers read the vault root |
| `<queries>` for `ACTION_IMAGE_CAPTURE` | knowing whether a camera app exists | not a permission |

Not declared, and stated as such in the permissions document: storage, `READ_MEDIA_*`,
`MANAGE_EXTERNAL_STORAGE`, `CAMERA`, `QUERY_ALL_PACKAGES`, `SCHEDULE_EXACT_ALARM`, contacts,
location. Re-read the **merged** manifest after every dependency change (Dropbox adds
`AuthActivity`, MSAL adds `BrowserTabActivity`, Play services may add
`com.google.android.gms.permission.AD_ID` only through ads libraries — none here).

**Data footprint** (the input to the privacy policy and the Data safety form):

| Data | Where | Leaves the device? |
| --- | --- | --- |
| Vault contents, metadata, thumbnails | vault directory, encrypted | only as ciphertext to the user's chosen backup target, or as a container the user mails |
| Master password, masterkey, recovery key | memory; scrypt-wrapped file; Keystore wrap | never |
| Provider credentials | Keystore-encrypted file; MSAL cache | to that provider only |
| Vault registry, settings, hash cache | app-private files | never |
| Crash reports (opt-in, off by default) | Bugsink instance | yes, when opted in: stack traces and device model; scrubbed of names, paths, titles, tags, notes, vault names, account labels, URLs |

**Debug-only conveniences**, compiled out of release: an in-memory `RemoteStore` selectable as a
target, a "point WebDAV at `10.0.2.2:8081`" switch with cleartext allowed only for that address in
the debug network security config, a "fill vault with N random files" action for backup tests.

**Testing**: hand-written fakes (`FakeRemoteStore` with failure injection, `FakeClock`,
`FakeVaultTree`), `MainDispatcherRule`, no mocking library, no Robolectric. cryptolib runs on the
plain JVM, so vault create/write/read/rename round trips are ordinary unit tests in `:app`. The
DocumentsProvider and the biometric flow are instrumented tests on the emulator
(`ANDROID_SERIAL` mandatory, as in the siblings). The Cryptomator desktop interop matrix
(`docs/PROVIDER_SETUP.md` §6) is a manual Phase 6 test recorded in `handoff.md`.

---

## 9. Screens

Home is the vault list; inside a vault, a browser with a top bar. Settings and About from the
vault list's top bar.

| Screen | Purpose |
| --- | --- |
| Vault list | every vault with lock state, location kind, last backup (green/amber/red), Unlock, Create, Open existing, Restore from backup |
| Create vault | name, location (private / choose folder), password with meter and suggestion, confirm; then the recovery-key ceremony (44 words, tap-to-reveal rows, "I wrote it down") |
| Unlock | password field (backoff countdown when applicable), biometric button when enabled, "Forgot password → recovery key" |
| Recovery | enter 44 words with per-word validation, new password, done |
| Browser | folders and items (list/grid), search, sort, multi-select actions (move, tag, delete, share, mail, export), FAB: import (file, photo, capture, scan), new note, new folder |
| Item detail | preview (image, text/markdown, PDF first page `[ASSUMED in-app previews]`), title, tags, note editing, Open with, Edit with, Share, Mail, Export, Move, Delete |
| Note editor | Markdown text with title; autosave into the vault |
| Import progress | files, sizes, thumbnails being made; then "Delete originals?" |
| Vault settings | rename, biometrics on/off, auto-lock timeout, change password, show recovery key (password required), export vault folder, delete vault |
| Backup | target (connect provider / choose folder), unmetered-only, retention (1–20, default 5), Back up now, snapshot list with dates and sizes, Restore into new vault, Delete remote backups, Active share links |
| Provider accounts | connected Dropbox / OneDrive / Google / WebDAV accounts, connect, disconnect |
| Restore wizard | target → folder → password → snapshot → progress |
| Mail / share sheet | items, container format, passphrase (generated/typed, copy), size warning, "Send by mail" (share sheet) / "Upload and send link" |
| Receive container | detected format, passphrase, vault and folder, import |
| Settings | default auto-lock, default container format, clipboard clearing, notifications, language, crash reporting opt-in, App information |
| Permissions, About, Legal | house screens; About names the AGPL/GPL components and links the source |

Runtime permission prompts happen at the point of use with a rationale, never at startup.

---

## 10. Monetization roadmap — not v1

- **v1 is free**: no ads, no purchases, no subscription; the scaffold's billing capability is not
  enabled and the legal documents carry no purchase sections.
- **Later**, in its own phase: one one-time purchase through Play Billing.
  `[OPEN: what the purchase unlocks — candidates: more than one backup target per vault, more than
  two vaults, or a "supporter" badge with nothing gated]`. The entitlement is a pure function in
  `:shared` from the start, so the phase adds billing and a screen, not a redesign.
- **Never**: gating unlock, open-with, export, restore, recovery or opening a received container
  behind payment; advertising; tracking.

---

## 11. Legal and Play consequences

Every row changes the privacy policy, `legal/permissions.md`, the terms of service, the in-app
Permissions and About screens and the Play Console declarations **in the commit that introduces
the feature**, then `python3 scripts/render_legal_docs.py`. Documents keep their "pre-release, as
designed" banner until Phase 6 removes it.

| Feature | Privacy policy | Permissions doc + screen | Terms of service | Play Console |
| --- | --- | --- | --- | --- |
| Local vaults | the strong statement: everything encrypted on the device, the developer has no key, no server, no account; device backups exclude vaults; deleting the app deletes private vaults | — | data loss with lost password and recovery key is by design; no warranty beyond the GPL's | Data safety: no collection for vault data |
| Biometrics | biometric data never leaves the platform; the app only receives a yes/no | `USE_BIOMETRIC` row | — | — |
| Open-with / share / DocumentsProvider | plaintext handed to apps the user chooses; the other app's policy governs it | `MANAGE_DOCUMENTS` row (system-granted, on the provider element) and a "no storage permission" statement | user responsibility for where they open data | — |
| Backup providers | one section per provider: host, what it receives (ciphertext, sizes, slug), its policy link, legal basis (contract, the user's own choice), retention (until deleted by the user), the "user chooses their own cloud" Data-safety exemption | `INTERNET`, notifications, foreground service, user-initiated jobs rows | provider terms are the user's; CryptVault is not responsible for provider availability | Data safety: no collection (user's own cloud exemption, quoted) |
| Mail containers and links | the mail app and provider receive the container; the passphrase never; links are the provider's | — | recipient tooling and quarantine are outside our control | — |
| Crash reporting (opt-in) | full section: what a report contains, the Bugsink host and location, retention, consent, withdrawal, scrubbing | `INTERNET` row mentions it | — | Data safety: crash logs, optional, encrypted in transit, not shared, deletable on request |
| Open source | — | — | GPL-3.0-or-later terms, source location, no additional restrictions | — |
| Content rating | — | — | — | IARC: no violence, no user interaction, no purchases (v1); expect "Everyone" |
| Target audience | — | — | — | adults / general; not for children; no Families policy |

Also owed at release (Phase 6): hosting the legal documents under
`https://piepgras.info/legal/crypt-vault/…` via the website entry; the Bugsink DSN; the feature
graphic and screenshots; the listing text; the release keystore; `LICENSE` (GPL-3.0-or-later
text) and `NOTICE` (cryptolib AGPL-3.0, ported Cryptomator code GPL-3.0, EFF wordlist CC-BY-3.0,
every Apache/MIT dependency) in the repository and in About. US export rules are self-classified
by the developer (Play asks no question) — `[OPEN: confirm the mass-market/open-source
self-classification stance]`.

---

## 12. Phased build plan

Each phase: preconditions → deliverables → acceptance → verification (the `CLAUDE.md` commands
plus `:shared:test`) → `handoff.md` entry with the real output → commit → push. Do not start a
phase with the previous one's checks red. The threat model's requirements (S1–S14) are checked at
the gate of the phase that owns them.

### Phase 0 — Foundation

- **Repoint the remote**: `origin` currently points at `…/git/skills`; set it to
  `git@192.168.5.5:/foundation/backup/git/cryptvault`, prove it with `git ls-remote`, and only
  then push anything. If the remote does not exist, stop and ask.
- Scaffold with `app-generate-android-project` from the committed `spec.json`; keep the scaffold as
  its own commit. Add `:shared` (the capability is in the spec) with the first real rule and test
  (`RecoveryKey` encode/decode round trip against a known vector).
- Add cryptolib 2.2.2 and a JVM round-trip test: create a vault in a temp directory, write a 3-chunk
  file, read it back sequentially and by random chunk, rename it, list the tree. Add `LICENSE`,
  `NOTICE`, `providers.properties.example`, the BC provider registration, `allowBackup=false`.
- Rewrite `CLAUDE.md`'s project line, module list and architecture section; copy this brief's
  permission table there as the target; record the lint baseline.
- **Accept when** all builds and tests are green, `CLAUDE.md` is accurate, and a vault created by
  the round-trip test opens in Cryptomator desktop with its password (developer check, noted in
  `handoff.md`).

### Phase 1 — Vault core (offline)

- Registry; create / open / lock; private and SAF locations with the self-test; the cryptolib
  adapter with streaming read/write and chunk reads; manifest, reconciliation, thumbnails; the
  four import paths and move-original; notes; browser, item detail, search, sort, multi-select;
  open-with with save-back and wipe; share-out; export; all strings in en/de/ru.
- **Accept when** a 2 GB video imported from the photo picker opens in a third-party player via
  open-with on the emulator without the app's heap growing; a file renamed on the desktop shows
  under its new name after re-unlock; killing the process mid-import leaves no plaintext in
  `cache/`; S1–S3 hold.

### Phase 2 — Security

- Biometrics with the Keystore wrap and invalidation handling; recovery key ceremony, display and
  reset; password change; strength gate and suggestions; backoff; `LockManager` with timeouts and
  screen-off; `FLAG_SECURE` everywhere; clipboard hygiene; sensitive semantics.
- **Accept when** S4–S7 hold on the emulator (enrol a second fingerprint → password asked, wrap
  re-created; recovery key from the phone resets the password on the desktop; a screenshot of the
  browser is refused; a copied note vanishes from the clipboard after 60 s).

### Phase 3 — DocumentsProvider

- Roots for unlocked vaults; browse; read via proxy descriptors; whole-file write; create, rename,
  move, delete; thumbnails; lock revokes.
- **Accept when** a PDF picked from the system file picker opens in a third-party viewer and its
  last page renders; seeking in a video played from the root works; creating a file from a
  third-party editor via the root lands encrypted in the vault; locking the vault makes an open
  descriptor fail (S8).

### Phase 4 — Backup and restore

- `RemoteStore` with `FakeRemoteStore`; planner, GC and retention in `:shared` with tests covering
  new/modified/deleted/renamed files, interrupted runs, the writer check and the 5-snapshot GC;
  WebDAV first (no registration needed; the docker Nextcloud), then SAF, Dropbox, OneDrive, Google
  Drive; workers, constraints, notifications, the user-initiated job; the backup and provider
  screens; the restore wizard; conflict refusal.
- **Accept when** killing the process during an upload leaves the remote restorable and the next
  run completes; the mirror opens in Cryptomator desktop through the Dropbox desktop client; a
  restore on a wiped emulator reproduces the vault byte-for-byte (hash check); the sixth snapshot
  removes the first and exactly the unreferenced versions; a second installation's write is
  refused; S9–S11 hold.

### Phase 5 — Mail and sharing

- The three container writers with the parameters of §7.2; passphrase generator and policy in
  `:shared`; the mail sheet and share intent; the size warning; the link fallback for Dropbox,
  OneDrive and Nextcloud with the Active links list; receive filters and container import;
  first-time deliverability notice.
- **Accept when** each container produced on the phone opens with 7-Zip, the `age` CLI and GnuPG
  respectively (`gpg --list-packets` shows v4 SKESK + SEIPDv1 + AES-256); a container mailed
  through Gmail arrives at Gmail, Outlook.com, Proton and iCloud (matrix in `handoff.md`); tapping
  the received attachment imports it after the passphrase; S12 holds.

### Phase 6 — Release preparation

- Legal documents through `app-legal-structure-generator` with this brief's §11 as input; remove
  the "as designed" banners after re-verifying every claim; resolve every `[OPEN]`; the
  Cryptomator desktop interop matrix (`docs/PROVIDER_SETUP.md` §6); Play declarations (Data safety,
  content rating, target audience); `app-generate-checklist` and `app-play-store-listing`; feature
  graphic and screenshots; Bugsink DSN; release keystore and a signed `.aab`; the website entry with
  the `[[legal]]` blocks; public source hosting (§13); the closed test with 12 testers for 14 days;
  developer verification of the package and signing key; S13–S14.

**Definition of done for v1**: a new user can install, create a vault, refuse every runtime
permission, import a photo and a PDF, open both in other apps, write a note, lose the app to a
factory reset and get everything back from Dropbox with the password; enable biometrics and unlock
with a fingerprint; reset a forgotten password with the recovery key; mail a document as a ZIP that
a friend opens with 7-Zip; open the backed-up vault folder in Cryptomator desktop; and every
document the app shows matches what the app does.

---

## 13. Open decisions and assumptions

Open — need the developer:

- ~~`[OPEN: display name]`~~ **Resolved 2026-09-19: "Crypt Vault"** (two words) is the display
  name — `app_name`, store title, legal documents. The package `info.piepgras.cryptvault`, the
  repository name, code identifiers and the `CryptVault/` folder on backup targets keep the
  one-word form; the slug stays `crypt-vault`.
- `[OPEN: what the later one-time purchase unlocks]` (§10)
- `[OPEN: public source hosting]` — **Decided 2026-09-19: GitHub.** A separate repository lives in
  the project folder (`github/`, a clone with full history, kept in step by
  `tools/sync_github_mirror.sh`) and is pushed to <https://github.com/piepgrasInfo/cryptvault> with a deploy key
  (`../keystores/keyDeployGithub_cryptvault`). The URL is in About; the store listing takes it
  in Phase 6.
- `[OPEN: Play-services-free build]` — whether to produce a variant without Google Play services
  (drops the Drive target; enables F-Droid). Default: not in v1.
- `[OPEN: house WebDAV server for Phase 4 testing]` (`docs/PROVIDER_SETUP.md` §5)
- `[OPEN: US export self-classification stance]` (§11)
- ~~`[OPEN: Bugsink DSN]`~~ Resolved 2026-09-19: set in `CrashReporting.kt` (project 7 on
  `piepgras.bugsink.com`).
- `[OPEN: hybrid PQC APK signing]` — Android 17 offers it; needs a fresh classical key; decide
  before the first Play upload because the signing key is permanent.

Assumed — build on these, say so in `handoff.md`, and the developer overturns them at review:

- Cryptomator's scrypt defaults and empty pepper are kept for interoperability (§3).
- The biometric wrap holds the masterkey, not the password; auth window 300 s (§4.3, layout §4).
- Password minimum: zxcvbn score 3 and 10 characters; auto-lock options and 1 min default (§4).
- No trash; deletion is immediate (§2.2). Thumbnails ≤ 256 px JPEG (layout §3).
- Open-with temp files are wiped after 10 minutes even while unlocked (§5.1); the
  DocumentsProvider supports whole-file writes only (§5.2).
- Remote vault folder is `<slug>-<id8>`; restore always creates a new local vault; Drive's
  non-conditional write race is accepted; the backup debounce is 3 minutes; amber/red thresholds
  7/30 days; one target per vault (§6).
- Container details: STORE for compressed media in ZIP; inner ZIP for multi-file age/OpenPGP; the
  note as `- note.txt`; passphrase policy for ZIP; Nextcloud links expire after 7 days (§7).
- `allowBackup=false`; `slf4j-nop`/`slf4j-simple`; `androidx.biometric` 1.1.0; Play services
  libraries under the system-library reading of the GPL; `template_project` for the scaffold is
  Existential Dread Runner (§8, `spec.json`).
- Cryptomator desktop ignores the `cryptvault/` sidecar; fallback in layout §9.
- In-app previews for images, text and PDF first page (§9). Capture "scan" is crop only (§2.3).

---

## Appendix A — Glossary

| Term | Meaning |
| --- | --- |
| vault | one Cryptomator format 8 directory with its own password; CryptVault manages several |
| item | a file in a vault's cleartext tree, with title, tags and note; a note item is a Markdown file |
| manifest | `.cryptvault/manifest.json` inside the vault: the item index |
| masterkey | the 64-byte key (encryption + MAC halves) every file key is wrapped under |
| recovery key | the masterkey encoded as 44 words; resets the password |
| biometric wrap | the masterkey encrypted by a Keystore key that requires biometric or credential auth |
| mirror | the remote copy of the ciphertext tree, itself a valid Cryptomator vault |
| sidecar | the remote `cryptvault/` folder: snapshot manifests, superseded versions, the commit point |
| snapshot | one encrypted manifest listing every ciphertext file of the vault at a point in time; five are kept |
| writer | the installation allowed to write a remote vault folder; enforced by the `latest` file's rev |
| `RemoteStore` | the provider abstraction the planner talks to |
| container | an encrypted file for mailing: ZIP AES-256, age, or OpenPGP |
| passphrase | the container's secret, exchanged out of band; distinct from any vault password |
| open-with | decrypt to a temp file and hand a content URI to another app |
| root | the entry the DocumentsProvider exposes for an unlocked vault in the system picker |
| interop | the promise that a CryptVault vault opens in Cryptomator desktop and vice versa |

## Appendix B — Interview record (2026-09-16 / 2026-09-17)

The 28 questions the brief was refined through, with the developer's answers. Answers marked
*(custom)* were typed rather than chosen from the offered options.

| # | Question | Answer |
| --- | --- | --- |
| 1 | Identity: is CryptVault the website's "Data Vault"; name and package? | CryptVault replaces Data Vault; `info.piepgras.cryptvault`. (The offered option said slug `cryptvault`; the website entry the developer renamed in parallel uses the house hyphenation `crypt-vault`, which is kept.) |
| 2 | What is a vault item? | Files of any type, plus notes; folders; title, tags, note per item |
| 3 | How many vaults and where do they live? | Vaults in user-chosen (SAF) folders — refined by Q5 |
| 4 | Unlock model and forgotten password? | Password + optional biometrics, with a recovery key |
| 5 | Vault location follow-up (SAF reliability caveats) | Hybrid: app-private by default, SAF folder optional with warning and self-test |
| 6 | How does a decrypted item reach an external app? | Both: temp file + FileProvider hand-off, and a DocumentsProvider root, in v1 |
| 7 | File sizes to handle well? | Anything, including multi-GB video |
| 8 | Import paths for v1 (multi) | File picker and Photo Picker; share to CryptVault; capture (camera/scan); move into vault |
| 9 | Backup targets for v1 (multi) | Dropbox (App folder); OneDrive personal (MSAL + Graph REST); Google Drive (Credential Manager + REST); WebDAV/Nextcloud; SAF fallback included |
| 10 | Backup semantics? | One-way backup with snapshots and restore; remote writers refused, never merged |
| 11 | When does a backup run? | After changes with constraints, plus manual "Back up now" |
| 12 | Visible in the cloud or hidden app area? | Hidden app area (Dropbox App folder, OneDrive approot, Drive appDataFolder) |
| 13 | Mail transport? | Hand off to the user's mail app via the share sheet |
| 14 | Container formats (multi) | ZIP AES-256; age; OpenPGP symmetric. Not self-decrypting HTML |
| 15 | Passphrase exchange; open received containers? | Out-of-band passphrase; CryptVault registers as opener for received containers |
| 16 | Mail size ceiling and behaviour above it? | Warn above 15 MB, offer a cloud link instead |
| 17 | Hardening in scope (multi) | Screen protection + auto-lock; clipboard and password hygiene; failed-attempt backoff. Not Advanced Protection awareness |
| 18 | Crash reporting? | Opt-in Bugsink, off by default, scrubbed |
| 19 | Monetization and audience? | Free core, one-time purchase later |
| 20 | Source availability and licence? | Open source, GPL-3.0/AGPL |
| 21 | Vault format: Cryptomator format 8 via cryptolib, or own? | Cryptomator vault format 8 via cryptolib |
| 22 | Brand colour? | Navy `#1E3A8A` |
| 23 | Icon concept? | *(custom)* "Cog as heavy vault door, slightly ajar. keyhole in the center of the cog" |
| 24 | Play account and closed-testing gate? | Same personal account as Flip Cards; the 12-testers/14-days gate applies |
| 25 | How may the agent use Cryptomator Android (GPL-3.0)? | Reference and port small pieces with attribution; house style stays |
| 26 | Biometric policy? | Per-vault opt-in, valid for a timeout window |
| 27 | Snapshot retention? | Keep the last 5 snapshots, garbage-collect the rest |
| 28 | Is desktop Cryptomator interop a promised feature? | Yes, promised and tested |

## Appendix C — The original stub, verbatim

The developer's stub of 2026-09-16, reproduced unchanged for provenance (the framing was "a short
outline of an Android productivity app (house rules, blueish color scheme)"):

```
CryptVault is a local cryptographic vault that contains important user data. Data can be
anyting, and must be opened by external apps after decrypting. Data vaults can be backed up to
one drive or dropbox or any other data storage provider. smaller items from the vault can be
emailed in a cryptographic container (investigate which frameworks work with mail).
```
