# Handoff log

Running project log. Each session appends a dated section: what changed, what was
verified (with the actual test/lint output), and the commit hash.

### [2026-09-18] Project scaffolded

- Created from the `app-generate-android-project` skill: Gradle/AGP toolchain in step with the
  sibling projects, Compose + Material 3, crash-reporting, file-sharing, material-icons-extended, navigation, notifications, networking, serialization, shared-module.
- Nothing is implemented yet. `MainActivity` shows a placeholder.
- Commit `806eb29`. Verified: `:app:assembleDebug :app:testDebugUnitTest :app:lintDebug
  :shared:jvmTest` — BUILD SUCCESSFUL, 0 lint errors, 16 warnings (15 version-currency, one
  `UnusedResources`).

### [2026-09-18] Phase 0 — Foundation (BUILD_BRIEF.md §12)

What changed:

- **The vault format adapter** `app/…/vault/`: `VaultStorage` (byte layer; `PathVaultStorage`),
  `CryptomatorVault` (create/open/close, list, create directory, streaming write, streaming and
  random-access read, rename/move across directories, delete with subtree, `.c9s` name
  shortening, `dirid.c9r` backups, password change, recovery-key reset) and `VaultConfigToken`
  (the `vault.cryptomator` JWT). `org.cryptomator:cryptolib` 2.2.2 pinned with a comment on why
  not 2.2.1; `slf4j-simple` (debug) / `slf4j-nop` (release).
- **`:shared`**: the recovery key (`RecoveryKey`, `WordEncoder`, `RECOVERY_WORDS`), ported from
  Cryptomator desktop with attribution. Test vectors were produced by compiling the original
  `WordEncoder.java` against Guava 33.5.0 and running it on three known keys; the two checksum
  bytes turned out to be the *low-order* CRC-32 bytes (Guava's `HashCode` is little-endian),
  contrary to the comment in the original — the vectors are what matters and are pinned.
- `LICENSE` (GPL-3.0), `NOTICE`, `providers.properties.example` → `BuildConfig.DROPBOX_APP_KEY`
  / `MSAL_CLIENT_ID`; `allowBackup="false"` + exclude-everything `data_extraction_rules.xml`;
  R8 rules for cryptolib/Gson/Guava; `CLAUDE.md` rewritten for the real modules and the
  permission table; the scaffold's smoke test and `:shared` placeholder removed.
- **Deviation from the brief, recorded here:** the Bouncy Castle provider registration the
  brief lists under Phase 0 is deferred to Phase 5, where the first library that needs it
  (PGPainless, kage) arrives — cryptolib shades what it uses and adding ~8 MB of `bcprov` now
  would serve nothing.

Verified (`./gradlew :shared:jvmTest :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
:app:assembleRelease`): **BUILD SUCCESSFUL**.

- `:shared:jvmTest` — `RecoveryKeyTest`, 9 tests, all green (desktop vectors encode and decode,
  CRC reference value, 200 random round trips, rejection cases).
- `:app:testDebugUnitTest` — 15 tests, all green: `CryptomatorVaultTest` (10),
  `VaultConfigTokenTest` (2), `CryptofsInteropTest` (2). The interop test opens a vault written
  by this app with Cryptomator's own `cryptofs` 2.10.0 (reads a 70 000-byte file, a 154-char
  name, a Unicode directory; edits it; this app sees the edits), and opens a vault that cryptofs
  created, changes its password, and cryptofs reads it back. This is the automated half of the
  Phase 0 acceptance; **the desktop GUI check is still the developer's** (docs/PROVIDER_SETUP.md
  §6), and there is no reason to expect a different result.
- Lint: 0 errors, **15 warnings** (14 version-currency, one `UnusedResources` on `R.color.brand`).
- `:app:assembleRelease` — R8 succeeds with the cryptolib rules; unsigned APK 3.6 MB
  (debug 27 MB).
- Nothing has been run on a device or emulator yet.

One bug found by the tests and worth remembering: `Masterkey.getEncoded()` returns the live key
array. Zeroing "the copy" after signing the vault config zeroed the vault's key; the vault stayed
self-consistent (every open zeroed it the same way), and only the cryptofs interop test caught
it. Every use now clones.

Commit: the one this entry is part of (`git log -1 -- handoff.md`).

### [2026-09-18] Phase 1 — Vault core (BUILD_BRIEF.md §12), plus Phase 2 groundwork

What changed (commits `b359294`, `3e317db` and this one):

- **`:shared`**: `items/` (Manifest model, Iso8601, Names, Reconcile, ItemQuery) and
  `unlock/` (Backoff, Passphrase with the EFF long list); 29 tests.
- **`:app`**: the item layer (`items/OpenVault`, `ThumbnailMaker`, `AndroidThumbnails`,
  `ImportSources`), the vault door (`vault/VaultRegistry`, `VaultRepository`, `SafVaultStorage`),
  `provider/ProxyDescriptors`, `openwith/OpenWith`, `unlock/LockManager`, `unlock/PasswordPolicy`
  (zxcvbn), `unlock/BiometricWrap` (Keystore, not yet wired to a screen), `security/SecureWindow`
  and `SensitiveClipboard`, and the whole UI: vault list, create vault (private or picked folder),
  unlock, browser (folders, search, sort, list/grid, multi-select, import via file picker / photo
  picker / camera, share-in banner, open-with, share, export, move, rename, delete, delete
  originals), item detail with inline preview and title/tags/note, note editor, vault settings,
  settings/about/permissions, the two startup gates. Strings in en/de/ru.
- Bugs found on the emulator and fixed on the way: `DocumentsContract.isDocumentUri(null, …)`
  NPE after an import; `resolveActivity()` returning null under package visibility (open-with
  reported "no app"); `setHideOverlayWindows` needing the `HIDE_OVERLAY_WINDOWS` permission
  (declared, four places updated); `FragmentActivity` + fragment 1.2.5 rejecting Compose's
  activity-result request codes (fragment pinned to 1.8.9); `launchMode` set to `singleTask` so a
  share reaches the running instance. `CryptomatorVault.writeFile` now writes to a `.tmp` sibling
  and renames on close, with a sweep on unlock, after the kill-mid-import test showed a truncated
  entry would otherwise be listed.
- Deviations from the brief, recorded: cryptolib's own `EncryptingWritableByteChannel` is not
  used (it appends an empty chunk after exact 32 KiB multiples that Cryptomator desktop then
  reports as an empty file — `CryptofsInteropTest` covers both edge cases); `PasswordPolicy`
  already carries zxcvbn (a Phase 2 item) because the create screen needed it; the Bouncy Castle
  registration stays deferred to Phase 5.

Verified:

- `./gradlew :shared:jvmTest :app:testDebugUnitTest :app:lintDebug` — **BUILD SUCCESSFUL**,
  29 + 29 tests green, lint 0 errors / **16 warnings** (15 version-currency incl. the fragment
  pin, one `UnusedResources` on `R.color.brand`). `:app:assembleDebug` green.
