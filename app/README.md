# app/ — Binnacle (read-only replacement cluster)

Installed as package `com.xbs.launcher` (see below; the Java code is in `app.binnacle`); the dash activity is `com.xbs.launcher.MainActivity`. Native Android (Java, Canvas-drawn panels), minSdk 21 / targetSdk 29, ~90 KB, no libraries. In daily use as the Home app on the owner's unit since 29 Oct 2026.
Layout = the agreed "Dream" mockup: **left** speed, PRND, indicators, speed limit, status lights, power/regen bar ·
**centre** parked side view that turns and opens the doors, boot and frunk that are open (stock door frames from the unit), or rear view on a moving road in Drive/Reverse, parking-sensor arcs with a STOP sign, the odometer bottom right, and optionally the forward camera ·
**right** outside temp and a network icon, battery % + estimated range, a swipeable parking-sensor / tyres card, and a dock (CarPlay, Camera, Apps).

## Read-only (absolute rule)
The app never sends anything to the CAN bus or the MCU. How that is guaranteed:
- `CanFeed` binds `com.xbs.canapp` and only ever makes IMcuService transaction **1** (register) and **2** (unregister); `call()` refuses (logs and drops) anything else.
- `tools/check_readonly.sh` runs before every build and fails it if the source mentions any car-write API (tx*, McuSerial, `com.xbs.can.provider`, `com.xbs.carsetup`, content-resolver writes), reflection or loaded code, or calls `transact()` outside `CanFeed`. It also refuses symlinks and `\u` escapes in the source, which javac reads differently from a text search.
- `tools/check_bytecode.sh` then checks what javac produced (and the packed APK), so no source trick gets past: `IBinder.transact` only in `CanFeed.call`, which must refuse anything but 1 and 2, pass its argument unchanged and only be called with 1 or 2; no reflection, dynamic code, native code, processes or settings writes; sockets only in `LocalShell`, HTTP only in `CarPacks`, file writes only in `CarPacks`/`CameraPane`; `LocalShell`'s string constants pinned; one dex and nothing executable in the APK.
- `tools/test_guards.sh` (run by hand after changing a guard) slips ~30 known bypasses into copies of the source (reflection, whitespace and unicode tricks, symlinks, a widened allow-list, a concatenated shell command, another ADB service...) and checks both guards refuse each one. It covers the logger's guards too.
- Emulator test against `testing/fakecan` (a stand-in canapp that logs every binder call it receives): the only calls ever seen were 1 (subscribe) and 2 (unsubscribe), across start, service kill/reconnect and demo on/off.
- The only things it sends are ordinary app intents on the head unit: start ZLink / the camera activity / the drive logger's service, and the stock launcher's own "close camera" broadcast to `com.xbs.frontcam` (`com.xbs.action.reqcam`, `reqVal=false`). Nothing reaches the MCU or the car.
- **On the unit itself** it does two more things, both pinned by the guard:
  - `LocalShell` runs a **fixed** list of shell-user commands through the unit's own ADB daemon on 127.0.0.1:5555: End CarPlay (`svc wifi disable && sleep 2 && svc wifi enable`) and Bluetooth auto-connect get/on/off (`settings … system xbs_isautoconnect`, which the stock Bluetooth screen writes and only the Bluetooth side reads). Callers can't pass their own text, sockets are allowed only in `LocalShell`, and each command must end with its success marker. **Never force-stop ZLink**: it leaves the Goodocom Bluetooth refusing the phone until a reboot.
  - `CameraPane` writes the TP9950 decoder's `/sys/ziqi/camera/channel` and `N_P_pre_format` before opening the camera, as the stock camera app does (world-writable, I2C chip on the unit, not the MCU). No other file writes are allowed.

