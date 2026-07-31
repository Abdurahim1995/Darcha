# Releasing Darcha

Everything needed to cut a signed release, and nothing that belongs in a secret
store. **No credential in this file is real** — every value below is a
placeholder you replace with your own.

---

## 1. Signing keys — one-time setup

The release build is signed from a keystore that lives **outside the
repository**. `.gitignore` covers `*.jks`, `*.keystore` and `keystore.properties`
already; `git check-ignore -v keystore.properties` will confirm it on your
machine.

### 1.1 Create the keystore

If you have not made one yet, run this and answer the prompts. Choose a strong
password and store it in your password manager — **a lost release key cannot be
recovered, and a Play Store listing can never be updated without it.**

```bash
keytool -genkeypair -v -keystore ~/keystores/darcha-release.jks -alias darcha -keyalg RSA -keysize 4096 -validity 10000
```

Keep the file somewhere backed up and *not* inside the project directory. The
25-year validity is deliberate: Play requires a key valid past 2033.

### 1.2 Write `keystore.properties`

Create the file at the **project root** — `Katakcha/keystore.properties`, beside
`settings.gradle.kts` — with exactly these four keys:

```properties
storeFile=/Users/you/keystores/darcha-release.jks
storePassword=<the keystore password you chose>
keyAlias=darcha
keyPassword=<the key password you chose>
```

Notes:

- `storeFile` may be absolute (as above) or relative to the project root. It is
  resolved with `rootProject.file(...)`.
- `keyAlias` must match the `-alias` you passed to `keytool`.
- If you accepted the same password for both prompts, `storePassword` and
  `keyPassword` are the same value.

### 1.3 Check the wiring, not the secrets

```bash
git check-ignore -v keystore.properties && ./gradlew :app:assembleRelease
```

The build behaves in three distinct ways, on purpose:

| `keystore.properties` | Result |
|---|---|
| Absent | Builds `app-release-unsigned.apk`. **This is not an error** — CI has no keystore and must still be able to run `./gradlew build`. |
| Present, all four keys set | Builds a signed `app-release.apk`. |
| Present, a key missing or blank | **Build fails**, naming the missing keys. A typo in a credential must not quietly yield an unsigned APK that looks signed. |

Verify a signed APK before uploading it:

```bash
$ANDROID_HOME/build-tools/36.0.0/apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
```

---

## 2. Cutting a release

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
./gradlew clean build            # all 319 tests must pass
./gradlew :app:assembleRelease   # signed APK, R8 + resource shrinking
```

Then check the size has not drifted:

```bash
ls -l app/build/outputs/apk/release/app-release.apk
```

The v1.0.0 APK is **1.10 MB**, against the TECH_SPEC §5 budget of 5 MB.

### Version numbers

Both live in `app/build.gradle.kts`. `versionCode` must increase on **every**
uploaded build, even a re-upload of the same `versionName`.

| Release | `versionCode` | `versionName` |
|---|---|---|
| v1.0.0 | 1 | `1.0.0` |

---

## 3. Verifying a release build on a device

R8 changes what is in the APK, so a release build is verified by what appears on
screen — **not** by logs. `proguard-android-optimize.txt` strips `Log.d` and
`Log.v`, so every diagnostic this project measures with is absent from a release
build. See `docs/PERF.md`.

To exercise the exact shipping bytes without your release key, sign a copy with
the debug key. Signing does not alter the DEX, so this verifies the same code:

```bash
BT=$ANDROID_HOME/build-tools/36.0.0
$BT/zipalign -p -f 4 app/build/outputs/apk/release/app-release-unsigned.apk /tmp/aligned.apk
$BT/apksigner sign --ks ~/.android/debug.keystore --ks-pass pass:android --key-pass pass:android --ks-key-alias androiddebugkey --out /tmp/darcha-check.apk /tmp/aligned.apk
adb install -r /tmp/darcha-check.apk
```

The run-through: open `values-basic`, `dates`, `styles-basic`, `merged`,
`frozen-both`, `multisheet` and `big-50k`; scroll and pinch the big one; switch
sheets; confirm recents survive a restart; trigger at least one error screen; and
check `adb logcat -b crash` is empty afterwards.

---

## 4. Publishing

1. Tag the commit: `git tag -a v1.0.0 -m "Darcha v1.0.0" && git push origin v1.0.0`
2. Create the GitHub Release from that tag, using `docs/RELEASE_NOTES_v1.0.0.md`.
3. Attach `app-release.apk` — **the one signed with your release key**, not the
   debug-signed verification copy.
