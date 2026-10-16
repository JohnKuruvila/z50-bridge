# Z50 Bridge

Android companion app for the DDPAI Z50 dashcam. It pulls finished clips over
the camera's Wi-Fi AP and streams them to an ingest host via high-speed
HTTP ingest (port 5008), using the same CGI flow as the
[ddpai-z50-api](https://github.com/JohnKuruvila/ddpai-z50-api) client.

It exists because the Play Store build of Termux could not use the local network
on the Android version I tested; this app declares `ACCESS_LOCAL_NETWORK` and
asks for it on first launch.

> **Unofficial.** Not affiliated with, endorsed by, or supported by DDPAI.
> Sideload only — this is not on Google Play, and it asks for background
> location and local-network permissions that Play policy would scrutinise.
>
> Built with AI coding agents under human direction. The companion write-up is linked from the GitHub profile.

## What it does

- Detects the camera by probing `193.168.0.1:80` (binds to that Wi-Fi even if
  mobile data or WireGuard is the default route).
- Same CGI flow as the [ddpai-z50-api](https://github.com/JohnKuruvila/ddpai-z50-api)
  client (`ddpai_sync.py`): session, certificate, skip the open clip,
  SuperDownload, resume `.part` files, `synced_history.json`.
- Downloads up to 4 clips in parallel (Settings → Parallel camera downloads)
  to work around single-stream throughput on the camera's 2.4 GHz AP. Stream
  counts for downloads and uploads apply to a running transfer: more streams
  start within half a second; with fewer, the extra streams finish their
  current file first.
- **Sync order**: GPS sidecars, then clips with a partial download on the
  phone, then the rest (newest first by default). The camera is re-listed every
  60 s during a sync, so clips recorded since it began go ahead of the backlog.
  The front camera records about 3.3 MB/s and the 2.4 GHz link moves about
  2.5–3 MB/s, so a sync while driving can't catch up; this order keeps the most
  recent footage. **Sync window** (All / 15 min / 30 min / 1 h / 2 h) limits a
  sync to clips from the last part of the recording.
- Partial downloads older than the oldest file the camera still lists are
  deleted at the start of a sync (loop recording has overwritten those clips).
- **Transfer diagnostics**: every sync records the phone's Wi-Fi link to the
  camera (band, channel, RSSI, link rates, sampled every 2 s), per-file timing
  split into network wait vs storage writes, and camera recording/temperature.
  During a sync the camera's SoC temperature, GPS fix and recording state are
  read once a minute and listed in the report.
  **Settings → About → Camera speed test** reads clips without saving them to
  compare 1 vs 2 streams and SuperDownload on/off. Recent reports go into
  **Share diagnostics** and to logcat (`adb logcat -s Z50Diag`).
- **Diagnostics without asking**: the full diagnostics report (secrets left
  out) is sent to the receiver's `/diagnostics/<name>.txt` after each offload,
  and at most every 10 minutes while the homelab is reachable and Activity has
  something new. The receiver keeps the newest 200 in
  `~/.local/share/z50-receiver/diagnostics`, apart from the clip inbox. The
  app also keeps today's report in **Downloads/Z50 Bridge** (a week of daily
  files), which survives uninstalling the app.
- Manual **Sync Camera** / **Offload** buttons probe on tap, and the persistent
  notification exposes Sync / Offload / Stop actions. Connectivity changes and
  app resume re-probe immediately instead of waiting for the poll interval.
- Offloads `.mp4` / `.gpx` / `.git` / `.gsn` to
  `http://<your-ingest-host>:5008/upload/<file>` directly into
  your configured inbox directory using OkHttp streaming, so memory use stays
  flat regardless of clip size. Home Wi-Fi is preferred; WireGuard is used only
  when LAN cannot reach the host. Offload never uses mobile data, including
  WireGuard running over
  mobile data; a transfer stops if the tunnel falls back to cellular.
- **Offload Wi-Fi networks** (Settings → Offload → Offload Wi-Fi networks): a
  tick list of the network the phone is on, networks it has joined while the
  app ran, and nearby networks from a Wi-Fi scan, plus "Add a network by
  name". Android 10+ does not let apps read the phone's saved networks, so
  these stand in for them. With any ticked, auto, manual, and Tasker offloads
  only run on those Wi-Fi networks. WireGuard over Wi-Fi stays
  exempt, so remote offload over the tunnel still works. Reading network names needs the Location runtime
  permission (Android gates SSIDs behind it) — the app never reads location.
- **Camera Wi-Fi auto-connect** (Settings → Camera): registers the Z50
  SSID through the `WifiNetworkSuggestion` API, which is the only automatic
  join path left since Android 10. Android asks once to approve Wi-Fi control,
  then the phone joins the camera AP on its own when it comes in range (Wi-Fi
  must be on). This is why Tasker cannot do it. Note Android suppresses
  auto-join after an explicit **Disconnect** in the Wi-Fi picker until that
  network is manually connected once; use the suggestion entry in the picker
  instead of disconnecting.
- **Watch in background** (Home) is one saved switch. While it is on, the
  watcher starts again after a phone restart, after an app update, and when the
  app is opened, so it is never silently off. **Stop watching** in the
  notification turns the switch off.
- Activity says when the camera connects and disconnects, and when the phone
  has been on the camera's Wi-Fi for 30 s without the camera answering.
- **Reconnecting without a tap**: when the camera goes away while watching (a
  quick stop), the app asks Android to rejoin it for the next 10 minutes;
  plugging the phone in starts a 3-minute search. Both are
  `WifiNetworkSpecifier` requests, so Android does the looking and they work
  without "Allow all the time" location. Scan-based auto-connect still needs
  it, and Home asks for it while auto-connect is on.
- **Connect to camera directly** does nothing while the camera is already
  reachable (a new request would drop the working connection), and the
  connection it makes is kept until the camera turns off.

## UI (v0.3 redesign, v0.4 camera features)

Design language: a graphite instrument cluster lit by two signal colors.
**Amber always means camera → phone (sync), cyan always means phone → homelab
(offload)**, green means reachable. These pipeline colors are fixed even under
Dynamic color, so the meaning never changes with the wallpaper. Headlines and
every number use the bundled **Manrope** variable font (OFL, see
`licenses/Manrope-OFL.txt`) with tabular figures so live counters don't jitter;
body text stays on the system font.

- **Navigation**: three tabs (Home, Clips, Activity) with one Navigation 3 back
  stack each. Settings and clip details open full screen and hide the bar;
  re-tapping a tab returns to its root. Collapsing large title bars throughout.
  Screens switch in place (no slide or fade between them). Back unwinds
  in-screen state first (selection, filters, search, full screen), then the
  stack; at the root of Clips or Activity it returns to Home, and at Home it
  leaves the app with the system back-to-home animation.
- **Home** is built around the **pipeline diagram**: Camera → Phone →
  Homelab as three nodes whose links show state (dotted = unreachable, solid =
  ready, animated dashes = transferring). While a transfer runs, the card
  shows speed, time left, a wavy progress bar, a throughput sparkline, and
  per-stream progress; **Stop transfer** replaces Sync/Offload. Below that:
  a **camera card** while the camera is connected (recording state, SD card
  free space, GPS fix, clock offset, a warning when the camera chip reads 100
  or more, with **Live view** and camera **Settings** buttons), latest clip
  thumbnails, storage, and recent activity. When idle the card says what the
  last transfer did ("Synced 6 clips at 22:39") and holds the **Watch in
  background** switch; tapping the camera or homelab node opens its
  diagnostics checklist. Notices above the camera card ask for what is
  missing: receiver setup, the camera's Wi-Fi, or "Allow all the time"
  location while auto-connect is on.
- **Clips** is an adaptive thumbnail grid grouped by day (with per-day count
  and size), All/Front/Rear as a connected button group, and long-press
  multi-select with a floating toolbar for share/delete (undo via snackbar).
- **Clip detail** is edge to edge: the player sits under the status bar with
  overlay controls (tap to show/hide, auto-hide while playing, double-tap the
  left/right third to seek 5 s, 0.25×–4× speed, mute, full screen in
  landscape with system bars hidden), followed by the time, tags (duration, size, GPS, IMU), Save to gallery /
  Share / Delete, and the file's sidecars.
- **Activity** is a per-day timeline with kind-colored nodes, search, and
  filters; tap an event to expand it, copy it, or retry a failure.
- **Settings** opens on a category list whose rows state the current value
  (Camera connection, Dashcam settings, Sync, Offload, Storage, Appearance,
  Automation, About); each category is its own page. Text values are edited
  in a dialog with validation; stream counts and the theme use connected
  button groups.
- **Dashcam settings** reads the camera's own settings (`API_GeneralQuery`)
  and writes changes straight back (`API_GeneralSave`), then re-reads them.
  On/off switches, 0–100 levels, the recording resolution and the documented
  H.264/H.265 recording codec are editable. Resolution is saved as
  `image_quality` (`high`/`middle`/`low` for the first, second and third mode
  the camera lists, as the official app does; not yet confirmed on the Z50).
  Resolution and codec changes apply to new clips; compare file sizes before
  relying on them to reduce sync time. Event length and parking recording
  mode remain read-only until their allowed values are known.
- **Live view** plays the camera's RTSP stream (`av4` front, `av5` rear) over
  the camera Wi-Fi with interleaved TCP and small buffers. The watcher does not
  sync while it is open; if a transfer is already running it offers Stop.
- **Camera clock**: on the first status read of each connection the app sets
  the camera clock to phone time (`API_SyncDate`) when it has drifted more
  than 2 s or cannot be read, then reads it back. It sends `date`, `imei`,
  `time_zone` and `lang` (the firmware needs the first two, and stores `zh_CN`
  without `lang`) and leaves the date format alone. Settings → Camera
  connection can turn this off.
- **Setup** is a four-step onboarding: welcome, homelab receiver (with a
  connection test), optional camera Wi-Fi auto-join, done.
- Reduced motion disables the pipeline and status animations.
- **Notifications** carry only the actions that apply: **Stop** while a
  transfer runs; **Sync**, **Offload** (when clips are staged), and **Stop
  watching** otherwise. Transfers use the amber/cyan signal color, show
  "774 MB of 2.00 GB · 9.0 MB/s" with the time left as the status chip, and
  on Android 16+ the Live Update bar has one segment per file. A finished
  sync offers **Offload** when the homelab is reachable.
- **Widget** (Glance, brand palette): status dot and line, the staged clip
  count as a large number with the size on phone, and Sync / Offload pills
  (Stop during a transfer). Narrow sizes drop the labels to icon pills.

### Screenshots without a device

`./gradlew :app:testDebugUnitTest -Pscreenshots` renders every screen in
several states (Robolectric + Roborazzi, `src/test/.../screenshots/UiGallery.kt`)
to `app/build/screenshots/`. The fixtures exist only in the test source set.
Thumbnails do not load in that JVM renderer, so tiles show their placeholder.

## Transfer reliability

- GPS (`.gpx`/`.git`) and G-sensor (`.gsn`) sidecars are one file per drive
  segment, not per clip: names are `yyyyMMddHHmmss_<seconds>` like the clips,
  but a `.gsn` can span an hour. A clip is shown with GPS / IMU when its time
  span overlaps a sidecar's span (before v0.4 only an exact filename match
  counted, so almost every clip looked like it had none).
