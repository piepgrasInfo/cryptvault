# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

**Crypt Vault** (display name, decided 2026-09-19; the package, the repository, code identifiers
and the `CryptVault/` folder on backup targets keep the one-word form) — An encrypted vault for
the files and notes that matter, in the open Cryptomator
format: open them in any app, back them up to your own cloud, mail them in a container only the
recipient can open. (Productivity / tools (security)). **GPL-3.0-or-later**; see `LICENSE` and
`NOTICE` — every dependency must be GPL-compatible and every ported file carries its origin.

Kotlin, Jetpack Compose, package `info.piepgras.cryptvault`, minSdk 26 / targetSdk 37.
Shipped as two product flavors of one codebase: **`play`** for Google Play and **`foss`**
(`info.piepgras.cryptvault.foss`) for F-Droid, GitHub Releases and piepgras.info — see
**Distributions** under Architecture. Two Gradle modules:

- **`:app`** — the Android app. The vault format adapter lives in `vault/` (`CryptomatorVault`,
  `VaultStorage`, `VaultConfigToken`) on top of `org.cryptomator:cryptolib`.
- **`:shared`** — platform-free Kotlin (KMP, JVM + Android targets, no Android imports): rules
  with unit tests that run in `./gradlew :shared:jvmTest`. Today: the recovery key
  (`recovery/`, ported from Cryptomator desktop, GPL-3.0). Everything with a decision in it —
  the backup planner, retention, passphrase policy, container sniffing, slug and name rules —
  goes here (BUILD_BRIEF.md §8).

`BUILD_BRIEF.md` is the design target (2026-09-17): what the app becomes, phase by phase, and
which decisions are settled. **Phases 0 (foundation), 1 (vault core), 2 (security), 3
(DocumentsProvider), 4 (backup and restore — WebDAV and folder targets; Dropbox, OneDrive
and Google Drive await their developer registrations) and 5 (mail containers; the link
fallback awaits a provider) are done; Phase 6 (release preparation) is next.** This file describes what exists;
the brief describes what comes next and wins where the two disagree about the target. Its
companions: `docs/VAULT_LAYOUT.md` (normative on-disk and remote layout), `docs/THREAT_MODEL.md`
(requirements checked at each phase gate), `docs/PROVIDER_SETUP.md` (developer registrations).

`handoff.md` is the running project log: each work session appends a dated
`### [YYYY-MM-DD] …` section describing what changed, test results, and the commit hash.
Keep it updated when making substantive changes.

## Build and test

`:app` has two product flavors on the `dist` dimension — `play` and `foss` (see **Distributions**
below) — so every variant task is flavoured. There is no `testDebugUnitTest` and no `lintDebug`.

```bash
./gradlew :app:compilePlayDebugKotlin :app:compileFossDebugKotlin --console=plain
./gradlew :app:test --console=plain                              # both flavors' unit tests
./gradlew :app:lintPlayDebug :app:lintFossDebug --console=plain
./gradlew :app:assembleDebug --console=plain                     # both flavors
./gradlew :shared:jvmTest --console=plain
```

Those five are the minimum verification for any change (CI runs all but the first on push/PR).
`./gradlew :app:assembleRelease` additionally proves the R8 rules in `app/proguard-rules.pro` for
both flavors (unsigned when `keystore.properties` is absent); run it whenever a dependency is
added. A single test class or method needs a flavoured task — method names are backtick-quoted
sentences:

```bash
./gradlew :app:testPlayDebugUnitTest --tests "info.piepgras.cryptvault.vault.CryptomatorVaultTest"
./gradlew :app:testFossDebugUnitTest --tests "info.piepgras.cryptvault.dist.FossDistributionTest"
./gradlew :shared:jvmTest --tests "info.piepgras.cryptvault.recovery.RecoveryKeyTest"
```

The unit tests need a **Java 25** test JVM (the Gradle daemon JVM from
`gradle/gradle-daemon-jvm.properties`): the test-only dependency `org.cryptomator:cryptofs` —
Cryptomator's own file-system layer, which opens the vaults this app writes — is compiled for
Java 25. `CryptofsInteropTest` is the automated half of the interop promise.

Instrumented tests (`app/src/androidTest`) need an emulator. Sibling projects' AVDs share this
host, so `ANDROID_SERIAL` is mandatory or the task fans out onto them:

