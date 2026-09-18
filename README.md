# CryptVault

An Android app: an encrypted vault for the files and notes that matter, in the open Cryptomator
format. Open items in any app, back the vault up to your own cloud (Dropbox, OneDrive, Google
Drive, WebDAV or any folder), and mail small items in an encrypted container the recipient opens
with free tools. GPL-3.0-or-later.

This repository is at the **design stage**. There is no app code yet. What is here:

- `BUILD_BRIEF.md` — the design target a building agent starts from: product, vault format,
  security, backup, mail, architecture, screens, legal consequences, the phased build plan, and
  the open decisions. Decisions in it are the developer's; read §0 before anything else.
- `docs/VAULT_LAYOUT.md` — the on-disk and remote layout (normative).
- `docs/THREAT_MODEL.md` — what is protected against whom, and the requirements checked at each
  phase gate.
- `docs/PROVIDER_SETUP.md` — the registrations and other lead-time items the developer does by hand.
- `docs/research/` — the research reports the brief rests on, with sources.
- `spec.json` — the input for the house scaffolder.

## Building the project from this brief

1. Repoint `origin` to `git@192.168.5.5:/foundation/backup/git/cryptvault` and prove it with
   `git ls-remote` (see `BUILD_BRIEF.md` §12, Phase 0).
2. Run the `app-generate-android-project` skill with the committed `spec.json`; it produces the
   Gradle project, `CLAUDE.md`, `handoff.md`, the legal and store scaffolding.
3. Follow the phases in `BUILD_BRIEF.md` §12.
