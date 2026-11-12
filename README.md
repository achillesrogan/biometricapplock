# Biometric App Lock

An Android app that covers other apps until a strong biometric unlocks a key in the Android Keystore.

Package name: `com.achillesrogan.applock`. Repository: https://github.com/achillesrogan/biometricapplock

Previously named Fingerprint App Lock (v1.2 and earlier); same package ID and signing certificate.

Current version: **1.5** (versionCode 6).

## Features

- Locks chosen apps behind a **strong** (class 3) biometric, typically a fingerprint. The prompt is AndroidX `BiometricPrompt` with `BIOMETRIC_STRONG` only. A device PIN, pattern, or password is not an unlock method.
- The prompt is bound to an Android Keystore AES key through `BiometricPrompt.CryptoObject`. The app continues only after `Cipher.doFinal` succeeds. The key requires authentication on every use, and enrolling or removing a biometric invalidates it (`setInvalidatedByBiometricEnrollment`).
- **Fails closed.** If no strong biometric is enrolled, or the sensor is unavailable, the settings list stays hidden and a locked app stays covered.
- `FLAG_SECURE` on the settings screen and the lock screen, so those windows are left out of screenshots and the Recents preview.
- `filterTouchesWhenObscured` on both screens, so a touch that passes through another window cannot flip a lock switch or press the unlock button.
- Search at the top of the app list. It filters live, case-insensitively, by app name and package name. Lock switches keep the real saved state while the list is filtered. A search with no hits shows an empty state. The field is inside the authenticated content and is hidden until biometric auth succeeds.
- The list opens with locked apps first, then the others. Each group is alphabetical in the phone’s locale, ignoring case. Turning a lock on or off leaves that row where it is until the next time the list loads.
- Edge-to-edge layout for target SDK 35. Content sits below the status bar, and the last list row scrolls above the navigation bar for both gesture and 3-button navigation. Light and dark themes use the Material 3 day/night theme.
- **Uninstall protection** (version 1.4). While the switch is on, a fingerprint is required before a locked app, or this app, can be uninstalled. The same prompt covers force stop and clear storage on those apps when the system screen is one this build recognizes, and it covers turning off device administrator. Android will not uninstall this app until that administrator is deactivated. The administrator requests no lock, wipe, or password policy. The explanation on the main screen collapses to one line after it has been shown, or once setup is finished. Details opens the full text again.
- A small credit at the bottom of the main screen opens https://github.com/achillesrogan. The app does not request internet access. If no browser is installed, it says so.

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
3. Open the APK and install it. Version 1.5 (versionCode 6) uses application id `com.achillesrogan.applock`. It installs in place over 1.4. It does not replace the 1.0 or 1.1 test installs, which used a different application id.
4. Open **Biometric App Lock**. Setup opens each system screen for you, one at a time: a fingerprint if you still need one, **Allow restricted settings** when Android has greyed out the accessibility switch, the accessibility service, Samsung Auto Blocker on a Galaxy phone with One UI 6 or newer, uninstall protection, then battery. Come back after each one and the next screen opens. You can reopen the list later with **Review setup**.
   - On Android 13 and newer, a file install often greys out the accessibility switch until you allow restricted settings. Setup sends you to the app info screen first and tells you to tap the ⋮ menu. Installing from F-Droid, IzzyOnDroid, or Obtainium usually does not set that flag. The same steps can be done from a computer: [docs/ADB_SETUP.md](docs/ADB_SETUP.md).
   - On a Galaxy phone with One UI 6 or newer, setup includes Auto Blocker. It does not ask you to turn Auto Blocker off. Block apps from unauthorized sources stops an APK install or update (Galaxy Store and Play Store installs are not blocked). Block commands by USB cable stops the computer setup commands. Maximum restrictions, on One UI 6.1.1 and newer, blocks device administrator, so uninstall protection cannot be turned on until that switch is off. App locking works with the rest of Auto Blocker left on. If the app cannot read a switch, that step is labeled Manual check. Other phones do not show the step.
   - Upgrading from 1.3 changes what the accessibility service is allowed to read. If Android turned the service off after the update, setup asks you to turn it on again.
5. After setup, confirm your fingerprint and turn on the switch for each app you want to lock. Uninstall protection is optional. Its screen only asks you to activate a device administrator that blocks uninstalling this app. It does not lock the phone or wipe it.

## How it works

