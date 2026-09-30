# Device Booster

An Android overlay utility that uses [Shizuku](https://shizuku.rikka.app/) to read
and adjust system-level performance settings, plus a configurable floating
**trick button** that can sit on top of any app.

Built with Flutter for the settings UI and plain Android views for the overlays,
because a service-hosted Flutter engine would cost far more memory than the
overlays need.

## Features

### Booster panel
A draggable, expandable overlay showing live CPU load, CPU frequency, RAM usage,
battery level and temperature.

### Tuning switches
Only switches the device actually supports are listed — each one is probed at
runtime rather than assumed:

| Switch | Effect |
| --- | --- |
| Fixed CPU performance | `cmd power set-fixed-performance-mode-enabled true` |
| Battery saver | `settings put global low_power 1` |
| Thermal override | `cmd thermalservice override-status 0` |
| Background process limit | `device_config` activity manager limit |
| Animation scales | window / transition / animator scales |
| Keep screen awake | stay awake while charging |
| Free memory now | `am kill-all` (one-shot) |

> The thermal override disables overheat protection. Leave it off in hot
> conditions. GPU frequency control is root-only on most devices and is
> reported as unavailable rather than faked.

### Trick button
A floating ring with a dot in the middle, drawn over a high-contrast backing so
it reads on any wallpaper:

- **Enable / disable** from the app
- **Button size**, **border width** and **opacity** sliders
- **Prevent over pulling** — resists drags that would pull the button under the
  status bar or off the screen
- **Pull speed** — how tightly the button tracks your finger. `100%` is 1:1;
  lower values make it trail the finger for heavier, more precise control
- Configurable **ring**, **dot** and **background** colours
- Tap to arm (the dot becomes a cross), tap again to disarm

## Requirements

- Android 8.0 (API 26) or newer
- [Shizuku](https://shizuku.rikka.app/) installed and running — required for
  the tuning switches. Live stats and the trick button work without it.
- The "Display over other apps" permission

## Building

```sh
flutter pub get
flutter build apk --release --target-platform android-arm64
```

The output is `build/app/outputs/flutter-apk/app-release.apk`.

> The release build is currently signed with the debug key so it can be
> installed by ADB. Configure a real signing key before publishing anywhere.

## Development

```sh
flutter analyze
flutter test
```

## Notes on permissions

The manifest deliberately does **not** request `QUERY_ALL_PACKAGES`. The app
only needs to see the Shizuku manager, which is declared as a single
`<package>` entry inside `<queries>`.

Overlay permission cannot be granted silently on Android. On some OEM builds
(HyperOS, MIUI) an app installed by ADB is additionally marked as a "restricted
app", and the overlay permission stays blocked until *Allow restricted settings*
is enabled in the app's system settings page.
