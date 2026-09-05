# Televa Messenger

**Televa** — a full-featured private messaging app. Televa Messenger connects to the open Telegram network (MTProto), using its own registered API credentials.

Built from the official open-source Telegram client for Android (GPLv2+), rebranded and customized as Televa.

## Building

The universal debug APK builds via GitHub Actions (`.github/workflows/televa-build.yml`) on every push to `master`.

Requirements:
- JDK 17
- Android SDK (API 36), NDK 27.2.12479018, CMake

```
./gradlew assembleAfatDebug
```

APK output: `TMessagesProj_App/build/outputs/apk/*/debug/`

## License

GPL-2.0-or-later — see [LICENSE](./LICENSE). Based on [Telegram for Android](https://github.com/DrKLO/Telegram) by Nikolai Kudashov.
