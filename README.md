<div align="center">
  <img src="https://raw.githubusercontent.com/shilapi/xcertplay/refs/heads/master/asset/xcertplay_small.png" width="180" height="180" alt="xcertplay icon" />
<h1><strong><font size="6">xcertplay</font></strong></h1>
  <a href="README.md">English</a> | <a href="README.zh-CN.md">中文</a>
  <p>An Android head-unit CarPlay receiver. It supports connecting to the MFi chip through a CH341 I2C bridge or directly through the board's I2C controller, and supports both wired and wireless CarPlay connections.</p>
</div>

> [!NOTE]
> **This is a fork of [shilapi/xcertplay](https://github.com/shilapi/xcertplay)**, maintained for
> aftermarket Tesla Model 3/Y cluster screens (Unisoc UMS512, Android 10 reporting itself as 12, MFi coprocessor on
> `/dev/i2c-1`) used with the [Binnacle](https://github.com/lotech/binnacle) dash app. All credit
> for xcertplay goes to its author and contributors; it stays under GPL-3.0. Work happens on the
> `binnacle` branch, which is upstream 1.4.2 plus the changes below.
>
> **Taken from open upstream pull requests** (authors credited in the commits):
> - MFi I2C settle delay after register select (shilapi/xcertplay#53, by BBleae)
> - Keep concurrent audio streams of the same type apart (shilapi/xcertplay#44, by code-new-bie)
> - Retry the hotspot start while the radio is busy (shilapi/xcertplay#46, by code-new-bie)
>
> **Fork changes:**
> - Wi-Fi Direct group on the connected network's 5 GHz channel (not on radar/DFS channels), so a
>   single-radio chip doesn't hop channels (fixes audio/video stutter while on home Wi-Fi)
> - Remove a leftover Wi-Fi Direct group when start-up reports "busy" (no Wi-Fi off/on needed)
> - Keep the CarPlay session, and its audio, when an unrelated AirPlay client (e.g. a media server
>   probing port 7000) disconnects
> - Steering-wheel keys sent to CarPlay as knob and media controls
> - Car-maker tile labelled "Binnacle" that returns to the head unit's home screen while CarPlay
>   keeps running
> - Tells the head unit's CAN app about CarPlay phone calls (the broadcast the stock ZLink app
>   sends, addressed to the CAN app only), so the car shows a phone call without the stock car
>   service locking other apps; sends "call off" on start in case a previous run quit mid-call,
>   and otherwise only after a "call on" it actually sent (a call routed to the car over this
>   unit's Bluetooth never tells the CAN app either way)
> - Call microphone for a dash-mounted mic: road-rumble high-pass, automatic gain, and a
>   half-duplex echo suppressor driven by what is actually being played (applied after the gain
>   stages so they can't lift the echo); its levels go to the session log
> - Calls to the car's own hands-free: when the car is connected to the head unit's Android
>   Bluetooth as a hands-free device, CarPlay call audio goes to the car over Bluetooth (car mic
>   and echo cancelling; the dash-mic clean-up is skipped). With the "CarPlay" calling account
>   enabled, calls are also mirrored into Android Telecom, so the car shows the caller and its
>   answer, hang-up and keypad buttons reach the iPhone as CarPlay telephony keys
> - Siri's voice goes to the car too: while the car plays the head unit's music over Bluetooth,
>   Siri and other non-call microphone streams keep Android's normal audio mode (communication
>   mode would move the voice to the head unit's own speaker)
> - Restores the music volume on the Bluetooth link to the car to full when it starts at zero (the
>   stock CAN app zeroes it when the car sleeps and only restores the built-in speaker on wake; the
>   car sets the loudness, so full keeps the head unit level with the car's other sources)
> - The video decoder keeps running on a hidden surface while another app is in front, so
>   CarPlay's picture is there straight away on return (during a call the phone never sent the
>   fresh frame a restarted decoder needs, leaving the screen blank)
> - Opt-in diagnostic recording of the first minute of call mic audio (with "Audio diagnostic
>   capture", last 3 calls kept)
> - Session log keeps the previous five runs (xcertplay.1.log to .5.log), so an app restart
>   doesn't erase the drive before it
> - No "not responding" kill when Android starts the media service for a media key while CarPlay
>   isn't running (e.g. the car's Bluetooth sending play/pause as it wakes)
> - One-finger edge swipe opens settings by default (a three-finger swipe is hard behind a
>   steering wheel)
> - Native I2C library built with CMake, so the project builds from a path with spaces
> - The CarPlay session runs on its own, apart from the CarPlay screen (`CarPlaySession`), and an
>   app signed with the same key can show CarPlay in a view of its own through a bound service
>   (`CarPlayEmbedService`, AIDL `ICarPlayEmbed`): it hands over a surface and its size, and
>   forwards touch and wheel keys; state comes back as Messages, and the sizes it switches between are
>   declared to the phone as view areas (no reconnect on a switch). Meant for a head-unit launcher
>   that shows CarPlay in part of its screen. xcertplay's own screen works as before
> - Malformed control messages can't crash the app: the binary-plist reader refuses cycles, deep
>   nesting, exponential expansion and sizes past the end of the data, the control channel caps
>   header and body sizes, and a failing request costs one error reply instead of the process
> - Calls mirrored to the car fall back to the plain Bluetooth link when Telecom refuses them or
>   the car ends a call the phone kept, and a car-dialled call hung up before the iPhone reported
>   it is ended on the phone
> - Wireless CarPlay's port 7000 only answers on the CarPlay hotspot: a connection from any other
>   network (home Wi-Fi, a media server probing the port) is closed at once, and nothing but
>   pairing and `/info` is served until the phone has completed pair-verify

## Features

- CarPlay host applications for Android and Android Automotive OS.
- Support for MFI chips connected through a CH341 bridge or native
  `/dev/i2c-N` devices, local certificate/private-key files, and Remote MFI
  authentication (see the API below).
- Wired and wireless CarPlay connections.
- CarPlay Ultra triggering (the protocol stack is untested/incomplete, but it
  can trigger the CarPlay Ultra prompt on an iPhone).
- Voice, navigation, and music multi-channel audio output mapped to the
  corresponding Android channels.
- Dynamic Activity resizing with automatic re-handshaking to the new
  resolution.
- Separate web pages for the main and instrument displays (the browser decodes
  H.264/H.265 directly; the main display supports touch and audio).
- Vehicle head-unit location reporting.
- Android 9 (API 28) support.

## Usage

1. Pair your iPhone with the head unit via Bluetooth.
2. Before a CarPlay video stream starts, tap the Settings button in the lower-right corner. You can also swipe down with three fingers to open Settings.
3. Make sure all the settings are configured as desired.
   To enable another entry gesture, turn on `More gestures to Settings page`. Start with one finger in the upper quarter of the left eighth of the screen, slide down along that strip, and lift in the lower quarter.
4. Scroll to the bottom and select `Save & Reconnect`.
5. Connect your MFi chip using the method you selected.
6. Wait for the connection to complete, then enjoy.

## Web displays

1. In Settings, under `Web displays`, enable `Main display` and/or
   `Instrument display`, set the resolution, then tap `Save & Reconnect`.
2. Put the browser device on the same LAN as the Android device and open the
   address shown in Settings (`https://<IP>:8080/main` or `/cluster`; it can be
   copied). An open page stays connected while CarPlay reconnects and resumes
   by itself.
3. The certificate is self-signed: each browser warns once, choose to continue.
4. Main display page: touch with up to two fingers; while it is open the
   phone's audio plays in the page. Tap the screen once to turn on sound
   (a browser requirement). The microphone stays on Android.
5. The instrument display page is view-only and needs the iPhone to provide the
   second display stream.

Use a recent Chrome, Edge, or Safari. If the browser cannot play H.265, turn
off `HEVC (H.265)` in Settings.

## Calls through the car's own Bluetooth (fork)

With this, the head unit acts as the car's phone: CarPlay music and calls go to the car over
the head unit's Android Bluetooth, and calls use the car's microphone, call screen, keypad and
hang-up button. Tested on a Tesla Model 3 with an Android 10 head unit.

1. **The head unit's Android Bluetooth must offer the phone-side roles** (A2DP source and the
   hands-free audio gateway). Head-unit ROMs often switch these off. On the tested unit they were
   switched on with a runtime resource overlay for `com.android.bluetooth` that sets
   `profile_supported_a2dp` and `profile_supported_hs_hfp` (on that unit, the
   `system_profile_supported_*` versions) to `true`, installed in `/product/overlay`.
2. **Pair the car with the head unit** as you would a phone. Some cars only connect one Bluetooth
   device at a time, so the head unit then carries both music and calls.
3. **Turn on the "CarPlay" calling account** (Settings > Calls > Calling accounts, or
   `adb shell telecom set-phone-account-enabled com.shilapi.xcertplay/com.shilapi.xcertplay.telecom.CarPlayConnectionService carplay 0`),
   and allow the phone-call permission when asked. Without the account, call audio still goes to
   the car, but with no caller details and the car's buttons do nothing. Without the permission,
   only incoming calls get them.
4. If the stock dialer's in-call screen pops up over CarPlay during calls, disable its in-call
   service, e.g. `adb shell pm disable com.android.dialer/com.android.incallui.InCallServiceImpl`.

During CarPlay the iPhone ignores iAP2 call control (EndCall, SendDTMF), so the car's buttons are
sent as CarPlay telephony keys. On a single-chip head unit, Bluetooth music can make the Wi-Fi
Direct link stall while the unit is also on home Wi-Fi; away from home it was stable.

## Current progress

It works 👍. It has been tested on car head units and phones. If you encounter
an incompatible car head unit, please open an issue and attach your log from
`/sdcard/Android/data/com.shilapi.xcertplay/files/logs/xcertplay.log`.

Adapter board: [CH341-to-MFI](https://github.com/shilapi/ch341-to-mfi-chip)

## Local MFI files

Choose `Local files` under `MFI certificate & signing target`, then use the two
`Choose` buttons to select the certificate and private key with Android's system
document picker. The supported formats are a DER PKCS#7 certificate (`.p7b`)
and its matching, unencrypted DER PKCS#8 private key (`.pk8`). The app validates
that the files match before starting the phone connection and reloads them on
MFI reconnect.

Store the private key in a protected location. Neither file is copied into app
preferences; only Android's persistent read permission and document URI are
saved.

## Project structure

| Path | Purpose |
| --- | --- |
| `common/` | Shared CarPlay host activity, settings UI, persistence, and app resources used by both targets. |
| `mobile/` | Standard Android target using the shared CarPlay host UI. |
| `automotive/` | Android Automotive OS target with the shared host UI and advanced audio channel mapping. |
| `shared/` | Car App Library code plus the CH341, I2C, MFi, iPhone, iAP2, NCM, VPN, AirPlay, and media implementations. |

## Remote MFI

The Remote MFi client treats a remote service as an MFi chip for remote calls,
or uses BAA authentication. Remote authentication avoids the process of
connecting to a local MFi chip for authentication.

### Endpoints

| Method | Path | Purpose | Request body | Success response | Failure response |
| --- | --- | --- | --- | --- | --- |
| `GET` | `/mfi/certificate` | Get the MFi chip version, certificate type, and certificate contents; cached by the client after the first call | None | Certificate JSON | `{"detail":"..."}` |
| `POST` | `/mfi/sign` | Sign the challenge | `{"challenge":"...","requestId":"..."}` | `{"signature":"..."}` | `{"detail":"..."}` |
| `POST` | `/mfi/reset` | Request a reset of the remote MFi chip | `{}` | `{"detail":""}` | `{"detail":"..."}` |

(Optional) Standard Bearer Authentication can be used for verification.

**Currently, only BAA Authentication has been tested.**

## Requirements

- JDK 17 or newer to launch Gradle. The daemon resolves Java 25 through the
  Gradle toolchain.
- Android SDK Platform 37.
- Android 9 (API 28) or newer.
  On Android 9, Wi-Fi P2P 5 GHz mode is unavailable and LocalOnlyHotspot is used instead.
- Android NDK `28.2.13676358`.
- A physical USB Host/OTG Android device and MFi hardware are required for
  hardware validation.

## Build

On Windows PowerShell:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :shared:testDebugUnitTest :common:lintDebug :mobile:lintDebug :automotive:lintDebug :mobile:assembleDebug :automotive:assembleDebug
```

On macOS or Linux:

```bash
./gradlew :shared:testDebugUnitTest :common:lintDebug :mobile:lintDebug :automotive:lintDebug :mobile:assembleDebug :automotive:assembleDebug
```

Unsigned release APKs:

```powershell
.\gradlew.bat :mobile:assembleRelease :automotive:assembleRelease
```

## Acknowledgements

Thanks to [LIVI](https://github.com/f-io/LIVI) for providing important
reference for this project.
Thanks to the [showcase](https://github.com/amineross/showcase) project for
providing important reference for the BAA authentication in this project.

## License

Licensed under the [GNU General Public License v3.0](LICENSE).
