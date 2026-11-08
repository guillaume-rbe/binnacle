# Security

## Reporting a problem
Please don't open a public issue for a security problem. Report it privately through GitHub instead: the **Security** tab of this repository → **Report a vulnerability**. Say what you found, which version (Advanced → About shows it) and how to reproduce it. You'll get an answer as soon as the owner can look at it. This is a one-person hobby project, so allow a few days.

## What counts
- **Binnacle** (`app/`), the **CarPlay app** (`carplay/`) and the **drive logger** (`capture/`): anything that could let another app or a device on the network control the unit, read what it shouldn't, or reach the car.
- The **build guards** (`app/tools/`, `carplay/guard/`, `capture/tools/`): any way to get a write to the car, the MCU or the CAN app past them.
- The **releases**: a published APK that doesn't match its source or its signing key.

Problems in the head unit's own firmware (the stock apps, its ADB, its root) are out of scope unless Binnacle makes them worse or could reasonably guard against them. Upstream xcertplay issues that also affect this fork are welcome here; nothing is posted upstream from this repo.

## Checking a download
Every release APK (`Binnacle.apk`, `BinnacleCarPlay.apk`, `BinnacleLogger-*.apk`) is signed with the same key, and `binnacle-update.txt` gives each one's size and SHA-256. To check the signer:

```
apksigner verify --print-certs Binnacle.apk
```

The SHA-256 certificate digest must be:

```
14ee8d96b1c63874f0767871f5346178d52fd8497cb813ce7ff5f9c507d4fa84
```

Android also refuses to update an installed Binnacle with an APK signed by any other key.
