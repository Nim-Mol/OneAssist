# OneAssist

A personal finance helper for Android: finds money leaks, tracks subscriptions, gives savings tips,
and has a private assistant that knows your own spending. Everything is stored on the phone.

## Install
1. Open the repo's **Releases** page on your phone and download `OneAssist.apk` from **latest**.
2. Allow installing from your browser when Android asks, then open the APK.

## One-time setup: signing key
So that new versions install over the old one without wiping your data, the APK must always be
signed with the same key. Add these repository secrets (Settings → Secrets and variables → Actions):
`ONEASSIST_KEYSTORE_BASE64`, `ONEASSIST_KEYSTORE_PASSWORD`, `ONEASSIST_KEY_ALIAS`, `ONEASSIST_KEY_PASSWORD`.
Without them, CI builds a debug APK.

See ARCHITECTURE.md for how it works.
