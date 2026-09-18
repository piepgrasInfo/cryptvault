# CryptVault — threat model

Status: companion to `BUILD_BRIEF.md`. It says what the app protects, against whom, with which
mechanism, and what it does not protect. Every "mechanism" row names the brief section and the
phase that delivers it, so the model can be checked against the implementation at each phase
gate and at release. `[ASSUMED]` marks author decisions not covered by the interview.

---

## 1. Assets

| Asset | Where it lives | Sensitivity |
| --- | --- | --- |
| A1 Vault contents (files, notes) | ciphertext in the vault directory; plaintext only transiently in RAM, in `cache/open/` while handed to another app, and inside the other app | highest |
| A2 Item metadata (titles, tags, notes, thumbnails, folder names) | encrypted inside the vault (`.cryptvault/`, AES-SIV names) | high |
| A3 Master password | user's memory; in RAM as `CharArray` during unlock | highest |
| A4 Masterkey (64 bytes) | RAM while unlocked; scrypt-wrapped in `masterkey.cryptomator`; Keystore-wrapped copy for biometrics | highest |
| A5 Recovery key (44 words) | shown once; wherever the user wrote it | highest |
| A6 Provider credentials (OAuth refresh tokens, WebDAV app password) | Keystore-encrypted app file; MSAL's own cache | high |
| A7 Backups | provider storage: ciphertext mirror + encrypted snapshot manifests | high (confidentiality), medium (availability) |
| A8 Mailed containers and their passphrases | mail systems; the passphrase in whatever channel the user used | high |
| A9 Vault registry (vault names, locations, backup targets) | app-private `vaults.json` | medium (existence and names of vaults) |
| A10 Crash reports (opt-in) | Bugsink instance | low; must contain no A1–A8 data |

## 2. Adversaries and scenarios

| # | Scenario | Mechanism (brief §, phase) | Residual risk |
| --- | --- | --- | --- |
| T1 | Phone lost or stolen while **locked**; attacker extracts storage | AES-256-GCM per file, AES-SIV names, scrypt-wrapped masterkey (§3, P1); biometric wrap needs hardware-backed auth (§4, P2); device file-based encryption | offline password guessing against `masterkey.cryptomator` — see T8 |
| T2 | Phone taken while **unlocked and the vault open** | auto-lock on background after the timeout (default 60 s) and on screen-off; temp-file wipe on lock; no "remember password" (§4, P2) | an attacker holding an open vault sees everything until the timeout |
| T3 | Forensic extraction with the device unlocked by coercion | out of scope for confidentiality of an unlocked device; locked vaults stay locked (T1) | none for locked vaults |
| T4 | A malicious app on the same device | app sandbox (private dirs unreadable); plaintext leaves the sandbox only by explicit user action (open-with, share, DocumentsProvider pick); `FLAG_SECURE` + recents blanking against screenshots; `HIDE_OVERLAY_WINDOWS` and `filterTouchesWhenObscured` on the unlock screen; sensitive-data accessibility semantics on password and note fields; clipboard sensitive flag + 60 s clear (§4, P2) | the app the user *chooses* to open a file with can keep and forward the plaintext; a rooted device voids the sandbox |
| T5 | The backup provider (curious employee, breach, subpoena) | zero-knowledge: ciphertext only, encrypted snapshot manifests, app-scoped folder (§6, P4) | learns vault slug, file count and sizes, cadence, app name; can delete backups (availability); can serve an old snapshot (rollback) — detected by the monotonic `seq` check, not prevented |
| T6 | The mail path (sender's and recipient's providers, gateways, a compromised mailbox) | container encryption: ZIP AES-256 / age scrypt+ChaCha20-Poly1305 / OpenPGP AES-256; passphrase never travels with the mail (§7, P5) | the attachment name and, for ZIP, inner file names and sizes are visible; delivery may be quarantined; a weak passphrase is brute-forceable offline (ZIP uses PBKDF2 with 1000 iterations) → the generator produces ≥ 6 words from the EFF long list (~77 bits) and the UI refuses shorter ZIP passphrases `[ASSUMED policy]` |
| T7 | Shoulder surfing, screen recording, screen sharing | `FLAG_SECURE` (blocks capture, blanks recents, hides from screen share); masked password; recovery key screen with tap-to-reveal per row (§4, P2) | a camera pointed at the screen |
| T8 | Offline brute force of the vault password from a stolen `masterkey.cryptomator` or backup | scrypt N=32768, r=8, p=1 (Cryptomator default, kept for interop `[ASSUMED]`); zxcvbn4j minimum score 3 at creation and change; passphrase suggestions (§4, P1/P2) | a user who insists on a score-3 password with a targeted dictionary; scrypt parameters cannot be raised without breaking Cryptomator interop |
| T9 | Online guessing on the device | exponential backoff after failed attempts (2 s doubling, cap 5 min), biometric lockout handled by the platform (§4, P2) | none beyond T8 |
| T10 | Lost password | recovery key (§3, P2); no escrow anywhere | lost password *and* lost recovery key = permanent data loss, by design and stated at creation |
| T11 | Lost or replaced phone | backup + restore with the password (§6, P4) | anything changed since the last snapshot; users without a backup target lose everything with the phone — the app nags once per week while no target is configured `[ASSUMED]` |
| T12 | Two installations writing to the same remote folder | `latest` rev/ETag precondition and the `seq` check; refuse and ask (§6, P4) | Drive has no conditional write; a write race in the same second could interleave — repaired by the next run's reconciliation |
| T13 | A second person with the vault password, or the desktop Cryptomator user | out of scope: the password *is* the authorisation | none |
| T14 | Supply chain: a compromised dependency | pinned versions in `libs.versions.toml`, licences reviewed, only libraries listed in the brief §8, Gradle dependency verification metadata `[ASSUMED]` | no reproducible-build proof in v1 |
| T15 | The developer | no server, no accounts, no analytics; crash reports are opt-in, off by default, and scrubbed (breadcrumbs carry no names, paths, titles, tags or account labels) (§11) | a crash report still reveals that the user runs CryptVault and their device model |
| T16 | Private space / work profile | the app works per profile with separate state; background backup does not run in a locked private space (platform) | none |

