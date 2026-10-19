"""Per-OS locations for config, data and the daemon's socket."""

import getpass
import os
import sys
import tempfile
from pathlib import Path

APP = "whistleflux"
OLD_APP = "sayflux"  # the project's earlier name; its folders are moved over on first use
IS_LINUX = sys.platform.startswith("linux")
IS_MAC = sys.platform == "darwin"
IS_WINDOWS = sys.platform == "win32"


def _moved_from_old_name(path: Path) -> Path:
    """Move <base>/sayflux to <base>/whistleflux once, so settings and models carry over."""
    old = path.parent / OLD_APP
    if not path.exists() and old.is_dir():
        try:
            old.rename(path)
            old_log = path / f"{OLD_APP}.log"
            if old_log.exists():
                old_log.rename(path / f"{APP}.log")
        except OSError:
            pass  # another process moved it first, or it's not ours to move
    return path


def config_dir() -> Path:
    if IS_WINDOWS:
        base = Path(os.environ.get("APPDATA") or Path.home() / "AppData" / "Roaming")
    elif IS_MAC:
        base = Path.home() / "Library" / "Application Support"
    else:
        base = Path(os.environ.get("XDG_CONFIG_HOME") or Path.home() / ".config")
    return _moved_from_old_name(base / APP)


def data_dir() -> Path:
    if IS_WINDOWS:
        base = Path(os.environ.get("LOCALAPPDATA") or Path.home() / "AppData" / "Local")
    elif IS_MAC:
        base = Path.home() / "Library" / "Application Support"
    else:
        base = Path(os.environ.get("XDG_DATA_HOME") or Path.home() / ".local" / "share")
    return _moved_from_old_name(base / APP)


def config_file() -> Path:
    return config_dir() / "config.toml"


def log_file() -> Path:
    return data_dir() / "whistleflux.log"


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