- **On the emulator** (Play image, API 37, `CryptVault.avd` on port 5560, driven by
  `uiautomator dump` + `input`; see CLAUDE.md "Emulator lessons"):
  - Gates → create vault "Personal" (private) → unlock → empty browser.
  - Import of three files (PNG, TXT, PDF) through the system file picker: thumbnails for the
    PNG and the PDF's first page, "Delete the originals?" → originals removed from Downloads.
  - Item detail shows the decoded image, metadata and SHA-256; **Open** decrypts to
    `cache/open/<random>/gradient.png` and Google Photos displays it.
  - **2 GB file import** through the picker (a zero-filled `.mp4`): 130 s, Java heap between
    17 and 42 MB throughout (baseline PSS 165 MB, during import 135–160 MB) — streaming holds.
    **Open-with of the 2 GB item**: decrypted to the cache with the heap between 12 and 21 MB;
    Google Photos received it.
  - **Kill mid-import** (`am force-stop` at ~0.4 GB of the 2 GB): only a `.tmp` sibling existed,
    no plaintext under `cache/`; after relaunch and unlock the listing shows the original item
    only, the temp is swept and the vault directory is back to 2.0 GB.
  - Share-in via `am start -a SEND --grant-read-uri-permission` cannot be tested from the shell
    (the shell cannot grant the app access; `SecurityException` on the app side, now logged as a
    warning). The intent plumbing itself is exercised: the banner appears in the running instance
    through `onNewIntent`. A real share from another app is a manual test (docs/PROVIDER_SETUP.md).
  - "Renamed on the desktop shows after re-unlock" is covered by `CryptofsInteropTest`
    (cryptofs renames and deletes, CryptVault sees them) rather than by a desktop run.
- Not yet exercised on the device: a vault in a picked (SAF) folder, export, the camera path,
  save-back after an external edit, the note editor and folders through the UI (the item layer
  tests cover them on the JVM). These are the first things to do in the next session.

Threat-model gates S1–S3 hold (no plaintext outside `cache/open`, passwords as `CharArray`s
zeroed after use — with the caveat that Compose text fields hold a `String` until the field is
cleared and collected; interop proven by cryptofs).

Commit: the one this entry is part of (`git log -1 -- handoff.md`).

### [2026-09-18] Phase 2 — Security (BUILD_BRIEF.md §12)

What changed:

- **Recovery key**: the ceremony after vault creation (`RecoveryKeyScreen`: 44 numbered words
  hidden until "Reveal", no copy/share, "I have written down" confirmation before Done), "Show
  recovery key" in the vault settings behind a password check, and "Forgot the password? Use the
  recovery key" on the unlock screen (`RecoverScreen`: per-word validation against the list, the
  checksum, a new password with the zxcvbn gate; the biometric shortcut is switched off by a
  reset). The words are parked in `Container.recoveryKeyToShow` and cleared on Done.
- **Biometric unlock**: `BiometricWrap` (Keystore AES-GCM key, `setUserAuthenticationParameters`
  300 s with BIOMETRIC_STRONG|DEVICE_CREDENTIAL on API 30+, StrongBox when offered, invalidated
  on enrolment) + `BiometricUnlock` (the system prompt) + `VaultRepository.unlockWithRawKey`
  (`CryptomatorVault.openWithRawKey` verifies the config signature with the key first). Enabled
  per vault in the settings after a password check and a prompt; the unlock screen prompts
  automatically when enabled and falls back to the password with one line when the key is gone.
- **Backoff**: `Backoff` (:shared, tested) persisted per vault in `UnlockPrefs`; the field is
  disabled and shows "Try again in n seconds" while waiting. No wipe.
- **Password policy**: zxcvbn score ≥ 3 and 10 characters, advice line under every password
  field, "Suggest a passphrase" (six EFF words) on the create screen.
- **Screen and clipboard hygiene**: `SecureWindow` (FLAG_SECURE, `setHideOverlayWindows`,
  recents blanked) applied in `MainActivity`; `secureDialogProperties()` on the shared dialogs;
  `SensitiveClipboard` (sensitive flag on 33+, cleared after 60 s) behind the note editor's Copy.
- `Modifier.sensitive()` (`security/SecureWindow.kt`) = Compose's `sensitiveContent()` (hidden
  from screen sharing on Android 15+) plus `isSensitiveData` semantics (Android 16+ accessibility
  gating), on every password field, the recovery words, the suggested passphrase and note text.
  Auto-lock on timeout was in place since Phase 1 (`LockManager`).

Verified:

- `./gradlew :shared:jvmTest :app:testDebugUnitTest :app:assembleDebug :app:lintDebug` — green,
  29 + 29 tests, lint 0 errors / 16 warnings (baseline).
- On the emulator (device PIN set with `locksettings set-pin`, no fingerprint hardware):
  - Create vault "Work" → ceremony → Reveal → 44 words captured → confirm → Done → unlock screen.
  - Three wrong passwords → "Wrong password. Try again in n seconds.", field disabled meanwhile.
  - Forgot → the 44 words typed (validated live) → new password → reset → unlock with it → browser.
  - Vault settings → "Unlock with biometrics" → password → system PIN prompt → "Biometric
    unlock is on."; "Show recovery key" → password → the same 44 words as at creation (diffed).
  - Tap "Work" from the list → the prompt appears by itself → PIN → the browser opens.
  - Harness note: while the field is disabled for the backoff, a hardware Enter activates the
    first focusable (the Back arrow) — a keyboard-user quirk, not a security issue.

Commit: the one this entry is part of (`git log -1 -- handoff.md`).

### [2026-09-18] Phase 3 — DocumentsProvider (BUILD_BRIEF.md §12)

What changed:

- **`provider/VaultDocumentsProvider`** (authority `<applicationId>.documents`, exported, guarded
  by `MANAGE_DOCUMENTS`, `android.content.action.DOCUMENTS_PROVIDER` filter): one root per
  unlocked vault (`FLAG_SUPPORTS_CREATE | SUPPORTS_IS_CHILD | LOCAL_ONLY | SUPPORTS_SEARCH`,
  MIME `*/*`, the vault name as title and "CryptVault" as summary). Document ids are
  `<vaultId>:<cleartext path>` (`DocumentIds.kt`, `DocumentIdTest`). `queryChildDocuments`
  registers a change-notification URI; `querySearchDocuments` searches the manifest index;
  `openDocument("r")` hands out `ProxyDescriptors.readOnly` (seekable, decrypted on demand),
  `"w"`/`"wt"`/`"rwt"` a reliable pipe whose reader thread streams into
  `OpenVault.replaceContent`, other modes are refused; thumbnails go through a pipe as an
  `AssetFileDescriptor`; `createDocument` (folder or empty file), `deleteDocument`,
  `removeDocument`, `renameDocument`, `moveDocument` (within a vault) map onto `OpenVault`.
- **`ProxyDescriptors`** tracks every open reader per vault; `LockManager.lock`/`lockAll` close
  them before the key is dropped and call `notifyRoots()`, and `CryptVaultApp` notifies the roots
  URI whenever the set of open vaults changes, so pickers refresh on unlock and lock.
- The Permissions screen gained the "Documents" line (three locales); CLAUDE.md's permission
  table has the `MANAGE_DOCUMENTS` row.