```bash
ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedPlayDebugAndroidTest --console=plain
ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedFossDebugAndroidTest --console=plain
```

The two flavors have different applicationIds, so they install side by side — and the `foss`
one's provider authorities carry the suffix (`info.piepgras.cryptvault.foss.documents`), which
the `adb shell content` probes below have to use when that is the flavor under test.

## Architecture

The scaffold plus the Phase 0 foundation. Change this section as reality arrives, and do not
let it drift:

- **Distributions** (`dist/`): one flavor dimension, `dist`, with **`play`** (Google Play) and
  **`foss`** (F-Droid, GitHub Releases, piepgras.info). `foss` carries
  `applicationIdSuffix = ".foss"`, so the two install side by side — necessary, because Play App
  Signing re-signs the Play copy with Google's key and a shared id could never be replaced by the
  other build, making a channel switch an uninstall and an uninstall a loss of every private
  vault. There is **no Google Play services dependency in either flavor today**; the flavor is
  the seam that keeps `foss` that way when Google Drive arrives (BUILD_BRIEF.md §6.1). Same code,
  same permissions, same legal documents, same crash-reporting opt-in; the only difference is
  which backup targets exist. `Distribution` (in `main`) reads `BuildConfig.DISTRIBUTION` and
  `PLAY_SERVICES_ALLOWED` and takes its target list from `FlavorTargets`, of which `src/play/`
  and `src/foss/` hold one copy each — so a target the build must not offer is not compiled into
  it rather than hidden by an `if`. `BackupScreen` and `RestoreScreen` build their "add target"
  buttons from that list. **Anything that depends on Google goes in `src/play/`** — the library
  in `playImplementation`, the manifest entry in `app/src/play/AndroidManifest.xml` — never in
  `main`. `tools/build_foss_apk.sh` builds, names and hashes the APK the three channels publish.
