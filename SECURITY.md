# Security

This repository contains application source code only.

Do not commit:
- Groww API/TOTP tokens, TOTP secrets, access tokens, generated OTPs, or account identifiers.
- Android keystores, signing keys, certificate private keys, or signing passwords.
- User trading history exports, SQLite databases, diagnostic ZIPs, or other account-derived runtime data.
- Local environment files containing credentials.

If a credential is ever committed, revoke/rotate it immediately and remove it from Git history rather than relying only on a later deletion commit.