| Piece | What it does |
|---|---|
| `MainActivity` | Lists launchable apps and a switch for each. Hidden until strong biometric auth succeeds. Includes search, the uninstall-protection switch (its explanation collapses after the first view, or once setup is done), a credit link, and a way back into setup. |
| `SetupActivity` | Checklist that opens each system setting the app needs. Rechecks when you return and opens the next unfinished step. |
| `AppLockService` | Accessibility service. For ordinary apps it only uses the package name of the window that came to the front. For package-installer and Settings windows it can read on-screen text to see if an action targets a protected app. |
| `LockActivity` | Full-screen cover over a locked app, or over a protected uninstall / force-stop / clear-storage / device-admin screen. Cancel and Back go to the home screen. If the biometric is unavailable, the cover stays up and the action does not proceed. |
| `Biometrics` | Creates the Keystore key and runs the `CryptoObject` prompt. There is no device PIN, pattern, or password fallback. |
| `LockStore` | Saves the set of locked package names in app-private `SharedPreferences`. |
| `LockState` | In-memory set of apps unlocked right now. An app locks again when you leave it, or when the screen turns off. Also remembers that an uninstall-protection prompt is already showing. |
| `DeviceAdmin` / `AppDeviceAdminReceiver` | Device administrator used only so Android refuses to uninstall this app. `onDisableRequested` returns a short warning. Turning the in-app switch off calls `removeActiveAdmin`. |
| `UninstallSignals` | Pure check: given the package, class, and text of a system window, decide whether it is a protected action. If the target is not clear, it does not block. |

The service ignores this app’s own windows, the system UI, and the keyboard, so the lock screen and the IME are not treated as “leaving” the locked app.

Uninstall protection is the device-administrator switch. It is off until you accept that screen. While it is on, the protected targets are every locked package plus this app. A successful fingerprint allows that package’s confirmation for about 60 seconds so you can press the system button. The screen turning off clears that allowance and, if a protected confirmation is still open, closes it. Install and update screens are not blocked.

### Permissions

Checked against `BiometricAppLock/app/src/main/AndroidManifest.xml` and the debug merged manifest.

| Permission or capability | Why it is there |
|---|---|
| `android.permission.USE_BIOMETRIC` | Declared by this app. Shows the system biometric prompt. |
| `android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Declared by this app so setup can open the system dialog that asks you to leave battery optimization. The permission does not grant the exemption. You still have to tap Allow. The app does not use usage access, draw-over-other-apps, or notifications. |
| `android.permission.USE_FINGERPRINT` | Not declared in this app’s manifest. AndroidX Biometric adds it in the merged manifest so older platforms can show the prompt. |
| `com.achillesrogan.applock.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | Signature permission added by AndroidX to protect its own non-exported receivers. It is not a privacy capability of this app. |
| Accessibility service | You enable it in system Settings. It receives `TYPE_WINDOW_STATE_CHANGED` and `TYPE_VIEW_CLICKED`. `canRetrieveWindowContent` is true, with `flagReportViewIds` and `flagRetrieveInteractiveWindows`, so it can read installer and Settings windows and press Cancel, Back, or Home when a fingerprint prompt is cancelled. The service element requires `android.permission.BIND_ACCESSIBILITY_SERVICE`, so only the system can bind to it. |
| Device administrator | Not a `uses-permission`. `AppDeviceAdminReceiver` is exported and guarded by `android.permission.BIND_DEVICE_ADMIN`, which only the system holds. While it is active, Android blocks uninstalling this app until it is deactivated. The XML requests no policies. |
| `<queries>` | On Android 11 and newer: launcher activities (the app list), the home screen (Back from the lock screen sends you home), the settings screens setup opens (accessibility, app info, device admin, battery, fingerprint, Security settings, and the Auto Blocker action when Samsung exposes it), the Auto Blocker package `com.samsung.android.rampart` so its presence can be checked, and an `https` VIEW intent so the credit link can see whether a browser is installed. None of these grant network access. |
| No `INTERNET` | The source manifest and the merged debug manifest do not request `INTERNET`. |

`android:allowBackup="false"`, so Android Backup does not include the lock list.

## Privacy

The app does not declare a network permission, and the source does not open sockets, HTTP clients, or analytics. Nothing in this project uploads the lock list, the app list, or the foreground package name.

What stays on the device:

