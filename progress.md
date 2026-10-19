# Whistleflux progress

Last updated: 2026-10-19

## Where things stand

Whistleflux (renamed from Sayflux on 2026-10-19; GitHub: `Mesum-Hussain/Whistleflux`) turns
speech into text and types it at the cursor. One pipeline on every platform:

1. **Speech to text, on the device:** Cactus Whistle (17 MB) on Cactus's Needle engine,
   called directly (ctypes on desktop, JNI on Android). Audio never leaves the device.
2. **Basic clean-up, on the device:** rules that remove "um"/"uh" and stutters, apply
   "scratch that", and turn "new line"/"new paragraph" into line breaks
   (`src/whistleflux/cleanup.py`, `mobile/.../engine/Cleanup.kt`).
3. **Smart clean-up with the Gemini API** (`gemini-3.5-flash-lite` by default), when an API
   key is set: self-corrections, lists, numbers, emails. Only the text is sent. If the
   chosen model is out of free quota, busy or retired, the Flash-Lite models are tried in
   turn; on any other failure the output of step 2 is typed.

### Desktop (Python, `src/whistleflux/`)

- Command `whistleflux`; works on Linux (tested on Fedora/GNOME, Super+W shortcut); macOS
  and Windows code paths exist. Config and data moved from `sayflux` folders to
  `~/.config/whistleflux` and `~/.local/share/whistleflux` automatically.
- Speech: Whistle only. `whistleflux download` fetches the model and this OS's engine
  (19 MB, pinned commits, SHA-256 checked) and removes old Parakeet folders. Config:
  `[speech] language` ("" = detect) and `keywords` (names and jargon to favor).
- Gemini model in `[format] model` (choices listed in the config file).
- Recording pill uses the logo's colors (`#101418`, red `#f50a0a`).

### Android (`mobile/`)

- React Native 0.87.1 setup screen + native Kotlin keyboard (InputMethodService), package
  `app.whistleflux`. Tested on a OnePlus 13s (Android 16, gesture navigation).
- Speech model bundled in the APK (`assets/models/whistle.cact`); the build downloads and
  verifies it, the engine (`libneedle.a`, arm64 only), AOSP's English word list and
  Unicode's emoji data once (cached in `android/.speech-model/`). Release APK about 36 MB.
- Setup screen: microphone, turn on the keyboard, switch to it, a "Try it" box,
  clean-up settings (basic on/off, Gemini on/off, API key, model from a dropdown, extra
  instructions), and "Forget learned words".
- Keyboard (`ime/`):
  - Opens on a big mic (tap: it grows into the voice pill; tap the pill to finish,
    anywhere else to cancel) with a keyboard / space / delete / enter row.
  - The keyboard button (there and in the toolbar) switches to a Gboard-style QWERTY:
    swipe typing (keys you pass light up), word suggestions, auto-capitals, double-space
    full stop, digits on long-press, long-press "," and "." for symbol rows, @#& for the
    symbols page (`. , ? ! ' " : ; - _ @ # $ & * + ( ) /`), slide or long-press space
    for a trackpad, and a mic key. QWERTY opens by itself for password/email/URL fields;
    number fields open on the number pad, phone fields on a dial pad.
  - Toolbar (shown when there are no suggestions): emoji (one long list, recent first,
    plus search by description using Unicode names and CLDR keywords), clipboard (Select
    all / Cut / Copy / Paste and the last hour of copies, in memory only, sensitive clips
    skipped), number pad, keyboard, undo/redo (Ctrl+Z / Ctrl+Shift+Z).
  - Material 3 colors (Material You on Android 12+). Keys stay clear of the system's
    "hide keyboard"/"switch keyboard" buttons (the OnePlus reports no navigation bar
    inset to keyboards), and those buttons get dark icons on the light keyboard.
- Learning (`ime/Learning.kt`): words you type, swipe or dictate, with counts, and which
  word follows which, for ranking and next-word suggestions. On the phone only (app's
  private files), not in password or incognito fields.
- App icon (adaptive, with a themed-icon layer) and setup screen use the logo
  (`assets/logo/`).

## Open question (pick up here)

