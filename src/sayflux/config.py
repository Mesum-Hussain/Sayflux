"""Config file: defaults below, overridden by the user's config.toml."""

import os
import tomllib
from pathlib import Path

from sayflux import paths

DEFAULT_CONFIG = """\
# Sayflux configuration

[speech]
# "redux" (169 MB download, default) or "parakeet-v3-int8" (490 MB, better with loud
# background noise). Run `sayflux download` after changing it.
model = "redux"
threads = 0                  # CPU threads for the speech model; 0 = automatic
silence_threshold_db = -50   # recordings quieter than this are skipped

[format]
# Remove "um"/"uh", stutters like "the the", apply "scratch that" and "new line".
# Runs on this computer with simple rules, before Gemini (if enabled).
basic_cleanup = true
# Then clean up the transcript with Gemini: self-corrections, lists, numbers, emails.
# Without an API key only the basic clean-up is applied.
enabled = true
model = "gemini-3.1-flash-lite"
api_key = ""                 # or set GEMINI_API_KEY in your environment or ~/.env
thinking_level = "minimal"   # "" to use the model's default
timeout = 10                 # seconds; on timeout or error the raw transcript is typed
# Extra rules for the formatter, e.g. names and jargon it should spell correctly:
instructions = ""

[output]
sounds = true                # short beeps when recording starts and stops
overlay = true               # sound-wave popup at the bottom of the screen while recording
key_delay_ms = 5             # Linux: pause between key presses
# Linux: tap this key before and after typing, to switch to a US QWERTY layout and back.
# One of: leftalt, rightalt, leftctrl, rightctrl, leftshift, rightshift, super
wrap_key = ""

[hotkey]
# macOS and Windows: global shortcut that starts and stops recording (pynput syntax).
# On Linux, bind `sayflux` to a shortcut in your desktop settings instead.
toggle = "<ctrl>+<alt>+space"
"""


def _merge(base: dict, override: dict) -> dict:
    out = dict(base)
    for key, value in override.items():
        if isinstance(value, dict) and isinstance(out.get(key), dict):
            out[key] = _merge(out[key], value)
        else:
            out[key] = value
    return out


def load() -> dict:
    cfg = tomllib.loads(DEFAULT_CONFIG)
    path = paths.config_file()
    if path.exists():
        with path.open("rb") as f:
            cfg = _merge(cfg, tomllib.load(f))
    return cfg


def write_default() -> Path:
    path = paths.config_file()
    if not path.exists():
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(DEFAULT_CONFIG, encoding="utf-8")
    return path


def gemini_api_key(cfg: dict) -> str:
    """Config file first, then the environment, then ~/.env (for shortcut-launched runs)."""
    key = cfg["format"].get("api_key") or os.environ.get("GEMINI_API_KEY", "")
    if key:
        return key
    env_file = Path.home() / ".env"
    try:
        for line in env_file.read_text(encoding="utf-8").splitlines():
            line = line.strip().removeprefix("export ").strip()
            if line.startswith("GEMINI_API_KEY="):
                return line.split("=", 1)[1].strip().strip("'\"")
    except OSError:
        pass
    return ""
