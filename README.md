# OneAssist

A personal finance helper for Android: finds money leaks, tracks subscriptions, gives savings tips,
and has a private assistant that knows your own spending. Everything is stored on the phone.

## Install
1. Open the repo's **Releases** page on your phone and download `OneAssist.apk` from **latest**.
2. Allow installing from your browser when Android asks, then open the APK.

## One-time setup: signing key
So that new versions install over the old one without wiping your data, the APK must always be
signed with the same key. On a computer with Java installed, run:

    keytool -genkeypair -keystore oneassist.p12 -storetype PKCS12 -alias oneassist -keyalg EC -groupname secp256r1 -validity 10000 -dname "CN=OneAssist"
    base64 -w0 oneassist.p12      # macOS: base64 -i oneassist.p12

Then add two repository secrets (Settings → Secrets and variables → Actions):
`ONEASSIST_KEYSTORE_BASE64` (the base64 output) and `ONEASSIST_KEYSTORE_PASSWORD` (the password you chose).
The key alias defaults to `oneassist` and the key password to the keystore password.
Without the secrets, CI builds a debug APK and does not publish a release. Keep `oneassist.p12` backed up:
if it is lost, the next install needs an uninstall, which wipes the app's data.

See ARCHITECTURE.md for how it works.