## Car art
The APK bundles its own car renders in `assets/cars/<hand>/<colour>/`, where hand is `rhd` or `lhd` for a Model 3 and `y-rhd` or `y-lhd` for a Model Y (Car → **Car model** and **Driving side**), with the same sizes and positions as the stock sets but Binnacle's own file names (`turn/0..24.png`, `body/{left,middle,right}.png`, `doors/{side,face}_<door>/0..6.png`, `covers/`, `tpmscar/`), rendered from a 3D car model by Ameer Studio, CC BY 4.0, modified (full credit in `assets/cars/CREDITS.txt`, the main README, and in the app under Advanced → About). Only silver (dark grey) right-hand drive is bundled, for the Model 3 and the Model Y, which keeps the APK reasonably small and quick to install. Other colours work the same way: Apps & settings → Car → **Car images** downloads the pack for the car's colour and driving side over Wi-Fi from this project's GitHub release (`cars-v2`, `binnacle-cars-<hand>-<colour>.zip`) into `/sdcard/Android/data/<APP_ID>/files/<hand>/<colour>/` (`CarPacks`; the guard pins the source URL, allows only downloads, and only writes inside that folder). A pack can also be copied there by hand. `CarArt` picks, first match wins: a pack in the app's external files dir, the bundled pack for the car's colour (`carcolor`, 4 = silver) and driving side, any bundled colour on that side, then the other side (so there's always a car). Door layers are named by their real side in both, and the door bits (`doorval`) are physical, so only the pictures change with the driving side. The stock dash's own renders are never used, so the app needs no storage permission. Without any it draws its own simple car shapes.

