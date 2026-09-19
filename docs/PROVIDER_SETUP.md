# CryptVault — provider registrations and other things with lead time

Status: companion to `BUILD_BRIEF.md` §6 and §12. Everything here is done by the developer in a
web console, not by the building agent, and most of it has to exist before Phase 4 can be tested
against real accounts. Sorted by lead time, longest first. Tick boxes are meant to be ticked in
the repository as the items are done.

Where the resulting identifiers go: client IDs and app keys are not secrets (they ship in the
APK), but they are per-fork, so they live in a gitignored `providers.properties` at the repository
root, read by `app/build.gradle.kts` into `BuildConfig`, with a committed
`providers.properties.example` listing every key with an empty value. Real secrets (none of the
flows below need a client secret on Android) never go in the repository.

---

## 1. Google Play — closed-testing gate (2 weeks minimum)

- [ ] The publishing account is the personal account that publishes Flip Cards, created on or after
      13 Nov 2023, so production access needs **12 opted-in testers for 14 continuous days**.
      Recruit the testers now; Work Time Tracker's page already asks for testers at
      `test@piepgras.info`.
- [ ] Register the package name `info.piepgras.cryptvault` and the signing key for Android
      developer verification in the Play Console (enforcement starts 30 Sep 2026 in the first
      countries).
- [ ] Decide on Play App Signing (house default: yes). The **app-signing key's SHA-1** (Play
      Console → Test and release → App integrity) is what Google and Microsoft registrations below
      need for store installs; the upload key's SHA-1 is needed for sideloaded release builds and
      the debug key's for development. Register all three where a SHA-1 is asked for.

## 2. Microsoft Entra — OneDrive (same day; publisher verification: weeks, optional)

Portal: https://entra.microsoft.com → App registrations → New registration.

- [ ] Name `CryptVault`; supported account types **"Personal Microsoft accounts and accounts in any
      organizational directory (multitenant)"**.
- [ ] Platform **Android**: package `info.piepgras.cryptvault`, signature hash = base64 of the
      SHA-1 of the signing certificate (`keytool -exportcert -alias <alias> -keystore <ks> | openssl
      sha1 -binary | openssl base64`). Add one Android platform entry per key (debug, upload,
      app-signing). The redirect URI becomes `msauth://info.piepgras.cryptvault/<hash>`.
- [ ] API permissions (delegated, Microsoft Graph): `Files.ReadWrite.AppFolder`, `offline_access`.
      Optionally `Files.ReadWrite` for M365 business tenants (best-effort; may need admin consent).
- [ ] Branding: publisher domain `piepgras.info` (verified via the `microsoft-identity-association.json`
      file on the site) so the consent screen does not say "unverified".
- [ ] Optional, only if business tenants matter: **publisher verification** (needs a Partner Center
      account with an MPN/Cloud Partner ID; weeks).
- [ ] Record: `MSAL_CLIENT_ID` in `providers.properties`; the MSAL config JSON goes to
      `app/src/main/res/raw/msal_config.json` with authority `https://login.microsoftonline.com/common`
      and the redirect URI.

## 3. Google Cloud — Google Drive (1–3 days incl. brand verification)

Console: https://console.cloud.google.com → new project `cryptvault`.

- [ ] Enable **Google Drive API**.
- [ ] OAuth consent screen: External; app name CryptVault; support e-mail `apps@piepgras.info`;
      logo (optional — uploading one triggers brand verification, a few days); home page
      `https://piepgras.info/apps/crypt-vault/`; privacy policy
      `https://piepgras.info/legal/crypt-vault/privacy-policy/`; terms
      `https://piepgras.info/legal/crypt-vault/terms-of-service/`. These pages must exist and be
      reachable before publishing the consent screen.
- [ ] Scopes: only `https://www.googleapis.com/auth/drive.appdata` (non-sensitive: no security
      assessment, no 100-user cap).
- [ ] Credentials → OAuth client ID → **Android**: package `info.piepgras.cryptvault` + SHA-1; one
      client per signing key (debug, upload, app-signing).
- [ ] Publishing status: **In production** (the non-sensitive scope needs no verification review).
- [ ] Record: nothing secret; the Android client is matched by package + SHA-1 at runtime. Keep the
      project ID in `PROVIDER_SETUP.md`'s log below.
- Constraint to remember: Google Drive needs Google Play services on the device. It is therefore
  a **`play`-flavor-only** target — the library goes in `playImplementation`, the code in
  `app/src/play/`, and `foss`'s `FlavorTargets` never lists it (`BUILD_BRIEF.md` §13). The OAuth
  Android client is registered for `info.piepgras.cryptvault` only; the `.foss` applicationId
  needs no Google registration at all, because it has nothing to authorise.
