# Card Vault (early prototype)

A personal, offline Android vault prototype.

## Current state

The app has master-password setup and unlock, a one-time recovery key, encrypted card and password entries, password generation, optional fingerprint unlock, selected-field sharing with confirmation, and sensitive clipboard handling. It has not been independently security-reviewed or exercised on a physical device. Keep using dummy information until those checks are complete.

## Project

- Kotlin with Android Gradle Plugin 9.4.0
- Minimum Android 11 (API 30)
- No network permission or backend configured
- Android backup disabled in the manifest
- Vault payload encrypted with AES-GCM; the random data key is wrapped by a PBKDF2-HMAC-SHA256 master-password key and a random recovery key
- Optional fingerprint unlock wraps the data key with an Android Keystore key requiring strong biometric authentication
- Screenshots are blocked; clipboard values are marked sensitive and cleared after 30 seconds if the clipboard still contains the copied value

Open this folder in Android Studio and allow Gradle sync. Android Studio may offer to install the required Android SDK. To run it, enable Developer options and USB debugging on an Android 11+ device, connect it by USB, then press Run; an emulator also works.

## Security notes

The master password must be at least 12 characters. The recovery key is shown during setup and replacement; keep it outside the phone and private. Losing both the password and recovery key means losing access to the vault. Changing or removing enrolled fingerprints can disable fingerprint unlock; the master password and recovery key remain the recovery path.

The app still needs a focused security review and device-level checks before storing real payment credentials. Sharing sends selected information to another app; CVV is excluded by default and requires confirmation when copied or shared.

## Web dashboard concept

A separate React/Next.js UI prototype lives in `web/`. It is for exploring a premium desktop and mobile interface only; it is not connected to the Android vault and must not receive real credentials. See `web/README.md` for setup instructions.
