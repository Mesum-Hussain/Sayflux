# Whistleflux progress

Last updated: 2026-10-18

## Where things stand

Whistleflux turns speech into text and types it at the cursor. One pipeline on every platform:

1. **Speech to text, on the device:** Cactus Whistle (17 MB) on Cactus's Needle engine,
   called directly (ctypes on desktop, JNI on Android). Audio never leaves the device.
2. **Basic clean-up, on the device:** rules that remove "um"/"uh" and stutters, apply
   "scratch that", and turn "new line"/"new paragraph" into line breaks
   (`src/whistleflux/cleanup.py`, `mobile/.../engine/Cleanup.kt`).
3. **Smart clean-up with the Gemini API** (`gemini-3.1-flash-lite`), when an API key is set:
   self-corrections, lists, numbers, emails. Only the text is sent. On any failure the
   output of step 2 is typed.

### Desktop (Python, `src/whistleflux/`)

- Works on Linux (tested on Fedora/GNOME, Super+W shortcut); macOS and Windows code paths exist.
- Speech model: Whistle only (`[speech] language`, `keywords`). `whistleflux download` fetches
  the model and this OS's engine (19 MB), checks pinned SHA-256s and removes old Parakeet
  folders. It
  verifies (SHA-256) and unpacks it.
- `[format] basic_cleanup = true` runs the rules before Gemini.

### Android (`mobile/`)

- React Native 0.87.1 setup screen + native Kotlin keyboard (InputMethodService).
- Tested on a OnePlus 13s (Android 16): keyboard, dictation and Gemini clean-up work
  (about 1 s for the Gemini step).
- The speech model is bundled in the APK (`assets/models/whistle.cact`); the build downloads
  and verifies it and the engine (`libneedle.a`, arm64 only) once. Debug APK is about 55 MB
  (was 490 MB with Parakeet).
- Setup screen: microphone, turn on the keyboard, switch to it, a "Try it" box, and
  clean-up settings (basic on/off, Gemini on/off, API key, model picked from a list,
  extra instructions). If the chosen model is out of quota, busy or retired, the
  Flash-Lite models are tried in turn (desktop does the same).
- Keyboard: Gboard-style, drawn in one custom view (`ime/KeyboardView.kt`): QWERTY with
  digits on long-press, two symbol pages, a number pad for number/phone fields, key
  previews, word suggestions, auto-capitals, double-space full stop, Material 3 colors
  (Material You). Swipe typing (`ime/GestureDecoder.kt`) uses AOSP's English word list
  (120k words): 80/80 simulated swipes in the top 3 (`GestureDecoderTest`). Slide or
  long-press space: the keyboard becomes a trackpad. The rounded-square mic key grows into
  the desktop's voice pill; tap the pill to finish, anywhere else to cancel.
- Bottom padding covers the system's "hide keyboard"/"switch keyboard" buttons on gesture
  navigation (the OnePlus reports no navigation bar inset to keyboards), and their icons
  are dark on the light keyboard.
- App icon and setup screen use the Whistleflux logo (`assets/logo/`).
- Install a release build (`assembleRelease`) to use the app without the computer;
  debug builds load their screen from Metro.

## Decisions and why

| Decision | Why |
|---|---|
| Whistle as the only speech model (2026-10-19, replaced Parakeet Redux) | 17 MB vs 169 to 490 MB, about 70 MB of memory vs 720 to 930 MB, keyword biasing for names. Trade-offs: 7 languages instead of 25, and higher published error rates than Parakeet (LibriSpeech 4.3%/10.5% vs 1.9%/3.6%, AMI meetings 26% vs 11%). Engine only published for arm64 on Android. |
| Engine called directly, not via `cactus-needle` | Avoids its huggingface_hub dependency tree and usage telemetry; downloads are pinned to commits and SHA-256 checked. |
| Gemini API for smart clean-up (desktop and Android) | Tested local options on the same 10 tricky dictations. SmolLM2-135M, Gemma 3 270M and Qwen2.5-0.5B wrote poems, answered questions or garbled text. Qwen3.5-4B and Gemma 4 E2B formatted well but translated or dropped words and took 4 to 9 s on the laptop. Qwen3.5-2B was safe but weaker. Gemini Nano (on-device, Android) was good, but Android blocks it unless the app itself is on screen, so it can't run from the keyboard. Gemini API: 10/10 in about 1 s. |
| Stay on `gemini-3.1-flash-lite` (re-checked 2026-10-19) | On 27 dictations (14 basic, 13 hard): 3.1 Flash-Lite and 3.5 Flash-Lite both 26/27 in about 1 s. 3.5 Flash and 3.8 Flash passed every case they ran, but their free tier is 20 requests per day per model (3.8 Flash also took about 3.7 s). Microsoft BitNet b1.58 2B4T (1.2 GB, local via llama.cpp): 6/27, answered questions, looped, about 30 s per dictation and 2.7 GB of memory. The one case both Lite models missed (misheard "PostgreSQL") is fixed at the source by Whistle's `keywords`. |
| No off-the-shelf local formatter (re-checked 2026-10-19) | Same 27 dictations, llama.cpp, best of the app prompt and a short few-shot prompt: Gemma 3 270M 5, LFM2.5-350M 6, Qwen3.5-0.8B 7, LFM2.5-1.2B 5, Gemma 3 1B 10, Llama 3.2 1B 14, Qwen3.5-2B 17 (1.2 GB, about 7 s per dictation on the OnePlus), Cactus Needle 3 2 (it extracts, doesn't rewrite). All wrote poems, translated, or answered questions. Phone speed (6 threads, 500-token prompt + 60 tokens out): LFM2.5-350M about 1.1 s, Qwen3.5-0.8B about 3 s, LFM2.5-1.2B about 4 s. Way forward: fine-tune a 270M to 0.8B model on this one task. |
| Rules run before Gemini | Instant, never change meaning, and still apply offline or without a key. |
| Speech model bundled in the Android app | No download step after install. At 17 MB it fits the Play Store's base module limit, so no asset pack is needed. |
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
4. Before a Play Store release: build a signed release AAB.

## Developer notes

- Android toolchain (installed in `$HOME`, no sudo): JDK 17 in `~/.local/share/jdk-17`,
  Android SDK in `~/Android/Sdk`, environment in `~/.bashrc.d/android.sh`.
- Debug builds load the setup screen from Metro on the computer. After `adb` restarts,
  run `adb reverse tcp:8081 tcp:8081`, or the app shows a red "Make sure you're running
  Metro" screen. The keyboard itself doesn't need Metro.
- If `adb devices` comes up empty while the phone is plugged in, `adb kill-server` fixes it.
- The phone's log buffer is tiny by default; `adb logcat -G 16M` keeps dictation logs
  (tags `WhistlefluxDictation`, `WhistlefluxFormatter`) long enough to read.
- Tests: `PYTHONPATH=src python3 -m unittest discover -s tests` (desktop),
  `cd mobile && npm test && npx tsc --noEmit` (setup screen),
  `cd mobile/android && ./gradlew :app:testDebugUnitTest` (Android clean-up).
