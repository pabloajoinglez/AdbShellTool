# ADB Shell Tool

An Android app that connects to the device's own ADB daemon via **Wireless Debugging** (Android 11+), pairs with a PIN code, and executes arbitrary ADB shell commands — all from within the device itself, without a PC.

## How it works

Android 11+ includes a built-in ADB daemon that listens on two local ports:

| Port | Purpose |
|------|---------|
| Pairing port | One-time SPAKE2 key exchange using a 6-digit PIN |
| Session port | TLS-authenticated ADB session for shell commands |

This app automates both phases:

1. **Pairing** — detects the pairing port automatically via mDNS (`_adb-tls-pairing._tcp`), shows a notification with an inline input field for the PIN, and performs the SPAKE2 handshake. Only needs to be done once.
2. **Connection** — after pairing, detects the session port via mDNS (`_adb-tls-connect._tcp`) and connects automatically.
3. **Shell** — runs any ADB shell command and displays the output.

The approach mirrors [Shizuku](https://github.com/RikkaApps/Shizuku) exactly: a foreground service listens for mDNS announcements, and a `RemoteInput` notification lets the user enter the PIN without leaving the Settings screen (which would expire the code).

## Requirements

- Android 11 or higher
- **Developer options** enabled
- **Wireless Debugging** enabled in Developer options

## Usage

### First time (pairing)

1. Open the app and tap **Start Pairing**.
2. On your device go to **Settings › Developer options › Wireless Debugging › Pair device with pairing code**.
3. A notification will appear automatically with the detected port.
4. Pull down the notification, tap **Enter code**, and type the 6-digit PIN shown on the Wireless Debugging screen.
5. After pairing completes, **disable and re-enable Wireless Debugging** — the app will connect automatically.

### Every subsequent time

If the ADB session port hasn't changed since the last connection you can just tap **Connect manually** with the saved port. Otherwise, run **Start Pairing** again (no PIN needed if already paired — just to trigger mDNS auto-connect).

### Running commands

Type any shell command in the text field and tap **Run**. The process runs as UID `shell` (2000), which has broad permissions:

- Inject touch/key input (`input tap`, `input keyevent`, …)
- List and manage packages (`pm list packages`, `pm install`, …)
- Manage processes (`am start`, `am force-stop`, …)
- Read system settings (`settings get`, …)
- And much more

## Architecture

```
App.java                    — Application class: registers Conscrypt TLS provider,
                              unlocks hidden APIs via HiddenApiBypass
AdbPairingService.java      — Foreground service: mDNS discovery, RemoteInput pairing,
                              auto-connect after pairing
MyAdbConnectionManager.java — Extends AbsAdbConnectionManager: RSA key pair + X.509
                              certificate generation and persistence
AdbManager.java             — Singleton facade: pair / connect / disconnect / openStream
CommandExecutor.java        — Runs shell commands over an ADB stream, returns output
MainActivity.java           — Single-screen UI: shows pairing/connection/command sections
```

## Key libraries

| Library | Purpose |
|---------|---------|
| [libadb-android](https://github.com/MuntashirAkon/libadb-android) | ADB protocol implementation (SPAKE2 pairing + TLS session) |
| [sun-security-android](https://github.com/MuntashirAkon/sun-security-android) | Compile-time stubs for `android.sun.security.x509.*` (X.509 cert building) |
| [hiddenapibypass](https://github.com/LSPosed/HiddenApiBypass) | Unlocks Android hidden APIs at runtime |
| [Conscrypt](https://github.com/google/conscrypt) | TLS 1.3 provider required by the ADB protocol |

## Building

```bash
./gradlew assembleDebug
```

The APK will be at `app/build/outputs/apk/debug/app-debug.apk`.

## License

MIT
