# TicBox

English | [简体中文](README.zh-CN.md)

TicBox is a local-first phone companion app for TicWatch watches (TicWatch Pro 5, Pro 5 Enduro and Atlas). It can replace the official Mobvoi Health phone app and adds a phone-to-watch toolbox.

By default, data flows **watch → local Bluetooth → phone → local database**. The Mobvoi cloud, Google cloud and the watch's internet proxy are off by default and only used after you turn them on in the app.

## Features

| Feature | What it needs |
|---|---|
| Notification sync: phone notifications on the watch, with their action buttons and text replies | Pairing, and notification access |
| Call control and media control | Pairing |
| Watch faces, watch preferences, screen lock and watch apps | Pairing |
| Emergency SOS, and finding the phone from the watch | Pairing |
| Finding the watch from the phone, and Do Not Disturb sync | [Local Bridge](#step-5-install-local-bridge-for-health-data) |
| Health data: heart rate, sleep, blood oxygen, steps and more, stored and charted on the phone | Local Bridge |
| Exporting and importing the phone's data | Nothing |
| Advanced tools: installing apps on the watch (including split APKs and data files), app management, files, clipboard, terminal and remote screen | The watch's wireless debugging, or root |
| Full workouts, GPS routes and per-point data | [Root](#advanced-root-and-the-watch-module) |
| Health change log: syncs read only what changed | Root |
| More health monitoring settings, and per-app notification sound and vibration | Root and the watch module |

For what isn't implemented yet or is limited compared with the official app, see [capability gaps](docs/dev/capability-gaps.md) (in Chinese).

## What you need

- **Watch**: a TicWatch Pro 5, Pro 5 Enduro or Atlas. The Atlas ships with Wear OS 4. The Pro 5 and Pro 5 Enduro ship with Wear OS 3.5 and have an official Wear OS 4 update; if yours hasn't received it, check under Settings → System → System updates on the watch. TicBox works on Wear OS 3.5 too, but on 3.5 the watch can't keep its data when it moves to another phone.
- **Phone**: Android 14 or later, with Bluetooth and Wi-Fi.
- **Wi-Fi**: one network that both the phone and the watch can join. The phone installs Local Bridge and runs the advanced tools over the watch's wireless debugging, so the watch never needs a cable to a computer; the two-pin charging cable that comes with it can't carry data anyway.
- **Computer (optional)**: only needed if the app can't be installed on the phone directly, with a cable to the phone; see [installing from a computer](#installing-from-a-computer-optional).

A watch can be paired with only one phone at a time. If yours is paired with another phone (or the official app), see [step 2](#step-2-put-the-watch-into-pairing-mode).

## Getting started

Do each step once, in order. TicBox's button names are as the app shows them in English; the phone's own menus may differ a little by brand and version.

### Step 1: Download and install the phone app

1. In the phone's browser, open this project's [Releases](https://github.com/doragonRider/TicBox/releases) page and download `ticbox-phone-<version>-release-<commit>.apk` from the latest release. That one file is all you need: the watch's components ship inside the phone app. The release's `SHA256SUMS.txt` lets you check the download.
2. Open the file from the browser's downloads or a file manager. The first time, Android says it can't install unknown apps: tap Settings, turn on "Allow from this source" for the browser (or file manager), go back and tap Install. If the phone's own security check asks, choose to install anyway.
3. Open TicBox once it's installed.

To look around first, tap "Try the demo watch" on the app's first page. It shows every page with sample data and doesn't connect to a real watch; only the tools under Advanced reach real devices, over wireless debugging.

**If the install is blocked:**

- **"App blocked to protect your device", with only a "Got it" button**: Google Play Protect blocks apps that ask for notification access when they don't come from an app store. You can pause its scanning: open the Play Store, tap your profile picture at the top right → Play Protect → the settings icon at the top right, turn off "Scan apps with Play Protect", and install again. Turn it back on afterwards.
- **Phones in Brazil, Indonesia, Singapore and Thailand**: since 30 September 2026, Android blocks apps from developers who haven't registered for developer verification, which includes TicBox; other regions follow from 2027. You can turn on "Allow apps from unverified developers" in the phone's Developer options. The first time, it takes a 24-hour wait; then you can keep it on for 7 days or indefinitely. The option is still rolling out and may not be on your phone yet.
- If none of that works, install from a computer. Neither block applies to installs over ADB.

#### Installing from a computer (optional)

You need a computer (Windows, macOS or Linux) and a USB cable for the phone that carries data; a charge-only cable won't work.

1. Download Google's [SDK Platform-Tools](https://developer.android.com/tools/releases/platform-tools), unzip it somewhere easy to find, such as `D:\platform-tools`, and put the APK there too.
2. Open a terminal in that folder: on Windows, right-click an empty part of the folder and choose "Open in Terminal"; on macOS and Linux, open Terminal and `cd` into the folder. If this prints a version, ADB works:

   ```bash
   ./adb version
   ```

   In the Windows Command Prompt (cmd), leave out the `./`: `adb version`. The same goes for the commands below.

3. Turn on Developer options on the phone: open Settings → About phone and tap Build number seven times. Then turn on USB debugging in Developer options. Some brands (such as Xiaomi) also need "Install via USB".
4. Connect the phone to the computer. When the phone asks "Allow USB debugging?", tap Allow. Check that the phone is connected; the list should show a line ending in `device`:

   ```bash
   ./adb devices
   ```

5. Install:

   ```bash
   ./adb install -r ticbox-phone-<version>-release-<commit>.apk
   ```

   `Success` means it's installed, and you can unplug the cable. `-r` replaces an installed copy and keeps its data, so the same command can update it later.

### Step 2: Put the watch into pairing mode

First check the watch's Wear OS version: it's the first item under Settings → System → About. Then pick what fits the watch now:

| The watch is | What to do |
|---|---|
| New, or already reset | Turn it on, choose a language, stop at the screen that asks you to pair it with a phone, and go on to step 3 |
| Paired with another phone, on Wear OS 4 | [Transfer it to this phone](#transfer-the-watch-to-this-phone); everything on the watch stays |
| Paired with another phone, on Wear OS 3.5 | Wear OS 3.5 can't change phones and keep its data. Update the watch to Wear OS 4 under Settings → System → System updates first, then transfer |
| Paired with the official app on this same phone | See [switching to TicBox on the same phone](#switching-to-ticbox-on-the-same-phone) |

A reset erases everything on the watch, so keep it as the [last resort](#reset-the-last-resort). Note that unpairing the watch in the official app also resets it.

#### Transfer the watch to this phone

This needs Wear OS 4. Keep the watch connected to the old phone, with enough battery.

1. In the official app on the old phone, start the transfer from its device settings (System → Transfer), or use the transfer option under Settings → System on the watch. Both need a Google account on the watch; without one, see below.
2. Confirm on the watch. It goes into transfer mode and waits for the new phone. Until the transfer has finished, don't unpair the watch on the old phone or forget it in the old phone's Bluetooth settings.
3. Do step 3 on this phone and follow TicBox's prompts. Once the watch is back on its watch face, the transfer is done.

After a transfer, the watch may stop accepting connections after one of them ends, for example after you update TicBox. If TicBox keeps trying to reconnect, restart the watch normally once; that clears it, with no reset and no unpairing needed. This is a known issue on the watch's side, and it's more likely when the old phone's pairing is removed before the transfer has finished.

**Without a Google account on the watch**, neither option shows. TicBox's demo watch can open the same confirmation page from this phone: the demo's Advanced tools work on the real watch over wireless debugging. This path hasn't been fully verified on a real watch yet.

1. Connect the watch to Wi-Fi and turn on its wireless debugging, as in step 5, items 1–3.
2. On TicBox's first page, tap "Try the demo watch", open Watch → Manage → Advanced → Wireless debugging, and pair and connect to the watch as in step 5, items 5–6.
3. Go back to Advanced, open Terminal, and enter this line:

   ```bash
   am start -W -a android.settings.WATCH_TRANSFER
   ```

4. Confirm on the watch, which goes into transfer mode. Back in TicBox, tap "Exit demo", then do step 3.

A computer can do the same without a cable: connect to the watch's wireless debugging with `./adb pair` and `./adb connect`, then run `./adb shell am start -W -a android.settings.WATCH_TRANSFER`.

#### Switching to TicBox on the same phone

When the official app and TicBox are on the same phone, a transfer doesn't work: it needs two different phones, and doing it on one phone deletes the watch's Bluetooth pairing.

To keep the watch's data, you can go through a second phone running Android 14 or later. This path hasn't been verified on a real watch yet.

1. Install TicBox on the other phone (step 1) and transfer the watch from the official app to it, as above. Once that's done, you can uninstall the official app on this phone.
2. In TicBox on the other phone, open Watch → Manage → Connection → Change paired phone, tap "Check watch support", then "Open transfer on watch", and confirm on the watch.
3. Do step 3 in TicBox on this phone to bring the watch back.
4. When it's back, tap "Clear this phone's pairing" on the same page on the other phone.

#### Reset (the last resort)

On the watch, choose Settings → System → Disconnect & reset, then treat it as new. This erases everything on the watch, including health records that haven't been synced anywhere. Before you reset, keep what you can somewhere else:

- With the official app, let it finish syncing. If you're signed in to a Mobvoi account, the data is in the Mobvoi cloud: later, sign in to the same account under Settings → Mobvoi account in TicBox to download the cloud health records to the phone. This is off by default, uses the internet, and hasn't been fully verified with real accounts yet.
- With TicBox, export under Settings → Data migration and import it on the new phone.

### Step 3: Pair

1. On the phone, turn on Location in the system settings. Android uses it to find the watch while pairing; it doesn't give TicBox location access.
2. Open TicBox and tap "Add watch". Allow the "Nearby devices" permission and turn on Bluetooth when asked.
3. Keep the watch near the phone, and choose your TicWatch in the list Android shows.
4. The phone and the watch show the same pairing code; confirm it on both.
5. "Set up watch" follows: read the terms, tick "I have read and accept the terms", and continue.
   - Google account transfer is skipped. You can add an account on the watch later.
   - "Google device check-in" is unticked by default; ticked, it uses the watch's network. Leave it unticked unless you need it.
6. "Your watch is ready" means you're done; the watch shows that it's connected to a phone.

From then on TicBox keeps the connection in the background; it doesn't need to stay open. If your phone closes background apps, allow TicBox to run in the background in the phone's settings.

### Step 4: Turn on notification sync

1. On the Watch tab, open Notifications, tap "Allow access", and turn on notification access for TicBox in the system page that opens.
2. If that switch is greyed out (usual for an app installed on the phone directly): open TicBox's App info, tap ⋮ at the top right, choose "Allow restricted settings", and go back and turn it on.

The same page chooses which apps' notifications reach the watch.

### Step 5: Install Local Bridge (for health data)

Health data recorded on the watch reaches the phone through Local Bridge, a small app on the watch. It ships inside the phone app, and the phone installs it through the watch's wireless debugging, so no computer is needed. Local Bridge has no internet permission on the watch; it only talks to this phone, over Bluetooth.

**On the watch:**

1. Connect to Wi-Fi, on the same network as the phone.
2. Turn on Developer options: open Settings → System → About → Versions and tap Build number seven times.
3. In Settings → Developer options, turn on ADB debugging and Wireless debugging. If the watch asks whether to allow debugging on this network, allow it.

**On the phone:**

4. Open the Health tab and tap "Set up" in the notice at the top to open the Local Bridge page. It's also under Watch → Manage → Advanced → Local Bridge.
5. Tap "Open wireless debugging". Now, in the watch's Wireless debugging, choose the option to pair a device with a pairing code; the watch shows a six-digit code. Keep it showing.
6. Back on the phone, under "Found on this Wi-Fi", tap the "Ready to pair" entry, enter the code from the watch and tap "Pair". Then tap the "Ready to connect" entry and wait for "Connected".
7. Go back to the Local Bridge page, tap "Install" and wait for it to finish.

**Back on the watch:**

8. Open Local Bridge from the watch's app list and turn on "Allow phone to read". If "Allow Bluetooth" shows, tap it too.

Back on the phone, the Local Bridge page should show every step done. A new Local Bridge doesn't answer the phone until "Allow phone to read" is on; that's expected.

After that, day-to-day use doesn't need wireless debugging, so you can turn it off and turn it on again to update Local Bridge.

### Step 6: Sync health data

1. Open the Health tab and tap "Sync from watch". From then on it also syncs when the watch connects and when you reopen the app.
2. To keep the first sync quick, it reads only the last 3 days of detail. If the watch has older data, the page says so; tap "Sync everything" to bring it over, which can take a few minutes.

To read everything again later, use Settings → Diagnostics → "Sync all health records now".

## Everyday use

- **Watch tab**: watch faces and notifications; under Manage: watch preferences (with Do Not Disturb sync, watch apps, screen lock and health monitoring), finding devices, media controls, phone calls, SOS, advanced tools and the connection.
- **Health tab**: charts and details for each kind of data, and workouts.
- **Settings tab**: privacy, appearance, language, data migration (exporting and importing the phone's data), diagnostics, and the Mobvoi account and cloud services, which are off by default.
- Features such as SOS and phone calls explain the permissions they need the first time you open them.
- **Do Not Disturb sync**: in Watch preferences, choose "Follow the phone". It needs the notification access from step 4, and the first time, a one-off permission for Local Bridge over wireless debugging; the page walks you through it.

### Changing phones

The watch needs Wear OS 4. TicBox on the old phone starts the transfer, and the phone's data moves with an export and an import.

1. **Old phone**: export under Settings → Data migration, leaving "Watch pairing identity" unticked, and copy the file to the new phone.
2. **New phone**: install TicBox (step 1), pick the file under Settings → Data migration and tap "Import and restart".
3. **Old phone**: open Watch → Manage → Connection → Change paired phone, tap "Check watch support", then "Open transfer on watch", and confirm on the watch.
4. **New phone**: pair as in step 3, then allow notification access again as in step 4. Other permissions are also kept per phone; allow them again when a page asks. Then check the Local Bridge page.
5. **Old phone**: once the watch stays connected to the new phone and health sync has resumed, tap "Clear this phone's pairing" on the Change paired phone page.

If the watch won't connect after the transfer, restart it here too.

### Updating

1. **Phone**: download the new APK and open it as in step 1; your data stays. Play Protect may say "App scan recommended": tap "Scan app" to install after a scan, which takes about half a minute, or "More details" → "Install without scanning". From a computer, use `adb install -r`.
2. **Watch**: open the Local Bridge page on the phone and follow its prompt to update. As in step 5, the watch's wireless debugging has to be connected; with root it can update without it (see below).

Each version's [release notes](docs/releases/) (in Chinese) say whether to update the phone, the watch, or both. When the private protocol between them changes, updating only one side makes the advanced tools refuse to connect, so if in doubt, update both.

### Troubleshooting

| What you see | What to do |
|---|---|
| Installing the APK on the phone is blocked | See "If the install is blocked" in step 1 |
| From a computer, `adb devices` lists no phone, or shows `unauthorized` | Use a cable that carries data, and tap Allow on the phone's USB debugging prompt |
| From a computer, `INSTALL_FAILED_USER_RESTRICTED` | Turn on "Install via USB" in Developer options (Xiaomi and some other brands) |
| The watch isn't in the pairing list | Turn on the phone's Location setting; keep the watch on its pairing screen, near the phone |
| The notification access switch is greyed out | App info → ⋮ → "Allow restricted settings" |
| Wireless debugging doesn't find the watch | Put both on the same Wi-Fi, keep the watch on its Wireless debugging page, and tap "Search again"; on Android 17 phones, also allow access to devices on the local network |
| The Local Bridge page says it isn't answering | On the watch, open Local Bridge, turn on "Allow phone to read", then tap "Check again" |
| Health data covers only the last few days | Tap "Sync everything" on the Health tab |

## Advanced: root and the watch module

This part is optional; nothing above needs root.

**Before you start**: the watch must already have [Magisk](https://topjohnwu.github.io/Magisk/) 26 or later. This guide doesn't cover unlocking the watch or flashing Magisk: that usually erases the watch, can affect its warranty, and done wrong can leave it unable to start. Decide on that risk yourself.

With root you can also:

| Feature | Needs | Where |
|---|---|---|
| Full workouts, GPS routes, laps and per-point data | Root | Local Bridge on the watch |
| Advanced tools without wireless debugging, and updating Local Bridge over root | Root | Watch → Manage → Advanced → Local Bridge with root |
| Health change log: syncs read only what changed | Root | The phone's Local Bridge page → Health change log |
| More health monitoring settings (16 of the 34 need the module) | Root and the watch module | Watch → Manage → Watch preferences → Health monitoring |
| Per-app notification sound and vibration (without the module, the watch rings and vibrates for every notification) | Root and the watch module | Watch → Notifications → Sound and vibration control |

### 1. Turn on root reading

1. On the watch, open Local Bridge and turn on "Read full workouts (root)".
2. Allow it when Magisk asks.

The sections below need this too. Syncs then also bring workouts, GPS routes and per-point details, shown under Workouts on the Health tab. Local Bridge makes a temporary snapshot in its own private folder, deletes it after reading, and never changes the official workout records. Turning the switch off doesn't affect ordinary health reading.

### 2. Use the advanced tools over root

Under Watch → Manage → Advanced, choose "Local Bridge with root". The phone then reaches the watch's tools as root over Bluetooth, without wireless debugging. Local Bridge's own updates can go this way too.

### 3. Install the watch module

The watch module (the Magisk module `ticwatch_health_settings`) also ships inside the phone app. It loads into the watch's official health app and system UI, and adds more health settings and per-app notification alerts.

1. On the phone, open Watch → Manage → Connection → Watch module, or Watch → Notifications → Sound and vibration control.
2. The page checks the watch connection, the module, Zygisk and the health app one by one. For per-app notification alerts, turn on "Notification sound and vibration control" first.
3. Tap the main button, such as "Install and restart the watch". After you confirm, it does every missing step in one go: installs or updates the module, turns on Zygisk in Magisk, and restarts the watch.
4. The watch reconnects by itself after about a minute, and the page confirms that the module has loaded.

Notes:

- Keep the watch screen on while it installs; if the watch goes to sleep, the install can stall.
- If the page says the health app is on Magisk's DenyList, remove it there, or the module won't load.
- The module supports particular versions of the official apps and the watch system. When yours isn't one of them, the page says so and the watch carries on as before.

### 4. Health change log

With it on, syncs read only what changed instead of scanning whole tables.

1. On the phone's Local Bridge page, tap "Health change log".
2. Turn it on for "Health data" and for "Workouts".
3. Read the confirmation and turn it on. It adds triggers to the watch health app's database and doesn't change any health values; if writing the log ever fails, the health app's own write can fail with it. The first sync after turning it on reads everything once.

You can remove it on the same page at any time. With the watch module, the health data log is read through the module, which is faster; it works without the module too.

### 5. Turning things off

- **Watch module**: disable or remove `ticwatch_health_settings` in the watch's Magisk, then restart normally. Health and alarm settings you saved belong to the official app and aren't rolled back.
- **Health change log**: turn it off on the Health change log page. Removing the module doesn't remove it.
- **Root reading**: turn off "Read full workouts (root)" in Local Bridge on the watch.

How these work is in [watch components](docs/dev/watch.md) and [health data](docs/dev/health.md) (in Chinese).

## Documentation

The documentation is written in Chinese.

- [Protocol specifications](docs/spec/README.md): the official Wear OS companion protocols and this app's private protocols.
- [Developer documentation](docs/dev/README.md): building, architecture, how each feature works, and known gaps.
- [Release notes](docs/releases/): what changed in each version.

## Building

Requires JDK 27, Node 26 and the Android SDK (see [building and checks](docs/dev/building.md)):

```bash
npm run check
npm run android:check   # needs the TICBOX_SIGNING_* signing environment variables
```

## License

Copyright (C) 2026 TicBox contributors.

Except for third-party material marked otherwise, the original code and documentation are licensed under the **GNU General Public License version 3 only (GPL-3.0-only)**; see [LICENSE](LICENSE). Third-party components keep their own licenses and copyright notices, including the [toolbox dependencies](android/core/platform/src/main/assets/toolbox-licenses/NOTICE.txt), the [scrcpy server](android/toolbox-resources/toolbox/SCRCPY-NOTICE.txt) and the [Material Symbols icons](android/ui/src/main/res/raw/material_symbols_notice.txt).

This project is not affiliated with Mobvoi or Google. TicWatch is a trademark of Mobvoi.