- The set of locked package names, in app-private preferences (`app_lock_prefs`).
- A boolean that remembers to explain a fingerprint-enrollment change (`biometric_gate`).
- Which optional setup steps you skipped (`setup_prefs`). Turning the real setting on later still counts as done.
- Whether the uninstall-protection explanation has already been shown (`setup_prefs`). Setup being finished collapses it even when this flag is still off.
- Which apps are unlocked right now, only in memory, cleared when you leave an app or the screen turns off.
- For about 15 seconds, the package name of the protected app whose Settings details page was just in front, so a confirmation dialog that does not repeat the name can still be recognized. It is dropped when you leave Settings or the screen turns off.
- For about 60 seconds after a successful fingerprint, the package that fingerprint allowed, so the system confirmation can be completed. The screen turning off clears it.
- The Keystore key, which never leaves the Android Keystore. The app does not store the ciphertext from the auth check.

To do its job locally, the app reads the list of launchable apps and, while the accessibility service is on, the package name of the window that came to the front. It does not read what you type, and it does not read ordinary app windows.

When uninstall protection is on, a window from a known package installer or from Settings (including MIUI Security, and a few other OEM settings packages) may be read if it looks like an uninstall activity, an app-info screen, a device-admin screen, or an alert dialog. The text and view ids are used to decide whether the action targets a locked app or this app, then discarded. Install and update screens are not read for this check. Settings pages that are not those screens, including Wi-Fi password dialogs, are not read. Cancelling the fingerprint prompt can press Cancel on that system window, or Back and Home.

## Build from source

Requirements: JDK 17 or 21, Android SDK 35. The Android Gradle Plugin is 8.7.3, Kotlin is 2.0.21, and the wrapper uses Gradle 8.11.1. Java source and bytecode target are 17. Gradle 8.11.1 does not run on Java 25; use JDK 21 if that is what the machine would otherwise pick.

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
├── docs/ADB_SETUP.md       one-time adb commands for setup, and how to undo them
├── .gitignore
└── BiometricAppLock/
    ├── build.gradle.kts    Android Gradle Plugin 8.7.3, Kotlin 2.0.21
    ├── settings.gradle.kts
    ├── gradle.properties
    ├── gradlew             Gradle 8.11.1 wrapper (upstream Apache-2.0 script)
    └── app/
        ├── build.gradle.kts
        ├── src/main/
        │   ├── AndroidManifest.xml
        │   ├── java/com/achillesrogan/applock/
        │   │   ├── MainActivity.kt
        │   │   ├── LockActivity.kt
        │   │   ├── AppLockService.kt
        │   │   ├── Biometrics.kt
        │   │   ├── LockStore.kt
        │   │   ├── LockState.kt
            │   │   ├── AppListAdapter.kt
            │   │   ├── AppListOrder.kt
        │   │   ├── SystemBars.kt
        │   │   ├── UninstallSignals.kt
        │   │   ├── UninstallProtection.kt
        │   │   ├── WindowReader.kt
        │   │   ├── AppDeviceAdminReceiver.kt
            │   │   ├── SetupPlan.kt
            │   │   ├── SetupStatus.kt
            │   │   ├── SetupIntents.kt
            │   │   ├── SetupActivity.kt
            │   │   ├── AutoBlocker.kt
            │   │   ├── UninstallDisclaimer.kt
            │   │   └── Credit.kt
        │   └── res/         layouts, strings, themes, accessibility service XML, device admin XML
        └── src/test/java/com/achillesrogan/applock/
            ├── UninstallSignalsTest.kt
            ├── SetupPlanTest.kt
            ├── AutoBlockerTest.kt
            └── UninstallDisclaimerTest.kt
