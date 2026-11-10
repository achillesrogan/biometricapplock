# Biometric App Lock

An Android app that covers other apps until a strong biometric unlocks a key in the Android Keystore.

Package name: `com.achillesrogan.applock`. Repository: https://github.com/achillesrogan/biometricapplock

Previously named Fingerprint App Lock (v1.2 and earlier); same package ID and signing certificate.

Current version: **1.3** (versionCode 4).

## Features

- Locks chosen apps behind a **strong** (class 3) biometric, typically a fingerprint. The prompt is AndroidX `BiometricPrompt` with `BIOMETRIC_STRONG` only. A device PIN, pattern, or password is not an unlock method.
- The prompt is bound to an Android Keystore AES key through `BiometricPrompt.CryptoObject`. The app continues only after `Cipher.doFinal` succeeds. The key requires authentication on every use, and enrolling or removing a biometric invalidates it (`setInvalidatedByBiometricEnrollment`).
- **Fails closed.** If no strong biometric is enrolled, or the sensor is unavailable, the settings list stays hidden and a locked app stays covered.
- `FLAG_SECURE` on the settings screen and the lock screen, so those windows are left out of screenshots and the Recents preview.
- `filterTouchesWhenObscured` on both screens, so a touch that passes through another window cannot flip a lock switch or press the unlock button.
- Search at the top of the app list. It filters live, case-insensitively, by app name and package name. Lock switches keep the real saved state while the list is filtered. A search with no hits shows an empty state. The field is inside the authenticated content and is hidden until biometric auth succeeds.
- Edge-to-edge layout for target SDK 35. Content sits below the status bar, and the last list row scrolls above the navigation bar for both gesture and 3-button navigation. Light and dark themes use the Material 3 day/night theme.

## Screenshots

No screenshots are in the repository yet.

## Requirements

- Android 8.0 or newer (minSdk 26). The project compiles and targets Android SDK 35.
- A strong biometric enrolled on the device. Without one, the app does not open its settings and does not unlock protected apps.

## Install from an APK

This repository does not attach release binaries yet. When a file is published (for example on a GitHub Release), check it before installing.

1. Compute the SHA-256 of the file you downloaded and compare it to the checksum published next to that file.

   ```bash
   sha256sum biometricapplock-<version>-release.apk
   ```

   On Windows: `certutil -hashfile biometricapplock-<version>-release.apk SHA256`

   A checksum only matches the exact file it was generated for. An APK you build yourself will have a different hash.

2. Allow installation from that source if Android asks. On Android 8 and newer this is per-app: the browser or file manager needs **Install unknown apps**.
3. Open the APK and install it. Version 1.3 (versionCode 4) uses application id `com.achillesrogan.applock`. It does not replace the 1.0 or 1.1 test installs, which used a different application id.
4. Open **Biometric App Lock** and authenticate with a fingerprint. If none is enrolled, the app stays on an explanation screen.
5. Turn on its accessibility service: **Open settings** on the “Protection is off” card, then *Installed apps* / *Downloaded apps* → *Biometric App Lock* → on.
   - On Android 13 and newer, a sideloaded app’s accessibility switch can be greyed out (“Restricted setting”). Use *Settings → Apps → Biometric App Lock → ⋮ → Allow restricted settings*, then try the switch again.
6. In the app, turn on the switch for each app you want to lock.

## How it works

| Piece | What it does |
|---|---|
| `MainActivity` | Lists launchable apps and a switch for each. Hidden until strong biometric auth succeeds. Includes search. |
| `AppLockService` | Accessibility service. Listens only for a window coming to the front and reads that window’s package name. |
| `LockActivity` | Full-screen cover over a locked app. Cancel and Back go to the home screen. If the biometric is unavailable, the cover stays up. |
| `Biometrics` | Creates the Keystore key and runs the `CryptoObject` prompt. |
| `LockStore` | Saves the set of locked package names in app-private `SharedPreferences`. |
| `LockState` | In-memory set of apps unlocked right now. An app locks again when you leave it, or when the screen turns off. |

