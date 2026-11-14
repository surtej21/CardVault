# Security Policy

Aura Vault is a local-only Android vault. Security reports are welcome and
taken seriously.

## Reporting a vulnerability

Please do **not** open a public issue for a vulnerability.

Use GitHub's private reporting flow: **Security → Advisories → Report a
vulnerability** on this repository. Include:

- the affected version or commit,
- a description of the issue and its impact,
- steps to reproduce, and
- any suggested fix.

You will get an acknowledgement as soon as possible. Please allow time for a
fix before public disclosure.

## Scope

In scope:

- vault encryption and key derivation (`VaultStore.kt`),
- biometric and session handling (`SessionKeeper.kt`, `MainActivity.kt`),
- backup export and import,
- clipboard and sharing behaviour,
- manifest and permission regressions, and
- any path that could move vault data off the device.

Out of scope:

- issues that require a rooted device or physical extraction of app-private
  storage,
- denial of service on the developer's own machine, and
- reports about the prototype status documented in the README.

## Design invariants

These must never regress:

- No `android.permission.INTERNET`.
- `android:allowBackup="false"`.
- `FLAG_SECURE` on sensitive windows.
- AES-256-GCM for vault payloads.
- Sensitive clipboard entries tagged and cleared.

## Supported versions

This is a prototype. Only the latest commit on `main` is supported.
