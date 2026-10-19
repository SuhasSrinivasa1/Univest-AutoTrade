# Stable Univest AutoTrade release signing (v2.9.11 onward)

**Pinned Android package:** `com.suhas.multyfideliverybuy`

**Pinned release certificate SHA-256:** `a6a31f47de23f019de4e6718fb430e56c4d83ebd9eb9b9d8c08fccfbc684d1fe`

The v2.9.11 clean-install signing identity is the certificate used for **every
owner-installed signed APK from v2.9.11 through v2.9.18**. v2.9.18 has been
independently verified against the v2.9.17 signed APK and this pinned certificate.

The old v2.9.3 "stable release" certificate (`31ece87c...`) is an
**incompatible legacy identity**; do not use it for the current APK line.
Android does not accept an in-place update signed by a different certificate.

## Private key handling

The corresponding private key is an encrypted PKCS#12 keystore. It must be
preserved securely, backed up offline by the owner, and reused for each future
APK. **Never generate a replacement key for routine version upgrades.**
Never commit the keystore, password, signing key or token to this repository.

If GitHub Actions signing is enabled, configure its protected repository secrets
to refer to **this exact key** (convert to a compatible keystore format if needed):

- `RELEASE_KEYSTORE_B64` — base64 of the *same* signing keystore
- `RELEASE_STORE_PASSWORD` — encrypted keystore password
- `RELEASE_KEY_ALIAS` — the actual signing-key alias
- `RELEASE_KEY_PASSWORD` — the actual key password

CI's APK signing step verifies that the resulting APK matches
`RELEASE_SIGNING_CERT_SHA256.txt` and aborts if the signer is different.
If those secrets are absent, CI intentionally produces an **unsigned aligned**
APK, which must then be signed offline with the preserved private key and
verified against this certificate before distribution.

The PEM in `RELEASE_SIGNING_CERT.pem` is a **public certificate only**,
not a signing secret. Keep its contents and SHA-256 pin synchronized whenever
checking releases. Never substitute a fresh-install signer silently.
