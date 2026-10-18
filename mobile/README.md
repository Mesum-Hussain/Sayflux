# Sayflux for Android

A voice keyboard: tap the mic, speak, tap again, and your words are typed into whatever app
you're in. Speech is recognized on the phone with Parakeet Redux, the same model as the
desktop app, which ships inside the app (no download after installing). The text then gets
the same basic clean-up as on desktop, and optionally Gemini's smart clean-up (only the
text is sent, never audio).

The setup screen is React Native. The keyboard, recording, speech model and clean-up are
native Kotlin, because Android keyboards must be native services.

```
App.tsx                      setup screen: permissions, model download, settings, try-it box
specs/NativeSayflux.ts       interface between App.tsx and the Kotlin code
android/app/src/main/java/app/sayflux/
  ime/SayfluxKeyboard.kt     the keyboard (InputMethodService)
  engine/Dictation.kt        record -> transcribe -> clean up
  engine/Recorder.kt         16 kHz mic recording
  engine/SpeechEngine.kt     Parakeet Redux via sherpa-onnx, read from the app's assets
  engine/ModelManager.kt     where the bundled model lives
  engine/Cleanup.kt          basic clean-up rules (same as src/sayflux/cleanup.py)
  engine/Formatter.kt        Gemini smart clean-up
  engine/Prompt.kt           Gemini prompt (same as src/sayflux/formatter.py)
  engine/Settings.kt         API key and options
  bridge/                    exposes the above to React Native
```

## Build and run

Needs Node 22+, JDK 17 and the Android SDK (platform 37, build-tools 37.0.0,
NDK 27.1.12297006, CMake 3.22.1), with `JAVA_HOME` and `ANDROID_HOME` set.

1. On the phone, turn on **Developer options** (tap *Build number* 7 times in
   *Settings → About phone*), then **USB debugging**. Plug it in and accept the prompt.
2. Check it's visible: `adb devices`
3. Install and run:

   ```sh
   npm install
   npm start          # JavaScript dev server; leave it running
   npm run android    # in a second terminal: builds and installs the app
   ```

The first build downloads the sherpa-onnx library (50 MB) into `android/app/libs/` and the
speech model (169 MB) into `android/.speech-model/`, both checked against their SHA-256.
The model is stored uncompressed in the APK so it loads quickly, which makes a debug APK
about 490 MB.

The app is built for 64-bit ARM phones only (`reactNativeArchitectures` in
`android/gradle.properties`); add `x86_64` there to run it on an emulator.

## Tests

```sh
npm test           # setup screen renders
cd android && ./gradlew :app:testDebugUnitTest   # clean-up matches shared/cleanup-cases.json
npx tsc --noEmit   # type check
```
