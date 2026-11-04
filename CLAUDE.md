# Binnacle — context for Claude

> **ABSOLUTE RULE — NOTHING TO THE CAR.** Nothing in this project ever sends anything onto the car's CAN bus or otherwise affects the car itself. The dash app *subscribes* to the stock CAN service and *receives* data; a write to the dash's own MCU is the one narrow exception, only as set out under "MCU writes" below. This is enforced in code (for the CAN feed one class, two allowed binder calls: subscribe/unsubscribe; the only other binder use is `CarPlayLink` to xcertplay, an app on the unit, see "CarPlay in the dash") and by build-time guards that refuse to build if any write path is referenced: `app/tools/check_readonly.sh` on the source and `app/tools/check_bytecode.sh` on the compiled classes and APK (the logger has its own pair in `capture/tools/`; `app/tools/test_guards.sh` checks that the guards still refuse known bypasses). The Gradle build runs the same scripts at the same points and also refuses any library in the APK (`buildSrc/src/main/java/app/binnacle/gradle/Guards.java`); `app/build.sh` and `capture/build.sh` still run them too. Never weaken or bypass them.

A read-only replacement instrument cluster / dashboard app (plus tooling) for the **Carview 10.25" Tesla Model 3/Y Android cluster** (ZIQI "A007" ROM). It shows live car data taken from the stock CAN app and launches CarPlay / Android Auto / the camera, running as an alternative Home app. It never modifies the stock system.

## The unit
- Carview 10.25" (sold by smeshmega), ODM **ZIQI A007**. SoC **Unisoc UMS512**. Android **10** under the hood (SDK 29) despite a "12" label. SELinux **permissive**, `userdebug`, root via `su 0` (note: `su 0 <cmd>`, not `su -c`).
- Stock dash = `com.xbs.launcher1` (Home). Car data app = `com.xbs.canapp`, which reads the MCU over serial `/dev/ttyHS0` and decodes CAN frames.

## How we read car data — READ ONLY
- Bind the **exported** service `com.xbs.canapp` (action `com.xbs.canapp.action.mcuservice`). AIDL: `com.mcu.iinterface.aidl.IMcuService` (helper `com.mcu.iinterface.McuManager`). Call `regPropIdList(Messenger, int[] codes)`; data arrives on the Messenger as `Message`s where `msg.what` = code and `msg.getData()` = a `Bundle` of fields. Call `unregPropIdList(Messenger)` to stop.
- Permission `com.xbs.permission.READ_CAN_DATABASE` is `protectionLevel="normal"`, so **any app** (our own signing key) can receive the data. No platform signature needed.
- Field map: **docs/FIELD_MAP.md**.

## READ-ONLY RULE (hard requirement)
The app only ever **registers and receives**. It must **never** call the write/tx side:
- never `IMcuService.txMessengerPack / txGeneralProtocol / txPerformUartPack / txUpdateProtocol / setUartNormalTransmitEnable`
- never the native MCU serial write (`com.mct.serial.headunit.McuSerial.writeData` / its `C()` enqueue)
- never write via `content://com.xbs.can.provider`
- never use the CarSetUp (`com.xbs.carsetup`) settings that push values to the car. (The dash only READS `Settings.System xbs_carmsgdisppost`, the CarPlay layout, in `StockScreens`; `check_readonly.sh` allows that one read and nothing else. It does not open the car-setup screen, because that screen also holds settings the CAN app forwards to the MCU.)

No bytes ever go onto the vehicle CAN bus. MCU writes only as below.