- Camera requests outside a transfer (status, settings, live view setup) take
  the same lock as sync and offload, so they never open a second camera session
  in the middle of a download.

- Every upload carries an `X-Content-SHA256` header; the receiver refuses a
  corrupted transfer (HTTP 422) and the clip stays on the phone for a retry.
- Partial downloads survive dropped Wi-Fi: the `.part` file is kept and the
  next attempt resumes with a `Range` request. Only a payload the camera
  explicitly rejects discards the partial.
- Deleting a staged clip (or clearing staging) keeps its
  `synced_history.json` entry, so a deleted clip that is still on the camera is
  not downloaded again. **Clear sync history** brings everything back.
- Only one transfer runs at a time. **Stop** (in the app, notification or
  widget) cancels only the transfer and keeps aborting in-flight HTTP until it
  has ended, so no new file starts after Stop. Watching carries on, but the
  watcher leaves that kind of transfer alone until its connection ends: a
  stopped sync stays stopped until the camera next connects, a stopped offload
  until the phone leaves that network. Tapping Sync or Offload lifts the pause.
- "Last synced/offloaded" is saved to disk and updated after every file, so it
  survives a restart and counts transfers cut short by the ignition. If the app
  process dies during a transfer, the next start says so in Activity.
- If two downloads fail in a row, the sync cycle aborts and retries on the next
  poll; partial files are kept for resume.
