# pLauncher

A minimal Android home screen with no app drawer and no grid you have to obey. Every app lives on a page or in a folder, widgets can be any size and anywhere, and pages form a 2D matrix you swipe through in four directions.

## Features

- **All apps on the home screen.** New installs drop into the first free cell, uninstalls disappear. On first start pLauncher can group apps into folders by package (for example all `com.google.*` apps).
- **Free-form widgets.** Add widgets from a picker with live previews, drop them anywhere, resize them to any size, or fix them to the grid. Optional per-widget padding removal.
- **2D pages.** Swipe left/right and up/down through a matrix of up to 12×12 pages, with a dot-matrix indicator. Set any page as the default; pressing Home returns to it.
- **Folders, large icons and z-order.** Drag apps onto folders, make icons 1.5× or 2×, overlap items freely and bring them to front or send them to back.
- **Icon gestures.** Swipe up/down/left/right on an icon to open a shortcut, another app or a system action.
- **Home button actions.** On the default page, Home can lock the screen or open notifications or quick settings.
- **Custom icons and names**, square or rounded icon shapes, hidden apps, and a **layout lock**.
- **Day / night colors** that follow the system or are set by hand.
- **Backup.** Export the whole layout, settings, custom icons and widgets to one file and import it on another device.
- **No ads, no network, no data collection.**

## Permissions

| Permission | Why | Required |
|---|---|---|
| Request uninstall | **Uninstall** in an app's menu | Optional |
| Expand status bar | Fallback for the "open notifications / quick settings" actions | Optional |
| Accessibility service | Lock screen with fingerprint/face unlock kept, open notifications and quick settings. It doesn't read screen content | Optional |
| Device admin | Fallback way to lock the screen (the next unlock needs the PIN) | Optional |

### Notes

- If device admin is enabled, Android won't uninstall pLauncher until it is removed (Settings → Security → Device admin apps).
- Work-profile apps aren't listed.
- Every menu and setting is described in the [guide](docs/GUIDE.md).

## Build

Requires Android 8.0+ (API 26). Built with Kotlin on the plain Android framework, without libraries.

```bash
./gradlew assembleRelease
```

The APK is written to `app/build/outputs/apk/release/`. Then press Home and choose **pLauncher**.

## License

See [LICENSE](LICENSE).