- `LockManager` logs schedule/lock/lockAll with the reason (vault ids only) at debug level.
- Caught up from Phase 2: the merged manifest carries `USE_BIOMETRIC` and `USE_FINGERPRINT`
  from `androidx.biometric`; they now have their row in CLAUDE.md's permission table and a
  "Biometrics" entry on the Permissions screen (three locales). Lesson: read the **merged**
  manifest after every dependency change, as CLAUDE.md says.

Verified:

- `./gradlew :shared:jvmTest :app:testDebugUnitTest :app:lintDebug` — green, 31 + 29 tests,
  lint 0 errors / 16 warnings (baseline).
- On the emulator, with the Files app (`com.google.android.documentsui`), Google Photos and
  Google Docs' PDF viewer as the third parties:
  - Files → Show roots lists "Personal / CryptVault" while unlocked. `clip.mp4` from the root
    plays in Photos through the proxy descriptor; a tap on the seek bar moved playback from
    0:06 to 0:27 (`shot49_seek.png`).
  - `Documents/page.pdf` opens in Google Docs' `PdfViewerActivity` and renders its (only,
    hence last) page (`shot50_pdf.png`).
  - Files → Downloads → select `notes.txt` → Copy to… → Personal → COPY: the file is listed in
    the root at 1100 bytes, `content read` of it hashes identical to the source, and at rest it
    is a 1196-byte `.c9r` (68-byte header + one 1128-byte chunk) in the root directory —
    `createDocument` plus the write pipe, end to end.
  - S8: while a shell `content read` streamed `big-video.mp4` (2 GB) from the root, a screen-off
    (`input keyevent 26`) locked all vaults; the reader failed after 163,708,928 bytes with
    `splice failed: EIO`, `content query …/root` returned no rows, and after unlocking the
    Personal root was back. Photos, playing the same file at another lock, went blank.
  - Seen once, not reproduced: Personal locked within minutes although its auto-lock had just
    been set to one hour (no screen-off, process alive, the registry held 3600). The reason
    logging in `LockManager` is there for the next occurrence; every later run logged
    "locks in 3600 s" and stayed unlocked.
- Not tested: a third-party editor's "Save as" into the root — the emulator has no such app;
  the Files app's copy exercises the same `createDocument` + `openDocument("w")` path. Also
  still open from Phase 1: SAF-folder vault creation, export, camera capture, save-back after an
  external edit, share-in from a real app.

Commit: the one this entry is part of (`git log -1 -- handoff.md`).

### [2026-09-18] Phase 4 — Backup and restore (BUILD_BRIEF.md §12), WebDAV and folder targets

What changed:

- **Rules in `:shared/backup`**: `Snapshot` manifest (schema 1) and `Latest` commit point,
  `RemoteLayout` names (`cryptvault/latest`, `snapshots/<seq>.json.enc`, `versions/<sha>.c9r`,
  `<slug>-<id8>` vault folders), the planner `plan(local, last)` (retire → revive → upload →
  meta, content-addressed idempotent steps), `checkWriter`, `Retention.gc` (newest N
  snapshots; a version survives while any retained snapshot references its hash) and `Locate`
  (a restore finds bytes in the mirror by hash via the newest snapshot, else in `versions/`).
  15 tests.
- **Executor in `:app/backup`**: `RemoteStore` (+ caps), `BackupIndex` (`index/<vaultId>/`:
  state, run journal, local manifest copies, `HashCache` keyed by size+mtime), `LocalScan`
  (`d/**` + meta files, `.tmp` skipped), `BackupRunner` (journaled idempotent steps, hash while
  uploading and refuse a file that changed underneath, `latest` written last with `If-Match`,
  GC only after the commit, take-over of a foreign commit point), `RestoreRunner` (meta files →
  password check → snapshot list → hash-verified downloads, tolerant of an interrupted later
  run). Snapshot manifests are AES-256-GCM under an HKDF key derived from the masterkey
  (`SnapshotCrypto`), kept Keystore-wrapped per vault (`SnapshotKeyStore`) so a **locked** vault
  backs up — a deviation from docs/VAULT_LAYOUT.md's "Cryptomator file format" wording, now
  recorded there and in THREAT_MODEL S9.
- **Targets**: `WebDavStore` (OkHttp: PROPFIND, PUT `If-Match`/`If-None-Match`, GET `Range`,
  MKCOL incl. its own vault folder and `CryptVault/`, MOVE, DELETE, Basic auth, typed auth and
  conflict errors, `probe()` for "Test connection"); `StorageRemoteStore` over any
  `VaultStorage` (`PrefixedVaultStorage` roots a SAF tree at `CryptVault/<folder>`); no ETags
  there, so the folder target has no concurrent-writer protection (as the brief accepts).
  `BackupTargetStore` keeps credentials in `secrets/providers.enc` under the new non-auth
  Keystore key `cryptvault.app` (`security/AppKeystore`). `StorageEntry` gained `lastModified`.
- **Orchestration**: `BackupService` (enable/disable/update per vault, `runNow`, the 3-minute
  debounced WorkManager job after manifest changes — `CryptVaultApp` watches every open vault's
  manifest generation — the expedited manual run, notification channels, the "foreign writer"
  and "three failures" alerts), `BackupWorker` (manual runs hold a `dataSync` foreground
  service with a progress notification; automatic runs retry up to three times),
  `RestoreService` (a remote folder becomes a **new** private vault named "<name> (restored
  <date>)"; "adopt as writer" re-points backup at that folder with a take-over).
  `VaultRecord.backup: BackupConfig` holds target, folder, retention, the Wi-Fi rule and a
  status copy for the list.
