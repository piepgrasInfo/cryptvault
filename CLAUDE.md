# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

**CryptVault** — An encrypted vault for the files and notes that matter, in the open Cryptomator
format: open them in any app, back them up to your own cloud, mail them in a container only the
recipient can open. (Productivity / tools (security)). **GPL-3.0-or-later**; see `LICENSE` and
`NOTICE` — every dependency must be GPL-compatible and every ported file carries its origin.

Kotlin, Jetpack Compose, package `info.piepgras.cryptvault`, minSdk 26 / targetSdk 37.
Two Gradle modules:

- **`:app`** — the Android app. The vault format adapter lives in `vault/` (`CryptomatorVault`,
  `VaultStorage`, `VaultConfigToken`) on top of `org.cryptomator:cryptolib`.
- **`:shared`** — platform-free Kotlin (KMP, JVM + Android targets, no Android imports): rules
  with unit tests that run in `./gradlew :shared:jvmTest`. Today: the recovery key
  (`recovery/`, ported from Cryptomator desktop, GPL-3.0). Everything with a decision in it —
  the backup planner, retention, passphrase policy, container sniffing, slug and name rules —
  goes here (BUILD_BRIEF.md §8).

`BUILD_BRIEF.md` is the design target (2026-09-17): what the app becomes, phase by phase, and
which decisions are settled. **Phase 0 (foundation) is done.** This file describes what exists;
the brief describes what comes next and wins where the two disagree about the target. Its
companions: `docs/VAULT_LAYOUT.md` (normative on-disk and remote layout), `docs/THREAT_MODEL.md`
(requirements checked at each phase gate), `docs/PROVIDER_SETUP.md` (developer registrations).

`handoff.md` is the running project log: each work session appends a dated
`### [YYYY-MM-DD] …` section describing what changed, test results, and the commit hash.
Keep it updated when making substantive changes.

## Build and test

```bash
./gradlew :app:compileDebugKotlin --console=plain
./gradlew :app:testDebugUnitTest --console=plain
./gradlew :app:lintDebug --console=plain
./gradlew :app:assembleDebug --console=plain
./gradlew :shared:jvmTest --console=plain
```

Those five are the minimum verification for any change (CI runs all but the first on push/PR).
`./gradlew :app:assembleRelease` additionally proves the R8 rules in `app/proguard-rules.pro`
(unsigned when `keystore.properties` is absent); run it whenever a dependency is added.
A single test class or method — method names are backtick-quoted sentences:

```bash
./gradlew :app:testDebugUnitTest --tests "info.piepgras.cryptvault.vault.CryptomatorVaultTest"
./gradlew :shared:jvmTest --tests "info.piepgras.cryptvault.recovery.RecoveryKeyTest"
```

The unit tests need a **Java 25** test JVM (the Gradle daemon JVM from
`gradle/gradle-daemon-jvm.properties`): the test-only dependency `org.cryptomator:cryptofs` —
Cryptomator's own file-system layer, which opens the vaults this app writes — is compiled for
Java 25. `CryptofsInteropTest` is the automated half of the interop promise.

Instrumented tests (`app/src/androidTest`) need an emulator. Sibling projects' AVDs share this
host, so `ANDROID_SERIAL` is mandatory or the task fans out onto them:

```bash
ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest --console=plain
```

## Architecture

The scaffold plus the Phase 0 foundation. Change this section as reality arrives, and do not
let it drift:

- **`MainActivity.kt`** — the single Activity. Navigation Compose is in the dependencies (the
  app clears six screens easily); until the screens exist it shows the scaffold placeholder.
  It will own the startup gate order: legal disclaimer → crash-reporting opt-in → app. There is
  no database, so no upgrade gate; the vault registry carries a `schema` field and migrates in
  place. Runtime permissions are never a gate: each is requested at the point of use.
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
| `POST_NOTIFICATIONS` | backup/restore progress and failure notices (Phase 4) | Request lazily at the first manual backup, never at startup. |

Still to come, each with its four places in the commit that adds it: `USE_BIOMETRIC` (Phase 2),
`FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC` and `RUN_USER_INITIATED_JOBS` (Phase 4),
`MANAGE_DOCUMENTS` on the provider element (Phase 3). Never: storage, `READ_MEDIA_*`,
`MANAGE_EXTERNAL_STORAGE`, `CAMERA`, `QUERY_ALL_PACKAGES`, `SCHEDULE_EXACT_ALARM`.

Re-read the **merged** manifest after any dependency change (`app/build/intermediates/merged_manifest/`).

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
- **Keep lint at 0 errors** and hold the warning count where it is: **15 warnings** after
  Phase 0 — 14 version-currency notices (`AndroidGradlePluginVersion`, `GradleDependency`,
  `NewerVersionAvailable`, drifting upward on their own as libraries move) and one
  `UnusedResources` on `R.color.brand`, which exists for the launcher icon and store assets.
  Anything else in the report is a real finding.
- When a task touches code, `git add`, commit, and push to `origin/master` at the end — do not
  leave changes uncommitted.
