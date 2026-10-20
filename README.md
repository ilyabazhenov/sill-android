# Sash: an Android client for Sill

[![License: GPL-3.0](https://img.shields.io/badge/license-GPL--3.0-blue.svg)](LICENSE)
![Android 10+](https://img.shields.io/badge/Android-10%2B-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7F52FF?logo=kotlin&logoColor=white)
[![Latest release](https://img.shields.io/github/v/release/ilyabazhenov/sill-android)](https://github.com/ilyabazhenov/sill-android/releases/latest)

**English** · [Русский](README.ru.md) · Website: [usesash.app](https://usesash.app)

Your Mac's windows and its whole desktop on an Android phone or tablet, driven by touch, keyboard, mouse and stylus.

This is an **unofficial** Android client for [Sill](https://getsill.app) (open source, Apache-2.0, [Saffsanity/sill](https://github.com/Saffsanity/sill)). The Mac runs the stock **Sill for Mac** app, unchanged. This app speaks Sill's protocol, so iPhones and iPads with the official Sill app and Android devices connect to the same Mac.

## Features

- **Finds Macs on the local network** (Bonjour `_sill._tcp` via NSD).
- **Pairing**, three ways:
  - tap "Not paired", then enter the code the Mac shows;
  - scan the QR code with the in-app or system camera (`sill://pair` link);
  - address plus code, for pairing from afar.
- **Encryption**: TLS 1.3 with mutual pinning of P-256 keys. The device key lives in the Android Keystore.
- **Video**: HEVC stream up to 120 fps, hardware MediaCodec decoder in low-latency mode.
- **Windows**:
  - a strip of live thumbnails;
  - long press for the traffic lights: close, minimize, full screen;
  - a Desktop button;
  - every app on the Mac, with search and launch.
- **Direct input**:
  - tap to click, long press to right-click;
  - long press and move to aim with a loupe, lift to click there;
  - hold still a moment more, then move to drag;
  - one- or two-finger scrolling with momentum;
  - mouse, touchpad and stylus act as the Mac's mouse, wheel and hover included.
- **Trackpad mode** (phone upright): picture on top, key row, trackpad below.
  - tap to click, two-finger tap to right-click;
  - two-finger scrolling;
  - long press and move to drag.
- **Three-finger gestures**: Mission Control, App Exposé, switching Spaces, Apps, Show Desktop.
- **Keyboard**:
  - on-screen: text, Delete, Return;
  - an esc/tab/ctrl/opt/cmd/shift row with arrows; modifiers latch for shortcuts like ⌘C;
  - a hardware keyboard sends every key as is (⌘ is the Meta/Win key).
- **Aa**: scales the Mac window to the device screen (0.5×–1.5×).
- **The Mac app's menus**: the frontmost app's menu bar, submenus load on open, items are pressed on the Mac.
- **The Mac's pointer** with its cursor shape when someone moves the mouse on the Mac itself.
- **Two-way clipboard**:
  - what you copy on the Mac lands on the Android clipboard;
  - "Paste on Mac" sends text from the phone.
- **Mac settings from the phone**: quality (Low…Extreme), Retina/Standard, 60/120 fps, encoder speed priority, virtual display.
- **Away from home**:
  - through a VPN (Tailscale and the like) or a forwarded port;
  - the Mac's addresses arrive in a signed message; the app tries them in parallel, 1 s apart.
- **App lock**: fingerprint, face or PIN on open and after a minute in the background.
- **Auto-reconnect** after a dropped connection, Sill quitting, or the app going to the background.

## Not supported (Apple devices only)

- Pairing over a USB cable: the Mac only recognizes an iPhone's or iPad's cable interface.
- Direct Wireless (AWDL): Wi-Fi needs a shared network or a VPN.

## Getting started

1. Install **Sill for Mac** from [getsill.app](https://getsill.app/download) and open it. Grant Screen Recording and Accessibility.
2. Download the APK from [Releases](https://github.com/ilyabazhenov/sill-android/releases/latest) and install it on Android, join the same Wi-Fi network and open the app.
3. Tap your Mac. The first time, the Mac shows a QR code and a 12-digit code. Scan the QR or type the digits.
4. Away from home: turn on Remote Access in Sill's settings on the Mac. Then either install Tailscale on both devices or forward Sill's port on your router. The Mac shows up under "Away from home".

## Building

Needs JDK 17+ and the Android SDK (compileSdk 36). The app runs on Android 10 (API 29) and later.

```bash
./gradlew :app:assembleDebug
```

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The release build (`./gradlew :app:assembleRelease`) is signed with the key from `SASH_KEYSTORE` (keystore path), `SASH_KEYSTORE_PASSWORD` and `SASH_KEY_ALIAS`; without them it is debug-signed, for testing only.

## Project layout

- `protocol/`: a pure Kotlin/JVM module with no Android dependencies.
  - Sill's message format (`Wire.kt`, `Messages.kt`);
  - pairing cryptography, checked byte for byte against golden vectors from Sill's Swift code (`Pairing.kt`, tests in `GoldenVectorsTest`);
  - the device's self-signed certificate (`Identity.kt`);
  - TLS 1.3 with ALPN and pinning (`Transport.kt`);
  - pairing and session clients (`PairingClient.kt`, `Session.kt`);
  - an HEVC SPS parser.
- `protocol/.../cli/TestClient.kt`: a command-line client that talks to a real Sill host the same way the app does.
- `app/`: the Android app (Jetpack Compose).
  - `Controller`: connecting, pairing, the session, reconnects, the catalog;
  - `VideoDecoder`: MediaCodec, conversion to Annex-B;
  - `Discovery`: NSD;
  - `InputLayer`: touches, trackpad, gestures;
  - `HidKeys`: keys to USB HID;
  - screens in `ui/`.
- `docs/sill-protocol-notes.md`: protocol notes taken from the iOS client and host code.
- `tools/vectors/main.swift`: generates the golden vectors from Sill's Swift code.
- `site/`: the project's landing page.
- `reference/sill`: a clone of Sill for reference, not checked in.

## Testing without a phone

The Sill host has a test mode: a synthetic picture instead of the screen, and input goes nowhere.

```bash
git clone https://github.com/Saffsanity/sill reference/sill
```

```bash
(cd reference/sill && swift build -c release --product SillHost)
```

```bash
SILL_TEST_LOOPBACK=1 SILL_TEST_SOFTWARE_ENCODER=1 SILL_TEST_NO_ROUTER=1 SILL_TEST_REMOTE_DIR=/tmp/sillid reference/sill/.build/release/SillHost --synthetic --pairing
```

The host prints a code and a `sill://pair…` link. Find its port with `lsof -nP -iTCP -sTCP:LISTEN`.

```bash
./gradlew :protocol:installDist
```

```bash
protocol/build/install/protocol/bin/protocol 127.0.0.1 PORT --link 'sill://pair?…'
```

From the Android emulator the host is at `10.0.2.2:PORT`: "Add a Mac…" → address → code.

## License

[GPL-3.0](LICENSE). Sill is a separate project by its own authors under Apache-2.0; this app is not affiliated with them.
