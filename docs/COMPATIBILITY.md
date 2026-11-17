# Will Binnacle work on my dash?

This page lists the dashes Binnacle has been tried on, and how to check yours.

**Short answer:** if your dash is a 10.25" Android screen behind the steering wheel of a Tesla Model 3 or Model Y, and its information screen says **Model: A007**, Binnacle should work. These dashes are all made by the same company (ZIQI) and sold under different names, such as Carview, Hansshow, Tesery, VJOYCAR and Satonic.

If yours isn't listed yet, you can still try it. Installing Binnacle doesn't remove anything, and **Apps → Advanced → Remove Binnacle** brings the original dash straight back.

## What the marks mean

| Mark | Meaning |
|---|---|
| ✅ **Confirmed** | Tried and working. |
| 🟡 **Partly confirmed** | Installed and running, but one or more features haven't been checked yet, or had a problem that a newer version should fix. |
| ❓ **Unconfirmed** | Not tried yet on this dash or car. |
| ❌ **Doesn't work** | Tried, and it doesn't work. |

## Dashes tried so far

| | Dash 1 | Dash 2 | Dash 3 |
|---|---|---|---|
| **Sold as** | Carview 10.25" | Tesery 10.25" | Not stated |
| **Car** | 2023 Model 3, right-hand drive | 2020 Model 3 Long Range AWD | Model 3 with the AMD Ryzen computer |
| **Model** | A007 | A007 | A007 |
| **Hardware version** | TSL3-Ta7862_V1.10 | TSL3-Ta7862_V1.10 | Not reported |
| **System version** | 202302282110 | 202401061605 | Not reported |
| **Software version** (App Store → Online Upgrade) | A007_C03_HA1_OSU-EN-D_2023-02-28-2057 | A007_C03_HA1_OSU-EN-D_2024-01-06-1556 | A007_C03_HA1_OSU-EN-D_2023-07-27-1412 |
| **App version** | TSL202302282015v1.0.5 | TSL202302282015v1.0.5 | Not reported |
| **MCU0 version** | mdl3-Ac1.04-1013 | mdl3-Ac1.07-4709 | Not reported |
| **MCU1 version** | MS048-mtk_C01_… | MS048-mtk_C03_… | Not reported |
| **Bluetooth version** | BT1036V2.5.6-1C38 | BT1036V2.9.2-D13E | Not reported |
| **Binnacle installs and runs** | ✅ | ✅ | ✅ |
| **Speed, gear, lights, doors, tyres** | ✅ | 🟡 Not reported yet | 🟡 Not reported yet |
| **Battery %** | ✅ | 🟡 Should work in the release after 0.9.1 (see below) | ❓ |
| **Power / regen** | ✅\* | ❓\* | ❓\* |
| **Forward camera** | ✅ | ❓ | ❓ |
| **Wireless CarPlay** | ✅ | ❓ | 🟡 Works on 0.9.4, in the centre window with auto-connect, after the changes [below](#wireless-carplay-on-a-dash-without-su). Calls don't work yet |
| **Bluetooth calls and music** | ✅ | ❓ | ❓ |
| **Reported in** | The owner's own car, in daily use | [Issue #91](https://github.com/lotech/binnacle/issues/91) | [Issue #90](https://github.com/lotech/binnacle/issues/90) |

\* **Power / regen and the November 2026 Tesla update:** Tesla software 2026.38.6.1 changed the power and regen data the dash receives, on every car we know of (other add-ons such as S3XY Commander are affected too). Until the new data is worked out, Binnacle shows an estimate based on how the car speeds up and slows down. Cars still on older Tesla software get the exact figures.

**Model Y:** ❓ not tried yet. The dash is sold for the Model Y too, so it is likely to work, but nobody has reported it yet.

**Left-hand drive:** ❓ not confirmed yet. Binnacle has a steering-wheel side setting in its setup.

## Known differences between dashes

- **Battery %.** On some cars the original dash's battery figure is stuck (at 77 % on the owner's car), so Binnacle works the % out from another figure instead. On other cars the battery figure works properly. From the release after 0.9.1, Binnacle notices when the figure changes and then uses it. On those cars, the first drive or charge after installing is what shows it the figure works. Some of those cars also send the range the car shows; there Binnacle trusts the battery figure straight away (from the release after 0.9.8). Binnacle then shows that range under the battery %, in place of its own estimate (the figure marked "est.").
- **Power and regen.** See the \* note under the table.
- **Root.** The owner's dash gives root to its own ADB, and Binnacle's unit setup uses it. Some dashes don't (Dash 3 reports `su: inaccessible or not found`). On those, Binnacle itself still runs, but the unit setup changes that need root are marked "Needs root" on the Unit page and setup skips them, and Binnacle can't keep ADB off the Wi-Fi, so ADB stays reachable from the Wi-Fi until the dash restarts. Dash 3 has no `su`, but `adb root` from a computer works (see below).
- **Newer dash software (2024).** Binnacle itself runs on it. The unit changes in [UNIT-CHANGES.md](UNIT-CHANGES.md) were tested on the February 2023 software. On other software, Binnacle's setup only makes the plain Android ones, and leaves Android Bluetooth, the stock camera and CarPlay calls as switches marked "not tested yet". You don't need any of them for Binnacle to work.

