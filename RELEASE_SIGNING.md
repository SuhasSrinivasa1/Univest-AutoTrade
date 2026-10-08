# Stable release signing

Univest AutoTrade v2.9.3 establishes a permanent release-signing identity.

The private signing key must **never** be committed to this repository. Future GitHub Actions releases use these repository secrets:

- `RELEASE_KEYSTORE_B64` — base64 of the stable JKS/keystore
- `RELEASE_STORE_PASSWORD` — keystore password
- `RELEASE_KEY_ALIAS` — key alias
- `RELEASE_KEY_PASSWORD` — key password

If these secrets are absent, CI deliberately produces an **UNSIGNED-ALIGNED** APK instead of inventing a new signing identity. This prevents accidental certificate changes and preserves Android update compatibility.

The v2.9.3 stable signing bundle is distributed privately to the app owner and is intentionally excluded from source control and release source ZIPs.

## Pinned public certificate

`RELEASE_SIGNING_CERT.pem` and `RELEASE_SIGNING_CERT_SHA256.txt` are public verification material only.
The workflow verifies that any secret-backed signed release matches this pinned certificate. The private JKS and
passwords must never be committed. The pinned SHA-256 fingerprint for the stable identity is:

`31ece87c32219dfc98b1dead1e0713c2a2457f62955263492b2b08a4b3f6cb9a`