```

## Limitations and known issues

This is an accessibility-service locker. Version 1.4 also turns on a device administrator so this app cannot be uninstalled until that administrator is turned off. It is not a device owner. The administrator does not lock the screen, wipe the phone, or set a password.

- Safe mode does not run third-party accessibility services, so app locks and uninstall prompts do not engage there. Device admin can still block uninstalling this app until it is deactivated, including from safe mode on current Android versions, but deactivation in safe mode is not covered by the fingerprint prompt.
- Anyone with ADB on the phone can disable the accessibility service, remove the administrator (`dpm remove-active-admin com.achillesrogan.applock/com.achillesrogan.applock.AppDeviceAdminReceiver`), or uninstall the app. Root can do the same.
- Anyone who can unlock the phone can turn the accessibility service off from Settings. Locking the Settings app makes that harder. It does not make it impossible. Uninstalling this app from Settings still requires deactivating the administrator first, and that screen asks for a fingerprint while the service is on.
- The locked app, or the system confirmation, can appear for a moment before the cover is shown. The service learns about the new window only after the system has placed it in front.
- Uninstall activities are recognized by class name on AOSP, Google, Samsung, Xiaomi, and a few other OEM package-installer package names (`com.android.packageinstaller`, `com.google.android.packageinstaller`, `com.samsung.android.packageinstaller`, `com.sec.android.packageinstaller`, `com.miui.packageinstaller`, `com.miui.global.packageinstaller`, plus Huawei, OPPO, OnePlus package-installer ids). The app name still has to be visible as text. A launcher or Play Store confirmation that never opens one of those installers is not covered.
- Force stop and clear storage depend on Settings (or MIUI Security) showing an AOSP-style control. The force-stop button id `left_button` is treated as force stop in any language. Other buttons are matched by their label, which includes English and a short set of other languages. A dialog that does not repeat the app name is tied to the details page seen in the previous 15 seconds. If that link is wrong or missing, the dialog is left alone.
- App info itself stays usable. The prompt appears for the uninstall, force stop, or clear-storage action, not for opening the page.
- Some OEMs refuse to activate a device administrator that requests no policies. If the system screen will not accept it, the switch stays off and uninstall prompts stay off with it.
- `onDisableRequested` is only a warning. It does not, by itself, require a fingerprint. The fingerprint gate is the cover over the deactivation screen.
- There is no PIN, pattern, or password fallback. If no strong biometric is enrolled, the cover stays up and Cancel leaves the action blocked.
- Viewing App info, installing, and updating are not blocked. An uninstall of an app that is not locked, and is not this app, is not blocked.
- Setup deep links fall back when a phone has no exact screen: accessibility details, then the accessibility list; the battery dialog, then the battery list, then app info. Some OEMs grey out the accessibility switch without the Android 13 restricted-settings app-op. The accessibility step still tells you to use the ⋮ menu. The app does not ask for usage access, an overlay, or notification permission.
- Samsung Auto Blocker is detected only on a Samsung device. The One UI version comes from `ro.build.version.oneui` when that property can be read, otherwise from `Build.VERSION.SEM_PLATFORM_INT` minus 90000 (60000 is One UI 6.0, 60101 is One UI 6.1.1). If neither can be read, the step is shown only when the package `com.samsung.android.rampart` is installed. A known One UI older than 6 does not show the step. There is no public API for the switches. The app reads `Settings.Secure` keys `rampart_main_switch_enabled`, `rampart_blocked_unknown_apps`, `rampart_blocked_adb_cmd`, and `rampart_strict_protection_switch_enabled`, and trusts a value only when it is `0` or `1`. Anything else is a manual check. The main switch reading off is treated as the affecting switches being off, unless one of those keys still reads `1`. The button tries Samsung's Auto Blocker settings action, then `com.android.settings.Settings$AutoBlockerSettingsActivity`, then Security settings. Those first two are not a stable public API.
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

### 1.5 — 2026-11-12

versionCode 6. Same package id `com.achillesrogan.applock`, so this installs over 1.4.

- Samsung Auto Blocker is a setup step on Galaxy phones with One UI 6 or newer. The step says what blocks an APK install, the computer setup commands, and device administrator, and what can stay on. The switches are read only when Samsung's secure settings return 0 or 1. Otherwise the step is a manual check. Other phones do not show it.
- The uninstall-protection explanation collapses to one line after it has been shown, or once setup is finished. Details opens the full text again.
- The main screen has a small credit, Made by Achilles Rogan, which opens https://github.com/achillesrogan. No internet permission was added. If no browser is installed, the app says so.

### 1.4 — 2026-11-11

versionCode 5. Same package id `com.achillesrogan.applock`, so this installs over 1.3.

- Uninstall protection. While the new switch is on, uninstalling a locked app or Biometric App Lock asks for a fingerprint. Force stop and clear storage on those apps ask too, on the system screens this build recognizes.
- Device administrator, with no extra policies, so Android will not uninstall this app until the administrator is turned off. The deactivation screen is covered by the same fingerprint prompt. `onDisableRequested` tells the user that uninstall protection is on.
- The accessibility service can read window text on package-installer and Settings screens to tell which app the action is for, and it can close that screen if the fingerprint is cancelled. Ordinary app windows are still not read.
- Guided setup. The first launch opens each needed system screen in order and advances when you return. Review setup opens the same list again. [docs/ADB_SETUP.md](docs/ADB_SETUP.md) has the matching adb commands.
- The app list groups locked apps above the others, each group in locale alphabetical order. A switch stays put until the list is loaded again.

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
