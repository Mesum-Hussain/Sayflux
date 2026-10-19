"""Type text at the cursor.

Linux: a virtual keyboard through /dev/uinput (works on X11 and Wayland; needs the
`input` group). ASCII is typed as US QWERTY key presses; other characters go through
the clipboard and Ctrl+V. macOS and Windows: pynput, which types any Unicode text.

Newlines are sent as Shift+Enter so chat apps add a line instead of sending the message.
"""

import logging
import os
import shutil
import struct
import subprocess
import time

from whistleflux import paths

log = logging.getLogger(__name__)


def make_typer(cfg: dict):
    if paths.IS_LINUX:
        return UinputTyper(cfg)
    return PynputTyper()


class PynputTyper:
    def __init__(self):
        from pynput.keyboard import Controller, Key

        self._kb = Controller()
        self._key = Key

    def type(self, text: str) -> None:
        for i, line in enumerate(text.split("\n")):
            if i:
                with self._kb.pressed(self._key.shift):
                    self._kb.tap(self._key.enter)
            if line:
                self._kb.type(line)


# --- Linux uinput ------------------------------------------------------------

# Key codes from linux/input-event-codes.h
KEY_BACKSPACE, KEY_TAB, KEY_ENTER, KEY_SPACE = 14, 15, 28, 57
KEY_LEFTCTRL, KEY_LEFTSHIFT, KEY_V = 29, 42, 47
WRAP_KEYS = {
    "leftalt": 56, "rightalt": 100, "leftctrl": 29, "rightctrl": 97,
    "leftshift": 42, "rightshift": 54, "super": 125,
}

# US QWERTY: character -> (key code, needs shift)
KEYMAP: dict[str, tuple[int, bool]] = {}
for _row, _first in (("qwertyuiop", 16), ("asdfghjkl", 30), ("zxcvbnm", 44)):
    for _i, _c in enumerate(_row):
        KEYMAP[_c] = (_first + _i, False)
        KEYMAP[_c.upper()] = (_first + _i, True)
for _i, _c in enumerate("1234567890"):
    KEYMAP[_c] = (2 + _i, False)
KEYMAP.update({
    " ": (KEY_SPACE, False), "\t": (KEY_TAB, False),
    "-": (12, False), "=": (13, False), "[": (26, False), "]": (27, False),
    ";": (39, False), "'": (40, False), "`": (41, False), "\\": (43, False),
    ",": (51, False), ".": (52, False), "/": (53, False),
})
for _shifted, _base in zip('!@#$%^&*()_+{}:"~|<>?', "1234567890-=[];'`\\,./", strict=True):
    KEYMAP[_shifted] = (KEYMAP[_base][0], True)

# Smart punctuation -> ASCII, so it can be typed instead of pasted.
ASCII_PUNCT = str.maketrans({
    "‘": "'", "’": "'", "“": '"', "”": '"',
    "–": "-", "—": "-", "…": "...", " ": " ",
})

# ioctl numbers from linux/uinput.h
UI_SET_EVBIT = 0x40045564
UI_SET_KEYBIT = 0x40045565
UI_DEV_SETUP = 0x405C5503
UI_DEV_CREATE = 0x5501
UI_DEV_DESTROY = 0x5502
EV_SYN, EV_KEY, SYN_REPORT = 0, 1, 0
BUS_USB = 0x03


class UinputTyper:
    def __init__(self, cfg: dict):
        import fcntl

        out = cfg["output"]
        self._delay = max(0, out.get("key_delay_ms", 5)) / 1000
        wrap = out.get("wrap_key", "")
        if wrap and wrap not in WRAP_KEYS:
            raise ValueError(f"Unknown wrap_key {wrap!r}; use one of {', '.join(WRAP_KEYS)}")
        self._wrap = WRAP_KEYS.get(wrap)

        try:
            self._fd = os.open("/dev/uinput", os.O_WRONLY | os.O_NONBLOCK)
        except PermissionError as e:
            raise PermissionError(
                "Can't open /dev/uinput to type text. Give the input group access and join it:\n"
                '  echo \'KERNEL=="uinput", GROUP="input", MODE="0660", OPTIONS+="static_node=uinput"\' '
                "| sudo tee /etc/udev/rules.d/99-whistleflux-uinput.rules\n"
                "  sudo usermod -aG input $USER\n"
                "Then reboot, or apply it now (if `id` already lists input):\n"
                "  sudo chgrp input /dev/uinput && sudo chmod 660 /dev/uinput"
            ) from e

        keys = {code for code, _ in KEYMAP.values()}
        keys |= {KEY_BACKSPACE, KEY_ENTER, KEY_LEFTCTRL, KEY_LEFTSHIFT, KEY_V, *WRAP_KEYS.values()}
        fcntl.ioctl(self._fd, UI_SET_EVBIT, EV_KEY)
        for code in sorted(keys):
            fcntl.ioctl(self._fd, UI_SET_KEYBIT, code)
        setup = struct.pack("HHHH80sI", BUS_USB, 0x1234, 0x5678, 1, b"whistleflux", 0)
        fcntl.ioctl(self._fd, UI_DEV_SETUP, setup)
        fcntl.ioctl(self._fd, UI_DEV_CREATE)
        time.sleep(0.2)  # let the desktop notice the new keyboard

        self._clip = self._clipboard_command()

    def close(self) -> None:
        import fcntl

        fcntl.ioctl(self._fd, UI_DEV_DESTROY)
        os.close(self._fd)

    def _emit(self, ev_type: int, code: int, value: int) -> None:
        os.write(self._fd, struct.pack("llHHi", 0, 0, ev_type, code, value))
        os.write(self._fd, struct.pack("llHHi", 0, 0, EV_SYN, SYN_REPORT, 0))

    def _tap(self, code: int, *modifiers: int) -> None:
        for m in modifiers:
            self._emit(EV_KEY, m, 1)
        self._emit(EV_KEY, code, 1)
        time.sleep(self._delay)
        self._emit(EV_KEY, code, 0)
        for m in reversed(modifiers):
            self._emit(EV_KEY, m, 0)
        time.sleep(self._delay)

    @staticmethod
    def _clipboard_command() -> list[str] | None:
        if os.environ.get("WAYLAND_DISPLAY") and shutil.which("wl-copy"):
            return ["wl-copy"]
        if shutil.which("xclip"):
            return ["xclip", "-selection", "clipboard"]
        return None

    def _paste(self, text: str) -> None:
        if not self._clip:
            log.warning("No wl-copy or xclip; skipping non-ASCII text %r", text)
            return
        subprocess.run(self._clip, input=text.encode(), check=False)
        time.sleep(0.1)  # give the clipboard owner time to take over
        self._tap(KEY_V, KEY_LEFTCTRL)
        time.sleep(0.1)  # let the app read it before the next copy

    def type(self, text: str) -> None:
        text = text.translate(ASCII_PUNCT)
        if self._wrap:
            self._tap(self._wrap)
        pending = ""  # run of non-ASCII characters, pasted in one go
        for ch in text:
            if ch in KEYMAP or ch == "\n":
                if pending:
                    self._paste(pending)
                    pending = ""
                if ch == "\n":
                    self._tap(KEY_ENTER, KEY_LEFTSHIFT)
                else:
                    code, shift = KEYMAP[ch]
                    self._tap(code, *((KEY_LEFTSHIFT,) if shift else ()))
            else:
                pending += ch
        if pending:
            self._paste(pending)
        if self._wrap:
            self._tap(self._wrap)