## MCU writes (owner rule, 1 Nov 2026)
A write to the dash's own MCU is **acceptable but not preferred**: the owner accepts the small risk to make the dash easier to use. Each one is still a deliberate exception:
- prefer anything that avoids the MCU (an Android setting, an intent, a stock app's own screen);
- **confirm with the owner before assuming any MCU write**: bring the exact message, what it does, and the evidence it stays on the dash (best: it mirrors what a stock app already sends for the same action), and wait for a go on that specific message;
- never a message that is, or could be, forwarded onto the CAN bus or changes anything in the car;
- the guards stay as strict as they are: an approved message is added as one pinned exception (exact call and payload), never a general write path, and `test_guards.sh` gets a case proving any other write is still refused;
- listed with its undo in `docs/UNIT-CHANGES.md`.
Approved so far: none.

## Dash-local writes (owner rule, 31 Oct 2026)
The absolute line is the car: nothing ever reaches the CAN bus, and the MCU / canapp write side only under "MCU writes" above; the guards stay as strict as they are. Changes that stay **on the dash itself** (an Android setting, a system property, another app's component or app-op, a file in the app's own storage) are allowed, but only when:
- the owner has approved that specific change;
- it is pinned in the guards (a fixed `LocalShell` command, or an exact allow-list entry), never built from caller text;
- it is opt-in for users, says what it does, and can be undone from the app;
- it is listed with its undo in `docs/UNIT-CHANGES.md`.
Approved so far: Bluetooth auto-connect (`settings put system xbs_isautoconnect`, via `LocalShell`), End CarPlay (Wi-Fi off/on, via `LocalShell`) and the start-up media volume (`StartupVolume`: `AudioManager.setStreamVolume(STREAM_MUSIC, level)` at start and when the car's Bluetooth audio connects, 100% by default, Settings → Start-up volume, owner OK 4 Nov: Android's own music volume, which the CAN app also sets locally for the wheel keys; not the car-setup "speaker level", which goes to the MCU). Anything the CAN app forwards to the MCU (backlight mode, speaker level, ambient light, camera type/trigger, night mode) is an MCU write: only under "MCU writes" above, never assumed.

## Must-keep features — never break these
- **Forward/reverse camera**: separate app `com.xbs.frontcam`, activity `com.xbs.frontcam.CameraActivity` (also responds to intent `com.xbs.action.reqcam`). Auto-shows on Drive via the CAN `frontcamstate` flag — a replacement Home re-does that (watch the flag, launch the camera). Independent of the Home app otherwise.
- **CarPlay / Android Auto**: CarPlay opens xcertplay (`com.shilapi.xcertplay/.CarPlayHostActivity`) when it is installed (owner, 1 Nov), otherwise ZLink (`com.zjinnova.zlink`, the licensed *xinbasi-a007* build, which also does Android Auto). Launch by intent, or, when xcertplay is signed with the dash's key, draw it in the dash (see "CarPlay in the dash"). Never uninstall or disable ZLink; with xcertplay installed it is only hidden from Binnacle's app list.
- Keep the stock launcher `com.xbs.launcher1` installed as the **fallback Home**.

## CarPlay in the dash (CarPlayLink, owner OK 4 Nov 2026)
The dash can draw CarPlay in its own window (centre or full screen) so it stays the top activity and the CAN app keeps the door pop-up hidden. xcertplay (an app on the unit) runs the session; `CarPlayLink` binds its `CarPlayEmbedService` and hands it a Surface, touch and wheel keys. This is the second binder exception after `CanFeed`, pinned the same way:
- one class (`CarPlayLink`), one `bindService` to the fixed component `com.shilapi.xcertplay/.CarPlayEmbedService`, one `transact` that refuses any code outside ICarPlayEmbed's 1..10 before touching the binder;
- state comes back on a Messenger the dash only receives on (never `Messenger.send`);
- both guards pin the class (source text; compiled opcodes and strings), and `test_guards.sh` has cases for a widened code check, another package, a changed txn, a Messenger send and a second transact.
It needs xcertplay signed with the dash's key (the service is signature-protected); otherwise binding fails and the CarPlay button opens xcertplay's own screen as before. Nothing in this path reaches the car or the MCU.

## ADB / network facts
- `adb connect <unit-ip>:5555` (persistent, no key auth on the LAN).
- **Single Wi-Fi radio**: wireless CarPlay brings up a SoftAP that drops the dash off home Wi-Fi, so ADB over the LAN dies while wireless CarPlay is connected.
- **Android Bluetooth stack is OFF by default**: Bluetooth calls and audio go through a separate Goodocom serial module. Setting `persist.car.use_system_bt=2` (dash-local, then reboot) turns Android Bluetooth on alongside it, with Bluetooth LE; on trial on the owner's unit since 31 Oct.
- Not in the data feed: **true range (km)** and **brake/stop-light state**. On this car `batterylevel` is stuck at 77, so the dash decodes the battery % from code 11 `jhmail` instead (`BatteryDecoder`, a table recorded during a 13% → 100% Supercharge on 30 Oct; see docs/FIELD_MAP.md).

## Local shell (LocalShell)
- The unit's own ADB daemon listens on all interfaces at 5555 with no auth, so the app reaches it on 127.0.0.1 and runs a **fixed** list of shell-user (not root) commands, e.g. End CarPlay = `svc wifi disable && sleep 2 && svc wifi enable && echo BINNACLE_OK` (run() requires the marker) (never force-stop ZLink: it breaks the Goodocom Bluetooth until a reboot).
- `check_readonly.sh` pins the exact command list, keeps sockets in `LocalShell` only, and forbids caller-supplied text. Never add a command that touches the car, the MCU or CAN. A dash-local setting or property is added only under the "Dash-local writes" rule above (owner approval, pinned, opt-in, reversible, documented).

## Steering-wheel input
`canapp` injects **standard Android KeyEvents** from the MCU. Scroll up/down = `DPAD_UP`(19)/`DPAD_DOWN`(20); right tilt (OK) = `ENTER`(66); left tilt = `BACK`(4); plus volume/media/home. Handle with normal `onKeyDown` / focus navigation — no special integration.

## Assets and privacy
The dash bundles its own car renders in `app/assets/cars/` (CC BY 4.0 model by Ameer Studio; keep the credit in `assets/cars/CREDITS.txt`, the README and Advanced → About). The dash never uses the stock dash's ZIQI renders (no /sdcard fallback, no storage permission); they have never been committed to this repo. Never add them. Never commit device identifiers (chip IDs, serials, MAC) or the ZLink licence key/SN/ID.

## Logging / working method
Record every change with how to undo it. Propose risky or outward-facing changes before applying. The owner keeps a running WORKLOG for the hands-on reverse-engineering; keep code-level decisions documented here and in docs/.