The service ignores this app’s own windows, the system UI, and the keyboard, so the lock screen and the IME are not treated as “leaving” the locked app.

### Permissions

Checked against `BiometricAppLock/app/src/main/AndroidManifest.xml` and the debug merged manifest.

| Permission or capability | Why it is there |
|---|---|
| `android.permission.USE_BIOMETRIC` | Declared by this app. Shows the system biometric prompt. |
| `android.permission.USE_FINGERPRINT` | Not declared in this app’s manifest. AndroidX Biometric adds it in the merged manifest so older platforms can show the prompt. |
| `com.achillesrogan.applock.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | Signature permission added by AndroidX to protect its own non-exported receivers. It is not a privacy capability of this app. |
| Accessibility service | You enable it in system Settings. It receives `TYPE_WINDOW_STATE_CHANGED` only. `canRetrieveWindowContent` is false. The service element requires `android.permission.BIND_ACCESSIBILITY_SERVICE`, so only the system can bind to it. |
| `<queries>` | On Android 11 and newer, two intents: launcher activities (the app list) and the home screen (Back from the lock screen sends you home). |
| No `INTERNET` | The source manifest and the merged debug manifest do not request `INTERNET`. |

`android:allowBackup="false"`, so Android Backup does not include the lock list.

## Privacy

The app does not declare a network permission, and the source does not open sockets, HTTP clients, or analytics. Nothing in this project uploads the lock list, the app list, or the foreground package name.

What stays on the device:

- The set of locked package names, in app-private preferences (`app_lock_prefs`).
- A boolean that remembers to explain a fingerprint-enrollment change (`biometric_gate`).
- Which apps are unlocked right now, only in memory, cleared when you leave an app or the screen turns off.
- The Keystore key, which never leaves the Android Keystore. The app does not store the ciphertext from the auth check.

To do its job locally, the app reads the list of launchable apps and, while the accessibility service is on, the package name of the window that came to the front. It does not read screen contents or what you type.

## Build from source

Requirements: JDK 17, Android SDK 35. The Android Gradle Plugin is 8.7.3, Kotlin is 2.0.21, and the wrapper uses Gradle 8.11.1. Java source and bytecode target are 17.

```bash
cd BiometricAppLock
./gradlew assembleDebug
./gradlew assembleRelease
```

- Debug APK: `BiometricAppLock/app/build/outputs/apk/debug/app-debug.apk`. Android Gradle Plugin signs it with the debug keystore on that machine (`~/.android/debug.keystore`). That file is not part of this repository.
- Release APK: `BiometricAppLock/app/build/outputs/apk/release/`. There is no `signingConfig` in `app/build.gradle.kts`, so `assembleRelease` produces an unsigned APK until you add your own signing config locally.

Do not commit signing keys. The root `.gitignore` ignores `*.jks`, `*.keystore`, and `local.properties`.

You can also open the `BiometricAppLock` directory in Android Studio (Ladybug or newer) and run the `app` configuration. On an emulator, enroll a fingerprint under Settings, then use *Extended controls → Fingerprint → Touch sensor* when the prompt appears.

## Project structure

```
.
├── LICENSE                 GNU GPLv3 (the app’s license)
├── README.md
├── .gitignore
└── BiometricAppLock/
    ├── build.gradle.kts    Android Gradle Plugin 8.7.3, Kotlin 2.0.21
    ├── settings.gradle.kts
    ├── gradle.properties
    ├── gradlew             Gradle 8.11.1 wrapper (upstream Apache-2.0 script)
    └── app/
        ├── build.gradle.kts
        └── src/main/
            ├── AndroidManifest.xml
            ├── java/com/achillesrogan/applock/
            │   ├── MainActivity.kt
            │   ├── LockActivity.kt
            │   ├── AppLockService.kt
            │   ├── Biometrics.kt
            │   ├── LockStore.kt
            │   ├── LockState.kt
            │   ├── AppListAdapter.kt
            │   └── SystemBars.kt
            └── res/         layouts, strings, themes, accessibility service XML
