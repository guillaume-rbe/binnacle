# Changes made to the unit outside the app

Binnacle itself installs as a normal app. Getting the most out of the unit took a few changes to the stock system as well. None of them touch the system partitions, and none send anything to the car or the MCU (an MCU write would be listed here with its undo, and only after the owner approved that exact message). Each one can be undone with the command shown.

**No stock apps were removed.** The stock dash (`com.xbs.launcher1`), the CAN app, the camera app and ZLink all stay installed. The only things uninstalled were this project's own test builds.

Commands assume `adb connect <unit-ip>:5555` first. `su 0` gives root on this unit (`su -c` doesn't work).

> **⚠️ Network ADB is root for anyone on the same network.** This firmware's adb asks for no key or password, and `su 0` works from it. While port 5555 is open, any device on the same Wi-Fi can take full control of the unit: that includes a shared or public network, and the unit's own hotspot if you share it. Keep it on only on networks you trust, or switch it off when you're done (`adb shell su 0 setprop persist.adb.tcp.port ""`, then reboot). Details: [ADB-GUIDE.md](ADB-GUIDE.md#️-security-warning-read-this-before-making-it-persistent).

> **Removing Binnacle doesn't undo these.** Uninstalling Binnacle brings the stock dash back, but the changes below stay until you undo them. In particular, if you disabled the stock camera screen, run its Undo command, or the camera won't open by itself in Drive any more.

| Change | Why | Command | Undo |
|---|---|---|---|
| Network ADB survives reboots | So you don't need the EngineerMode steps after every reboot (see `ADB-GUIDE.md`) | `adb shell su 0 setprop persist.adb.tcp.port 5555` | `adb shell su 0 setprop persist.adb.tcp.port ""` |
| Binnacle is the Home app | Replaces the stock dash; the stock dash stays installed as the fallback | `adb shell su 0 cmd package set-home-activity com.xbs.launcher/com.xbs.launcher.MainActivity` | `adb shell su 0 cmd package set-home-activity com.xbs.launcher1/.MainActivity` |
| Stock camera screen disabled | The stock dash opens a full-screen camera in Drive even when it isn't on screen; Binnacle shows the camera in the dash instead | `adb shell su 0 pm disable com.xbs.frontcam/com.xbs.frontcam.CameraActivity` | `adb shell su 0 pm enable com.xbs.frontcam/com.xbs.frontcam.CameraActivity` |
| Camera permission for Binnacle | For the camera inside the dash | `adb shell pm grant com.xbs.launcher android.permission.CAMERA` | `adb shell pm revoke com.xbs.launcher android.permission.CAMERA` |
| Stock dash pop-ups blocked | The stock dash keeps running in the background and shows toasts over everything (for example a white pill on Autopilot) | `adb shell appops set com.xbs.launcher1 TOAST_WINDOW ignore` | `adb shell appops set com.xbs.launcher1 TOAST_WINDOW allow` |
| GPS crash dumps off | The GPS daemon crashes often and each crash wrote a core dump of up to ~470 MB to `/data/corefile` (about 180 of them here). Turning them off and clearing the folder freed the space; GPS keeps working | `adb shell su 0 setprop persist.vendor.sys.core.enabled 0`, then `adb shell "su 0 sh -c 'cd /data/corefile && rm -rf ./*'"` | `adb shell su 0 setprop persist.vendor.sys.core.enabled 1` (the deleted dumps are gone for good) |
| CarPlay full width | The stock split layout draws CarPlay at the wrong width with a touch offset | Stock dash: Settings → Screen display mode → Full, then reboot (or `adb shell settings put system xbs_carmsgdisppost 2` and reboot) | Set it back to Right (`... xbs_carmsgdisppost 0`) and reboot |
| CarPlay only when asked | Stops CarPlay grabbing the phone and the unit's Wi-Fi by itself | In ZLink: Background Connection → off. Bluetooth auto-connect can stay on (Binnacle: Display → Bluetooth auto-connect) | Turn Background Connection back on |
| Media volume at full on start-up | The car sets the loudness; a lower level on the unit only makes CarPlay, music and Bluetooth audio quieter than the car's own sources | Automatic: Binnacle sets Android's music volume (the one the wheel's volume keys change; nothing goes to the car) to 100% when it starts and when the car's Bluetooth audio connects. Level in Apps → Settings → General → Start-up volume | Start-up volume → Leave as is (then the volume keys) |
| Wi-Fi scans paused during CarPlay | Each auto-connect scan takes the single radio off CarPlay's Wi-Fi Direct channel for about a second: music and calls stutter away from home | Opt-in: Apps → Settings → General → Pause Wi-Fi scans during CarPlay. Binnacle runs `service call wifi 58 i32 0` (Android 10 only) while CarPlay connects or runs, `service call wifi 58 i32 1` when it ends and at every start. The Wi-Fi icon runs the second (plus `svc wifi enable`) to rejoin home Wi-Fi | Switch it off; a reboot also resets scanning to on (`adb shell service call wifi 58 i32 1` by hand) |

Earlier, before Binnacle existed, the unit also had a tiny shortcut app that opened the hidden Android Settings. Binnacle's app list now does that, so it isn't needed.

## What we tried and didn't keep

- **Patching the stock dash** to show the right range: the patched launcher crash-looped the Home screen, so it was reverted. Binnacle replaced it instead.
- **Updating ZLink:** the unit's package manager rolls ZLink back to the preinstalled copy at every boot (`/product/preinstall`), so a sideloaded update doesn't stick.
