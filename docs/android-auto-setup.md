# Android Auto status screen: installation and acceptance

Version 0.6.5 updates the native, read-only screen introduced in 0.6.2. It uses real
controller state, never starts a camera command or transfer, and includes no
Shizuku dependency. The phone's background watcher remains responsible for
automatic sync. Opening the car screen does not enable watching or finish
phone setup.

## Build

From the repository root:

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease --max-workers=2
```

Use the APK whose signing certificate matches the installed app: a debug
installation updates from `app/build/outputs/apk/debug/app-debug.apk`, a release
installation from `app/build/outputs/apk/release/app-release.apk`. Do not uninstall
the existing app to work around a signature error: verify the certificate first.

The car host is checked with the Car App Library's published host signature
allowlist. The app does not allow arbitrary hosts, including in debug builds.

## Installation candidate: no Play developer account

Google's [testing documentation](https://developer.android.com/training/cars/testing)
states that Unknown sources does not apply to Car App Library apps. The
following is an unofficial candidate with device-dependent results, not a
supported Google distribution method.

APK delivery and privileged installation are separate steps. Copying the APK
to the phone does not change Android Auto's source checks.

1. Record the phone's Android version, Android Auto version and head-unit model.
2. Obtain [KingInstaller](https://github.com/fcaronte/KingInstaller/releases) and
   [Shizuku](https://github.com/RikkaApps/Shizuku/releases) from their upstream
   projects. Neither needs a paid Play developer account.
3. Enable Android Auto developer mode and Unknown sources. These settings are
   separate from the phone's Developer options.
4. Temporarily enable phone Developer options and debugging, and start Shizuku
   using its [official setup guide](https://shizuku.rikka.app/guide/setup/).
5. Authorize KingInstaller in Shizuku. Use KingInstaller's Shizuku installation
   method to install the Z50 APK with the matching signing key as an update.
6. Confirm Z50 Bridge appears in Android Auto's launcher and opens in the actual
   car. A launcher entry or an emulator screenshot alone is not a pass.

KingInstaller changes installation-source metadata to work around Android
Auto's source filter. It does not add car support to an ordinary APK; that
support is implemented in this app. Some setups still check real Play ownership
and reject this installation method. Do not respond to a rejection by rooting,
disabling unrelated protections, changing accounts or clearing app data.

## Required acceptance test

Perform car interaction while parked. Use the real camera and existing setup;
never send test clips or diagnostics to a production destination.

- Stop Shizuku, turn off phone debugging and phone Developer options, and reboot.
  Keep only Android Auto developer mode/Unknown sources enabled.
- Reconnect Android Auto. Confirm the Z50 launcher entry is still present and
  opening it shows the native status screen.
- With the existing background watcher enabled, confirm camera connection and
  transfer progress change without touching the phone. Verify speed/counts
  against the phone's current transfer, including parallel downloads.
- Confirm progress refreshes for longer than five updates without a host quota
  error. Returning from another car app must show current state.
- Confirm loss of bytes hides old speed after ten seconds and a continued
  stall shows after sixty seconds, with elapsed time since the last data. Confirm completion removes the active transfer metrics.
- Confirm ordinary progress and recovery produce no message pop-ups. A sustained
  problem should alert once, with at least five minutes between problem pop-ups.
- Disconnect/reconnect Android Auto once more with phone Developer options
  still off. Confirm the phone app and existing sync behavior remain functional.

**The sideloading route meets the requirement only after all these checks pass.**
If a future update is installed using a different installer, metadata may change;
updates may require temporarily repeating the installer setup.

## Scope and limits

The screen displays two stable rows: Status and Progress, each with two lines of details.
Only row text changes, so refreshes remain within the host's template refresh
rules. Polling stops while the screen is hidden/destroyed. Both download and upload speed use fresh byte activity. Waiting and stall states
include elapsed time without data. File counts distinguish completed, active and
waiting work, and sent upload bytes do not imply receiver acknowledgement.

The optional Dashcam message alerts remain available independently. Android Auto
may restrict the layout and refresh cadence. This screen does not establish
eligibility for public Google Play distribution in the IoT category.