- The other registrations and the `foss` flavor: Dropbox is keyed to the app key, not the package,
  so one registration serves both flavors. MSAL's redirect URI is `msauth://<package>/<cert
  hash>`, so if OneDrive is ever offered in `foss` too, the Entra registration needs a **second**
  redirect URI for `info.piepgras.cryptvault.foss` and that build's signing key (and a third for
  F-Droid's key, if F-Droid signs rather than republishing ours).

## 4. Dropbox App Console (same day; production approval: 1–2 weeks when it becomes due)

Console: https://www.dropbox.com/developers/apps → Create app.

- [ ] API: Scoped access; access type **App folder**; name `CryptVault` (this becomes the folder
      `Apps/CryptVault` in the user's Dropbox; Dropbox branding rules forbid "Dropbox" in the name).
- [ ] Permissions tab: `files.metadata.read`, `files.metadata.write`, `files.content.read`,
      `files.content.write`, `sharing.write` (for the mail link fallback). Submit.
- [ ] Settings: no redirect URI needed for the Android SDK (it uses the `db-<APP_KEY>` scheme);
      note the **App key** → `DROPBOX_APP_KEY` in `providers.properties`. The app secret is not
      used (PKCE).
- [ ] The app starts in Development status. **When 50 users have linked**, Dropbox gives two weeks
      to apply for Production; the application asks for a description, screenshots of the OAuth
      flow and confirmation of branding compliance. Apply as soon as the closed test starts.

## 5. WebDAV / Nextcloud test infrastructure (an afternoon)

- [ ] A throwaway Nextcloud on the development host for Phase 4:
      `docker run -d -p 8081:80 -e SQLITE_DATABASE=nc nextcloud:latest`, create a user, create an
      **app password** (Settings → Security) and use `http://10.0.2.2:8081/remote.php/dav/files/<user>/`
      from the emulator (cleartext allowed only for that address in the debug network security
      config, as Existential Dread Runner does for its server).
- [x] Docker is not installed on the development host (2026-09-18), so Phase 4 used
      `tools/webdav_test_server.py` instead: a stdlib WebDAV server with ETags, conditional PUT,
      Range, MOVE and `--fail-every N` for interrupted runs. `python3 tools/webdav_test_server.py
      --root /tmp/dav --port 8081 --user test --password secret`, then
      `http://10.0.2.2:8081/dav/` from the emulator. `WebDavEndToEndTest` starts the same script
      as a subprocess. A real Nextcloud (chunking, TLS) remains a Phase 6 check.
- [ ] Optional second target with real TLS and chunking limits (a Hetzner Storage Box or the
      house Nextcloud, if one exists) — `[OPEN: is there a house WebDAV server to test against?]`.

## 6. Cryptomator desktop for the interop test (Phase 6, an hour)

- [ ] Install Cryptomator desktop (1.19.x at the time of writing) on the development host.
- [ ] Test matrix: (a) a vault created by CryptVault, copied off the phone with
      `adb pull` or restored from Dropbox's `Apps/CryptVault/<slug>` via the Dropbox desktop
      client, opens with the password; (b) files added, renamed and deleted on the desktop show up
      after the next unlock on the phone; (c) the recovery key made on the phone resets the password
      on the desktop; (d) desktop's vault health check tolerates the `cryptvault/` sidecar (see
      `docs/VAULT_LAYOUT.md` §9 for the fallback if it does not).

## 7. Bugsink (minutes)

- [ ] Create the CryptVault project in the Bugsink instance and record the DSN in `spec.json`
      (`crash_reporting_dsn`) and `CLAUDE.md`, as the siblings do. Scrubbing rules are in the brief
      §11 and threat model S14.

## 8. Website and legal hosting (before the Play listing)

- [ ] `website/content/apps/crypt-vault.toml` (already replaces `data-vault.toml`; still without a
      `repo` key): add `repo`, the icon and the `[[legal]]` entries once `legal/*.md` exist, so the
      four documents publish under `https://piepgras.info/legal/crypt-vault/<doc>/` — the URLs the
      consent screens above cite. Settle the display name first (`BUILD_BRIEF.md` §13).
- [ ] Imprint at `https://piepgras.info/imprint/` (exists).

## Log

| Date | Item | Identifier / note |
| --- | --- | --- |
| | | |
