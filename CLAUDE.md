# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

**CryptVault** — An encrypted vault for the files and notes that matter, in the open Cryptomator format: open them in any app, back them up to your own cloud, mail them in a container only the recipient can open. (Productivity / tools (security))

Kotlin, Jetpack Compose, package `info.piepgras.cryptvault`, minSdk 26 / targetSdk 37.
Two Gradle modules: `:app` (Android/Compose UI) and `:shared` (platform-free Kotlin
Multiplatform rules/engine code, JVM-testable without an emulator — prefer putting new
logic there).

`handoff.md` is the running project log: each work session appends a dated
`### [YYYY-MM-DD] …` section describing what changed, test results, and the commit hash.
Keep it updated when making substantive changes.

## Build and test

```bash
./gradlew :app:compileDebugKotlin --console=plain
./gradlew :app:testDebugUnitTest --console=plain
./gradlew :app:lintDebug --console=plain
./gradlew :app:assembleDebug --console=plain
```

Those four are the minimum verification for any change (CI runs the last three on push/PR).
A single test class or method — method names are backtick-quoted sentences:

```bash
./gradlew :app:testDebugUnitTest --tests "info.piepgras.cryptvault.SomeTest"
```

Instrumented tests (`app/src/androidTest`) need an emulator. Sibling projects' AVDs share this
host, so `ANDROID_SERIAL` is mandatory or the task fans out onto them:

```bash
ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest --console=plain
```

## Architecture

Scaffolded, not yet built. The intended shape — change this section as reality arrives, and
do not let it drift:

- **`MainActivity.kt`** — the single Activity and the whole navigation graph, hoisted as a
  `Screen` sealed interface plus a `when` block and a matching `BackHandler`. No navigation
  library until there is a reason for one. It also owns the startup gate order:
  legal disclaimer → permission requests → database upgrade notice → app.
- **One ViewModel per coherent area of state**, exposing `StateFlow` collected with
  `collectAsState()`. DAOs/repositories are reached only from a ViewModel.
- **JVM-testable logic belongs in a plain file, not in a Composable** — anything with rules
  in it (date maths, scoring, validation, formatting) goes in its own file with unit tests.
- **Preferences** are small `object` helpers over `SharedPreferences`, one per concern.

## Conventions

- Every string must exist in `values`, `values-de`, `values-ru`.
- **Legal documents live in `legal/`** — each as `.md` plus a generated `.html`
  (`python3 scripts/render_legal_docs.py`, which also copies the Markdown into
  `app/src/main/assets/legal/` for the in-app screens). They describe what the app actually
  does, so any change to permissions, bundled SDKs or data flow has to update them in the
  same commit; the in-app Permissions and About screens must agree with them word for word.
- **Dialog content must scroll.** A dialog that overflows hides its own buttons.
- Unit tests use hand-written `Fake*` implementations plus `MainDispatcherRule` — no mocking
  library, no Robolectric.
- **Keep lint at 0 errors** and hold the warning count where it is; record the count here once
  the app has real code, so a regression is visible.
- When a task touches code, `git add`, commit, and push to `origin/master` at the end — do not
  leave changes uncommitted.
