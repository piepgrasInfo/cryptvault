# Research: cloud storage providers and email transport (2026-09-16)

Provenance: produced by a web-research agent for the CryptVault build brief on 2026-09-16.
Facts marked UNVERIFIED were not confirmed against a primary source. Sources are linked inline.
The brief's decisions win where this report and the brief disagree.

---

## PART 1 — Cloud storage providers

### 1.1 Dropbox
- **SDK**: `com.dropbox.core:dropbox-core-sdk` / `dropbox-android-sdk` **8.0.2** (15 Jul 2026), MIT, Android 8+ (API 26+), pure Java, OkHttp optional. 8.x needs Java 21 for the build toolchain (use 7.x for Java 8–20). Versions <7.0.0 **stopped working in Jan 2026**. [github.com/dropbox/dropbox-sdk-java](https://github.com/dropbox/dropbox-sdk-java)
- **Auth**: `Auth.startOAuth2PKCE(...)` + `AuthActivity` with `db-<APP_KEY>` scheme in the manifest; uses the Dropbox app if installed, else browser. Access tokens are short-lived (~4 h); request `token_access_type=offline` to get a refresh token; PKCE is mandatory for mobile (no secret). Store the serialized `DbxCredential` in Keystore-wrapped storage. [docs.dropboxapi.com/…/oauth](https://docs.dropboxapi.com/dropbox-api/docs/oauth)
- **Registration**: App Console, "Scoped access", choose **App folder** access type → app sees only `/Apps/<AppName>`. Least-privilege scopes: `files.metadata.read`, `files.content.read`, `files.content.write`. Apps start in *development* (up to 500 linked users); **at 50 users you get 2 weeks to obtain Production approval** before linking is frozen. [developer-guide](https://www.dropbox.com/developers/reference/developer-guide)
- **API**: `/files/upload` ≤150 MiB per request; upload sessions valid **7 days**, ≤150 MiB per `append_v2`, concurrent sessions need offsets that are multiples of 4 MiB; `list_folder` + `list_folder/continue` cursor (may return `reset`); `list_folder/longpoll` timeout 30–480 s (+ up to 90 s jitter, unauthenticated notify endpoint); `list_revisions` ≤100; `rev` "can be used to detect changes and avoid conflicts"; `move_v2` has no case-only rename. [files.stone spec](https://raw.githubusercontent.com/dropbox/dropbox-api-spec/main/files.stone). `content_hash` = SHA-256 of concatenated SHA-256s of 4 MiB blocks (computable locally). [content-hash](https://www.dropbox.com/developers/reference/content-hash). 350 GB max file size via sessions: UNVERIFIED this session (spec states 2^41−2^22 bytes).
- **Rate limits**: not published; 429 `too_many_requests` with `Retry-After`; retries count against the limit. [error-handling-guide](https://developers.dropbox.com/error-handling-guide)
- **Verdict**: cheapest integration; app-folder model is exactly right. Include in v1.

### 1.2 Microsoft OneDrive (personal + business)
- **Auth**: MSAL `com.microsoft.identity.client:msal` **8.4.2** (21 Aug 2026), MIT, minSdk 16/targetSdk 33+, extra Maven repo (Duo SDK feed) and Lombok plugin required; redirect `msauth://<package>/<base64 signing-cert hash>` + `BrowserTabActivity`; authority `common` (work + personal) or `consumers`. [MSAL repo](https://github.com/AzureAD/microsoft-authentication-library-for-android), [MSAL Android docs](https://learn.microsoft.com/en-us/entra/msal/android/). Token cache lives in SharedPreferences, "encrypted when possible" — an open issue documents plaintext fallback. [issue #849](https://github.com/AzureAD/microsoft-authentication-library-for-android/issues/849)
- **Graph SDK**: `com.microsoft.graph:microsoft-graph` **6.69.0** (2 Sep 2026), MIT, Kiota-based; README: "supported at runtime for Java 8+ and **Android API 26+**", recommends ProGuard/multidex. Its `azure-identity` `InteractiveBrowserCredential` does not work on Android ([issue #2160](https://github.com/microsoftgraph/msgraph-sdk-java/issues/2160)) → use MSAL and hand the token to the SDK via `AccessTokenProvider`, or simply call Graph REST with OkHttp/Ktor (recommended: the surface you need is ~8 endpoints). [msgraph-sdk-java](https://github.com/microsoftgraph/msgraph-sdk-java). The old **onedrive-sdk-android is archived (2 Oct 2024)**. [repo](https://github.com/OneDrive/onedrive-sdk-android)
- **Registration**: Entra app registration with "Personal Microsoft accounts" enabled. No review for MSA. For work accounts, multi-tenant apps registered after Nov 2020 that are not **publisher-verified** (needs a Cloud Partner Program ID) may be blocked from user consent by tenant policy. [publisher-verification](https://learn.microsoft.com/en-us/entra/identity-platform/publisher-verification-overview)
- **App folder**: scope `Files.ReadWrite.AppFolder`, path `/me/drive/special/approot` → `Apps/<App name>`; supports all item ops incl. `createUploadSession` and `delta` on `approot:/{path}:/delta`; counts against user quota. [special-folders-appfolder](https://learn.microsoft.com/en-us/onedrive/developer/rest-api/concepts/special-folders-appfolder), [onedrive-sharepoint-appfolder](https://learn.microsoft.com/en-us/graph/onedrive-sharepoint-appfolder). **Gotcha**: Microsoft Q&A states the delegated AppFolder scope is only valid for personal accounts, and `createUploadSession` lists `Files.ReadWrite` as least privilege — plan to request `Files.ReadWrite` for business tenants and verify on a real M365 tenant. [Q&A](https://learn.microsoft.com/en-us/answers/questions/1418013/when-is-api-permission-(delegated)-files-readwrite)
- **API**: simple PUT for small files; upload session for larger (docs recommend resumable >10 MiB), fragments **multiples of 320 KiB, <60 MiB, sequential**, `nextExpectedRanges`, 404 → restart, `If-Match` eTag/cTag → 412, `@microsoft.graph.conflictBehavior`. [createUploadSession](https://learn.microsoft.com/en-us/graph/api/driveitem-createuploadsession). `delta` → `@odata.deltaLink`, `token=latest`, HTTP 410 resync, "always track items by id"; consumer OneDrive delete entries omit `ctag`/`size`. [driveitem-delta](https://learn.microsoft.com/en-us/graph/api/driveitem-delta). Max file 250 GB; forbidden name chars `" * : < > ? / \ |`. [OneDrive limits](https://support.microsoft.com/en-us/office/invalid-file-names-and-file-types-in-onedrive-and-sharepoint-64883a5d-228e-48f5-b3d2-eb39e07630fa). Graph global limit 130,000 req/10 s per app; OneDrive limits are in the SharePoint throttling doc. [throttling-limits](https://learn.microsoft.com/en-us/graph/throttling-limits)
- **Verdict**: v1 for personal accounts; business tenants need testing (scope + admin consent).

### 1.3 Google Drive
- **Auth (2026)**: Google Sign-In for Android is "outdated and no longer supported"; sign-in → Credential Manager, scope grants → `Identity.getAuthorizationClient()` + `AuthorizationRequest` (play-services-auth). Returns a ~1 h access token; refresh is handled by Play Services on re-`authorize()`; no refresh token on device (`requestOfflineAccess` is for servers only). [authorization](https://developer.android.com/identity/authorization), [legacy GSI](https://developer.android.com/identity/legacy/gsi). Requires Google Play services on device.
- **Registration**: GCP project → Android OAuth client with package name + SHA-1; with Play App Signing register the **app-signing** key fingerprint, not the upload key. [OAuth setup](https://support.google.com/cloud/answer/6158849), [Play App Signing](https://support.google.com/googleplay/android-developer/answer/9842756). `drive.file` and `drive.appdata` are **non-sensitive**; `drive`, `drive.readonly`, `drive.metadata` are **restricted** (verification + annual security assessment if data is stored/transmitted server-side). [api-specific-auth](https://developers.google.com/workspace/drive/api/guides/api-specific-auth). The unverified-app screen and 100-user cap apply only to sensitive/restricted scopes; all apps still need a homepage/privacy policy ("brand verification"). [unverified apps](https://support.google.com/cloud/answer/7454865), [verification](https://support.google.com/cloud/answer/13464321)
- **App-scoped storage**: `appDataFolder` is hidden from the user and other apps, no sharing/trash, deleted when the user removes the app from Drive; counts against storage quota ("hidden app data"). [appdata](https://developers.google.com/workspace/drive/api/guides/appdata), [Google One](https://support.google.com/googleone/answer/9312312). Alternative: `drive.file` — visible folder the app created (user can browse/restore it).
- **API**: simple/multipart ≤5 MB; resumable upload with 256 KiB-multiple chunks, 308 + `Range`, session URI valid 1 week; max 5 TB/file; 750 GB/user/day upload. [manage-uploads](https://developers.google.com/workspace/drive/api/guides/manage-uploads), [Workspace limits](https://support.google.com/a/answer/172541). `changes.getStartPageToken` / `changes.list` / `newStartPageToken` (only files visible to the app under `drive.file`/`drive.appdata` — inferred from scope semantics). [manage-changes](https://developers.google.com/workspace/drive/api/guides/manage-changes). `md5Checksum`, `sha1Checksum`, `sha256Checksum`, `headRevisionId`, monotonic `version`, `appProperties`. [files resource](https://developers.google.com/workspace/drive/api/reference/rest/v3/files). Quotas: 1,000,000 units/min/project, 325,000/user/min, 1 TB egress/day; 403/429 → exponential backoff. [limits](https://developers.google.com/workspace/drive/api/guides/limits)
- **Libraries**: `com.google.apis:google-api-services-drive:v3-rev20260712-2.0.0` exists ([Maven Central](https://central.sonatype.com/artifact/com.google.apis/google-api-services-drive/versions)), but `google-api-client-android`'s `GoogleAccountCredential` is AccountManager-era. Recommend plain REST (OkHttp/Ktor) with the AuthorizationClient token.
- **Verdict**: v1 with `drive.appdata` (zero verification friction) or `drive.file`; no Play-services-free path.

### 1.4 Any provider via Storage Access Framework
- Mechanics: `ACTION_OPEN_DOCUMENT_TREE` + `takePersistableUriPermission`; Android 11+ forbids picking internal-storage root, `Download`, `Android/data|obb`; access is lost if the folder is moved/deleted; check `FLAG_SUPPORTS_WRITE/DELETE` (and rename/move flags) per document; "iterating through large numbers of files … may reduce performance". [documents-files](https://developer.android.com/training/data-storage/shared/documents-files). Providers must implement `isChildDocument`/`FLAG_SUPPORTS_IS_CHILD` for trees. [Ian Lake](https://medium.com/androiddevelopers/building-a-documentsprovider-f7f2fb38e86a)
- Provider reality: Nextcloud added tree support in 3.5.0 ([issue #303](https://github.com/nextcloud/android/issues/303)) but has had 0-byte-write and rapid-rewrite bugs ([#6726](https://github.com/nextcloud/android/issues/6726), [#11283](https://github.com/nextcloud/android/issues/11283)). Keepass2Android's wiki: "Not all Cloud apps (e.g. Dropbox) can be used as SAF Content Provider", some are read-only, providers cache locally and produce "(conflicted)" copies, persistent links break, and transactional writes are impossible through SAF. [KP2A file handling](https://github.com/PhilippC/keepass2android/wiki/Keepass2Android-file-handling). Google Drive/OneDrive/Proton Drive/Synology tree + write support: UNVERIFIED (Proton's forum indicates SAF create/edit works; test each on-device). Dropbox forum confirms no tree support. [forum](https://www.dropboxforum.com/t5/Discuss-Dropbox-Developer-API/why-using-Intent-ACTION-OPEN-DOCUMENT-does-not-list-the-Dropbox/td-p/209654)
- How others cope: Cryptomator uses native SDKs (Dropbox, Drive, OneDrive, pCloud, WebDAV, S3) and SAF only for "Local storage" ([docs](https://docs.cryptomator.org/en/latest/android/cloud-management/)); Keepass2Android same pattern; Syncthing's native binary cannot use SAF at all ([FAQ](https://github.com/syncthing/syncthing-android/wiki/Frequently-Asked-Questions)); RCX (rclone for Android, GPL-3, last push Nov 2023) bundles the rclone binary ([repo](https://github.com/x0b/rcx)); FolderSync: UNVERIFIED.
- Caveats: no delta/change token, no ETag/hash (only `last_modified`/size), no atomic rename guarantee, provider may be offline-cached, cannot detect remote changes except by re-listing. **Verdict**: include as "Local folder / other provider" fallback only.

### 1.5 WebDAV / Nextcloud / ownCloud / Synology
- **dav4jvm** 4.1.0 (1 Sep 2026), **MPL-2.0** (not GPL), Ktor-based, JitPack, Android's XmlPullParser; used by DAVx⁵ and Seedvault. [repo](https://github.com/bitfireAT/dav4jvm). **sardine-android** Apache-2.0, last push Feb 2024. [repo](https://github.com/thegrizzlylabs/sardine-android). **nextcloud/android-library** 2.26.0 (16 Sep 2026), MIT, minSdk 21, pulls OkHttp + dav4jvm + Jackrabbit — heavy. [repo](https://github.com/nextcloud/android-library)
- Minimal client: PROPFIND Depth 1 (`getetag`, `getcontentlength`, `getlastmodified`), PUT with `If-Match`/`If-None-Match: *`, GET with `Range`, MKCOL, MOVE (`Overwrite`), DELETE. Nextcloud chunking v2: MKCOL `uploads/<id>`, PUT chunks 5 MB–5 GB, MOVE `.file` → target, `Destination` header on all three, `OC-Total-Length`, upload dir expires after 24 h. [chunking](https://docs.nextcloud.com/server/latest/developer_manual/client_apis/WebDAV/chunking.html)
- **Verdict**: v1 (raw OkHttp WebDAV, ~400 lines) — covers Nextcloud, ownCloud, Synology, Hetzner Storage Box, etc. Credentials stored on device.

### 1.6 S3-compatible
- AWS SDK for Kotlin: Apache-2.0, Android minSdk 24 + core-library desugaring. [targets.md](https://raw.githubusercontent.com/awslabs/aws-sdk-kotlin/main/docs/targets.md). MinIO Java 9.0.3, Apache-2.0, Java 8+ (Android: UNVERIFIED). [repo](https://github.com/minio/minio-java). B2 ([docs](https://www.backblaze.com/docs/cloud-storage-s3-compatible-api)), R2 (`<account>.r2.cloudflarestorage.com`, region `auto`, Range/multipart supported — [docs](https://developers.cloudflare.com/r2/api/s3/api/)), Hetzner (5 GB single PUT, multipart above — [docs](https://docs.hetzner.com/storage/object-storage/overview)).
- **Verdict**: v1.5. A hand-written SigV4 signer + PUT/GET(Range)/List/Delete/Multipart is small; the AWS SDK is heavy. Object stores have no rename (copy+delete) and no delta (list with ETag).

### 1.7 Others (one line each)
- **iCloud Drive**: no Android access; CloudKit is Apple-platform + CloudKit JS web services only, and iCloud Drive files aren't exposed. [Apple](https://developer.apple.com/documentation/cloudkit/managing_icloud_containers_with_the_cloudkit_database_app/obtaining_an_api_token_for_an_icloud_container). Not feasible.
- **Proton Drive**: SDK (JS/C#) published but "not yet ready for third-party production use", auth module missing; no Kotlin SDK. [Jan 2026 blog](https://proton.me/blog/drive-sdk-january-2026). Not feasible (except SAF).
- **pCloud**: official Java SDK, Apache-2.0, active (Jun 2026). [repo](https://github.com/pCloud/pcloud-sdk-java). Feasible later.
- **Box**: Android SDK Apache-2.0, last push Jul 2023 (dormant); business-oriented. [repo](https://github.com/box/box-android-sdk). Skip.
- **MEGA**: SDK under "Mega Limited Code Review Licence" — non-commercial only. [repo](https://github.com/meganz/sdk). Not usable.

### 1.8 Comparison

| Provider | SDK | Auth | App-folder scope | Delta API | Resumable upload | Dev burden | v1? |
|---|---|---|---|---|---|---|---|
| Dropbox | dropbox-android-sdk 8.0.2 (MIT) | PKCE + refresh | App folder | cursor + longpoll | sessions, 7 d | App Console; prod approval at 50 users | Yes |
| OneDrive | MSAL 8.4.2 + Graph REST | MSAL, common/consumers | approot (`AppFolder`, personal-verified) | deltaLink | sessions, 320 KiB frags | Entra reg.; publisher verif. for org tenants | Yes (personal), test business |
| Google Drive | AuthorizationClient + REST | Play services token | appDataFolder / drive.file | changes pageToken | resumable, 256 KiB | GCP + SHA-1 + policy page | Yes |
| SAF folder | none | none | user-chosen | none | none | none | Fallback |
| WebDAV | OkHttp (or dav4jvm MPL) | Basic/app password | user path | ETag re-list | Nextcloud chunks | none | Yes |
| S3 | SigV4 / aws-sdk-kotlin | access keys | bucket/prefix | ETag re-list | multipart | none | v1.5 |

### 1.9 Provider abstraction & semantics
```kotlin
interface RemoteStore {
  val caps: Caps            // rangeGet, atomicMove, delta, contentHash(kind), maxSingleUpload, resumable
  suspend fun list(path, cursor: String?): Page<Entry>   // Entry: path, size, mtime, etag/rev, hash?
  suspend fun stat(path): Entry?
  suspend fun upload(path, src: Source, size, ifMatch: String?, progress): Entry  // chunked/resumable inside
  suspend fun download(path, range: LongRange?): Source
  suspend fun delete(path, ifMatch: String?)
  suspend fun move(from, to, overwrite: Boolean)
  suspend fun changes(token: String?): Changes   // provider delta or full re-list fallback
}
```
- **Backup** (v1): content-addressed encrypted chunks/files + a signed manifest (`manifest.<n>.json`). Upload new objects first, then write the manifest with `If-Match` on the previous manifest's ETag/rev → detects a concurrent writer without a lock. Keep N manifests (snapshots) and GC unreferenced objects after all retained manifests agree. Full-snapshot = new manifest referencing mostly existing objects, so incremental by construction.
- **Multi-device sync** (v2): per-item version vectors (or per-device Lamport counters in the manifest) rather than last-writer-wins on mtime, since provider mtimes are upload times. Detect conflicts by comparing the remote manifest ETag against the one last seen; never merge ciphertext — keep both versions and surface in UI.
- **Metadata leakage**: provider sees object count, sizes, upload timestamps, access pattern, and app name (App folder name). Mitigate with fixed-size padding/chunking, random object names, and one manifest blob.

## PART 2 — Email transport from Android

### 2.1 Delegating to the mail app
`ACTION_SEND` (`type = application/vnd.cryptvault`… mail apps ignore custom types but still attach; use `application/octet-stream` when filtering targets), `EXTRA_STREAM` = FileProvider `content://` URI, `FLAG_GRANT_READ_URI_PERMISSION` **plus** `setClipData` so the grant survives the chooser; `ACTION_SEND_MULTIPLE` for several files; `ACTION_SENDTO mailto:` narrows the chooser to mail apps but attachments with SENDTO are unreliable (historic K-9 bug [#1336](https://github.com/thunderbird/thunderbird-android/issues/1336)). [sharing docs](https://developer.android.com/training/sharing/send). The user must press Send; `startActivity` gives no result → CryptVault cannot know if it was sent. Per-app attachment behaviour of Gmail/Outlook/Samsung/Thunderbird/FairEmail/Proton: UNVERIFIED (test matrix needed).
Size limits: Gmail 25 MB, above that Gmail auto-converts to a Drive link; Workspace admin-set ([Gmail](https://support.google.com/mail/answer/6584)); Outlook mobile 33 MB (20 MB for iCloud accounts) ([MS](https://support.microsoft.com/en-US/Outlook/send-attachments-and-images-in-outlook-mobile)); Exchange Online default 35 MB send / 36 MB receive, admin-configurable to 150 MB, 112 MB effective for external mail ([limits](https://learn.microsoft.com/en-us/office365/servicedescriptions/exchange-online-service-description/exchange-online-limits)); iCloud 20 MB, Mail Drop 5 GB ([Apple](https://support.apple.com/en-us/102198)); Proton 25 MB out / 50 MB in ([Proton](https://proton.me/support/attaching-multiple-documents-to-a-message)). Practical envelope: **≤ ~15 MB after base64**.

### 2.2 SMTP from the app
- Legacy `com.sun.mail:android-mail:1.6.7` (Apr 2021, `javax.mail`) is end-of-life ([Maven](https://mvnrepository.com/artifact/com.sun.mail/android-mail/1.6.7)). Current: **Angus Mail 2.0.5** (27 Feb 2026), EPL-2.0 — `org.eclipse.angus:jakarta.mail` + `angus-activation` + `jakarta.activation:jakarta.activation-api`, API 19+, `packagingOptions.pickFirst META-INF/*.md`; SASL unsupported on Android but XOAUTH2 is built in. [Angus Android](https://eclipse-ee4j.github.io/angus-mail/Android), [releases](https://github.com/eclipse-ee4j/angus-mail). Do not mix `javax.activation` and `jakarta.activation` on the classpath.
- **Gmail**: XOAUTH2 needs scope `https://mail.google.com/` — **restricted**, verification + full-scope justification ([XOAUTH2](https://developers.google.com/workspace/gmail/imap/xoauth2-protocol)); App Passwords need 2-Step Verification and are unavailable with security-key-only 2SV, Advanced Protection, or admin-restricted accounts ([Google](https://support.google.com/accounts/answer/185833)).
- **Microsoft**: Outlook.com personal accounts lost Basic auth on 16 Sep 2024 (app passwords too) ([Q&A](https://learn.microsoft.com/en-us/answers/questions/4664796/will-smtp-auth-continue-working-for-outlook-com-ac)). Exchange Online SMTP AUTH Basic: unchanged through Dec 2026, **disabled by default end of Dec 2026**, unavailable for new tenants after that, final removal date to be announced H2 2027 ([timeline](https://techcommunity.microsoft.com/blog/exchange/updated-exchange-online-smtp-auth-basic-authentication-deprecation-timeline/4489835)). OAuth scope `https://outlook.office.com/SMTP.Send` (+`offline_access`), supported for M365 and Outlook.com ([MS](https://learn.microsoft.com/en-us/exchange/client-developer/legacy-protocols/how-to-authenticate-an-imap-pop-smtp-application-by-using-oauth)).
- Ports 587 STARTTLS / 465 implicit TLS; credentials or refresh tokens must live on the device. **Verdict**: generic SMTP for self-hosted/other providers only; not for Gmail/Microsoft.

### 2.3 Provider APIs
- **Gmail API** `users.messages.send` via `/upload/gmail/v1/users/{id}/messages/send`; `gmail.send` is **sensitive** (verification, no security assessment) whereas `gmail.compose/modify/readonly` are restricted ([scopes](https://developers.google.com/workspace/gmail/api/auth/scopes)). Message size cap 35 MB: UNVERIFIED this session (reference page did not render it). Uses the same AuthorizationClient flow as Drive.
- **Graph** `POST /me/sendMail`, `Mail.Send` delegated, personal accounts supported; returns 202 Accepted (not delivery). Attachments <3 MB inline; 3–150 MB via `attachments/createUploadSession` (needs `Mail.ReadWrite`, ≤4 MB per PUT). [sendMail](https://learn.microsoft.com/en-us/graph/api/user-sendmail), [large attachments](https://learn.microsoft.com/en-us/graph/outlook-large-attachments)

### 2.4 Receiving
- (a) `ACTION_VIEW` filters: a `<data>` with only `mimeType` implicitly matches `content:` and `file:`; `path*` attributes are ignored unless scheme **and** host are set, and `pathPattern` is a limited, non-backtracking glob. [data element](https://developer.android.com/guide/topics/manifest/data-element). Mail apps hand over opaque `content://` URIs (no extension in path) typed `application/octet-stream`, so: declare `application/vnd.cryptvault` **and** `application/octet-stream`, then sniff a magic header and reject non-CryptVault files. OpenKeychain does exactly this (`application/pgp-*`, `application/octet-stream`, `text/plain`, plus `pathPattern` for `.gpg/.pgp/.asc/.bin` with `scheme=file|content` + `host=*`) [manifest](https://raw.githubusercontent.com/open-keychain/open-keychain/master/OpenKeychain/src/main/AndroidManifest.xml); Cryptomator only registers an inbound `ACTION_SEND */*` "share into vault" [manifest](https://raw.githubusercontent.com/cryptomator/android/develop/presentation/src/main/AndroidManifest.xml); Keepass2Android manifest: UNVERIFIED (fetch failed).
- (b) "Share to CryptVault": `ACTION_SEND`/`SEND_MULTIPLE` with `*/*` — most robust path from any mail app.
- (c) IMAP polling with Angus Mail needs `https://mail.google.com/` (restricted) or `IMAP.AccessAsUser.All`, stored credentials, background work and folder heuristics — defer.

### 2.5 Deliverability of encrypted attachments
- Gmail blocks listed executables (incl. `.apk`, `.jar`, `.js`, `.iso`, `.vhd`), the same types inside archives, and **"password-protected archives with archived content"**; over-size messages don't send. [Gmail](https://support.google.com/mail/answer/6590). Not listed: `.pdf`, `.html`, `.gpg`, `.pgp`, `.age`, custom extensions → an opaque `.cryptvault` blob should pass (not a zip container).
- Outlook.com/Exchange: Level-1 blocked list is script/executable oriented; the fetch summary claimed `.zip` is listed — UNVERIFIED, check manually. [Outlook](https://support.microsoft.com/en-us/office/blocked-attachments-in-outlook-434752e1-02d3-4e90-9124-8b81e49a8519). Defender **Safe Attachments** has "Block messages containing encrypted attachments that could not be scanned" (quarantine, identified by true file type, admin-configurable exclusions). [Safe Attachments](https://learn.microsoft.com/en-us/defender-office-365/safe-attachments-about)
- Google Workspace: "Protect against encrypted attachments from untrusted senders" defaults to warn, admins can set spam/quarantine; "anomalous attachment types" likewise. [Workspace](https://knowledge.workspace.google.com/admin/gmail/advanced/advanced-phishing-and-malware-protection)
- Corporate gateways quarantining unscannable/encrypted archives: plausible and consistent with the above vendor settings, but UNVERIFIED as a general claim.

### 2.6 Alternatives
Share the container via `ACTION_SEND` to any app (Signal, WhatsApp, Quick Share/Nearby, Files); or upload it to the user's own cloud (Part 1) and share a time-limited link (Dropbox/Drive/OneDrive/Nextcloud all support expiring links) while sending only the link and an out-of-band passphrase; QR code for keys/short payloads; NFC/Quick Share for device-to-device. These avoid attachment size and scanning issues, and links can be revoked.

### 2.7 Transport options

| Mechanism | User effort | Credentials on device | Size limit | Delivery known? | Verdict |
|---|---|---|---|---|---|
| Mail-app Intent (`ACTION_SEND`) | pick app + press Send | none | mail app/provider (~15–25 MB) | No | **v1** |
| SMTP (Angus Mail) | none | password/token | server | Accepted only | self-hosted only |
| Gmail API | one-time consent | Play token | UNVERIFIED (35 MB?) | Accepted | v2, needs verification |
| Graph Mail.Send | one-time consent | MSAL refresh token | 150 MB | 202 only | v2 |
| IMAP polling | consent + config | token | n/a | n/a | not v1 |
| Cloud link / other apps | share sheet | none | provider | No | v1 fallback |

## Recommendations for the brief
1. Ship v1 with Dropbox (App folder), Google Drive (`drive.appdata` or `drive.file`), OneDrive personal (approot), WebDAV, and a SAF "local/other" fallback; S3 in v1.5; skip iCloud, Proton, MEGA, Box.
2. Talk REST (OkHttp/Ktor) for Drive and Graph; use vendor SDKs only for auth (MSAL) and Dropbox.
3. Design around `RemoteStore` with capability flags; treat SAF as delta-less and hash-less.
4. Backup = encrypted objects + manifest chain with `If-Match`/rev on the manifest; retain N manifests; GC later.
5. Always use resumable/chunked upload above ~5 MB (Drive 256 KiB, OneDrive 320 KiB, Dropbox ≤150 MiB, Nextcloud ≥5 MB, S3 multipart).
6. Store refresh tokens/credentials in Keystore-backed storage; MSAL's cache may fall back to plaintext.
7. Register apps early: Dropbox production approval (50-user trigger), GCP OAuth consent screen + policy pages + app-signing SHA-1, Entra with personal accounts; consider publisher verification if business tenants matter.
8. Prefer `drive.appdata`/`Files.ReadWrite.AppFolder`/Dropbox App folder to avoid verification and to keep the app out of user files; document that users cannot see appdata files.
9. Email v1 = `ACTION_SEND` with FileProvider + ClipData; custom extension `.cryptvault` with `application/octet-stream` content; keep containers ≤15 MB; never wrap in password-protected zip.
10. Register `ACTION_VIEW` for `application/vnd.cryptvault` + `application/octet-stream` with magic-byte validation, plus an `ACTION_SEND */*` receiver.
11. Don't build SMTP/IMAP for Gmail/Microsoft; Basic auth is gone for Outlook.com and going for Exchange Online (default-off Dec 2026); Gmail SMTP needs a restricted scope.
12. If in-app sending is required later, Graph `Mail.Send` is simpler to get approved than Gmail's restricted scopes; `gmail.send` alone is sensitive but still needs verification.
13. Test attachment delivery against Gmail, Outlook.com, M365 with Safe Attachments, Proton, iCloud; expect quarantine in strict tenants and offer the link-based fallback.
14. Pad/randomize object names and sizes to limit metadata leakage; state the residual leakage in the privacy policy.

## Open questions for the app owner
- Backup only (one device) or multi-device sync in scope? (Drives the conflict/version-vector design.)
- Must the vault be visible/browsable in the user's cloud (drive.file / normal folder) or hidden (appdata / App folder)?
- Which OneDrive audience: personal only, or also M365 business tenants (admin consent, publisher verification)?
- Is a Play-services-free build (F-Droid) required? If so, Google Drive needs a different auth path or is dropped.
- Max container size for email sharing, and is a cloud-link fallback acceptable?
- Is in-app sending (Gmail API/Graph) worth the OAuth verification effort, or is the mail-app hand-off sufficient?
- Retention policy: how many snapshots, and who pays for storage quota (appdata counts against the user)?
- Self-hosted users: WebDAV only, or also S3/SFTP?

---

Note added by the brief's author: the interview settled backup-only semantics, hidden app areas,
OneDrive personal (business best-effort), 15 MB mail ceiling with a link fallback, mail-app
hand-off only, five snapshots, WebDAV only. A Play-services-free build and S3/SFTP are carried in
`BUILD_BRIEF.md` §13. Recommendation 9's "never wrap in password-protected zip" was overridden by
the interview: ZIP AES-256 is one of the three offered containers because it has the widest
recipient tooling; the Gmail warning and Exchange quarantine risk are stated to the sender.
