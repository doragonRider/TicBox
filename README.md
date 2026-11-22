# TicBox

English | [简体中文](README.zh-CN.md)

TicBox is a local-first phone companion app for TicWatch watches (TicWatch Pro 5, Pro 5 Enduro and Atlas). It can replace the official Mobvoi Health phone app and adds a phone-to-watch toolbox. The phone app needs Android 14 (API 34) or later; the watch components need API 30 (Wear OS 3.5) or later.

By default, data flows **watch → local Bluetooth → phone → local database**. The Mobvoi cloud, Google cloud and the watch's internet proxy are off by default and only used after you turn them on.

## Features

- Pairing and first-time setup, staying connected in the background, and unpairing while keeping data.
- Notification sync (actions, text replies, per-app sound and vibration), call control, media control, watch faces, device settings, screen lock, emergency SOS, and finding the phone or the watch.
- Health data: the Local Bridge app on the watch reads heart rate, sleep, blood oxygen and other records over a dedicated Bluetooth link; they are stored on the phone and charted there. With root, it also reads full workouts, GPS routes and per-point data, and can change the watch's health monitoring settings.
- Toolbox over wireless ADB or root: app installs (including split APKs and data files), app management, files, clipboard, terminal and remote screen.
- Do Not Disturb sync, and exporting and importing the phone's data.

## Documentation

The documentation is written in Chinese.

- [Protocol specifications](docs/spec/README.md): the official Wear OS companion protocols and this app's private protocols.
- [Developer documentation](docs/dev/README.md): building, architecture, how each feature works, and known gaps.

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