- Transfer failures, camera warnings, and screen action errors show a brief
  toast. The Activity tab keeps the detailed transfer errors.
- `.gsn` G-sensor sidecars are listed and pulled with `.gpx` / `.git` when
  GPS sync is enabled.

## Networking scope

Android clears the HTTP path only for the endpoints listed in
`app/src/main/res/xml/network_security_config.xml`: the DDPAI AP defaults
(`193.168.0.1`, `192.168.0.1`), the ingest server (`192.168.1.100`, the
example default), the WireGuard relay example (`10.8.0.1`), and loopback. If
your camera or ingest host uses any other address, add it there before
building or uploads fail with a cleartext-not-permitted error.

Camera Wi-Fi auto-connect adds its suggestion once and updates it in place
(Android 11+), instead of removing and re-adding it on every app start, which
reset Android's auto-join state. On Android 13+ the approval status is asked for
again after each add, so Settings shows the real state instead of staying on
"waiting for approval"; it also says when the phone is connected to the camera
right now.

The optional ingest token and camera Wi-Fi password are stored in DataStore as
AES/GCM ciphertext backed by an Android Keystore key, not as plaintext.

`ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` are requested on first launch.
Android redacts connected Wi-Fi SSIDs unless the app holds them, and SSID
reading is needed to spot the camera's AP and to honour an offload allowlist;
no location data is ever read or stored. `CHANGE_WIFI_STATE` powers the camera
Wi-Fi suggestion (`addNetworkSuggestions`).

