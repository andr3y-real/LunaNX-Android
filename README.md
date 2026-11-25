# LunaNX Android

LunaNX started on the Nintendo Switch. This fork brings the client to Android, where you can browse and search your Amazon Luna library, then play in fullscreen with a Bluetooth controller, a USB controller, or on-screen controls.

The port is experimental. You'll need an ARM64 device running Android 9 or later and an Amazon account with Luna access. Your region and plan determine which games are available.

## Playing a game

1. Open the app, select your Amazon account's country, and generate a sign-in code. Enter it on the Amazon page shown in the app.
2. Pick a game from your library. The on-screen buttons disappear when you connect a physical controller.
3. Double-tap the video or press **Start + Select** to open the player menu. From there, you can adjust how the video fits the screen, show playback metrics, or leave the game.

To request Brazilian Portuguese for the next game you launch, open **Conta → Idioma dos jogos** (Account → Game language). This setting is experimental. Available languages and the controls for changing audio or subtitles vary by game.

## Building on Linux

Install JDK 17 or later, Python 3.12 or later, CMake, and Ninja. Install Android SDK Platform 35, SDK Build Tools, and an Android NDK. Set `ANDROID_SDK_ROOT` and `ANDROID_NDK_HOME` to their installation directories, then run this command from the repository root.

```sh
JOBS=4 sh android/build-apk.sh
```

The APK is written to `android/build/LunaNX-Android.apk`. The script generates a development signing key on the first build. To update an existing installation, sign the new APK with the same key as the installed one.

## If playback stutters

Open **Métricas** (Metrics) in the player menu. If you file an issue, include the app version, your device model, the incoming and decoded FPS, RTP drops, and reset counts. Mention whether you were mirroring the screen to a TV. Keep Amazon session credentials and signing keys on your device.

## Credits and license

This fork ports LunaNX 3.1 to Android. It adds a native interface, H.264 video through asynchronous MediaCodec, AAudio playback, and Android controller support. The port changes were first published on November 25, 2026.

**KazaR‑RooT** created the original LunaNX. LunaNX Android is a community project independent of Amazon and Nintendo. See the [original LunaNX project](https://github.com/kazar-root/LunaNX), the [GPL‑3.0 license](LICENSE), and the [third-party notices](THIRD_PARTY_NOTICES.md).
