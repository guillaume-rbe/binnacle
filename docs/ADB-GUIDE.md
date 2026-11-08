# Enabling network ADB (and root) on the Carview 10.25" Tesla Model 3/Y Android cluster

This guide gets you an ADB shell over Wi-Fi on the Carview 10.25-inch "Android 12" 4G instrument cluster (really Android 10, see the README) for the Model 3/Y (sold by smeshmega). It is likely the same hardware as the Hansshow and Tesery 10.25" units, so it may work on those too, as long as your firmware matches the table below. The USB-C port on these units is host-only, so a USB cable won't work. Everything here goes through Wi-Fi.

## Check your unit matches

These steps were tested on a unit reporting the following (check under About, or later with `getprop`):

| Property | Value |
|---|---|
| SoC | Unisoc (Spreadtrum/sprd) UMS512, not MediaTek |
| Android | Labelled 12 (build SP1A.210812.016), but SDK 29 = **Android 10**; security patch 2021-12-05 |
| Build | `A007-userdebug 12 SP1A.210812.016 202302282110 release-keys` |
| Fingerprint | `Vastking/KingPad_SA10_EEA/KingPad_SA10_EEA:12/.../202302282110:userdebug/release-keys` |
| Kernel | 4.14.133+ (Feb 2023) |
| Model / board | A007 |

If your unit is a different SoC or firmware, the EngineerMode route may not exist or may behave differently.

## What you need

- The unit connected to your Wi-Fi, and a computer on the same network with `adb` installed (Android platform-tools).
- A VNC viewer on the computer (macOS has one built in: `vnc://` in Finder > Go > Connect to Server).
- Google Play on the unit, to install two free apps.

In the commands below, replace `<unit-ip>` with the unit's IP address on your network. Giving it a DHCP reservation in your router makes life easier.

## Steps

1. **Join the unit to Wi-Fi** and note its IP address. The app drawer is behind the home icon and has two pages.

2. **Install droidVNC-NG** from Play. Grant it Accessibility and screen-capture permission, then start the server. Connect from your computer to `vnc://<unit-ip>:5900`. The cluster screen is very wide and the on-screen keyboard covers most of the UI, so typing from the VNC view is much easier. Granting these permissions also takes you into the hidden stock Android Settings, which is handy.

3. **Install Activity Launcher** (`de.szalkowski.activitylauncher`) from Play. Find `com.sprd.engineermode` and open its **main** activity.
   Don't try to launch `AdbShellCMDActivity` directly. It isn't exported, so you get a SecurityException.

4. **In EngineerMode, open the DEBUG&LOG tab, then Adb shell.** This runs commands as the system user (uid 1000). How to use it:
   - Type a command into the top "Adb shell ___" field.
   - Close the keyboard with the back arrow at the top right.
   - Tap the orange **START/END** button.
   - The output appears under "Command result".
   It isn't a real shell. Enter doesn't run the command, and `>` redirection, pipes and chained commands don't work. Run one command at a time.

5. **Run these three commands, one at a time:**
   ```
   setprop service.adb.tcp.port 5555
   settings put global adb_enabled 1
   setprop ctl.restart adbd
   ```

6. **Connect from your computer:**
   ```
   adb connect <unit-ip>:5555
   adb -s <unit-ip>:5555 shell
   ```

At this point ADB works until the next reboot.

**With Binnacle installed** you can stop after step 5: Binnacle's setup (or Settings → Unit) keeps ADB on after restarts and makes the other unit changes for you. Binnacle also blocks ADB from the Wi-Fi each time it starts, so step 6 only works with **Apps → Settings → Unit → Network ADB** turned on.

## Root

On this firmware the build is `userdebug`, `/system/xbin/su` is present, and SELinux is **Permissive**. Once you're in `adb shell`:

```
su 0 id          # should print uid=0(root) ... context=u:r:su:s0
```

The syntax is `su 0 <command>`. `su -c` isn't supported. The bootloader also reports as unlocked (`ro.boot.flash.locked=0`, verified boot state "orange").

## Make network ADB survive reboots (optional)

```
adb -s <unit-ip>:5555 shell su 0 setprop persist.adb.tcp.port 5555
```

`adb_enabled` is already saved by step 5. To check, reboot the unit and then run:

```
adb connect <unit-ip>:5555
adb -s <unit-ip>:5555 shell uptime     # should say "up 1 min" or similar
```

If that works, you never need the EngineerMode steps again.

**To undo:**
```
adb -s <unit-ip>:5555 shell su 0 setprop persist.adb.tcp.port ""
```
Then reboot. ADB over Wi-Fi will be off again.

## ⚠️ Security warning: read this before making it persistent

On this firmware `ro.adb.secure` is **not set**, so adbd **does not ask for authorization**. There's no "Allow USB debugging?" prompt and no key check. With port 5555 open:

- **Anyone on the same network can connect without a password and get root** with `su 0`, with SELinux permissive. That includes the unit's own 4G hotspot if you share it, and any public or shared Wi-Fi the unit joins.
- With root they can read anything on the unit, install software, or brick it.

Only make it persistent if the unit stays on networks you trust. Otherwise use the non-persistent steps and redo them when you need access, or undo the change when you're finished.

## Dead ends (so you don't waste time)

- **USB-C to a computer:** the port is host-only, with no device mode, so ADB over USB is impossible.
- **Port 5555** is closed by default.
- **Developer options** are present, but there's no Wireless debugging and no USB mode or OTG switch.
- **Activity Launcher** has no hidden wireless-ADB activity on this firmware.
- **"6688" factory settings:** no USB or ADB toggle found.
- **The EngineerMode Adb shell** has no redirection or chaining, so you can't script it. Only single commands.
- **The SoC is Unisoc, not MTK,** so MediaTek guides and tools (MTK Engineering Mode, SP Flash Tool, mtkclient) don't apply.

## Strongly recommended: back up first

With root you can dump every partition before changing anything. The unit-specific NV and calibration partitions (`prodnv`, `miscdata`, `l_fixnv1/2`, `l_runtimenv1/2`, `persist`) can't be replaced if they get corrupted.

```
adb -s <unit-ip>:5555 shell ls -l /dev/block/by-name/
adb -s <unit-ip>:5555 exec-out "su 0 sh -c 'dd if=/dev/block/by-name/boot bs=4M 2>/dev/null'" > boot.img
```

The `2>/dev/null` *inside* the quotes matters. `su` merges stderr into stdout, so without it dd's summary line ends up at the end of your image. Check each image against `adb shell su 0 sha1sum /dev/block/by-name/<name>`. Over Wi-Fi it runs at about 4 to 5 MB/s, so the 5 GB `super` partition takes about 20 minutes.

Don't post your dumps or `getprop` output publicly. They contain the unit's serial and IMEI-style identifiers.
