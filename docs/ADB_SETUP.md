# One-time setup from a computer

Biometric App Lock can be set up on the phone without these commands. This page is for a computer with [USB debugging](https://developer.android.com/studio/command-line/adb) turned on. The package id is `com.achillesrogan.applock`.

Install the APK first (`adb install -r path/to/the.apk` upgrades an existing install). Then run the commands you need. Each section has the command that turns the setting on and the command that turns it off.

Galaxy phones on One UI 6 and newer have Auto Blocker under Settings → Security and privacy → Auto Blocker. Leave it on unless one of these is stopping the command you are running:

- **Block apps from unauthorized sources** stops `adb install` and an APK file install. Galaxy Store and Play Store installs are not blocked. Turn it off only while you install or update, then turn it back on. App locking does not need it off.
- **Block commands by USB cable** stops the `adb shell` commands on this page. Turn it off only while you use them. Setup done on the phone does not need it off.
- **Maximum restrictions** (One UI 6.1.1 and newer, off unless you turn it on) blocks activating a device administrator, so the device-admin command below fails until that switch is off. The other items under Maximum restrictions do not affect this app.

Malware-image blocking and app scans can stay on. The app's setup screen explains the same limits and opens the Auto Blocker screen when it can.

Installing from F-Droid, IzzyOnDroid, or Obtainium usually does **not** set Android’s restricted-settings flag, so the accessibility switch is not greyed out. A file install or `adb install` on Android 13 and newer often does set that flag.

## Allow restricted settings (Android 13 and newer)

Needed when the accessibility switch is greyed out.

```bash
adb shell appops set com.achillesrogan.applock ACCESS_RESTRICTED_SETTINGS allow
```

Undo:

```bash
adb shell appops set com.achillesrogan.applock ACCESS_RESTRICTED_SETTINGS ignore
```

The app-op name exists on Android 13 and newer. On older versions the command is unnecessary and may report an unknown operation.

## Accessibility service

`settings put` replaces the whole list of enabled accessibility services. Read the current value first.

```bash
adb shell settings get secure enabled_accessibility_services
```

If that prints `null` or is empty, enable only this app:

```bash
adb shell settings put secure enabled_accessibility_services com.achillesrogan.applock/com.achillesrogan.applock.AppLockService
adb shell settings put secure accessibility_enabled 1
```

If it already lists other services, put that text back with this component added after a colon:

```bash
adb shell settings put secure enabled_accessibility_services 'EXISTING_VALUE:com.achillesrogan.applock/com.achillesrogan.applock.AppLockService'
adb shell settings put secure accessibility_enabled 1
```

Undo, when this app is the only service in the list:

```bash
adb shell settings delete secure enabled_accessibility_services
adb shell settings put secure accessibility_enabled 0
```

`accessibility_enabled 0` turns off **every** accessibility service on the phone. If other services were enabled, put the previous list back instead of deleting it, and leave `accessibility_enabled` at `1`.

## Device administrator (uninstall protection)

```bash
adb shell dpm set-active-admin --user 0 com.achillesrogan.applock/com.achillesrogan.applock.AppDeviceAdminReceiver
```

`--user 0` is the main user. While this admin is active, Android will not uninstall the app until it is removed.

Undo:

```bash
adb shell dpm remove-active-admin --user 0 com.achillesrogan.applock/com.achillesrogan.applock.AppDeviceAdminReceiver
```

The administrator requests no policies. It does not lock the screen or wipe the phone.

## Battery optimization

Optional. Stops some phones from killing the accessibility service.

```bash
adb shell dumpsys deviceidle whitelist +com.achillesrogan.applock
```

Undo:

```bash
adb shell dumpsys deviceidle whitelist -com.achillesrogan.applock
```

## What these commands do not replace

A strong fingerprint still has to be enrolled on the phone. The app will not show its lock list or unlock a protected app without one. There is no device PIN fallback.