## Wireless CarPlay on a dash without su

Dash 3 (software `A007_C03_HA1_OSU-EN-D_2023-07-27-1412`, Binnacle 0.9.4, CarPlay app 1.4.5) has no `su`, so setup's root steps fail. Its build is `userdebug` (`ro.debuggable=1`), so `adb root` from a computer gives root until the next restart. These steps took it from "Waiting for the MFi coprocessor" to CarPlay starting by itself in the centre window. Save a copy of anything you change first; the undo for each is in [UNIT-CHANGES.md](UNIT-CHANGES.md).

1. **MFi chip on I2C.** The CarPlay app looks for a USB (CH341) MFi bridge until told otherwise, and these dashes have none. In the CarPlay app: Settings → Connection → *Certificate & signing target* → **I2C**, I2C device `/dev/i2c-1`, then *Save and reconnect*. The chip can refuse the first few reads; the app keeps trying and finds it.
2. **Android Bluetooth on.** `adb root`, then `adb shell setprop persist.car.use_system_bt 2`, then restart.
3. **Pair the iPhone from the dash.** The iPhone doesn't list the dash's Android Bluetooth ("A007"): it reports itself as a phone, and iOS hides those. Pair from the dash instead: Android settings → Connected devices → *Pair new device*, with the iPhone's Bluetooth settings open, then pick the iPhone. Pairing to "a007_bt" (the stock Bluetooth module) doesn't help: the CarPlay app only sees Android Bluetooth ("No bonded PHONE_SMART devices found").
4. **CarPlay allowed to start in the background.** As root, add the `com.shilapi.xcertplay` line to `/data/system/appPowerSaveConfig.xml` as in UNIT-CHANGES.md, with **`secondarylaunch="2"`**: with `1` the dash's bind stayed refused after a restart and auto-connect never started.
5. **ADB kept after a restart.** `adb root`, then `adb shell setprop persist.adb.tcp.port 5555`. Without it, the dash can't run its own commands after a restart (it pauses Wi-Fi scans during CarPlay through ADB), and the first wireless CarPlay attempt after a restart stalled with the iPhone never joining the CarPlay Wi-Fi. Leave Apps → Unit → Network ADB off afterwards. **Warning:** without `su`, Binnacle can't keep ADB off the Wi-Fi, so with this setting ADB is open to the Wi-Fi after every restart, with no password, and `adb root` works on these builds. Anyone on the same Wi-Fi can then take full control of the dash. Only use it on a Wi-Fi you trust, and undo it with `adb shell setprop persist.adb.tcp.port ""` and a restart.
6. **CarPlay app's own *Start on boot* off** (CarPlay app: Settings → Startup & window). Once the CarPlay app may start in the background (step 4), this option opens it full screen at boot, ahead of Binnacle's auto-connect. Turn it off and leave Apps → Display → CarPlay auto-connect on "Show".

Don't disable or force-stop ZLink for any of this: it breaks the stock Bluetooth module until a restart, and CarPlay works with ZLink running.

### Known problems on Dash 3

