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
| **Power / regen** | 🟡 Estimated (see below) | 🟡 Estimated (see below) | ❓ |
| **Forward camera** | ✅ | ❓ | ❓ |
| **Wireless CarPlay** | ✅ | ❓ | 🟡 Picture and touch were off on 0.8; 0.9.1 should fix it |
| **Bluetooth calls and music** | ✅ | ❓ | ❓ |
| **Reported in** | The owner's own car, in daily use | [Issue #91](https://github.com/lotech/binnacle/issues/91) | [Issue #90](https://github.com/lotech/binnacle/issues/90) |

**Model Y:** ❓ not tried yet. The dash is sold for the Model Y too, so it is likely to work, but nobody has reported it yet.

**Left-hand drive:** ❓ not confirmed yet. Binnacle has a steering-wheel side setting in its setup.

## Known differences between dashes

- **Battery %.** On some cars the original dash's battery figure is stuck (at 77 % on the owner's car), so Binnacle works the % out from another figure instead. On other cars the battery figure works properly. From the release after 0.9.1, Binnacle notices when the figure changes and then uses it. On those cars, the first drive or charge after installing is what shows it the figure works.
- **Power and regen.** A Tesla software update in late 2026 (2026.38.6.1) changed the data the dash receives, so the exact power and regen figures stopped working on the cars we know of, and other products are affected too. Until the new data is worked out, Binnacle shows an estimate based on how the car speeds up and slows down. Cars still on older Tesla software get the exact figures.
- **Newer dash software (2024).** Binnacle itself runs on it. The optional changes in [UNIT-CHANGES.md](UNIT-CHANGES.md) were only tested on the February 2023 software, so don't try those on a 2024 dash yet. You don't need them for Binnacle to work.

## How to find your dash's details

You don't need a computer for this. Take a photo of each of these screens on the original dash:

1. **CarSetUp → Device information.** This shows Model, Hardware version, Bluetooth version, Android version, System version, App version, MCU0 and MCU1.
2. **App Store → Online Upgrade.** This shows the Current version, a long name starting with `A007_`.
3. Optional: **the original dash's data screen**, the one that shows lines like `code 10: carspeed=…`. This helps with problems like the battery % or power figures.

You can get back to the original dash at any time from **Apps → Advanced → Remove Binnacle**, and reinstall Binnacle afterwards.

## Add your dash to this list

Please [open an issue](https://github.com/lotech/binnacle/issues/new) and include:

- The name your dash was sold under, and your car (Model 3 or Y, year, left- or right-hand drive).
- The photos or values from the screens above.
- Which Binnacle version you installed, and what works and what doesn't.

Leave out anything that identifies your unit or you personally, such as serial numbers, MAC addresses, licence keys or your home address. None of those are needed.
