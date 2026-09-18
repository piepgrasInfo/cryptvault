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

**Open before first release** (see also `RELEASE_CHECKLIST.md` once generated by the
`app-generate-checklist` skill):

- [ ] **Play closed-testing gate — start now.** Personal account created on/after 13 Nov 2023:
      12 testers opted in for 14 continuous days before production. Two weeks of calendar time.
- [ ] Provider registrations with lead time: docs/PROVIDER_SETUP.md (Dropbox, Entra, GCP).
- [ ] Legal documents in `legal/` (`app-legal-structure-generator` skill, Phase 6), hosted at
      `https://piepgras.info/legal/crypt-vault/…`.
- [ ] Launcher icon set and 512×512 Play listing icon (`app-launcher-icon-creator` skill;
      concept: a cog as a heavy vault door, slightly ajar, keyhole in the centre).
- [ ] 1024×500 feature graphic and screenshots.
- [ ] Store listing text under `fastlane/metadata/android/`.
- [ ] Release keystore, `keystore.properties` (never committed), and a signed `.aab`.
- [ ] Bugsink DSN in `CrashReporting.kt`.
- [ ] Play Console declarations: Data safety, content rating, target audience, ads,
      account deletion. None of these can be inferred from this repository.
- [ ] Every `[OPEN]` in BUILD_BRIEF.md §13, above all the display name (CryptVault vs Crypt Vault)
      and the public source mirror the GPL requires before the first external tester.
