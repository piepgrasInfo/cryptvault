# Research: cryptographic libraries and mail container formats (2026-09-16)

Provenance: produced by a web-research agent for the CryptVault build brief on 2026-09-16.
Version and date facts come from `repo1.maven.org` `maven-metadata.xml` (authoritative) or
`central.sonatype.com`; the legacy `search.maven.org` index was found to be months stale and was
ignored. Dates are `lastUpdated` of the artifact. Facts marked UNVERIFIED were not confirmed
against a primary source. The brief's decisions win where this report and the brief disagree
(notably: the brief adopts the Cryptomator format via cryptolib, which this report's section C
had recommended against on licence grounds before the licence decision was taken).

---

## A. Library landscape (Android/Kotlin)

### A1. Google Tink
- `com.google.crypto.tink:tink-android:1.23.0`, 2026-07-09, Apache-2.0 ([metadata](https://repo1.maven.org/maven2/com/google/crypto/tink/tink-android/maven-metadata.xml), [releases](https://github.com/tink-crypto/tink-java/releases)).
- Pure Java: the repo language breakdown is Java/Starlark/Shell only, no C/C++ ([GitHub languages API](https://api.github.com/repos/tink-crypto/tink-java/languages)) → no `.so`, 16 KB page size not applicable.
- AEAD: AES-GCM, AES-CTR-HMAC, XChaCha20-Poly1305, ChaCha20-Poly1305; AES-GCM-SIV needs Conscrypt. Streaming AEAD: AES-GCM-HKDF (1 MB or 4 KB segments) and AES-CTR-HMAC; segments are bound to their position, only the needed segment is decrypted (random access) ([supported key types](https://developers.google.com/tink/supported-key-types), [streaming AEAD](https://developers.google.com/tink/streaming-aead)).
- `AndroidKeysetManager` stores keysets in SharedPreferences, optionally wrapped by an Android Keystore key. Its own Javadoc says "Android Keystore is unreliable", runs self-tests, and recommends omitting the master-key URI; `rotate()`, `doNotUseKeystore()`, `promote()` are `@Deprecated` ([source](https://github.com/tink-crypto/tink-java/blob/main/src/main/java/com/google/crypto/tink/integration/android/AndroidKeysetManager.java)). Key rotation is native to keysets (multiple keys, one primary).
- 1.22.0 dropped API 23 testing; min SDK likely 24 soon (UNVERIFIED). Plain Java API, usable from Kotlin, no Kotlin wrappers. Known friction: protobuf-javalite conflicts ([issue #31](https://github.com/tink-crypto/tink-java/issues/31)). No password KDF; no biometric-bound Keystore integration (you do that yourself).

### A2. Android Keystore
- Key material never enters the app process; TEE or StrongBox (API 28+, `setIsStrongBoxBacked`, AES/RSA-2048/P-256/HMAC only, slower) ([Keystore docs](https://developer.android.com/privacy-and-security/keystore)).
- `setUserAuthenticationParameters(duration, AUTH_BIOMETRIC_STRONG | AUTH_DEVICE_CREDENTIAL)`; `duration=0` means per-operation auth via `BiometricPrompt` + `CryptoObject`; `setInvalidatedByBiometricEnrollment(true)` is default on API 24+; handle `KeyPermanentlyInvalidatedException` / `UserNotAuthenticatedException` ([biometric auth guide](https://developer.android.com/identity/sign-in/biometric-auth)).
- Key attestation: API 24+ hardware attestation; verify chain server-side ([attestation](https://developer.android.com/privacy-and-security/security-key-attestation)). Not needed for a local vault.
- Keystore keys are never included in Auto Backup or device-to-device transfer; restored ciphertext becomes undecryptable ([write-up](https://www.thecodeside.com/2020/09/14/android-auto-backup-keystore-encryption-broken-heart-love-story/)). Consequence: the Keystore must only ever hold a *secondary* wrap of the DEK.
- Recommended pattern: DEK wrapped (a) by the password-derived KEK (portable, in the vault header) and (b) by a Keystore AES-GCM key with auth parameters (device-local cache). Biometric unlock decrypts wrap (b); if invalidated, fall back to password and re-create wrap (b).

### A3. Jetpack Security
`androidx.security:security-crypto` final release 1.1.0 (2025-07-30); all APIs deprecated since 1.1.0-beta01 (2025-06-04) "in favour of existing platform APIs and direct use of Android Keystore" ([release notes](https://developer.android.com/jetpack/androidx/releases/security), [metadata](https://dl.google.com/dl/android/maven2/androidx/security/security-crypto/maven-metadata.xml)). Do not adopt; use Tink + Keystore directly.

### A4. Password KDFs
- **argon2kt** `com.lambdapioneer.argon2kt:argon2kt:1.6.0` (2024-09-06), MIT, JNI wrapper of reference C; 1.6.0 notes "Added support for 16 KB page size in Android 15", minSdk 21; uses direct ByteBuffers to limit secret copies ([releases](https://github.com/lambdapioneer/argon2kt/releases), [README](https://github.com/lambdapioneer/argon2kt)). Slow release cadence but functional.
- **signalapp/Argon2** `org.signal:argon2:13.1@aar`, GPL-3.0, archived 2024-04-18 ([repo](https://github.com/signalapp/Argon2)) → avoid.
- **Bouncy Castle** `Argon2BytesGenerator` pure Java, no native, slower ([javadoc](https://downloads.bouncycastle.org/java/docs/bcprov-jdk18on-javadoc/org/bouncycastle/crypto/generators/Argon2BytesGenerator.html)); good as fallback.
- **libsodium `crypto_pwhash`** via lazysodium (A5).
- Parameters: OWASP: Argon2id m=19456 KiB, t=2, p=1 (or m=47104/t=1, m=12288/t=3 …); scrypt N=2^17 r=8 p=1; PBKDF2-HMAC-SHA256 600k ([OWASP](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html)). RFC 9106: first choice t=1, p=4, m=2 GiB; constrained-memory choice t=3, p=4, m=64 MiB, 128-bit salt, 256-bit tag ([RFC 9106 §4](https://www.rfc-editor.org/rfc/rfc9106.html)).
- Android memory reality: the Java heap is capped per app (`ActivityManager.getMemoryClass()`, larger with `largeHeap`), whereas native allocations are limited only by device RAM ([reference](https://developer.android.com/reference/android/app/ActivityManager#getMemoryClass())). A 64 MiB Argon2id via argon2kt (native) is fine; a 64 MiB pure-Java BC run can hit the heap cap on low-end devices. Store the parameters in the vault header and calibrate at vault creation.

### A5. libsodium bindings
- **lazysodium-android** `com.goterl:lazysodium-android:5.2.0@aar` (2025-05-31), MPL-2.0, JNA, libsodium 1.0.20, "All native libsodium.so libs are now 16KB aligned", minSdk 24 ([releases](https://github.com/terl/lazysodium-android/releases)).
- **libsodium-jni**: last release 2.0.2 (2019), no 16 KB work → unmaintained ([releases](https://github.com/joshjdevl/libsodium-jni/releases)).
- Only worth adding if you want XChaCha20 secretstream or `crypto_pwhash`; Tink + argon2kt already cover the vault needs.

### A6. Bouncy Castle
- `org.bouncycastle:bcprov-jdk18on:1.86` (2026-09-11), plus `bcpkix`, `bcpg`, `bcmail`, `bcutil` same version; MIT-style "Bouncy Castle Licence" ([metadata](https://repo1.maven.org/maven2/org/bouncycastle/bcprov-jdk18on/maven-metadata.xml)).
- Android ships a stripped provider also named "BC"; since Android P, BC algorithms duplicated by Conscrypt were deprecated and later removed, so `getInstance(..., "BC")` can throw `NoSuchAlgorithmException` ([Android blog](https://android-developers.googleblog.com/2018/03/cryptography-changes-in-android-p.html)). Standard fix: `Security.removeProvider("BC"); Security.insertProviderAt(BouncyCastleProvider(), 1)` (PGPainless documents `insertProviderAt(..., 1)`), or use BC's lightweight API without the JCA provider. Spongy Castle is obsolete. FIPS jars not needed.

### A7. OpenPGP
- **PGPainless** `org.pgpainless:pgpainless-core:2.0.4` (2026-07-09), Apache-2.0, BC 1.84; `pgpainless-sop` implements `sop-java` 15.0.1 (2026-02-01). 2.0.0 added OpenPGP v6 keys and an `rfc9580` profile (SEIPDv2/AEAD); 2.0.4 fixed several security issues ([CHANGELOG](https://raw.githubusercontent.com/pgpainless/pgpainless/main/CHANGELOG.md), [Maven](https://central.sonatype.com/artifact/org.pgpainless/pgpainless-core)). Runs on Android with the BC provider swap above.
- **bcpg** direct use: lower level, more room for mistakes; only if PGPainless is too heavy.
- **OpenKeychain**: README says "no longer actively developed", security fixes only; last release 6.0.4 (2024-02-27) ([repo](https://github.com/open-keychain/open-keychain)). Its `openpgp-api` is JitPack-only ([repo](https://github.com/open-keychain/openpgp-api)). Do not depend on it.
- **RFC 9580 vs LibrePGP**: RFC 9580 (July 2024) defines v6 keys, SEIPDv2 (AEAD) and Argon2 S2K. GnuPG follows its own LibrePGP instead and "will not support the current version of OpenPGP"; stable GnuPG is 2.5.22 (2026-08-31) ([Wikipedia](https://en.wikipedia.org/wiki/GNU_Privacy_Guard)). The 2026 Email Summit minutes record "GnuPG breaks when getting v6 keys"; Thunderbird/RNP are pursuing v4-key PQC rather than v6; Proton (GopenPGP v3, RFC 9580 capable) withholds v6 keys from external users ([minutes](https://www.openpgp.org/community/email-summit/2026/minutes/)). Thunderbird warns on keys advertising LibrePGP AEAD flags and does not support that mode ([Mozilla KB](https://support.mozilla.org/en-US/kb/openpgp-secret-keys-with-unsupported-feature-flags)). Kleopatra = GnuPG = LibrePGP. RFC 9580 supporters: Sequoia, OpenPGP.js 6, GopenPGP 3, PGPainless 2, rpgpie ([interop suite](https://sequoia-pgp.gitlab.io/openpgp-interoperability-test-suite/index.html)).
- **What to emit for widest compatibility**: v4 keys; AES-256 in SEIPDv1 (MDC) packets; for passphrase-only messages, a v4 SKESK with iterated-and-salted S2K (SHA-256, high count). Argon2 S2K is only permitted together with AEAD/SEIPDv2 ([openpgp.dev](https://openpgp.dev/book/migration.html)) and GnuPG support for it is not established (UNVERIFIED) → avoid for mail. DidiSoft's compatibility advice is the same: AES symmetric, NIST/Brainpool curves for keys ([DidiSoft](https://didisoft.com/2024/10/08/openpgp-updates-librepgp-and-rfc-9580/)).

### A8. S/MIME
`bcmail` (CMS/EnvelopedData) can produce it. Recipients need a certificate first: Gmail hosted S/MIME only on Enterprise Plus/Education/Frontline Plus Workspace tiers ([Google](https://knowledge.workspace.google.com/admin/gmail/advanced/s-mime-certificate-profiles)); Outlook, Apple Mail, Thunderbird support it natively; Actalis offers free personal certs ([Actalis](https://www.actalis.com/s-mime-certificates)). Because you must hold the recipient's certificate before sending and consumer Gmail cannot decrypt, it is unsuited to ad-hoc sharing.

### A9. age
- Spec (C2SP): textual header + recipient stanzas + header MAC; payload = 64 KiB chunks of ChaCha20-Poly1305 with STREAM nonce counter; 16-byte file key; `scrypt` stanza = 16-byte salt + log2 work factor; no filename/metadata encryption ([spec](https://github.com/C2SP/C2SP/blob/main/age.md)).
- **kage** `com.github.android-password-store:kage:0.7.0` (2026-09-06), Apache-2.0 (README: Apache/MIT dual), Kotlin, Android minSdk 26, depends on BC 1.85.2, no native code ([repo](https://github.com/android-password-store/kage), [Maven](https://central.sonatype.com/artifact/com.github.android-password-store/kage)). Streaming API: UNVERIFIED.
- **Jagged** 1.0.0 (2024-10-10), Apache-2.0, Java 21 → not for Android ([repo](https://github.com/exceptionfactory/jagged)). **age-kotlin** 0.1.1, KMP transliteration of rage, 0 stars, in progress ([repo](https://github.com/KotlinMania/age-kotlin)) → not production-ready.
- Recipient tooling: `age`/`rage` CLIs (Win/mac/Linux); AgePony (free, iOS + Android, opens `.age` from Files) ([AgePony](https://www.agepony.com/)); Mage (Android). No GUI shipped with the OS anywhere.

### A10. SQLCipher for Room
`net.zetetic:sqlcipher-android:4.19.0` (2026-09-08), BSD-style with mandatory attribution in the app UI ([licence](https://www.zetetic.net/sqlcipher/license/)); 16 KB support since 4.6.1 ([Zetetic blog](https://www.zetetic.net/blog/2025/06/26/sqlcipher-for-android-16kb-page-size-support/)). Fit: fine for a local searchable index, but a single DB file changes on every write (whole-file re-upload on sync) and it adds a second KDF/key path. Prefer an AEAD-encrypted manifest (Tink) plus per-file blobs; add SQLCipher only if you need query-heavy metadata at scale.

### A11. Existing vault formats
- **Cryptomator** (format 8 since 1.6.0, 2021-10-19): scrypt → KEK, AES-KeyWrap of two 256-bit masterkeys in `masterkey.cryptomator`, `vault.cryptomator` JWT with format/cipher; 68-byte file header (12-byte nonce, GCM-encrypted content key, tag); content in 32 KiB chunks of AES-GCM with chunk number + header nonce as AAD; AES-SIV filenames with parent directory ID as AAD; flattened `d/` tree, `.c9r`/`.c9s` ([architecture](https://docs.cryptomator.org/en/latest/security/vault/)). Library `org.cryptomator:cryptolib:2.2.2` (2026-01-30) is AGPL-3.0 or commercial ([repo](https://github.com/cryptomator/cryptolib)): using it in a non-AGPL app requires a paid licence. Reimplementing the published format from the spec avoids the library licence (legal review advised). A cross-vendor "Unified Vault Format" with key rotation is in progress ([Cryptomator blog](https://cryptomator.org/blog/2025/07/24/post-quantum-roadmap/)).
- **KDBX 4.1**: Argon2d/id or AES-KDF; AES-256/ChaCha20; HMAC-SHA-256 1 MB blocks; attachments in the encrypted inner header ([KeePass](https://keepass.info/help/kb/kdbx_4.html)). Libraries: kotpass `app.keemobile:kotpass:0.13.0` (MIT, ~Oct 2025, KDBX 4.1) ([repo](https://github.com/keemobile/kotpass)); KeePassJava2-jackson 2.2.4 (2025-03-05, Apache-2.0) ([repo](https://github.com/jorabin/KeePassJava2)). One monolithic file → every change re-uploads the whole vault; unsuitable for large arbitrary files.
- **gocryptfs**: gocryptfs4j 0.3.0 (MIT, Java 8+, read/write, AES-GCM/XChaCha20) ([repo](https://github.com/sfuhrm/gocryptfs4j)); libgocryptfs is incomplete. Filesystem-oriented, no manifest/versioning; viable but niche. **EncFS** deprecated (UNVERIFIED current status; known audited weaknesses).
- Verdict: Cryptomator's *design* is the right template for "arbitrary files + metadata + sync-friendly"; the library licence is the blocker (resolved by the brief: the app is GPL-3.0-or-later, so cryptolib's AGPL-3.0 is usable).

### A12. Password strength and secure memory
zxcvbn4j `com.nulab-inc:zxcvbn:1.9.0` (MIT, ~2024) ([Maven](https://central.sonatype.com/artifact/com.nulab-inc/zxcvbn)); nbvcxz `me.gosimple:nbvcxz:1.5.1` (MIT, ~2023, used by KeePassDX) ([repo](https://github.com/GoSimpleLLC/nbvcxz)). Both stale but stable. JVM limits: keep passwords in `CharArray`/`ByteArray` and zero them; `String` is immutable and copies survive in the heap; no `mlock`; GC compaction copies buffers; native/direct buffers (as argon2kt uses) reduce but do not eliminate exposure.

## B. Container formats for emailing an item

Delivery facts: Gmail's blocked list is executables/scripts (.exe, .js, .iso, .apk …) and explicitly "Password-protected archives with archived content" and blocked types found inside archives; `.zip`, `.7z`, `.html`, `.pdf`, `.gpg`, `.age` are not in the list ([Google](https://support.google.com/mail/answer/6590)). In practice Gmail delivers encrypted archives with an "encrypted attachment warning" and scans only the file *names* inside archives ([Labnol](https://www.labnol.org/internet/email/virus-in-gmail-due-to-password-protected-files/5696)). Outlook.com's blocked list also excludes all of the above ([Microsoft](https://support.microsoft.com/en-us/office/blocked-attachments-in-outlook-434752e1-02d3-4e90-9124-8b81e49a8519)). Exchange Online Safe Attachments cannot scan password-protected files; a new opt-in "Block messages containing encrypted attachments that could not be scanned" (default off, rollout from Aug 2026) quarantines them for zip/gzip/7z/rar/tar/pdf/Office, with user self-release by entering the password ([Microsoft Learn](https://learn.microsoft.com/en-us/defender-office-365/safe-attachments-about)). iCloud Mail: "all file types are supported" ([Apple](https://support.apple.com/en-mide/108329)).

1. **OpenPGP symmetric (.gpg)**: recipient uses GnuPG/Kleopatra/Gpg4win, GPG Suite, OpenKeychain (dormant), PGPony-type apps; non-technical users struggle. Produce with PGPainless (v4 SKESK + SEIPDv1 + AES-256). Public-key mode requires the recipient's key. PGP/MIME (RFC 3156) shows as empty body + `encrypted.asc` in clients without PGP ([RFC 3156](https://www.rfc-editor.org/rfc/rfc3156.html)); a plain `.gpg` attachment is more robust. Crypto: sound with SEIPDv1/MDC; S2K is SHA-based, not memory-hard.
2. **ZIP AES-256 (WinZip AE-2)**: zip4j `net.lingala.zip4j:zip4j:2.11.6` (2026-02-11, Apache-2.0) writes AES-256 ([repo](https://github.com/srikanth-lingala/zip4j)). Opens with 7-Zip, WinRAR, PeaZip, Keka/The Unarchiver, iZip; Windows Explorer cannot (error 0x80004005) ([Chilkat](https://www.chilkatsoft.com/p/p_168.asp)), macOS Archive Utility cannot ([Setapp](https://setapp.com/how-to/password-protect-zip)), iOS Files cannot open password-protected zips ([Apple thread](https://discussions.apple.com/thread/251537690)). Never use ZipCrypto: Biham–Kocher known-plaintext attack, tooling `bkcrack` ([bkcrack](https://github.com/De30/bkcrack)). Filenames, sizes, timestamps leak from the central directory. AES-CTR + HMAC-SHA1 per entry; PBKDF2-SHA1 1000 iterations (weak KDF, so needs a long passphrase).
3. **7z AES-256 + header encryption**: Commons Compress: encryption "only supported when reading" 7z; ZIP: "no support for encryption" ([limitations](https://commons.apache.org/proper/commons-compress/limitations.html)). Only writer is 7-Zip-JBinding-4Android 16.02-2.03 (LGPL-2.1, JitPack, 7-Zip 16.02, "16 KB ELF alignment") ([repo](https://github.com/omicronapps/7-Zip-JBinding-4Android)) — native, old engine, small project. Recipients: 7-Zip, Keka, ZArchiver; Windows 11 24H2 Explorer opens 7z but not encrypted ones ([Pureinfotech](https://pureinfotech.com/window-11-extract-rar-7zip-archival-formats/)). `-mhe` hides names.
4. **age (.age)**: kage writes it; scrypt passphrase recipient is memory-hard; ChaCha20-Poly1305 STREAM. Recipient needs age/rage CLI or AgePony/Mage; no OS-native support. Filename not stored at all (send name separately or wrap a zip/tar inside).
5. **Password-protected PDF (documents only)**: PDFBox 3.0.8 (2026-07-15, Apache-2.0) writes AES-256 revision 6 (`prepareEncryptionDictRev6`) ([source](https://fossies.org/linux/pdfbox/pdfbox/src/main/java/org/apache/pdfbox/pdmodel/encryption/StandardSecurityHandler.java)); OpenPDF 3.0.5 requires Java 21 → not Android. Acrobat and pdf.js/Chrome open R6; macOS Preview's R6 support is reported as absent by vendor docs (UNVERIFIED for current macOS) ([Tungsten](https://docshield.tungstenautomation.com/powerpdf/en_us/4.2.0-nfgf7crc1x/help/en/permissions.html)); AES-128 (R4) is universally readable but weaker (RC4-era KDF with SHA-256 in R6 only). Only applies to PDFs.
6. **Self-decrypting HTML** (StatiCrypt: WebCrypto AES-CBC + 600k PBKDF2 ([repo](https://github.com/robinmoisson/staticrypt)); portable-secret uses AES-GCM): needs only a browser and works offline; `.html` is not on the Gmail or Outlook.com blocked lists, but many corporate filters block HTML attachments and Gmail's viewer will not execute it (must be downloaded). Security caveats: indistinguishable from phishing, no integrity binding to sender, payload size limited to a few MB, PBKDF2 not memory-hard. Java side: trivial (template + AES-GCM ciphertext in base64).
7. **S/MIME**: see A8. Requires recipient certificate exchange; excluded from Safe Attachments dynamic delivery; consumer Gmail cannot decrypt.
8. **Proprietary `.cvault`**: strongest crypto (Argon2id + AES-GCM/XChaCha20), but no iOS/desktop reader unless you ship a web decryptor (which is then item 6) or desktop CLI. Unscannable → same Safe Attachments risk.

| Format | Recipient tooling | Java/Kotlin lib | Gmail/Outlook delivery risk | Crypto quality | Name/metadata leakage | Verdict |
|---|---|---|---|---|---|---|
| OpenPGP symmetric .gpg | GnuPG/Kleopatra/GPG Suite; apps | PGPainless 2.0.4 (Apache) | Low (unknown type, unscannable) | Good (AES-256 SEIPDv1); S2K not memory-hard | Inner filename in literal packet (encrypted) | Good for technical recipients |
| ZIP AES-256 | 7-Zip/WinRAR/Keka/iZip; not Explorer/Archive Utility/iOS Files | zip4j 2.11.6 (Apache) | Medium: warning in Gmail, quarantine if EXO "block unscanned" on | Acceptable; PBKDF2-SHA1 1000 iters | Names, sizes, dates visible | Best "everyone has a tool" option |
| 7z AES-256 + `-mhe` | 7-Zip/Keka/ZArchiver; not Explorer | 7-Zip-JBinding-4Android (LGPL, JitPack, native) | Same as ZIP | Good; header encrypted | Hidden | Good format, weak Java writer |
| age | age/rage CLI, AgePony, Mage | kage 0.7.0 (Apache) | Low | Excellent (scrypt, ChaCha20-Poly1305) | No name stored | Best crypto, niche tooling |
| PDF AES-256 R6 | Acrobat, Chrome, Edge; Preview UNVERIFIED | PDFBox 3.0.8 (Apache) | Low (PDF), may be quarantined under EXO | Good (R6) | Fine | Documents only |
| Self-decrypting HTML | Any browser, offline | Own code | Medium (HTML filters, phishing heuristics) | Good if AES-GCM + PBKDF2/Argon2-wasm | Fine | Good UX, phishing-lookalike risk |
| S/MIME | Outlook/Apple Mail/Thunderbird; Workspace Enterprise only | bcmail 1.86 | Low | Good | Subject/headers in clear | Not for ad-hoc sharing |
| .cvault | CryptVault only | Own | Low | Excellent | Fine | Only with a web/desktop decryptor |

## C. Recommended cryptographic design (as researched, before the format decision)

- **Key hierarchy**: master password → Argon2id (argon2kt; 64 MiB, t=3, p=4 target, calibrated, parameters + 16-byte salt in header) → 256-bit KEK. Random 256-bit vault DEK (plus a separate 256-bit filename/SIV key, both derived from one root via HKDF). DEK wrapped with AES-256-GCM (or AES-KeyWrap) under KEK; optional second wrap under a Keystore AES-GCM key with `setUserAuthenticationParameters` (biometric cache, device-local, excluded from backup); third wrap under a random 256-bit **recovery key** shown once as a BIP39/base32 string. Password change = re-wrap only; no file re-encryption.
- **Per-file keys**: each file gets a random 256-bit content key stored in a per-file header encrypted under the DEK (Cryptomator-style). This makes per-file re-keying and key rotation cheap and removes nonce-reuse risk across files.
- **Content encryption**: chunked AEAD. Either Tink `AES256_GCM_HKDF_1MB` StreamingAEAD with the file ID as associated data (simplest, seekable), or hand-rolled 32–64 KiB chunks of AES-256-GCM/XChaCha20-Poly1305 with AAD = header nonce ‖ chunk index ‖ final-flag (prevents reordering/truncation). Prefer Tink unless you need a format other tools can read.
- **Names/metadata**: encrypt filenames with AES-SIV (deterministic, parent-dir ID as AD) or keep names only in the encrypted manifest and store blobs under random IDs (simpler, no lookup by name on disk). Manifest = protobuf/CBOR encrypted with AES-GCM under DEK, with monotonic version counter and MAC → whole-vault integrity and rollback detection.
- **Header**: magic, format version, KDF id + params, salt, wrapped DEK(s), header MAC. Reserve fields for future KDFs/ciphers.
- **Atomicity**: write to temp file in the same directory, fsync, atomic rename; write blob before manifest; manifest journal (`manifest.N`) with last-good pointer; verify GCM tags on every read.
- **Cloud-sync layout**: one directory per vault; immutable content blobs named by random ID (or content hash) so sync only uploads new/changed files; small manifest changes each commit; never rewrite blobs in place; delete via manifest tombstones + later GC. Do not use a single monolithic DB.
- **Rotation**: rotate DEK by re-wrapping per-file content keys (headers only) — O(files) small writes, no content rewrite; keep key IDs in headers for lazy migration.

## Recommendations for the brief (as researched)

- Core crypto: **Tink 1.23.0** (Apache-2.0) for AEAD and StreamingAEAD; pure Java, no 16 KB concerns.
- KDF: **argon2kt 1.6.0** (MIT, native, 16 KB-ready); fallback to Bouncy Castle `Argon2BytesGenerator` (pure Java) on devices where the AAR fails to load.
- Do not use Jetpack Security (deprecated) or Tink's Keystore-wrapped keysets as the primary key store; use Keystore only as a biometric cache wrap of the DEK.
- Mandatory recovery key at vault creation; warn that biometric unlock is lost on reinstall/new device.
- Vault format: own format modelled on Cryptomator 8 (per-file header, chunked GCM, encrypted manifest), not cryptolib (AGPL), not KDBX (monolithic), not SQLCipher for content.
- Metadata: encrypted manifest file; add SQLCipher (BSD, attribution required) only if search over many thousands of entries demands it.
- Email sharing default: **ZIP AES-256 via zip4j** (widest recipient tooling) with an in-app hint listing free openers; force AES, forbid ZipCrypto, put files inside an inner folder with generic names if name leakage matters.
- Offer **age (kage 0.7.0)** as "strong" option for technical recipients and **OpenPGP symmetric via PGPainless 2.0.4** for GnuPG users; emit v4/SEIPDv1/AES-256 only.
- Skip 7z writing (only native JitPack wrapper), S/MIME and PGP/MIME for ad-hoc sharing.
- Consider a self-decrypting HTML export (AES-GCM, PBKDF2 or Argon2 WASM) as the "no tools needed" option, clearly branded and offline-only; expect some corporate filters to drop it.
- Warn senders that encrypted attachments may be quarantined by Exchange Online tenants with "block unscanned attachments"; provide a share-link fallback.
- Bouncy Castle 1.86 is pulled in transitively (kage, PGPainless); register it with `removeProvider("BC")` + `insertProviderAt(…, 1)` once at startup.
- Password strength: zxcvbn4j 1.9.0 (MIT) with a minimum score gate.
- Library shortlist (licences): Tink (Apache-2.0), argon2kt (MIT), zip4j (Apache-2.0), kage (Apache-2.0), PGPainless (Apache-2.0), Bouncy Castle (MIT-style), PDFBox (Apache-2.0, optional), SQLCipher (BSD + attribution, optional), zxcvbn4j (MIT).

## Open questions for the app owner

1. Is the app closed-source? (Decides cryptolib AGPL vs. own format; SQLCipher attribution obligation.)
2. Typical file sizes and count (streaming thresholds, manifest vs. DB).
3. Which recipients matter most for email sharing: non-technical Windows/mac users (ZIP AES), security-minded (age/OpenPGP), or corporate Exchange tenants (quarantine risk)?
4. Is a web-based decryptor (hosted or self-contained HTML) acceptable, or must everything stay offline/native?
5. Which cloud targets for backup (SAF/Drive/WebDAV) and whether sync is one-way backup or multi-device (affects manifest conflict handling)?
6. Required minSdk (kage needs 26, lazysodium 24; Tink likely 24 soon).
7. Is Keystore/StrongBox attestation or device-binding wanted, or is portability (restore on new phone with password) the priority?
8. Should the recovery key be mandatory, and is there any cloud/escrow expectation?
9. Is post-quantum a stated requirement (would favour age/OpenPGP v6 roadmaps over ZIP)?

---

Note added by the brief's author: the interview answered questions 1–8 (open source
GPL-3.0-or-later → cryptolib adopted, so Tink and argon2kt are **not** used for the vault; sizes
up to multi-GB; ZIP+age+OpenPGP; no HTML; four cloud targets, one-way backup; minSdk 26;
portability over device binding; recovery key mandatory, no escrow). Question 9 is carried in
`BUILD_BRIEF.md` §13 as an assumption (not a v1 requirement).
