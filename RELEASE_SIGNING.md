# Univest AutoTrade stable signing (v2.9.19 onward)

**Android package**: `com.suhas.multyfideliverybuy`

**Current certificate SHA-256**: `35823034c0213bb4a60be2d59e0e2763a6ad21ad3cb19508faca9148b09d461d`

Version 2.9.19 uses this newly created, encrypted, backed-up PKCS#12
signing identity because the earlier private key was unavailable and
the owner confirmed uninstalling the previous APK. This identity
**differs from** the v2.9.11-v2.9.18 certificate `a6a31f47...`.
An in-place update from v2.9.18 to v2.9.19 cannot succeed because
Android forbids a different signing identity. A fresh install is required.

From v2.9.19 onwards, ALL installable APK builds must be signed with the
**same** encrypted PKCS#12 key and verified against the pinned public
certificate SHA-256 above. Never generate a new signing key during routine
release builds. Future builds require the owner to retain the encrypted
keystore backup and password separately; the private material is not
in this repository and must never be committed.

The GitHub Actions workflow accepts these protected secrets (set using the
v2.9.19 private key, and never previous credentials):
- `RELEASE_KEYSTORE_B64`: base64 representation of the v2.9.19 encrypted keystore
- `RELEASE_STORE_PASSWORD`: keystore password
- `RELEASE_KEY_ALIAS`: `univest-autotrade-release`
- `RELEASE_KEY_PASSWORD`: signing key password

When these secrets are unset, the workflow produces an UNSIGNED-ALIGNED
APK. It must be signed offline with the preserved v2.9.19 identity.
The public `RELEASE_SIGNING_CERT.pem` is not secret. The workflow must
reject any output signed with an unexpected certificate.

Keep backups of the keystore and password securely and separately.
A lost key requires a new fresh installation and can erase local data.