To regenerate a colour: `Blender -b --factory-startup -P work/3d/scripts/make_pack.py -- <fbx> <out> paint=<colour> hand=rhd` (scripts live in the project's work folder, not this repo).

## Hiding the stock door popup (package name)
The stock CAN app (`com.xbs.canapp`) pops up its own door picture on every door or boot change unless the activity in front is exactly `com.xbs.launcher` / `com.xbs.launcher.MainActivity` (or the stock `com.xbs.launcher1`), hardcoded in its `r1.e`. So the dash's activity is named `com.xbs.launcher.MainActivity`, the Home role sits on that activity itself (an alias showed up under its own class name and let the pop-up through), and the app is built with the matching package name (the stock dash is `com.xbs.launcher1`, so they don't clash; it's the default):
```
APP_ID=com.xbs.launcher app/build.sh
```
`APP_ID=app.binnacle app/build.sh` builds a copy under another name, for example to sit beside it on an emulator; the pop-up then shows as before. Nothing in the CAN app is changed.

## Build
```
./gradlew :app:assembleRelease   # -> app/build/gradle/outputs/apk/release/app-release.apk
./release.sh                     # with the logger, release key -> build/release/Binnacle-<version>.apk
app/build.sh                     # the same APK without Gradle -> app/build/binnacle.apk
```
Gradle runs the same guards and tests at the same points as build.sh (below) and builds only the release variant. `-Pbinnacle.appId=app.binnacle` (or `APP_ID=app.binnacle`) builds the side-by-side copy. build.sh starts by deleting `app/build/`, Gradle's folder (`app/build/gradle/`) included, so Gradle just rebuilds after it. The version lives in `AndroidManifest.xml` (`versionCode`, `versionName`) for both builds; minSdk 21 / targetSdk 29 are in `build.gradle.kts` and in build.sh's aapt2 flags.

`app/build.sh`:
Needs JDK 17 and the Android SDK (build-tools + a platform). It runs `tools/check_readonly.sh` and `tests/run.sh` (tyre-pressure and battery decoding) first, `tools/check_bytecode.sh` on the classes and the APK, and stops on any failure. Signs with `~/.android/debug.keystore` unless `KEYSTORE`/`KS_ALIAS`/`KS_PASS` are set. Use the same key every time or upgrades will be refused.

## Test off the car
With no `com.xbs.canapp` on the device (emulator), or with **Apps & settings → Advanced → Demo data** on, it plays a 40 s simulated drive (park, drive off, indicate, regen, reverse with sensors) and shows a **DEMO DATA** badge. The demo never triggers the real camera.
```
adb -s emulator-5554 shell wm size 1920x720 && adb -s emulator-5554 shell wm density 320
adb -s emulator-5554 install -r app/build/binnacle.apk
```

## Emulator test against a fake CAN service
`testing/fakecan/` builds an **emulator-only** stand-in for `com.xbs.canapp` (it can't install on the unit: the real one is a system app with another signature). It streams capture-shaped Bundles (plus a wrong-typed field and an unknown code) and logs every binder transaction under tag `FakeCan`, printing `VIOLATION` for anything other than 1/2.
```
app/testing/fakecan/build.sh
adb -s emulator-5554 install -r app/testing/fakecan/build/fakecan.apk
adb -s emulator-5554 logcat -s FakeCan
```

## Install on the unit
```
adb connect <unit-ip>:5555
adb install -r app/build/binnacle.apk
adb shell pm grant com.xbs.launcher android.permission.ACCESS_FINE_LOCATION   # GPS compass
adb shell su 0 cmd package set-home-activity com.xbs.launcher/com.xbs.launcher.MainActivity
adb shell appops set com.xbs.launcher SYSTEM_ALERT_WINDOW allow   # lets the Drive camera open over CarPlay
```
- After an install that **adds a permission** (e.g. the network permissions), reboot the unit: until then netd refuses the app's sockets (EPERM), so End CarPlay fails.
- Car sleep force-stops the dash and the drive logger; the Home role brings the dash back on wake, and the dash restarts the logger's service (the Unisoc power manager can still block that; then start the logger by hand).
- Undo: `adb shell su 0 cmd package set-home-activity com.xbs.launcher1/.MainActivity` (the stock dash is never removed; it is also one tap away in **Apps → Stock dash**), then `adb uninstall com.xbs.launcher` if wanted.

## Car scene (centre)
- **Parked:** the stock turn frames (`turn/0..24`: 0 = left side, 12 = behind, 24 = right side) turn the car to face open doors, and the stock 7-frame door layers open at the stock offsets (`DoorScene`, re-implementing the stock `CarDoor3DLayout`); an open boot or frunk is tinted with the pack's cover silhouettes (`covers/` in the colour folder). The car stands on a tilted floor ellipse. Frames load lazily into a 48 MB cache, released on detach and under memory pressure.
- **Lights** (`CarLights`): own-drawn additive glows at lamp positions measured from the renders (rear lamps from their red pixels; front lamps by eye): amber indicators following the car's flag (it already flashes), red tail/position, white headlights (bigger for high beam), and in the driving view a soft headlight wash that pools around the car's nose and fans up the road (longer and brighter on high beam). **No brake lights**, since the feed has no brake field.
- **Fog lights:** front fog glows low in the front bumper on the side views; rear fog glows red in the lower rear bumper on the rear views.
- **Park <-> drive hand-over** (`CentreView.updateBlend`): to Drive the parked car turns to face away (turn frame 12 has the same framing as the driving render), then the road fades in while the car slides to its driving spot (0.7 s smoothstep); to Park the reverse, ending with the turn back to the side view. A gear must hold 400 ms before the scene follows (D-N-D or a feed blip doesn't trigger it), an unknown gear keeps the scene, and the wait for the turn times out after 1.5 s so it can never stall. While handing over, `DoorScene.force()` holds the car facing away with doors closed.
- **Autopilot** (`autodrivermode`, code 9, = Autosteer): the lane under the car fades to blue and the lane lines turn light blue; Apps & settings → Car → **Rainbow road on Autopilot** swaps the blue for perspective colour bands that flow with the road (their colour fades out where they get too thin to draw, so the far end is a soft glow rather than flicker). The follow-distance setting (`cardistance`) shows on a backing under the car. The feed has **no set-speed field**, and cruise-only (TACC) isn't flagged.
- **Parking sensors:** coloured arcs front and rear (they fade in and out), and a red STOP octagon above the fan when an obstacle is very close. While parked they also show on the right-hand card, so they stay visible when the camera takes the centre.
- **Badges** at the top of the centre: only things that need attention (doors open, waiting for car data, problems, demo).
- **No stale data:** if the car is moving and nothing at all arrives from the CAN app for 8 s (`DashApp.STALE_MS`, about 3× the longest gap in real drive captures), the dash clears speed, gear and lights, shows **NO CAR DATA, RECONNECTING** until live data returns, and subscribes again (unregister, register). If the feed drops while the stock camera is up, a pending camera open is dropped but the camera is not closed blind (the car might be in Reverse); it closes on the next live flag.
- Undo: these are drawing-only; reverting the commits restores the plain side/rear renders.

## Left panel and warnings
- **Gears** read R P N D (reverse on the left).
- **Status icons:** the five fixed ones, then any of seatbelt, Auto Hold and fog that are on (no auto high beam: its field never changed in the car), in line on one row (it tightens and scales down slightly when many are on).
- **Attention warning:** while the car's Autosteer nag is on (`driverwarning`), a blue LED-style bar pulses out from the centre to both edges along the top of the centre window; it fades out a quarter-second after the car clears it.
- **After a restart:** the car only resends lights and warnings when they change, so the last ones are kept (`last_code9` prefs) and restored if under 30 minutes old (indicators and the nag are never restored).

## Steering wheel
Scroll down/up moves the dock selection (highlight times out after 8 s), right tilt (OK/ENTER) opens it, left tilt (BACK) clears it. As Home, BACK never closes the dash. In Apps & settings, scroll walks through the tabs and tiles in reading order (blue outline) and OK selects.

## Right panel
- **Network icon** (top right): Wi-Fi fan on home Wi-Fi, rings when the unit is running CarPlay's hotspot, dim when neither. Tap it for a prompt: start CarPlay, or end CarPlay and rejoin Wi-Fi.
- **Battery:** the car's own % field (`batterylevel`) is used once it has been seen to change (it works on most cars, issue #91). On the owner's car it is stuck at 77, so until it moves the % is decoded from code 11 `jhmail` (`BatteryDecoder`, a table recorded during a 13 % → 100 % Supercharge; checked against every reading from 13 % to 100 %). Under it: `≈ N km est.` from the % and **Range at 100 %**. When no live value has decoded yet (just woken, or a value off the table) it shows the last known % with a quiet "Estimated" line until the car sends one. While charging (code 9 `chargelight`) the label reads CHARGING and a light sweeps along the bar as the % climbs; no charge power or time to full is shown, since the car doesn't send them (the owner: no estimates).
- **Middle card, three pages:** parking sensors (automatic while parked), tyre pressures (automatic once driving; the car only sends pressures then), and **efficiency**: net Wh/km over the last 5 km, a 15-minute strip of power (amber, up) and regen (green, down), and this drive's km, Wh/km and regen share (`EfficiencyTracker`, fed on every code 10 so it keeps counting behind CarPlay; `curentergyval` taken as kW: driving peaks ~210, still to confirm against the car's trip screen). Swipe left/right moves a page; a swipe holds until you next drive off.
- **Dock:** CarPlay (starts ZLink; ZLink's own Background Connection is off so CarPlay only starts from here), Camera (lit and reads Close while open), Apps.

## Apps & settings (dock → Apps)
One wide screen (`HubActivity`), compact cards, tabs with icons down one side (‹ Back to Dash, Apps, Display, Camera, Car, Advanced; the chosen tab is filled blue), on the **right by default** for a right-hand-drive car. The steering wheel walks through tabs and tiles.
- **Display:** **Home app** (Set as Default opens Android's Home picker), Theme (**Auto** follows the screen brightness the car sets, code 18: dark at ≤ 30, light at ≥ 45, after it holds 30 s; or Light / Dark), Text size (100 / 115 / 130 %), Menu side, **Bluetooth auto-connect** (the unit's own setting, via `LocalShell`), **End CarPlay** (cycles Wi-Fi: drops CarPlay's hotspot and rejoins home Wi-Fi), and the CarPlay layout (read only).
- **Camera:** **Camera in the dash** shows the forward camera in the centre window. The panel runs at 56 Hz and the camera at 30 fps, so `FramePacer` shows each frame for exactly two refreshes (28 fps) and auto-exposure is locked; the image is revealed after 1 s to hide the decoder's settle roll. It opens on the car's camera flag or the dock button, and in Drive stays until 15 km/h or you close it. **Open stock camera on Drive** uses the stock camera screen instead (off by default).
- **Car:** Car model (3 / Y), Driving side, **Car colour** (Auto from the car's `carcolor`, or a paint swatch), **Car images** (built in, or Download / Remove), **Units** (Auto from the car / Metric / Imperial), Tyre pressure (psi / bar), **Range at 100 %** (default 410 km, about 4.1 km per % on this car), and **Autopilot indication** (Normal blue lane or Rainbow road).
- **Advanced:** Show raw CAN values, Demo data, the optional drive logger, **Reset or remove** (reset Binnacle's settings, or uninstall it and go back to the stock dash), About.

**CarPlay layout:** split-screen CarPlay (shown in the unit's manual) is currently broken on this unit: ZLink renders 1280 px wide into a 1920 px window, so the picture is letterboxed and touch is offset. The stock app owns that setting (it sets system properties only a system app can write, and its screen also holds settings the CAN app forwards to the MCU), so the dash only reads it.

## Still to verify in the car
- ~~Tyre pressure scale~~ confirmed in the car on 27 Oct: 2.9 bar = the stock 42.0 psi. Fixed scale taken from the stock launcher (raw = 1/10000 of the reported unit), so low pressures read low. Shown in psi by default. `tests/run.sh` (run by build.sh) checks the decoding.
- Parking-sensor scale (255 = nothing; smaller assumed closer).
- ~~Indicators~~ confirmed working in the car on 27 Oct.
- No brake-light or true-range field exists in the feed.
- Battery % below 13 % (the table there is untested).
- Forward camera opening over CarPlay (needs the appops grant above; Android 10 blocks background activity starts otherwise).
- Power bar full scale (raw 0–60 seen; bar full at 80 power / 40 regen).

## Files
| File | What |
|---|---|
| `src/.../CanFeed.java` | subscribe-only binder client for the stock CAN service |
| `src/.../DemoFeed.java` | simulated drive, same Bundle shapes as the real feed |
| `src/.../CarState.java` | tolerant decoding of the Bundles (field map in `docs/FIELD_MAP.md`) |
| `src/.../DashApp.java` | process-wide state + feed, forward-camera trigger |
| `src/.../DashActivity.java` | the dash screen, steering-wheel keys (registered as its subclass `com.xbs.launcher.MainActivity`) |
| `src/.../LeftPanelView`, `CentreView`, `RightPanelView` | the three Canvas-drawn panels (1920x720 design px, scaled) |
| `src/.../HubActivity` | apps and settings for the wide screen (side tabs, tiles, cards) |
| `src/.../Launch.java` | intents for ZLink, camera, apps |
| `src/.../BatteryDecoder.java` | battery % from `jhmail` (table) |
| `src/.../CameraPane.java`, `FramePacer.java` | the camera inside the dash, paced to the 56 Hz panel |
| `src/.../DoorScene.java`, `CarArt.java`, `CarLights.java` | the parked car: turn frames, doors, boot/frunk covers, lights |
| `src/.../LocalShell.java` | the fixed list of on-unit shell commands (End CarPlay, BT auto-connect) |
| `src/.../StockScreens.java` | reads the CarPlay layout setting |
| `tests/` | tyre-pressure and battery decoding tests, run by `build.sh` |
| `tools/check_readonly.sh`, `tools/check_bytecode.sh` | build-time read-only guards (source; compiled classes and APK) |
| `tools/test_guards.sh` | checks the guards (app and logger) still refuse known bypasses |
| `testing/fakecan/` | emulator-only fake CAN service for testing the real feed path |