- **`MainActivity.kt`** — the single Activity (a `FragmentActivity`, because
  `androidx.biometric` needs one; `androidx.fragment` is pinned to 1.8.9 since the 1.2.5 that
  biometric drags in rejects Compose's activity-result request codes). Navigation Compose with
  `@Serializable` routes (`ui/Routes.kt`); the browser pushes one route per folder so Back goes
  up. `launchMode="singleTask"`, so "Share to CryptVault" reaches the running instance through
  `onNewIntent` and waits in `MainActivity.pendingImport` until a vault is unlocked. Startup
  gates: legal disclaimer → crash-reporting opt-in → app. No database, no upgrade gate.
  Runtime permissions are never a gate: each is requested at the point of use.
- **Manual construction**: `CryptVaultApp.Container` holds the registry, repository,
  thumbnails, open-with, lock manager, clipboard and biometric wrap; screens get it through
  `CryptVaultApp.container(context)` and build their ViewModels with `viewModel { … }`.
- **Vaults** (`vault/`): `VaultRegistry` (`vaults.json`, atomic writes), `VaultRepository` (the
  one door: create private or in a picked folder, add existing, unlock/lock, rename, auto-lock,
  change password, recovery key, reset, delete, export the ciphertext folder),
  `SafVaultStorage` (DocumentsContract with cached ids, self-test on selection).
- **Items** (`items/`): `OpenVault` is an unlocked vault — the manifest index over the cleartext
  tree, reconciled on every unlock, every mutation committing the manifest atomically.
  `ImportSources` turns picker/share/camera URIs into name, type, size and a stream;
  `AndroidThumbnails` makes JPEGs for images (EXIF-aware), videos (a frame through
  `MediaDataSource`) and PDFs (first page through a proxy descriptor). Imports stream straight
  from the source into the vault (no plaintext temp file); the vault writes to a `.tmp` name and
  renames on close, and sweeps stray temps on unlock, so a killed import leaves nothing behind.
- **Open-with** (`openwith/OpenWith`): decrypt into `cache/open/<random>/<name>`, hand a
  FileProvider URI with explicit grants and ClipData to the chosen app, notice on resume whether
  a writable hand-off changed and write it back, wipe on lock, process start and age. Never gate
  on `resolveActivity()` (package visibility makes it lie); catch the launch failure instead.
- **DocumentsProvider** (`provider/`): `VaultDocumentsProvider` (authority
  `<applicationId>.documents`, guarded by `MANAGE_DOCUMENTS`, so only the system picker and the
  Files app can call it) exposes one root per **unlocked** vault; document ids are
  `<vaultId>:<cleartext path>` (`DocumentId`, tested). Reads are seekable proxy descriptors
  (`ProxyDescriptors.readOnly`: `openProxyFileDescriptor`, decrypting chunk by chunk on one
  dedicated thread — a video player seeks, a PDF viewer jumps to the last page); writes are
  whole-file through a pipe into `OpenVault.replaceContent`; create, delete, rename, move and
  search map onto `OpenVault`. `ProxyDescriptors` tracks every open reader per vault and
  `LockManager` closes them on lock, so a descriptor another app still holds gets EIO on its
  next read, and the roots change notification empties the pickers (docs/THREAT_MODEL.md S8).
  An empty "Copy to…"/"Save to" picker therefore means "locked", not a DocumentsUI filter.
- **Backup and restore** (`backup/`, rules in `:shared`'s `backup/`): a run mirrors the
  vault's **ciphertext** (`d/**` plus the two meta files) into `<target>/CryptVault/<slug>-<id8>/`
  and commits a snapshot manifest (docs/VAULT_LAYOUT.md §6). `LocalScan` lists and hashes with
  the mtime-keyed `HashCache`; the pure `plan()` diffs against the last snapshot into
  content-addressed idempotent steps (retire to `versions/`, revive, upload, meta);
  `BackupRunner` executes them with a journal (`BackupIndex`: state, journal, local manifest
  copies under `index/<vaultId>/`), hashes while uploading, writes `latest` conditionally on the
  ETag read at the start, then `Retention.gc`. Snapshot manifests are AES-GCM under an
  HKDF-derived key kept Keystore-wrapped (`SnapshotKeyStore`), so a **locked** vault backs up.
  Targets: `WebDavStore` (OkHttp) and `StorageRemoteStore` over a SAF tree; `BackupTargetStore`
  keeps credentials in `secrets/providers.enc` under `AppKeystore`. `BackupService` owns
  configuration, runs, the 3-minute debounced job after manifest changes (`CryptVaultApp`
  watches every open vault's manifest generation) and the notifications; `BackupWorker` is the
  WorkManager entry (manual runs hold a `dataSync` foreground service). `RestoreService` +
  `RestoreRunner` build a **new** private vault from a remote folder: meta files, password check,
  snapshot list, hash-verified downloads; "adopt as writer" re-points backup at that folder
  with a take-over. Dropbox, OneDrive and Google Drive are not built yet (registrations pending).
- **Mail containers** (`share/`, rules in `:shared`'s `share/`): "Share" on items opens
  `ShareContainerDialog` — ZIP AES-256 (default, remembered), age or OpenPGP; a generated
  six-word passphrase (or a typed one that `SharePolicy` accepts: zxcvbn ≥ 3, ≥ 4 for ZIP
  unless six list words), Copy through `SensitiveClipboard`, the 15 MB warning, a one-time
  deliverability notice, "As is" for a plain share. `ShareService` writes the container into
  `cache/share/` (`ContainerWriter`: zip4j AE-2 under one folder; kage scrypt; PGPainless v4
  SKESK + SEIPDv1 + AES-256 with SHA-256 S2K at the maximum count through `CryptVaultPgp`;
  several files ride in an inner STORE zip for age/PGP) and hands it to the share sheet with
  subject and body; the passphrase never enters the mail. Receiving: `MainActivity` accepts
  `ACTION_VIEW` for zip / pgp-encrypted / vnd.age / octet-stream, `ReceiveScreen` copies the
  attachment to `cache/import/`, `ContainerSniff` decides by magic bytes, `ContainerReader`
  decrypts (an inner zip is unpacked, entries flattened, zip-slip impossible), the files are
  imported into an unlocked vault with `- note.txt` becoming the item's note. The manifest
  sets `android:largeHeap="true"` because age's default scrypt work factor (18) needs 256 MB,
  above the 192 MB standard growth limit — without it, opening an age file killed the process;
  a remaining `OutOfMemoryError` becomes `ContainerTooLargeException` and one line on screen.
  Not built: the link fallback (needs a Dropbox/OneDrive target or a reachable Nextcloud).
- **Locking and unlocking** (`unlock/`): `LockManager` — per-vault background timeout,
  screen-off locks all, every lock wipes that vault's hand-offs. `BiometricWrap` holds the
  Keystore-wrapped masterkey copy (AES-GCM, auth window 300 s, BIOMETRIC_STRONG or, on API 30+,
  the device credential; invalidated on enrolment change); `BiometricUnlock` shows the system
  prompt from the `FragmentActivity`; `VaultRepository.unlockWithRawKey` opens the vault with
  the unwrapped key. `PasswordPolicy` is zxcvbn score ≥ 3 plus a 10-character floor with
  EFF-word suggestions; `prefs/UnlockPrefs` persists the per-vault failure count so the
  `:shared` `Backoff` schedule (2 s doubling, 5 min cap, never a wipe) survives restarts.
  The recovery key: `CreateVaultViewModel` computes the 44 words right after creation and parks
  them in `Container.recoveryKeyToShow`; `RecoveryKeyScreen` shows them once (hidden until
  revealed, no copy, "written down" confirmation) and clears them; vault settings re-show them
  after a password check; `RecoverScreen` resets the password from the words (validated per
  word against the list, checksum, then `CryptomatorVault.resetPassword`, which refuses a key
  that does not sign this vault's config).
- **Screen hygiene** (`security/`): `SecureWindow` sets FLAG_SECURE, hides overlays and blanks
  recents; in **debug builds only**, `cache/allow-screenshots` (create it with
  `adb shell run-as info.piepgras.cryptvault touch cache/allow-screenshots`) turns FLAG_SECURE
  off so emulator screenshots work. `SensitiveClipboard` flags clips sensitive and clears them
  after 60 s.
- **The launcher icon** (`art/ic_launcher.svg`): a cog read as a vault door on a blue gradient,
  one 108x108 canvas carrying both adaptive layers as `<g id="background">` and
  `<g id="foreground">`, the disc at Android's 66dp keyline circle. It is the **source of truth**
  — `app/src/main/res/mipmap-*`, the three `art/` Play assets and the `fastlane/…/images/icon.png`
  copies are all generated from it by the `app-launcher-icon-creator` skill; edit the SVG, never
  them. Three things the regeneration has to carry, all recorded in handoff.md 2026-09-20:
  `--keep-monochrome`, because `art/ic_launcher_monochrome.svg` is hand-made (the mechanical
  flattening turns the door into a plain disc) and closes two anti-aliasing seams; `--no-round`
  plus merging `mipmap-anydpi-v26` back into `mipmap-anydpi`, because minSdk 26 makes a round
  icon and a `-v26` qualifier dead weight that lint counts; and
  `python3 scripts/shape_legacy_launcher_icons.py` afterwards, which gives the legacy PNGs the
  squircle silhouette. Those legacy PNGs stay even though no launcher here uses them: F-Droid and
  other store tooling pull a raster icon out of the APK. There is no `android:roundIcon` in the
  manifest, for the same minSdk reason.
- **Emulator lessons** (this host): AVDs live in `/home/martin/.android/avd`; run the emulator
  with `ANDROID_AVD_HOME` pointing there and a per-project ini (`Medium_Phone_5560-CryptVault.ini`
  → `CryptVault.avd`, port 5560). The Play image needs `-partition-size 16384` for the 2 GB
  tests and accepts adb only after the "Allow USB debugging" prompt, which the emulator console's
  `event mouse` can tap without a window. Gboard's first-run stylus tutorial swallows `input
  text` until cancelled; Escape does not hide the keyboard, Back does. The shell holds
  `MANAGE_DOCUMENTS`, so the provider can be probed without a UI:
  `adb shell content query --uri content://info.piepgras.cryptvault.documents/root --projection root_id:title`
  says which vaults are unlocked, `…/document/<vaultId>%3A/children` lists the root and
  `content read --uri …/document/<vaultId>%3A<path>` streams a file; `input keyevent 26`
  (screen off) locks every vault. Shell-granted URIs (`am start --grant-read-uri-permission`)
  are not honoured, so share-in cannot be driven from the shell. The `sdk_gphone16k` image is
  **Android 17 (API 37)** despite its name: apps targeting 37 cannot reach local addresses —
  `10.0.2.2` included — without `ACCESS_LOCAL_NETWORK`; a connect just times out, and `run-as
  <pkg> nc` reproduces it without the app.
- **One ViewModel per coherent area of state**, exposing `StateFlow` collected with
  `collectAsState()`. Repositories are reached only from a ViewModel.
- **JVM-testable logic belongs in a plain file, not in a Composable** — anything with rules
  in it goes in its own file with unit tests; if it has no Android dependency, in `:shared`.
- **Preferences** are small `object` helpers over `SharedPreferences`, one per concern.
- **The vault format** (`app/…/vault/`). A vault is a Cryptomator format 8 directory, byte for
  byte (docs/VAULT_LAYOUT.md §2). `VaultStorage` is the byte layer (`PathVaultStorage` for the
  app-private directory and tests; a SAF implementation comes with Phase 1); `CryptomatorVault`
  owns the layout — hashed directory ids under `d/`, AES-SIV names with the parent directory id
  as associated data, `dir.c9r` markers, `.c9s` shortening above 220 characters, `dirid.c9r`
  backups — and hands every cryptographic operation to cryptolib. `VaultConfigToken` writes and
  verifies the `vault.cryptomator` JWT (HS256 over the 64 raw masterkey bytes). Two things
  learned the hard way, both covered by tests: `Masterkey.getEncoded()` returns the **live**
  key array (clone before zeroing), and the `CryptorProvider` is instantiated directly rather
  than through `ServiceLoader`, which R8 would have to be trusted with.
- **Passwords are `CharArray`s** wrapped in `CharBuffer` for cryptolib; no `String` of a
  password or key is ever created, logged or put in a crash breadcrumb (docs/THREAT_MODEL.md S2).
- **Recovery key** (`shared/…/recovery/`): the 64 masterkey bytes plus the two low-order bytes
  of their CRC-32, as 44 words from Cryptomator's 4096-word list. `RecoveryKeyTest` pins the
  encoding to vectors produced by running Cryptomator's original Java code, so a key written
  down from either app resets the password in the other.
- **Provider identifiers** (Dropbox app key, MSAL client id) come from the gitignored
  `providers.properties` into `BuildConfig`; `providers.properties.example` lists the keys and
  docs/PROVIDER_SETUP.md says where they come from. Empty means the target hides itself.
- **No device backup.** `allowBackup="false"` plus exclude-everything `data_extraction_rules.xml`:
  vaults are only readable with their password, the Keystore wrap never transfers, and the
  registry would point at vaults that are not there. The app's own backup (BUILD_BRIEF.md §6)
  is the way a vault survives a phone.
- **Debug builds** log cryptolib through `slf4j-simple` (stderr, visible in logcat); release
  builds bind `slf4j-nop`.

## Permissions and data

The app's whole data footprint, because four things have to agree at all times — the
manifest, `legal/privacy-policy.md` and `legal/permissions.md`, the in-app Permissions screen,
and the Play Console declarations. Change one, change all four in the same commit. The target
is BUILD_BRIEF.md §8; what is declared today:

| Declared | For | Notes |
| --- | --- | --- |
| `INTERNET`, `ACCESS_NETWORK_STATE` | opt-in crash reporting today; backup providers and the mail link fallback from Phase 4 | Crash reporting defaults to off and the DSN is set in code, so the SDK cannot start before the user answers. No analytics. |
| `POST_NOTIFICATIONS` | backup/restore progress and failure notices | Requested lazily at the first "Back up now", never at startup; automatic runs post nothing but the three-failures alert. |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC` | a backup or restore the user started keeps running with a progress notification (WorkManager `setForeground`, type `dataSync`) | The `SystemForegroundService` element in our manifest merges the `dataSync` type into WorkManager's. Automatic runs after changes are plain background work. The brief's user-initiated data-transfer job for API 34+ was **not** built (one code path instead of two); Android 15's 6-hour dataSync budget stops a run, which the next trigger resumes. |
| `HIDE_OVERLAY_WINDOWS` | hiding other apps' overlays above the unlock screen | Normal permission, granted at install; `setHideOverlayWindows` throws a SecurityException without it. |
| `USE_BIOMETRIC`, `USE_FINGERPRINT` | the system biometric prompt for vaults with biometric unlock switched on | Normal permissions, merged in by `androidx.biometric` (check the merged manifest, not ours). The app stores no biometric data; the Keystore releases the wrapped key only after the system has authenticated the user. |
| `ACCESS_LOCAL_NETWORK` | a WebDAV server on the user's own network (home Nextcloud, NAS) on Android 17+, which blocks apps targeting API 37 from local addresses otherwise | Runtime permission that exists from API 37; `LocalNetwork` asks for it in the WebDAV form only when the host is a private, link-local, loopback or unqualified address, and a run refuses with a clear message when it is missing. The emulator's host `10.0.2.2` counts as local, which is how this was found. |
| `MANAGE_DOCUMENTS` (on the provider element only) | guarding `VaultDocumentsProvider` so that only the system document picker and the Files app can call it | Signature-level permission the app never holds itself: the standard guard every DocumentsProvider must declare; nothing to request, nothing to show the user beyond the Permissions screen's "Documents" line. |

**Both distributions declare exactly this set** — the `foss` build adds nothing and drops
nothing, which is what lets one set of legal documents and one Permissions screen cover both.
Nothing else is planned; `RUN_USER_INITIATED_JOBS` from the brief is not needed with the single
WorkManager path. Never: storage, `READ_MEDIA_*`,
`MANAGE_EXTERNAL_STORAGE`, `CAMERA`, `QUERY_ALL_PACKAGES`, `SCHEDULE_EXACT_ALARM`.

Re-read the **merged** manifest after any dependency change
(`app/build/intermediates/merged_manifest/<variant>/`) — **both flavors**, and check that the
`foss` one still has no `com.google.android.gms` entry and no permission the `play` one lacks:

```bash
diff <(sed 's/\.foss//g' app/build/intermediates/merged_manifest/fossRelease/*/AndroidManifest.xml) \
     app/build/intermediates/merged_manifest/playRelease/*/AndroidManifest.xml
```

## Conventions

- Every string must exist in `values`, `values-de`, `values-ru`.
- **Legal documents live in `legal/`** — each as `.md` plus a generated `.html`
  (`python3 scripts/render_legal_docs.py`, which also copies the Markdown into
  `app/src/main/assets/legal/` for the in-app screens). They describe what the app actually
  does, so any change to permissions, bundled SDKs or data flow has to update them in the
  same commit; the in-app Permissions and About screens must agree with them word for word.
  They are produced in Phase 6 by the `app-legal-structure-generator` skill with BUILD_BRIEF.md
  §11 as input.
- **Licences.** New dependency → `libs.versions.toml` with a comment saying why and its licence,
  a line in `NOTICE`, and (from Phase 6) the About screen. GPL-compatible only. Code ported from
  Cryptomator gets a file-level attribution comment with the origin file and commit.
- **Dialog content must scroll.** A dialog that overflows hides its own buttons.
- Unit tests use hand-written `Fake*` implementations plus `MainDispatcherRule` — no mocking
  library, no Robolectric. cryptolib runs on the plain JVM, so vault round trips are ordinary
  unit tests on a temp directory.
- **Keep lint at 0 errors** and hold the warning count where it is: **18 warnings in each
  flavor** (`lintPlayDebug` and `lintFossDebug` report the same 18) — 17 version-currency notices (`AndroidGradlePluginVersion`, `GradleDependency`,
  `NewerVersionAvailable`, drifting upward on their own as libraries move; two of them are
  OkHttp 4.12 → 5.x, kept at 4.12 because that is what Ktor 3.0.3 pulls in) and one
  `UnusedResources` on `R.color.brand` (`#1E3A8A`), which exists for store assets. The launcher
  icon no longer uses it — it is `#127EFD`/`#03256B` — so the token is still awaiting the
  developer's word on whether it follows the icon (handoff.md 2026-09-20).
  Anything else in the report is a real finding. Categories cleared on the way and not to be
  reintroduced: `PluralsCandidate` (use `<plurals>`), `LocalContextGetResourceValueCall` (use
  `LocalResources.current`), `UseKtx`, `NewApi`.
- When a task touches code, `git add`, commit, and push to `origin/master` at the end — do not
  leave changes uncommitted. Commits are authored as the developer (the repository's local git
  identity, set 2026-09-19); an agent's commits add the `Co-Authored-By` trailer. Then run
  `tools/sync_github_mirror.sh`, which fast-forwards the
  public clone in `github/` (gitignored; the developer pushes it to GitHub by hand) so the
  published source never lags the house remote.
