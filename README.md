# Sayflux

Dictate anywhere on Linux, macOS and Windows. Press a shortcut, talk, press it again, and
clean text is typed at your cursor.

- **Local speech-to-text** with NVIDIA Parakeet TDT 0.6B v3 (int8 ONNX, ~490 MB download).
  Your audio never leaves your computer. 25 European languages, with punctuation and capitals.
- **Formatting** with Gemini (`gemini-3.1-flash-lite` by default): removes "um"s and false
  starts, applies corrections like "Tuesday, no, Wednesday", turns spoken lists into lists,
  and writes numbers, emails and dates properly. Only the text is sent, never the audio.
  Without an API key, or if Gemini fails, the raw transcript is typed instead.
- **Small**: three Python dependencies on macOS and Windows (`sherpa-onnx`, `sounddevice`,
  `pynput`) and two on Linux.

## How it works

```
shortcut ─▶ record mic ─▶ shortcut ─▶ Parakeet (local) ─▶ Gemini cleanup ─▶ typed at cursor
```

A background process keeps the model loaded, so after the first run each dictation takes
about half a second plus the Gemini round trip. The first `sayflux` call starts it
automatically. While recording, a small pill at the bottom of the screen shows sound
bars that follow your voice, then a blue wave while it transcribes (turn it off with
`overlay = false`). You'll also hear a high beep when recording starts, a lower one when
it stops, and a low beep if nothing was heard or something went wrong.

## Install

You need Python 3.11+ and [uv](https://docs.astral.sh/uv/) (or pipx).

```sh
git clone <this repo> sayflux && cd sayflux
uv tool install .
sayflux download        # fetch the speech model once (~490 MB)
```

Then add your Gemini API key (free at [aistudio.google.com](https://aistudio.google.com/apikey))
to `~/.env`, your environment, or the config file:

```sh
echo 'GEMINI_API_KEY=your-key' >> ~/.env
```

### Linux

Recording uses PortAudio, and typing uses a virtual keyboard (`/dev/uinput`), which works
on both X11 and Wayland.

1. Install PortAudio, plus `wl-clipboard` (Wayland) or `xclip` (X11) for non-English characters:
   - Fedora: `sudo dnf install portaudio wl-clipboard`
   - Debian/Ubuntu: `sudo apt install libportaudio2 wl-clipboard`
   - Arch: `sudo pacman -S portaudio wl-clipboard`
2. Allow your user to create the virtual keyboard, then **reboot**:
   ```sh
   echo 'KERNEL=="uinput", GROUP="input", MODE="0660", OPTIONS+="static_node=uinput"' \
     | sudo tee /etc/udev/rules.d/99-sayflux-uinput.rules
   sudo usermod -aG input $USER
   ```
   If `id` already lists `input`, you can skip the reboot and apply it now with
   `sudo chgrp input /dev/uinput && sudo chmod 660 /dev/uinput`.
3. Bind `sayflux` to a shortcut in your desktop settings. Apps can't register global
   shortcuts on Wayland, so this is done by the desktop:

<details>
<summary>GNOME</summary>

```sh
name="sayflux"
binding="<Ctrl><Alt>space"
action="$(command -v sayflux)"

media_keys=org.gnome.settings-daemon.plugins.media-keys
custom_kbd=org.gnome.settings-daemon.plugins.media-keys.custom-keybinding
kbd_path=/org/gnome/settings-daemon/plugins/media-keys/custom-keybindings/$name/
new_bindings=`gsettings get $media_keys custom-keybindings | sed -e"s>'\]>','$kbd_path']>"| sed -e"s>@as \[\]>['$kbd_path']>"`
gsettings set $media_keys custom-keybindings "$new_bindings"
gsettings set $custom_kbd:$kbd_path name "$name"
gsettings set $custom_kbd:$kbd_path binding "$binding"
gsettings set $custom_kbd:$kbd_path command "$action"
```
</details>

<details>
<summary>KDE Plasma</summary>

System Settings → Keyboard → Shortcuts → Add New → Command or Script → `sayflux`.
</details>

<details>
<summary>i3 / sway</summary>

```
bindsym $mod+d exec sayflux
```
</details>

<details>
<summary>Hyprland</summary>

```
bind = $mainMod, D, exec, sayflux
```
</details>

Typing assumes a US QWERTY layout. On other layouts, set up a key that switches to US
QWERTY and set `wrap_key` in the config; Sayflux taps it before and after typing.

### macOS

1. Start it once: `sayflux daemon`. Leave it running, or add it as a login item.
2. When asked, allow **Microphone**, **Accessibility** (to type) and **Input Monitoring**
   (for the shortcut) for your terminal, or for Python if you start it another way.
3. Press **Ctrl+Option+Space** to start and stop. Change it with `[hotkey] toggle` in the config.

### Windows

1. Start it once: `sayflux daemon`. To start it at login, put a shortcut to
   `pythonw -m sayflux daemon` in `shell:startup`.
2. Press **Ctrl+Alt+Space** to start and stop. Change it with `[hotkey] toggle` in the config.

## Usage

| Command | What it does |
| --- | --- |
| `sayflux` | Start recording, or stop and type the text |
| `sayflux cancel` | Stop recording without typing anything |
| `sayflux status` | Idle, recording or processing |
| `sayflux stop` | Stop the background process (e.g. after changing the config) |
| `sayflux transcribe file.wav` | Transcribe and format a 16-bit WAV file and print the result |
| `sayflux config` | Create the config file and print its path |
| `sayflux log` | Show recent activity, including raw and formatted text |

## Configuration

Run `sayflux config` to create the file, then run `sayflux stop` after editing so the
next shortcut press picks up the changes. Locations:

- Linux: `~/.config/sayflux/config.toml`
- macOS: `~/Library/Application Support/sayflux/config.toml`
- Windows: `%APPDATA%\sayflux\config.toml`

The most useful settings:

```toml
[format]
model = "gemini-3.1-flash-lite"   # any Gemini model that supports generateContent
instructions = "Spell these as written: Kubernetes, PostgreSQL, Sayflux."
enabled = true                    # false = type the raw transcript

[speech]
silence_threshold_db = -50        # recordings quieter than this are skipped

[output]
sounds = true
```

## Troubleshooting

- **Nothing happens**: run `sayflux log`. If the background process failed to start, the
  reason is there (for example, the `/dev/uinput` permission on Linux).
- **Wrong characters on Linux**: your layout isn't US QWERTY; see `wrap_key` above.
- **Non-English characters don't appear in a Linux terminal**: they're pasted with Ctrl+V,
  which most terminals don't use for paste. Letters, digits and punctuation are typed
  directly and work everywhere.
- **New lines** are typed as Shift+Enter so chat apps don't send your message early.

## Mobile

Android and iOS need their own apps: to type into other apps, Sayflux has to be a custom
keyboard (Kotlin on Android, Swift on iOS). The same Parakeet model files run there through
sherpa-onnx, and the same Gemini prompt can be reused. On iOS, keyboards can't use the
microphone and have tight memory limits, so recording would happen in the main app.

## Credits

Speech model by
NVIDIA ([Parakeet TDT 0.6B v3](https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3), CC-BY-4.0),
run with [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx).