## 3. Explicitly out of scope

- A rooted or otherwise compromised operating system, malware with root, or a compromised
  TEE/StrongBox.
- Physical coercion of the user.
- Cryptanalysis of AES-GCM, AES-SIV, scrypt, ChaCha20-Poly1305 or HMAC-SHA-256.
- Cache-timing or power side channels on the device.
- Post-quantum threats to stored ciphertext (symmetric AES-256 is considered adequate; no
  asymmetric cryptography is used in the vault).
- Protecting the plaintext once the user has opened it in another app, shared it, or mailed it
  and the recipient decrypted it.

## 4. Security requirements checked at each phase gate

| Req. | Statement | Phase |
| --- | --- | --- |
| S1 | No plaintext of A1/A2 is ever written outside `cache/open/`, and that directory is emptied on lock, timeout and process start | P1 |
| S2 | The master password exists only as a `CharArray` that is zeroed after key derivation; no `String` of it is created; it is never logged, never in a crash breadcrumb | P1 |
| S3 | A vault created by CryptVault opens in Cryptomator desktop with the same password, and vice versa | P1 (dev check), P6 (release test) |
| S4 | With biometrics enabled, the masterkey copy is decryptable only after a successful BIOMETRIC_STRONG (or device-credential on API 30+) authentication within the last 300 s; enrolment changes invalidate it and the app falls back to the password without error dialogs beyond one line | P2 — verified 2026-09-18 on the emulator with a device PIN (no fingerprint hardware there); the enrolment-change path is unit-level only (`KeyPermanentlyInvalidatedException` → disable + one snackbar line) |
| S5 | The recovery key resets the password without any other secret; a wrong word or bad CRC is rejected before anything is written | P2 — verified on the emulator (reset with the 44 words shown at creation, then unlock with the new password); `CryptomatorVaultTest` covers the foreign-key refusal |
| S6 | Every window (activity, dialog, popup, DocumentsProvider-triggered UI) is `FLAG_SECURE` | P2 — `SecureWindow` on the Activity, `secureDialogProperties()` on the shared dialogs; debug builds can switch it off with `cache/allow-screenshots` (release builds cannot). Dialogs built with a bare `AlertDialog` in the screens still need the properties passed — an audit item for Phase 6 |
| S7 | Clipboard content placed by the app is flagged sensitive and cleared after 60 s | P2 — `SensitiveClipboard`, used by the note editor's Copy action; not yet by the passphrase flows (Phase 5) |
| S8 | The DocumentsProvider returns no roots while every vault is locked and stops serving reads the moment a vault locks (open descriptors fail, they are not kept alive) | P3 — verified 2026-09-18 on the emulator: `content query …/root` returns no rows while locked; a shell `content read` streaming a 2 GB vault file through the proxy descriptor failed with `EIO` at the screen-off lock after 163 MB; Google Photos' player went blank at the same moment |
| S9 | Backups contain no cleartext and no cleartext names; the snapshot manifest is unreadable without the vault password | P4 — the mirror is the ciphertext tree byte for byte (`BackupRunnerTest`, `WebDavEndToEndTest` compare hashes); manifests carry ciphertext paths, sizes and hashes only and are AES-GCM under an HKDF key derived from the masterkey (`SnapshotCrypto`; a wrong key is rejected). Deviation to note: the app keeps that derived key Keystore-wrapped so a locked vault can back up — an attacker with the unlocked device reads manifest metadata, never content |
| S10 | A backup run that is interrupted at any byte leaves the remote folder restorable from the previous commit point | P4 — `latest` is written last and conditionally; steps are idempotent and journaled; `BackupRunnerTest` kills a run mid-way (fake store failure injection), restores the previous snapshot byte for byte, then completes the run |
| S11 | Provider tokens are stored encrypted under a Keystore key; revoking access in the app deletes them and, where the API allows, revokes the token server-side | P4 — WebDAV app passwords and folder tree URIs live in `secrets/providers.enc` under `AppKeystore` (`cryptvault.app`, AES-GCM); "Forget" deletes the entry and switches backup off for the vaults using it. Server-side revocation applies to Dropbox only (not built yet) |
| S12 | Mailed containers use only the parameters in the brief §7; ZipCrypto is impossible to produce; a passphrase weaker than the policy is refused | P5 |
| S13 | The manifest, the permissions document, the in-app Permissions screen and the Play declarations agree | every phase, P6 |
| S14 | Opt-in crash reporting sends no file names, paths, titles, tags, notes, vault names, provider account labels or URLs; verified by a test that scrubs a synthetic event | P0 (SDK), P6 (audit) |
