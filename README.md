# Crypt Vault

An Android app: an encrypted vault for the files and notes that matter, in the open Cryptomator
format. Open items in any app, back the vault up to your own cloud (Dropbox, OneDrive, Google
Drive, WebDAV or any folder), and mail small items in an encrypted container the recipient opens
with free tools. GPL-3.0-or-later.

The design is complete and the foundation is built (Phase 0 of the brief: the Cryptomator
format adapter with its tests, the recovery key, licences). The app's screens, backup and mail
come in the later phases. What is here:

- `BUILD_BRIEF.md` — the design target a building agent starts from: product, vault format,
  security, backup, mail, architecture, screens, legal consequences, the phased build plan, and
  the open decisions. Decisions in it are the developer's; read §0 before anything else.
- `docs/VAULT_LAYOUT.md` — the on-disk and remote layout (normative).
- `docs/THREAT_MODEL.md` — what is protected against whom, and the requirements checked at each
  phase gate.
- `docs/PROVIDER_SETUP.md` — the registrations and other lead-time items the developer does by hand.
- `docs/research/` — the research reports the brief rests on, with sources.
- `spec.json` — the input the house scaffolder generated the project from.
- `CLAUDE.md` — build commands, conventions and the architecture as it is; `handoff.md` — the
  session log with what was verified.

## Building

```bash
cp providers.properties.example providers.properties   # fill in later; empty is fine
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :shared:jvmTest
```

Needs an Android SDK in `local.properties` (`sdk.dir=…`) and a Java 25 JDK for the daemon
(downloaded automatically through `gradle/gradle-daemon-jvm.properties`).

## Licence

GPL-3.0-or-later. `NOTICE` lists the third-party components, above all Cryptomator's
`cryptolib` (AGPL-3.0) and the recovery-key code and word list ported from Cryptomator desktop
(GPL-3.0).

## Public source on GitHub

The GPL obliges source availability to everyone who receives the app. The public repository is
<https://github.com/piepgrasInfo/cryptvault>: a full clone living in `github/` inside this project folder (ignored by this
repository's git), pushed with the deploy key `../keystores/keyDeployGithup_cryptvault_`
(`core.sshCommand` in that clone points at it). `tools/sync_github_mirror.sh` creates it on first use and fast-forwards it to
the current `master` afterwards; pushing is deliberate and manual:

```bash
tools/sync_github_mirror.sh
git -C github push -u origin master
```

Run the sync after every push to the house remote so the two never drift.