- **UI**: `BackupScreen` (from the vault settings: targets list with "WebDAV server…" —
  URL/user/app password with a connection test — and "Folder…" via the system picker; turn on
  (password check when the vault is locked), status card with progress, "Back up now" (asks
  for `POST_NOTIFICATIONS` first on 33+), snapshots to keep, only on Wi-Fi, switch off, forget a
  target; a foreign writer shows "Restore from it" / "Take over"), `RestoreScreen` (wizard:
  target → vault folder → password → snapshot → progress → done with "back this vault up to the
  same place from now on"), "Restore from backup…" in the vault list menu, a backup status line
  on every backed-up vault's row (tertiary colour after 7 days, error after 30 or on failure).
  Strings in en/de/ru.
- **Permissions** (four places): `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC` (the
  merged `SystemForegroundService` element carries `dataSync`); `POST_NOTIFICATIONS` now has
  its use. **Not built**: the brief's user-initiated data-transfer job for API 34+ and
  `RUN_USER_INITIATED_JOBS` — one WorkManager path serves every API level; Android 15's 6-hour
  dataSync budget stops a run, which the next trigger resumes (CLAUDE.md table says so).
- **Test infrastructure**: `FakeRemoteStore` (failure injection), `tools/webdav_test_server.py`
  (stdlib WebDAV with ETags, conditional PUT, Range, MOVE, `--fail-every`) because docker is
  not installed on this host; `WebDavEndToEndTest` starts it as a subprocess. Debug builds
  allow cleartext to 10.0.2.2/localhost only (`network_security_config`).
- Dependencies: OkHttp 4.12.0 declared explicitly (was transitive via Ktor), MockWebServer
  (test), WorkManager 2.11.2 (2.12.0 is not on Google's Maven yet). NOTICE updated.
- **Not built in this phase**: Dropbox, OneDrive and Google Drive targets — each needs a
  developer registration first (docs/PROVIDER_SETUP.md §2–4: Dropbox app key, Entra client id
  with the `msauth://` redirect, GCP OAuth client per SHA-1) and the SDK dependencies (MSAL
  brings a Maven repo and Lombok). The `RemoteStore` interface and the target picker are
  ready for them; `BackupTarget.Kind` lists them.

Verified:

- `./gradlew :shared:jvmTest :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
  :app:assembleRelease` — green, 44 + 45 tests, lint 0 errors / 18 warnings (17 version
  notices incl. two for OkHttp 4.12 → 5.x, plus the known unused colour), release build with
  R8 passes with OkHttp and WorkManager on board.
- Unit level: first backup mirrors byte for byte; modify/delete/new/rename → retire, upload,
  revive (a rename costs no upload); an interrupted run (failure injected mid-way) leaves
  snapshot 1 restorable and the next run completes with the journal cleared; a second
  installation is refused (`Foreign`) until it takes over, after which the first one is; the
  sixth snapshot removes the first and exactly its unreferenced version; restore of the newest
  snapshot is byte-identical and an older one reads its old content; the same sequence over
  the real Python WebDAV server (`WebDavEndToEndTest`), including a 300 kB file streamed both
  ways and the conditional-write refusal of a second installation.
- Emulator (Android 17 / API 37 image, host WebDAV server `tools/webdav_test_server.py` on
  port 8081, reached as `http://10.0.2.2:8081/dav/`):
  - **First finding**: the app could not connect while the shell could — `run-as <pkg> nc`
    timed out too. Android 17 enforces local-network protection for apps targeting 37: the
    new runtime permission `ACCESS_LOCAL_NETWORK` is now declared, asked for in the WebDAV form
    when the host is a local address, and shown on the Permissions screen and in CLAUDE.md
    (a home Nextcloud or NAS is exactly this case). After granting it: connected.
  - Vault settings → Backup → "WebDAV server…" → URL/user/app password → Test connection
    (system prompt for the local network → Allow) → Connected → Save → Turn on backup. The
    first run uploaded the Personal vault (16 files, 2.15 GB incl. the 2 GB video, ~2 MB/s
    through the emulator's NAT into Python) and committed snapshot 1; the status card read
    "Last backup 0 minutes ago (snapshot 1)". Host-side, `CryptVault/personal-67de1e46/`
    holds `d/**`, the two meta files, `cryptvault/latest` (seq 1 + hash) and
    `snapshots/00000001.json.enc`; SHA-256 of every mirrored file equals the vault's local
    ciphertext (18 files compared).
  - 200 MB written into `notes.txt` from the shell through the DocumentsProvider's write pipe
    (`content write`, 3 s) → "Back up now" (the `POST_NOTIFICATIONS` prompt appears first) →
    process killed by `am force-stop` at 108 MB uploaded: `latest` still says 1, the journal
    holds the completed steps (among them a *revive* — the rewritten manifest's `.bak` is the
    previous manifest's bytes, so the rename optimisation fires in real life) → next "Back up
    now" completes: snapshot 2, mirror again byte-identical, the two superseded files are in
    `versions/`.
  - Restore wizard: vault list → More → "Restore from backup…" → the target → folder
    `personal-67de1e46` → password → snapshot list "16 files · 2.2 GB · snapshot 2" /
    "… 2.0 GB · snapshot 1" → snapshot 2 → progress → "Restored as “Personal (restored
    2026-09-18)”" after about three minutes (downloads run faster than uploads here) → Done.
    The new vault's directory is byte-identical to the original (18 files compared by hash),
    and it unlocks with the same password and lists Documents, Notes, big-video.mp4, clip.mp4
    and the 191 MB notes.txt.
  - Progress inside one large file was invisible at first ("4.4 KB of 2.0 GB" for the whole
    2 GB upload): both runners now report every megabyte within a file.
  - Automatic run: a note created in the unlocked Personal vault scheduled the debounced job
    (JobScheduler showed the 3-minute latency and the battery constraint); snapshot 3 was
    committed three minutes later with no further interaction.
  - Second installation: a second restore with "back this vault up to the same place" ticked
    made the restored vault adopt the folder — its take-over run committed snapshot 4 within
    20 s. "Back up now" on the original Personal then stopped with the status card "Another
    device wrote this backup last …" and the two buttons; "Take over" ran and committed
    snapshot 5, and the vault list shows both vaults' status lines. (Both live in one app
    install, so the check runs on the per-vault index rather than the installation id — the
    same code path a second phone hits.)
  - Sixth snapshot: another note → "Back up now" → snapshot 6; host-side
    `snapshots/` now holds 2–6, `versions/` went from five files to the three still referenced
    by a retained snapshot, and the local manifest copies were pruned to 2–6.
  - Not done from the acceptance list: "the mirror opens in Cryptomator desktop through the
    Dropbox desktop client" — no Dropbox target yet and no desktop on this host; the mirror is a
    byte-identical vault directory and `CryptofsInteropTest` opens what this app writes, so
    the remaining risk is the provider's handling of file names, a Phase 6 check. "Restore on a
    wiped emulator" was done as a restore into a new vault on the same emulator.

Commit: the one this entry is part of (`git log -1 -- handoff.md`).

### [2026-09-18] Phase 5 — Mail and sharing (BUILD_BRIEF.md §12): the three containers, share sheet, receiving

What changed:

- **Rules in `:shared/share`**: `ContainerKind` (ZIP/age/PGP with extensions and MIME types),
  `ContainerSniff` (by magic bytes: `PK\x03\x04`, `age-encryption.org/v1\n`, `0xC3`/`0x8C`),
  `SharePolicy` (passphrase verdict — zxcvbn ≥ 3, ≥ 4 for ZIP unless six list words; 15 MB
  warning; container names `<name>.zip` / `<name>.age` / `<name>.gpg`, several files
  `<title>.zip.age` / `.zip.gpg`; STORE for already-compressed media; `<title> - note.txt`).
- **Writers and reader in `:app/share`**: `ContainerWriter` — zip4j AES-256 AE-2 under one
  top-level folder (no ZipCrypto path exists), kage `ScryptRecipient` at the default work
  factor, PGPainless symmetric v4 SKESK + SEIPDv1 + AES-256 in binary with the file name in
  the literal packet; `CryptVaultPgp` overrides Bouncy Castle's S2K to SHA-256 at count 0xFF
  (the default was SHA-1 at 65 536). Several files travel inside an uncompressed inner ZIP for
  age/PGP. `ContainerReader` detects by content, decrypts, unpacks an inner ZIP, flattens entry
  names (zip-slip impossible) and maps every library's wrong-key failure to
  `WrongPassphraseException`.
- **Share**: `ShareContainerDialog` from the browser's selection, the item menu and the item
  screen — format chips (default ZIP, remembered in `AppPrefs`), generated six-word
  passphrase with "New one" and "Copy" (`SensitiveClipboard`) or a typed one with the policy
  verdict, size line and warning, "Share encrypted" / "As is"; a one-time deliverability
  notice first. `ShareService` builds the entries (a file item's note as `- note.txt`), writes
  the container into `cache/share/` and hands it to the system chooser with subject
  "<title> (encrypted)" and a body naming the attachment, the format, the size, the openers
  and "the passphrase comes separately" — nothing else.
- **Receive**: `ACTION_VIEW` filters for `application/zip`, `application/pgp-encrypted`,
  `application/vnd.age` and `application/octet-stream` on `MainActivity` (mail apps hand
  over generic types); `pendingContainer` → `ReceiveScreen`: copy to `cache/import/`, detect
  (or decline in one line), passphrase, contents, choose an unlocked vault (locked ones lead
  to the unlock screen), import — one file into the root, several into a folder named after
  the container, `- note.txt` becoming its file's note — then wipe.
- Dependencies: zip4j 2.11.6, kage 0.7.0 (its `bcprov-jdk15to18` excluded), PGPainless 2.0.4,
  Bouncy Castle 1.86 (`bcprov`, `bcpg`, `bcutil` pinned together); duplicate `META-INF`
  licence files excluded in packaging. NOTICE updated. Three count strings became `<plurals>`
  (lint's `PluralsCandidate` stays at zero).
- **Not built**: the link fallback (§7.3) — Dropbox and OneDrive targets do not exist yet and
  no Nextcloud is reachable from this host to test the OCS share API against; the size warning
  tells the user to hand the container to a cloud app instead. The Gmail/Outlook/Proton/iCloud
  delivery matrix needs real mailboxes (§7.5) and is left for the developer.

Verified:

- `./gradlew :shared:jvmTest :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
  :app:assembleRelease` — green, 47 + 49 tests, lint 0 errors / 18 warnings (baseline held;
  three new count strings went straight to `<plurals>`), release build with the three new
  libraries under R8 passes (6.4 MB unsigned APK; no new keep rules were needed).
- `ContainerTest` round-trips every format (two files in ZIP, one and several files in age and
  PGP), refuses a wrong passphrase in each, and leaves samples under `app/build/containers/`.
  On the host: `gpg --list-packets` shows `symkey enc packet: version 4, cipher 9, s2k 3,
  hash 8, count 65011712 (255)`, `mdc_method: 2` and the literal packet with the file name;
  `gpg -d` returns the original bytes; libarchive's `bsdtar --passphrase` lists and extracts
  the AES ZIP and reports "Incorrect passphrase" for a wrong one; the inner ZIP of a
  multi-file `.gpg` lists both entries. No `age` CLI is installed here (no Go/Rust either), so
  age is verified against kage's own decryption only — a CLI check remains for the developer.
- Emulator: long-press `report.pdf` → Share → the deliverability notice → the dialog with a
  generated passphrase ("unhitched manmade abiding said crummiest tinwork") → OpenPGP →
  "Share encrypted" → the system chooser "Sharing 1 file — report.pdf.gpg" with Gmail, Drive
  and Quick Share in 2 s. The container pulled from `cache/share/` decrypts on the host with
  GnuPG and that passphrase to the original bytes.
- Emulator receive: Files → Downloads → `report.pdf.gpg` → "Open with CryptVault" → the
  screen names it an OpenPGP container → passphrase → "Contents: report.pdf" → Import →
  "Imported 1 item into Personal." → the browser lists it.
  - Emulator receive of `Holiday.zip.age` (two entries: a video and its `- note.txt`): the
    first attempt **killed the process** — `OutOfMemoryError` at the 192 MB heap growth limit,
    because age's default scrypt work factor 18 needs 256 MB. Fix: `android:largeHeap="true"`
    (576 MB on this image, 512 MB+ on phones) and a typed `ContainerTooLargeException` that the
    screen turns into one line instead of a crash. Second attempt: "age container:
    Holiday.zip.age" → passphrase → "Contents: clip.mp4, clip - note.txt" → Import → "Imported
    1 item" — the note file was paired with its video and shows on the item as its note.
  - Also seen: sharing the 200 MB `notes.txt` as OpenPGP took well over a minute on the
    emulator (S2K at the maximum count is cheap; AES over 200 MB in the emulator is not) with
    the "Decrypting…" progress dialog up the whole time — acceptable, but the 15 MB warning
    is what keeps real mails small.

Commit: the one this entry is part of (`git log -1 -- handoff.md`).

### [2026-09-19] Developer decisions applied: display name, Bugsink DSN, GitHub mirror

- **Display name is "Crypt Vault"** (two words). `app_name` and every user-facing sentence in
  en/de/ru say so; the package `info.piepgras.cryptvault`, the repository, code identifiers and
  the `CryptVault/` folder on backup targets keep the one-word form (renaming that folder would
  orphan existing backups). `spec.json`, README, NOTICE, CLAUDE.md and BUILD_BRIEF.md §13 updated.
- **Bugsink DSN** set (in `CrashReporting.kt` at the time; since 2026-09-25 it comes from the
  gitignored `providers.properties`); reporting stays opt-in and off by default, the SDK cannot
  start before the user answers.
- **Public source**: `tools/sync_github_mirror.sh` creates and fast-forwards a full clone in
  `github/` (gitignored) that the developer pushes to GitHub; the script refuses to run if a
  credential file were ever tracked. The GitHub URL for About and the store listing is still
  to come.
- Deferred by the developer: the provider registrations (Dropbox, OneDrive, Google Drive) and
  the launcher icon.

Verified: `./gradlew :app:lintDebug :app:testDebugUnitTest` — 49 tests green, lint 0 errors /
18 warnings (every locale still complete); the emulator shows "Crypt Vault" as the app title and
as the DocumentsProvider root summary. `tools/sync_github_mirror.sh` created `github/` at this
commit with `house` as its only remote.

Commit: the one this entry is part of (`git log -1 -- handoff.md`).

### [2026-09-19] GitHub deploy key and the public URL

- Repository: <https://github.com/piepgrasInfo/cryptvault>. An ed25519 deploy key was generated
  outside the repository and registered on GitHub with write access; it is owned by the developer,
  so pushes to GitHub run from the developer's shell. The `github/` clone has `origin` set to the
  repository and `core.sshCommand` pinned to that key with `IdentitiesOnly`.
- The About text in en/de/ru now names the URL; README and BUILD_BRIEF.md §13 updated.

Commit: the one this entry is part of (`git log -1 -- handoff.md`).

### [2026-09-19] Commit authorship moved to the developer

All commits on `master` were rewritten with `Martin Kruse <martin@piepgras.info>` as author and
committer (file trees identical, 16 commits, `4fff655` → `227a5a7`); the agent's contribution
stays visible through the `Co-Authored-By` trailer on the commits it made. The previous history is
kept as `backup/authored-by-agent` on the house remote. The repository's local git identity is
now the developer's, so future commits from either side carry the same author. GitHub needed a
forced push of `master` from `github/` (`--force-with-lease`); its CI re-ran on the new SHAs.

Commit: the one this entry is part of (`git log -1 -- handoff.md`).

### [2026-09-19] A Play-services-free distribution anyone can install

`[OPEN: Play-services-free build]` in BUILD_BRIEF.md §13 is resolved: **yes, in v1**, as one
flavor dimension `dist` with `play` and `foss`.

- **The starting point was better than expected.** Resolving `releaseRuntimeClasspath` in full
  showed **no `com.google.android.gms`, no Firebase, no Play Core anywhere** — the only
  Google-authored artifacts are Guava, Gson, jsr305 and error_prone annotations, ordinary
  libraries that need no Play services. Today's APK already runs on a device without them. The
  flavor is therefore not a de-Googling but a seam that keeps it true when the Drive target
  arrives (BUILD_BRIEF.md §6.1).
- **`foss` carries `applicationIdSuffix = ".foss"`.** Play App Signing re-signs the Play copy
  with Google's key, so the two builds can never replace one another whatever the id; with a
  shared id a channel switch would mean an uninstall, and an uninstall takes every private vault
  with it. Separate ids let both be installed at once, so a vault can be carried across with the
  app's own backup or export. Remote backup folders do not depend on the applicationId, so a
  backup made by one build restores into the other.
- **The seam.** `dist/Distribution.kt` in `main` reads `BuildConfig.DISTRIBUTION` and
  `PLAY_SERVICES_ALLOWED` and takes its target list from `FlavorTargets`, of which `src/play/`
  and `src/foss/` hold one copy each — a target the build must not offer is not compiled into it
  rather than hidden behind an `if`. `BackupScreen` and `RestoreScreen` build their "add target"
  buttons from that list. Google-dependent libraries go in `playImplementation`, Google-dependent
  manifest entries in `app/src/play/AndroidManifest.xml`.
- **Everything else is identical**: same permissions, same data flow, same opt-in crash reporting
  (the developer's decision — expect F-Droid to apply its "Tracking" anti-feature label; moving
  Sentry to `playImplementation` later is a two-line change now that the seam exists), so one set
  of legal documents covers both builds. About gains one line naming the edition.
- **No in-app update check**, by decision: F-Droid updates its own installs and the two APK
  channels are checked by hand. Nothing contacts a server on its own schedule.
- **Channels**: F-Droid main repo, GitHub Releases, piepgras.info. `tools/build_foss_apk.sh`
  builds, renames (`cryptvault-<version>-foss.apk`) and prints the SHA-256 to publish with it;
  `base.archivesName` makes the build outputs self-describing (`cryptvault-foss-release.apk`).
  `fastlane/metadata/android/*/changelogs/1.txt` added — F-Droid reads the tree Play already uses.
- **Task names changed.** With a flavor dimension there is no `testDebugUnitTest` and no
  `lintDebug`; the verification set is now `:app:compilePlayDebugKotlin
  :app:compileFossDebugKotlin`, `:app:test` (both flavors), `:app:lintPlayDebug
  :app:lintFossDebug`, `:app:assembleDebug` (both) and `:shared:jvmTest`. CLAUDE.md, README.md
  and `.github/workflows/ci.yml` follow; CI would otherwise have failed on the first push.

Verified:

- `:app:compilePlayDebugKotlin :app:compileFossDebugKotlin` — BUILD SUCCESSFUL.
- `:app:test` — 55 tests playDebug, 56 fossDebug, 0 failures (the new `DistributionTest` runs in
  both, `PlayDistributionTest` and `FossDistributionTest` in one each); `:shared:jvmTest` 47, 0
  failures.
- `:app:lintPlayDebug :app:lintFossDebug` — **0 errors, 18 warnings each**, the same 18 as before
  (3 `AndroidGradlePluginVersion`, 3 `GradleDependency`, 11 `NewerVersionAvailable`, 1
  `UnusedResources`). The baseline is unchanged and now holds per flavor.
- `:app:assembleRelease` — both R8 builds pass; `cryptvault-play-release-unsigned.apk` and
  `cryptvault-foss-release-unsigned.apk`, 6.1 MB each.
- **Merged manifests of `fossRelease` and `playRelease` are identical** once the `.foss` suffix is
  normalised away, and the `foss` one contains no `com.google.android.gms` entry — so the
  Play-services-free build declares nothing extra and drops nothing.
- **A pristine checkout builds**: `git ls-files -c -o --exclude-standard` copied to a temporary
  directory, with neither `providers.properties` nor `keystore.properties` nor
  `local.properties` (only `ANDROID_HOME`), builds `:app:assembleFossRelease` — the precondition
  for F-Droid building from source.

Still open, and now written into BUILD_BRIEF.md §13: whether to take F-Droid's
**reproducible-build** route, which is what would give the F-Droid copy and the piepgras.info copy
one signature instead of two colliding ones; and whether F-Droid's build server can supply the
**Java 25** the cryptofs interop test needs.

Commit: the one this entry is part of (`git log -1 -- handoff.md`).

### [2026-09-20] The launcher icon: a vault door, and the whole generated set

The developer dropped a finished 1024x1024 drawing into `art/` and asked for the assets. No
candidate round, therefore — the `app-launcher-icon-creator` skill's step 2 says not to
manufacture throwaways when the design is settled — so the work was re-authoring that artwork
onto the adaptive-icon canvas and generating from it.

- **The mark**: a cog read as a heavy vault door, its four-spoke handle in the centre, on a
  vertical blue gradient (`#127EFD` at the top to `#03256B` at the bottom). That is the concept
  the release checklist has carried since Phase 0, minus the "slightly ajar".
- **`art/ic_launcher.svg` is the source of truth.** The original was a 2048-unit square with the
  disc filling it and a C2PA metadata blob three times the size of the drawing; what is committed
  is the ten mark paths on a `0 0 108 108` viewBox, the gradient rebuilt as a full-bleed
  `<g id="background">`, and the disc scaled to **66dp centred** — Android's keyline circle — so
  the circular and squircle masks both leave the blue rim showing. The temporary file is gone.
- **The monochrome layer is hand-made**, and `--keep-monochrome` is therefore mandatory on every
  regeneration. The generator's mechanical flattening collapses the four foreground paints into
  one tint, which turns the door into a plain disc. `art/ic_launcher_monochrome.svg` instead uses
  the light shapes as a mask and the dark ones as cut-outs, so the themed icon keeps the teeth,
  the dial ring and the handle. Two anti-aliasing seams had to be closed by hand: a black ring
  along the rim, where the cut-outs share the disc's outer edge, and a black stroke on the two
  body halves, which abut along an arc — two anti-aliased edges meeting do not cancel, and a
  25%-alpha hairline survived both places until they did.
- **minSdk 26 made the pre-26 fallbacks dead weight, and lint said so** — 16 warnings above the
  baseline: `IconDuplicates` x5 (`ic_launcher.png` and `ic_launcher_round.png` byte-identical),
  `IconLauncherShape` x10 (a full-bleed square, and a "round" icon that is not round),
  `ObsoleteSdkInt` (a `-v26` qualifier under minSdk 26). Fixed rather than suppressed:
  `android:roundIcon` dropped from the manifest with a comment saying why, `mipmap-anydpi-v26`
  merged back into `mipmap-anydpi`, and `scripts/shape_legacy_launcher_icons.py` masks the
  remaining legacy PNGs with the squircle. Those PNGs are kept, not deleted, because F-Droid and
  other store tooling pull a raster icon out of the APK for their listing thumbnail.
- **Three Play listing assets** in `art/` (`ic_launcher.svg`, `ic_launcher_monochrome.svg`,
  `ic_launcher_512.png` — 26 KB, 32-bit RGBA, well under Play's 1024 KB cap) and the byte copy in
  `fastlane/metadata/android/{en-US,de-DE,ru-RU}/images/icon.png`, which is what an upload
  actually sends. The listing builder also wanted to write `fastlane/Appfile` and `Fastfile`;
  both were removed again as Phase 6's business, not the icon's.

To regenerate the whole set after editing `art/ic_launcher.svg`:

```bash
python3 <skill>/scripts/generate_launcher_icons.py --svg art/ic_launcher.svg \
    --res-dir app/src/main/res --keep-monochrome --no-round
rm -rf app/src/main/res/mipmap-anydpi && mv app/src/main/res/mipmap-anydpi-v26 \
    app/src/main/res/mipmap-anydpi
python3 scripts/shape_legacy_launcher_icons.py
```

Verified: `:app:compilePlayDebugKotlin :app:compileFossDebugKotlin`, `:app:test`,
`:app:lintPlayDebug :app:lintFossDebug`, `:app:assembleDebug`, `:shared:jvmTest` — all green, lint
back to **0 errors / 18 warnings in each flavor**, the two merged manifests still identical once
the `.foss` suffix is stripped. Not verified on a device: the home-screen icon and the themed
variant with system theming on were judged from rendered circle/squircle/themed/48px previews,
not from a launcher.

Still open: `R.color.brand` is `#1E3A8A`, which CLAUDE.md describes as existing "for the launcher
icon and store assets" — the icon that now exists is `#127EFD`/`#03256B`, so the developer should
say whether the brand token follows the icon before the feature graphic is drawn.

Commit: the one this entry is part of (`git log -1 -- handoff.md`).

### [2026-09-20] The icon's blue becomes the house blue

The developer judged yesterday's hue wrong and asked for Flip Cards' and Work Time Tracker's
background gradient instead — background only, the vault door untouched.

- Both sibling projects carry the same one, `#1E88E5` at `y=0` to `#0D47A1` at `y=108`,
  `gradientUnits="userSpaceOnUse"` over the full canvas. It is copied into
  `art/ic_launcher.svg` verbatim, replacing `#127EFD`/`#03256B`, and the three productivity
  apps now sit together in a launcher. Only `<defs>` and the background rect's `fill` changed;
  the ten mark paths and their transform are byte for byte what they were.
- **The proof that the mark is untouched** is in the diff: across all five densities
  `ic_launcher_foreground.png` and `ic_launcher_monochrome.png` are unchanged, and only
  `ic_launcher_background.png`, the flattened `ic_launcher.png`, `art/ic_launcher_512.png` and
  the three fastlane `icon.png` copies moved.
- **The generator's export scan walks into `github/`.** It found the mirror clone's
  `art/ic_launcher_512.png` and its three fastlane copies, matched them against the previous
  assets and rewrote them in place — dirtying a working tree that is only ever meant to move by
  fast-forward. Restored with `git -C github checkout -- .`, and `tools/sync_github_mirror.sh`
  then carried it forward properly. The scan also warned about `github/art/ic_launcher.svg`
  matching no version of the assets, which is the script's own documented blind spot: the SVG was
  hand-edited before the run, so the "previous" bytes it fingerprints were already the new ones.
  Written into CLAUDE.md as a step of the regeneration recipe.

Verified: `:app:compilePlayDebugKotlin :app:compileFossDebugKotlin`, `:app:test`,
`:app:lintPlayDebug :app:lintFossDebug`, `:app:assembleDebug`, `:shared:jvmTest` — all green,
lint still **0 errors / 18 warnings in each flavor**. The icon was judged from
circle/squircle/themed/48px renders and side by side with the two sibling icons, not on a device.

Still open, unchanged from yesterday: whether `R.color.brand` (`#1E3A8A`) should follow the icon,
now that the icon's blues are the house `#1E88E5`/`#0D47A1`.

Commit: the one this entry is part of (`git log -1 -- handoff.md`).

### [2026-09-21] The brand colour follows the icon into the house palette

BUILD_BRIEF.md §13 row 22 had settled navy `#1E3A8A`. Yesterday's icon took Flip Cards' and Work
Time Tracker's background gradient, whose bottom stop is `#0D47A1`, and the app's own `primary`
was then a different blue from its own launcher icon — with two comments in the source
(`colors.xml`, `Color.kt`) still promising the two were kept in step. The developer chose to
revise the row rather than let them disagree.

- **What the token actually was.** `R.color.brand` is flagged `UnusedResources`, which made it
  look inert; it is not. The same value lives a second time in `ui/theme/Color.kt` as `Brand`,
  the Material 3 **`primary` of the light scheme**, so it paints every screen. Only the XML
  resource has no consumer.
- **Adopted whole, not just the primary.** Flip Cards' CLAUDE.md states the palette is "a house
  rule for the productivity apps, not this app's own choice", so taking one role and leaving the
  rest would have missed the point. The old secondary `#625B71` and tertiary `#7D5260` were the
  Android Studio template defaults anyway — never chosen. Now primary `#0D47A1`/`#90CAF9`,
  secondary `#455A64`/`#B0BEC5`, tertiary `#00695C`/`#80CBC4`, and `ui/theme/Color.kt` is
  symbol for symbol and value for value identical to Flip Cards' (checked, not assumed).
  `BrandLight` was renamed `BrandDark` to match the family: it is the *dark scheme's* primary.
- **Five places moved together**: `res/values/colors.xml`, `ui/theme/Color.kt`, `spec.json`'s
  `theme_color`, BUILD_BRIEF.md §1 and §13 row 22 (struck through rather than overwritten, so the
  revision stays legible). `art/feature_graphic.svg` joins them when Phase 6 draws it.
- **The timing was the cheap part.** `art/` holds only the three icon assets — no feature
  graphic, no screenshots — so nothing had to be redrawn. After Phase 6 this would have been a
  redraw rather than a find-and-replace.
- **Dynamic colour stays off and is now explained as a family decision** rather than "the navy is
  this app's identity", which is no longer the reason. The `dynamicColor` parameter is left in
  place, defaulting to false, with no caller passing it; Flip Cards has removed its branch
  entirely, so closing that gap is a small follow-up if the family should match there too.

Verified: `:app:compilePlayDebugKotlin :app:compileFossDebugKotlin`, `:app:test`,
`:app:lintPlayDebug :app:lintFossDebug`, `:app:assembleDebug`, `:shared:jvmTest` — all green, lint
still **0 errors / 18 warnings in each flavor**; a repo-wide grep finds no `#1E3A8A`, `#93A8F0`,
`#625B71` or `#7D5260` left outside this log and the brief's struck-through row. Not seen on a
device: the new scheme was verified by value, not by screenshot.

Commit: the one this entry is part of (`git log -1 -- handoff.md`).

### [2026-09-25] The public repository carries nothing host-specific

The repository is public, so every tracked file is. A sweep removed what only belongs on the
development machine or in the developer's notes:

- **Key file names and paths** (the GitHub deploy key) from README, BUILD_BRIEF.md §13 and two
  handoff entries; the key itself was never tracked. **Home-directory paths and the internal
  address of the house remote** from `spec.json` (`git_remote`, `template_project` now empty,
  `project_dir` is `.`) and BUILD_BRIEF.md Phase 0; **per-host AVD names** from CLAUDE.md's
  emulator notes. The README's mirror recipe became a plain statement of where the source is;
  the recipe stays in CLAUDE.md, which now also says that nothing host-specific goes into
  tracked files.
- **The Bugsink DSN** left `CrashReporting.kt` for the gitignored `providers.properties`
  (`BUGSINK_DSN` → `BuildConfig.BUGSINK_DSN`, alongside the provider keys;
  `providers.properties.example` documents it). `CrashReporting.isAvailable` is false when it is
  blank: then the opt-in dialog is skipped, the Settings switch is hidden and the SDK never
  starts. The DSN still ships inside every APK the developer builds and can be read out of one,
  so this keeps it out of the indexed source rather than making it secret; the guard against
  abuse remains on the Bugsink side. A build from the public source without the file — an
  F-Droid build — has no crash reporting at all, which suits that channel.
- The Android emulator's host alias `10.0.2.2` stays: it is the platform's documented loopback,
  not a local detail.
- Still visible on GitHub: two older commit messages name the deploy key *file* (a name, not a
  key). Removing them means rewriting history again; left to the developer's call.

Verified: `:app:compilePlayDebugKotlin :app:compileFossDebugKotlin :shared:jvmTest :app:test
:app:lintPlayDebug :app:lintFossDebug` — green, 47 + 55 + 56 tests, lint 0 errors with 10 (play) /
11 (foss) warnings, all version notices plus the known unused colour; CLAUDE.md's stale "18 each"
baseline corrected to these. `git grep` for home paths, key names, the internal remote address,
AVD names and the DSN over every tracked file returns nothing. The developer's builds keep the
DSN through the local, gitignored `providers.properties`.

Commit: the one this entry is part of (`git log -1 -- handoff.md`).

**Open before first release** (see also `RELEASE_CHECKLIST.md` once generated by the
`app-generate-checklist` skill):

- [ ] **Play closed-testing gate — start now.** Personal account created on/after 13 Nov 2023:
      12 testers opted in for 14 continuous days before production. Two weeks of calendar time.
- [ ] Provider registrations with lead time: docs/PROVIDER_SETUP.md (Dropbox, Entra, GCP).
- [ ] Legal documents in `legal/` (`app-legal-structure-generator` skill, Phase 6), hosted at
      `https://piepgras.info/legal/crypt-vault/…`.
- [x] Launcher icon set and 512×512 Play listing icon — done 2026-09-20 from the developer's own
      drawing; source of truth `art/ic_launcher.svg`, see the entry above.
- [ ] 1024×500 feature graphic and screenshots.
- [ ] Store listing text under `fastlane/metadata/android/`.
- [ ] Release keystore, `keystore.properties` (never committed), and a signed `.aab`.
- [ ] Bugsink DSN in `CrashReporting.kt`.
- [ ] Play Console declarations: Data safety, content rating, target audience, ads,
      account deletion. None of these can be inferred from this repository.
- [ ] Every `[OPEN]` in BUILD_BRIEF.md §13, above all the display name (CryptVault vs Crypt Vault)
      and the public source mirror the GPL requires before the first external tester.
- [ ] The `foss` channels: F-Droid submission (fdroiddata merge request), the GitHub Release and
      the piepgras.info download, each with the APK's SHA-256; decide the reproducible-build
      question so the three channels do not ship two different signatures.

### [2026-09-24] The build tree moved onto EverydayHero's version

Every app on this machine now sits on one build tree. EverydayHero had been moved
onto it alone (its F8 currency sweep, 2026-09-24); this run brought the eight siblings up to the
same pins so no project is a version behind another.

**What moved** — `gradle/libs.versions.toml` and the Gradle wrapper only; no source change:

| | from | to |
| --- | --- | --- |
| Gradle wrapper | 9.7.0 | 9.7.1 |
| AGP | 9.3.1 | 9.4.1 |
| Kotlin / Compose compiler | 2.4.10 | 2.4.20 |
| Compose BOM | 2026.06.01 | 2026.09.00 |
| core-ktx | 1.19.0 | 1.19.1 |
| Sentry | 8.53.0 | 8.58.0 |
| navigation-compose | 2.8.5 | 2.10.2 |

The wrapper files (`gradle-wrapper.jar`, `gradle-wrapper.properties`, `gradlew`) were taken
byte-for-byte from EverydayHero's 9.7.1 wrapper, so all nine projects now carry the identical
scripts. `gradle-daemon-jvm.properties` (JDK 25 via foojay), `gradle.properties`,
`settings.gradle.kts`, `compileSdk`/`targetSdk` 37 and `minSdk` are untouched.

**Deliberately left alone:** every version key that is *not* part of the shared tree — the vault and container stack (cryptolib 2.2.2, cryptofs 2.10.0, zip4j, kage, PGPainless, Bouncy Castle 1.86), Ktor 3.0.3, OkHttp 4.12.0, WorkManager 2.11.2, biometric, fragment, exifinterface, zxcvbn and slf4j.
They are this project's own dependencies, they were not what the sweep was about, and the
`NewerVersionAvailable` rows lint still reports for them are a separate decision.

**The AGP catch.** AGP 9.4.1 is past what Android Studio 2026.1.3 can sync: it caps at the 9.3.x
line and refuses the project with *"The project is using an incompatible version (AGP 9.4.1) of
the Android Gradle plugin"*. The owner's decision on 2026-09-24 was to keep 9.4.1 and update
Studio rather than walk the plugin back. Until Studio is updated **this project builds from the
command line only** — and note that the CLI never reads
`android.studio.latest.known.compatible.agp.version`, so a green `assembleDebug` proves nothing
about the IDE. Written into `CLAUDE.md` as an invariant so a later session does not "fix" it.

**Verified** (`--console=plain`, clean rebuild — the toolchain bump invalidated every cached
task): both flavors built and tested: `:app:assemblePlayDebug`, `:app:assembleFossDebug`, `:app:testPlayDebugUnitTest` (55), `:app:testFossDebugUnitTest` (56), `:app:compilePlayDebugAndroidTestKotlin` and `:shared:jvmTest` (47) all pass. `:app:lintPlayDebug` **0 errors, 10 warnings** — 18 before the bump. What is left is 7 `NewerVersionAvailable` on the libraries above, 2 `GradleDependency`, 1 `UnusedResources`. The toolchain line in `BUILD_BRIEF.md` was updated to match.