```

## Limitations and known issues

This is an accessibility-service locker, not a device-owner or device-admin lock.

- Safe mode does not run third-party accessibility services, so locks do not engage there.
- Anyone with ADB on the phone can disable the service or uninstall the app. Root can do the same.
- Anyone who can unlock the phone can turn the accessibility service off, or uninstall this app, from Settings. Locking the Settings app and the store you installed from makes that harder. It does not make it impossible.
- The locked app can appear for a moment before the cover is shown. The service learns about the new window only after the system has placed it in front.
- `FLAG_SECURE` covers this app’s settings and lock windows. The Recents thumbnail of a **locked app** can still show that app’s own last frame.
- Adding a fingerprint invalidates the old Keystore key. The replacement key accepts every strong biometric enrolled at that moment. Someone who can enroll a new fingerprint can pass the new prompt. There is no device-PIN step before the new key is created.
- Some phone makers stop background services to save battery. If locking stops, set this app’s battery use to Unrestricted.
- Google Play restricts accessibility-service lockers. This build is for sideloading and personal use.

## Roadmap

Not implemented. Listed so the direction is explicit.

- GitHub Releases that attach an APK and its SHA-256.
- IzzyOnDroid, then F-Droid.
- A later Google Play build. Play’s accessibility policy is a poor fit for this design. A Play build would likely detect the foreground app with `UsageStatsManager` instead of an accessibility service. This repository does not use Usage Stats today, and it does not request that permission.

## Contributing

Issues and pull requests: https://github.com/achillesrogan/biometricapplock

Contributions are under GPL-3.0-or-later, the same license as the app. Do not commit keystores, `local.properties`, or built APKs.

## License

Copyright (C) 2026 Achilles Rogan. Licensed under GPL-3.0-or-later.

The full GNU General Public License v3 text is in [LICENSE](LICENSE). You may use the app under GPLv3 or, at your option, any later version of the GNU GPL.

The Gradle wrapper scripts `BiometricAppLock/gradlew` and `BiometricAppLock/gradlew.bat` keep their upstream Apache License 2.0 headers. Those files are the Gradle wrapper, not the license for the app.

## Changelog

### 1.3 — 2026-11-10

versionCode 4.

- User-facing name is Biometric App Lock. The project directory is `BiometricAppLock`. Same package ID (`com.achillesrogan.applock`) and signing certificate as Fingerprint App Lock v1.2.

### 1.2 — 2026-11-10

versionCode 3.

- Package, namespace, and application id are `com.achillesrogan.applock`. The Keystore key alias moved with them. Copyright is Achilles Rogan.
- This build installs as a separate app from the 1.0 and 1.1 test builds. Those used a different application id, so installing 1.2 does not upgrade or remove them.

### 1.1 — 2026-11-10

versionCode 2.

- Settings and lock screens pad for the status bar, display cutout, and navigation bar. The app list uses `clipToPadding="false"` so the last row scrolls clear of gesture and 3-button navigation.
- Removed the action-bar divider that target SDK 35 drew through the status bar. System-bar contrast scrims are off. Day and night themes still set light or dark system-bar icons.
- Search with no matches shows “No apps match …”.

### 1.0 — 2026-11-10

versionCode 1. The repository’s first commit on 2026-11-09 used this same versionName under `com.example.applock`. The versionCode 1 build installed for testing already included the items below.

- Lock selected apps behind a strong biometric.
- Keystore-bound `CryptoObject` auth, fail-closed settings and lock screen, `FLAG_SECURE`, and obscured-touch filtering.
- Search field on the authenticated app list.
- Test application id, later changed in 1.2. Those installs stay on the device beside 1.2.
- License: GPL-3.0-or-later.
