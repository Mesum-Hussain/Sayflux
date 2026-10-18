"""Per-OS locations for config, data and the daemon's socket."""

import getpass
import os
import sys
import tempfile
from pathlib import Path

APP = "sayflux"
IS_LINUX = sys.platform.startswith("linux")
IS_MAC = sys.platform == "darwin"
IS_WINDOWS = sys.platform == "win32"


def config_dir() -> Path:
    if IS_WINDOWS:
        base = Path(os.environ.get("APPDATA") or Path.home() / "AppData" / "Roaming")
    elif IS_MAC:
        base = Path.home() / "Library" / "Application Support"
    else:
        base = Path(os.environ.get("XDG_CONFIG_HOME") or Path.home() / ".config")
    return base / APP


def data_dir() -> Path:
    if IS_WINDOWS:
        base = Path(os.environ.get("LOCALAPPDATA") or Path.home() / "AppData" / "Local")
    elif IS_MAC:
        base = Path.home() / "Library" / "Application Support"
    else:
        base = Path(os.environ.get("XDG_DATA_HOME") or Path.home() / ".local" / "share")
    return base / APP


def config_file() -> Path:
    return config_dir() / "config.toml"


def log_file() -> Path:
    return data_dir() / "sayflux.log"


def ipc_address() -> tuple[str, str]:
    """(address, family) for multiprocessing.connection."""
    if IS_WINDOWS:
        return rf"\\.\pipe\{APP}-{getpass.getuser()}", "AF_PIPE"
    if IS_LINUX:
        # Abstract socket: no file to clean up after a crash.
        return f"\0{APP}-{os.getuid()}", "AF_UNIX"
    return os.path.join(tempfile.gettempdir(), f"{APP}-{os.getuid()}.sock"), "AF_UNIX"


def ipc_key() -> bytes:
    """Shared secret so other users can't drive the daemon. Created on first use."""
    path = data_dir() / "ipc.key"
    if not path.exists():
        path.parent.mkdir(parents=True, exist_ok=True)
        flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_BINARY", 0)
        try:
            fd = os.open(path, flags, 0o600)
        except FileExistsError:
            pass  # another process just created it
        else:
            with os.fdopen(fd, "wb") as f:
                f.write(os.urandom(32))
    return path.read_bytes()