- **Calls through the CarPlay calling account turn the screen off.** With xcertplay's "CarPlay" calling account on (Unit setup's *CarPlay calls*), a CarPlay call goes through Android's Telecom. This firmware's Telecom (`com.unisoc.server.telecom.InCallScreenOffControllerEx`) puts the dash to sleep 5 s after a call becomes active ("turn off the screen 5s after the call in active state"), and the CarPlay call then drops. Turning off the stock in-call screen doesn't help. The switch is the carrier config `screen_off_in_active_call_state_bool` (true); without a SIM only the built-in default applies, so it can't be overridden from `/data`. Workaround: keep the calling account off (`adb shell telecom set-phone-account-disabled com.shilapi.xcertplay/com.shilapi.xcertplay.telecom.CarPlayConnectionService carplay 0`) and leave *CarPlay calls* off on this dash.
- **No call audio in the car: the call mode sent to the MCU mutes it (found 16 Nov).** On the Android side a CarPlay call plays exactly like music (same output, `AUDIO_DEVICE_OUT_SPEAKER`, same HAL profile, voice-call volume at maximum). What silences the car is the call state the CarPlay app sends at the start of every call: `PHONE_CALL_ON` to the CAN app (`com.xbs.canapp`), which sets the MCU's call bit (message `0xA0`, bit 3). Proof: during a silent call, sending the CAN app `PHONE_CALL_OFF` by hand (`am broadcast -a com.zjinnova.zlink -p com.xbs.canapp --es phoneMode carplay_wireless --es status PHONE_CALL_OFF`) brought the voice back in the car at once. The stock CAN app tells ZLink to use a Tesla-specific call output (`com.zjinnova.zlink.action.PHONR_TSL_OUT_AUDIO`) on these units, so ZLink handles calls differently there. Not every dash without the overlay does this: on the owner's car, before the overlay, the same call mode played calls on the car's speakers, so the switch stays on by default. Fix: the CarPlay app's *Tell the head unit about calls* switch (Settings → Audio), off on this dash; the car then doesn't show a call, but the driver hears it. Until a CarPlay app with that switch is installed, a root shell loop that answers every `PHONE_CALL_ON` in the CarPlay log with `PHONE_CALL_OFF` works (lost at each restart).
- **The other end barely hears the driver (not solved).** The dash's microphone works: only the main microphone input (`MainMIC`) picks anything up (aux, headset and digital mic inputs stay at about -100 dBFS), and the driver's voice reaches -26 to -31 dBFS when the other end is silent. But it also picks up the far end inside the unit (about -30 dBFS, even with the car muted), and the CarPlay app's echo suppressor (`CallEchoSuppressor`) is half-duplex: it cuts the microphone by 45 dB whenever playback in the last 600 ms is above -55 dBFS, unless the microphone is 8 dB louder than it. A cellular line's background noise (-45 to -63 dBFS here) is enough to keep it shut for most of a call, so the driver is cut even while the other end says nothing. Raising the threshold (around -35 dBFS, above line noise and below speech) should fix it; not tried on the dash yet, since the installed CarPlay app is signed with the release key. The suppressor logs `call echo far=… mic=… gain=…` once a second.
- **Unused stock apps stay off after a restart only with their factory flag at 0.** At every boot `android.car.server` (`AppCheckHelper`) turns apps back on from `/data/zq_factory/factory_set/data.factory.app.enable_*` (a link to `/mnt/vendor/ziqi_data`). Seen at boot on Dash 3: `enable_youtube` → YouTube, `enable_google_map` → Google Maps, `enable_assistant` → `android.car.app.apk`, `enable_mp4` → `android.car.app.mp4`, `enable_file_manager` → `android.car.app.carfilemanager`, `enable_txz_voice` → the TXZ voice apps (`com.txznet.txz` and others), `enable_zlink` → ZLink. With the flag at `0` (owner `system`, mode 644) and the app disabled with `pm disable-user`, it stays off. Apps without a flag (Amap, QQ Music, Tencent Video, the iFlytek keyboard) stay disabled anyway. Leave `com.xbs.txzadapter` alone: it is a persistent system app.

## How to find your dash's details

You don't need a computer for this. Take a photo of each of these screens on the original dash:

1. **CarSetUp → Device information.** This shows Model, Hardware version, Bluetooth version, Android version, System version, App version, MCU0 and MCU1.
2. **App Store → Online Upgrade.** This shows the Current version, a long name starting with `A007_`.
3. Optional: **the original dash's data screen**, the one that shows lines like `code 10: carspeed=…`. This helps with problems like the battery % or power figures.

Already have Binnacle installed? **Apps → Unit** shows your System version and Binnacle versions on one page, and a photo of that page helps too.

You can get back to the original dash at any time from **Apps → Advanced → Remove Binnacle**, and reinstall Binnacle afterwards.

## Add your dash to this list

Please [open an issue](https://github.com/lotech/binnacle/issues/new) and include:

- The name your dash was sold under, and your car (Model 3 or Y, year, left- or right-hand drive).
- The photos or values from the screens above.
- Which Binnacle version you installed, and what works and what doesn't.

Leave out anything that identifies your unit or you personally, such as serial numbers, MAC addresses, licence keys or your home address. None of those are needed.
