# Sayflux progress

Last updated: 2026-10-18

## Where things stand

Sayflux turns speech into text and types it at the cursor. One pipeline on every platform:

1. **Speech to text, on the device:** Parakeet Redux (Moondream's compressed NVIDIA
   Parakeet TDT 0.6B v3) via sherpa-onnx. Audio never leaves the device.
2. **Basic clean-up, on the device:** rules that remove "um"/"uh" and stutters, apply
   "scratch that", and turn "new line"/"new paragraph" into line breaks
   (`src/sayflux/cleanup.py`, `mobile/.../engine/Cleanup.kt`).
3. **Smart clean-up with the Gemini API** (`gemini-3.1-flash-lite`), when an API key is set:
   self-corrections, lists, numbers, emails. Only the text is sent. On any failure the
   output of step 2 is typed.

### Desktop (Python, `src/sayflux/`)

- Works on Linux (tested on Fedora/GNOME, Super+W shortcut); macOS and Windows code paths exist.
- Speech model: `[speech] model = "redux"` (default, 169 MB download) or
  `"parakeet-v3-int8"` (490 MB, better in loud noise). `sayflux download` fetches,
  verifies (SHA-256) and unpacks it.
- `[format] basic_cleanup = true` runs the rules before Gemini.

### Android (`mobile/`)

- React Native 0.87.1 setup screen + native Kotlin keyboard (InputMethodService).
- Tested on a OnePlus 13s (Android 16): keyboard, dictation and Gemini clean-up work
  (about 1 s for the Gemini step).
- The speech model is bundled in the APK (`assets/models/`), uncompressed so it loads fast;
  the build downloads and verifies it once. Debug APK is about 490 MB.
- Setup screen: microphone, turn on the keyboard, switch to it, a "Try it" box, and
  clean-up settings (basic on/off, Gemini on/off, API key, model, extra instructions).

## Decisions and why

| Decision | Why |
|---|---|
| Parakeet Redux as default speech model | Same words as the 8-bit original on all our test clips; 169 MB vs 670 MB download. Weaker in loud noise (official benchmark: 9.0% vs 6.7% WER). |
| Gemini API for smart clean-up (desktop and Android) | Tested local options on the same 10 tricky dictations. SmolLM2-135M, Gemma 3 270M and Qwen2.5-0.5B wrote poems, answered questions or garbled text. Qwen3.5-4B and Gemma 4 E2B formatted well but translated or dropped words and took 4 to 9 s on the laptop. Qwen3.5-2B was safe but weaker. Gemini Nano (on-device, Android) was good, but Android blocks it unless the app itself is on screen, so it can't run from the keyboard. Gemini API: 10/10 in about 1 s. |
| Rules run before Gemini | Instant, never change meaning, and still apply offline or without a key. |
| Speech model bundled in the Android app | No download step after install. For the Play Store it should move to a Play Asset Delivery install-time asset pack (base module limit is 200 MB). |
| Kotlin and Python clean-up kept identical | Both are tested against `shared/cleanup-cases.json`. |
| Keep the desktop app in Python | It works; it will be packaged into installers instead of rewritten. |

Known clean-up rule trade-offs: "scratch that" removes the whole previous sentence
(including a greeting like "Hey John,"), and a filler with a full stop attached can leave
an odd full stop ("The price is. Fifty.").

## Next steps

1. **Android: move from React Native to Kotlin Multiplatform** (Compose), with the desktop's
   look (dark pill, white sound bars, red dot, indigo "working" animation). Most code is
   already Kotlin; the setup screen is the main rewrite.
2. **iOS app** (Kotlin Multiplatform + a keyboard extension). iOS keyboards can't use the
   microphone, so the keyboard opens the main app to record and passes the text back.
   No Mac or iPhone available: build it on GitHub Actions' macOS runners; it can't be
   tested on a device yet.
3. **Desktop installers** for Linux, macOS and Windows, built by GitHub Actions, plus a
   settings window instead of editing `config.toml`. Unsigned at first, so macOS and
   Windows will show an "unknown developer" warning.
4. Before a Play Store release: move the speech model to an asset pack and build a signed
   release AAB.

## Developer notes

- Android toolchain (installed in `$HOME`, no sudo): JDK 17 in `~/.local/share/jdk-17`,
  Android SDK in `~/Android/Sdk`, environment in `~/.bashrc.d/android.sh`.
- Debug builds load the setup screen from Metro on the computer. After `adb` restarts,
  run `adb reverse tcp:8081 tcp:8081`, or the app shows a red "Make sure you're running
  Metro" screen. The keyboard itself doesn't need Metro.
- If `adb devices` comes up empty while the phone is plugged in, `adb kill-server` fixes it.
- The phone's log buffer is tiny by default; `adb logcat -G 16M` keeps dictation logs
  (tags `SayfluxDictation`, `SayfluxFormatter`) long enough to read.
- Tests: `PYTHONPATH=src python3 -m unittest discover -s tests` (desktop),
  `cd mobile && npm test && npx tsc --noEmit` (setup screen),
  `cd mobile/android && ./gradlew :app:testDebugUnitTest` (Android clean-up).