## Build

Needs JDK 17+ and Android SDK 37 (AGP 9.4 downloads the platform automatically).

```bash
echo "sdk.dir=$HOME/Android/Sdk" > local.properties
./gradlew :app:assembleDebug      # debug APK
./gradlew :app:assembleRelease    # R8-shrunk release (~3 MB, unsigned)
./gradlew :app:testDebugUnitTest :app:lintDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

Install: `adb install -r app/build/outputs/apk/debug/app-debug.apk`

Toolchain notes: Gradle 9.7.1, AGP 9.4.1, Kotlin 2.4.20 (the Compose and
serialization plugins pull the matching KGP, so the old stdlib pin is gone),
Compose BOM 2026.09.00, and `material3 1.5.0-alpha28` (the stable 1.4.0
artifact keeps `MaterialExpressiveTheme`/`MotionScheme` internal). OkHttp is
on 5.x. `targetSdk` stays at 36 until Android 17 behavior changes are
validated on the device.

## First run

1. Allow notifications. On Android 16/17 also allow **Nearby devices / local
   network** if the system asks.
2. On **home Wi-Fi or WireGuard** (not the Z50 AP), open the setup wizard.
3. Verify host IP (`192.168.1.100` is the example default), port (`5008`), and optional token.
4. Tap **Test Ingest Server** to confirm connectivity to the ingest host.
5. Check that **Watch in background** on Home is on (it is by default).
6. Optional but recommended: Settings → **Camera** → set the camera SSID
   (`DDPAI_Z50_*`, default password `1234567890`) and enable auto-connect.
   Approve the system prompt once; afterwards the phone joins the AP itself
   when in range. Settings → Offload also takes an SSID allowlist there.
7. Keep **Developer options → Mobile data always active** and stay connected
   to the Z50 AP that has no internet. WireGuard can stay on. If Always-on VPN
   is set to **Block connections without VPN**, exclude this app or turn that
   lockdown off, or the camera Wi-Fi bind cannot work.

Tasker can still fire it without Termux:

- Action `io.github.johnkuruvila.z50bridge.SYNC`
- Action `io.github.johnkuruvila.z50bridge.OFFLOAD`

The same actions are available as launcher shortcuts (long-press the icon), a
Quick Settings tile (**Z50 sync**), and the home-screen widget.