**Symbols from the old third page** (`~ ` | • √ π ÷ × ¶ ∆ £ ¢ € ¥ ^ ° = { } \ % © ® ™ ✓
[ ] < >`) aren't reachable right now. Options proposed:
1. Long-press the related symbol on the symbols page, Gboard-style (recommended):
   `$` → € £ ¥ ¢, `-` → – — •, `*` → × ÷ =, `(` → [ ] { } < >, `/` → \ | ~,
   `'` → ` ^, `.` → … °, `&` → % © ® ™ ✓.
2. A fifth row on the symbols page with only the chosen symbols.
3. A "more" key opening a small page with only the chosen symbols.
Still to decide: which option, and which symbols are never used.

## Decisions and why

| Decision | Why |
|---|---|
| Whistle as the only speech model (replaced Parakeet Redux) | 17 MB vs 169 to 490 MB, about 70 MB of memory vs 720 to 930 MB, keyword biasing for names. Trade-offs: 7 languages instead of 25, and higher published error rates than Parakeet (LibriSpeech 4.3%/10.5% vs 1.9%/3.6%, AMI meetings 26% vs 11%). Engine only published for arm64 on Android. |
| Engine called directly, not via `cactus-needle` | Avoids its huggingface_hub dependency tree and usage telemetry; downloads are pinned to commits and SHA-256 checked. |
| Gemini API for smart clean-up | Earlier test of local models on 10 tricky dictations: tiny models wrote poems, answered questions or garbled text; 2B to 4B models were slow or translated. Gemini Nano is blocked unless the app itself is on screen, so it can't run from a keyboard. Gemini API: 10/10 in about 1 s. |
| Default `gemini-3.5-flash-lite`, with a Flash-Lite fallback | On 27 dictations (14 basic, 13 hard): 3.1 and 3.5 Flash-Lite both 26/27 in about 1 s. The free tier is per model and per day (3.1 Flash-Lite: 500; 3.5 and 3.8 Flash: 20), and running out silently left raw text, so the app now tries the other Flash-Lite model on 429/503/404. Android picks the model from a dropdown (a typed name was too easy to get wrong). |
| No local formatter yet (2026-10-19) | Same 27 dictations, llama.cpp: Microsoft BitNet b1.58 2B4T (1.2 GB) 6/27 and about 30 s; Gemma 3 270M 5, LFM2.5-350M 6, Qwen3.5-0.8B 7, LFM2.5-1.2B 5, Gemma 3 1B 10, Llama 3.2 1B 14, Qwen3.5-2B 17 (1.2 GB, about 7 s on the OnePlus), Cactus Needle 3 2 (it extracts, doesn't rewrite). All wrote poems, translated, or answered questions. Way forward if wanted: fine-tune a 270M to 0.8B model on this one task (Colab/Kaggle GPU; this laptop has none). |
| Mic view as the Android default, QWERTY one tap away | The app is voice-first. An old-phone T9 keypad was tried as the main layout (with keypad swipe typing) and dropped. |
| Swipe typing with AOSP's word list (Apache 2.0) | 120,022 words (offensive and non-words removed). The decoder compares the swipe with each candidate's path (position and shape) plus word frequency: 80/80 simulated swipes in the top 3 (`GestureDecoderTest`). |
| On-device learning only | Personal words and word pairs never leave the phone; "Forget learned words" deletes them. |
| Undo/redo as Ctrl+Z / Ctrl+Shift+Z | Android gives keyboards no undo call; standard text fields and Chrome handle the shortcuts. Some apps may ignore them. |
| Rules run before Gemini | Instant, never change meaning, and still apply offline or without a key. |
| Speech model bundled in the Android app | No download step after install; small enough for the Play Store's base module. |
| Kotlin and Python clean-up kept identical | Both are tested against `shared/cleanup-cases.json`. |
| Keep the desktop app in Python | It works; it will be packaged into installers instead of rewritten. |

Known clean-up rule trade-offs: "scratch that" removes the whole previous sentence
(including a greeting like "Hey John,"), and a filler with a full stop attached can leave
an odd full stop ("The price is. Fifty.").

Known keyboard gaps: no autocorrect (suggestions only), no emoji skin tones, no accent
popups, no screen-reader support for individual keys, English word list only.

## Next steps

1. Decide the third-page symbols (see "Open question").
2. Keyboard polish from use on the phone (swipe accuracy with real fingers, emoji search
   results, the mic view).
3. **iOS app** (keyboard extension). iOS keyboards can't use the microphone, so the
   keyboard opens the main app to record and passes the text back. No Mac or iPhone
   available: build on GitHub Actions' macOS runners; can't be tested on a device yet.
4. **Desktop installers** for Linux, macOS and Windows, built by GitHub Actions, plus a
   settings window instead of editing `config.toml`. Unsigned at first.
5. Optional: a fine-tuned small local formatter so clean-up works offline.
6. Before a Play Store release: build a signed release AAB (currently signed with the
   debug key).

## Developer notes

- Android toolchain (installed in `$HOME`, no sudo): JDK 17 in `~/.local/share/jdk-17`,
  Android SDK in `~/Android/Sdk`, environment in `~/.bashrc.d/android.sh`.
- Install a standalone build on the phone: `cd mobile/android && ./gradlew
  :app:assembleRelease && adb install -r app/build/outputs/apk/release/app-release.apk`.
  The OnePlus asks Play Protect to confirm each install on the phone. Debug builds load
  the setup screen from Metro (`npm start`, and `adb reverse tcp:8081 tcp:8081`).
- The app's native build: `src/main/cpp/CMakeLists.txt` builds both React Native's
  `libappmodules.so` and the speech JNI bridge. (Replacing RN's default CMake without
  including its app setup broke the setup screen once.)
- If `adb devices` comes up empty while the phone is plugged in, `adb kill-server` fixes it.
- Screenshots (`adb exec-out screencap`) come out black while a password field is
  focused on the OnePlus.
- Logs: tags `WhistlefluxKeyboard` (each swipe and what it decoded), `WhistlefluxDictation`,
  `WhistlefluxFormatter`. `adb logcat -G 16M` keeps them longer.
- Tests:
  - desktop: `PYTHONPATH=src python3 -m unittest discover -s tests` (clean-up rules,
    Gemini fallback),
  - setup screen: `cd mobile && npm test && npx tsc --noEmit`,
  - Android: `cd mobile/android && ./gradlew :app:testDebugUnitTest` (clean-up rules,
    swipe decoder, learning),
  - on the phone: see `mobile/README.md` (speech engine through the JNI bridge).
