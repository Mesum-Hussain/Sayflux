"""Local speech-to-text with Cactus Whistle, run by Cactus's Needle engine.

`whistleflux download` fetches two files once and checks them against the SHA-256 hashes
pinned below:
- whistle.cact: the model, 17 MB (Apache 2.0).
- the Needle engine for this OS, about 1.6 MB, taken out of Cactus's Python wheel.

The engine is called directly through ctypes (see needle.h in Cactus-Compute/needle3),
so Cactus's Python package and its dependencies aren't needed.
"""

import ctypes
import json
import logging
import platform
import shutil
import sys
import urllib.error
import urllib.request
import zipfile
from array import array
from pathlib import Path

from whistleflux import paths

log = logging.getLogger(__name__)

SAMPLE_RATE = 16000  # what the model wants
LANGUAGES = ("en", "de", "fr", "es", "it", "nl", "pl")

# Pinned commits on Hugging Face, so an upstream change can't swap the files under us.
MODEL_URL = (
    "https://huggingface.co/Cactus-Compute/whistle/resolve/"
    "741118687e1f008f06d1351a402a731274789790/whistle.cact"
)
MODEL_SHA256 = "b6e02f048568ac5d01a2042556c658061e699acbc0aa2a1439f52f3d461dffeb"
ENGINE_VERSION = "3.3.2"
ENGINE_URL = (
    "https://huggingface.co/Cactus-Compute/needle3/resolve/"
    "3391a1007e7db31d2fe5c69c734bbe93a9c1ce79/python/cactus_needle-{version}-py3-none-{tag}.whl"
)
ENGINE_SHA256 = {  # by wheel platform tag
    "manylinux2014_x86_64": "8948bb145e061f4243459dd900e3ce2353f63c4acb7ca6eca8cb85e15d292225",
    "manylinux2014_aarch64": "b395560f7fa696d73aad5ced0cc091ee22f89e231ce79a2854f759fcdf3af69d",
    "musllinux_1_2_x86_64": "56ec08ce33ffd10ed48867cfb5f7f94825f10d3a150f7db6aeb514bbec32adc3",
    "musllinux_1_2_aarch64": "0df22e61f4989da1e2e9463951180a3796eb914c2b1e045c1b417600bcdbea09",
    "macosx_11_0_arm64": "d9c411c39aaf46aa915634521ad95d482dd109164b8fb92d427ec78923202a8a",
    "macosx_11_0_x86_64": "697a3a1b8f0ad3cd478231a955e61913d0e8afd223b7faaad01ef3849fc5dd55",
    "win_amd64": "c99f8590a54e589db27fc4d2d1808ce4e2e46b17daf3738a014403caefedf9dc",
    "win_arm64": "1b76b81fa6cb13136b7c5053120ee0fe9eba98fc417c20161c0be035359d57c3",
}
MODEL_FILE = "whistle.cact"
ENGINE_FILE = {"darwin": "libneedle3.dylib", "win32": "libneedle3.dll"}.get(sys.platform, "libneedle3.so")


def model_dir() -> Path:
    return paths.data_dir() / "models" / "whistle"


def is_downloaded() -> bool:
    return all((model_dir() / name).is_file() for name in (MODEL_FILE, ENGINE_FILE))


def _engine_tag() -> str:
    machine = platform.machine().lower()
    arch = {"x86_64": "x86_64", "amd64": "x86_64", "aarch64": "aarch64", "arm64": "aarch64"}.get(machine)
    if arch is None:
        raise RuntimeError(f"The speech engine isn't available for {platform.machine()} processors")
    if sys.platform == "darwin":
        return "macosx_11_0_" + ("arm64" if arch == "aarch64" else "x86_64")
    if sys.platform == "win32":
        return "win_arm64" if arch == "aarch64" else "win_amd64"
    libc = "manylinux2014_" if platform.libc_ver()[0] == "glibc" else "musllinux_1_2_"
    return libc + arch


def _fetch(url: str, dest: Path, label: str) -> None:
    """Download to dest, resuming a partial file. Prints progress to stderr."""
    start = dest.stat().st_size if dest.exists() else 0
    req = urllib.request.Request(url, headers={"Range": f"bytes={start}-"} if start else {})
    try:
        resp = urllib.request.urlopen(req, timeout=30)
    except urllib.error.HTTPError as e:
        if e.code == 416:  # already complete
            return
        raise
    with resp:
        if start and resp.status != 206:  # server ignored the range: start over
            start = 0
        total = start + int(resp.headers.get("Content-Length") or 0)
        done = start
        with dest.open("ab" if start else "wb") as out:
            while chunk := resp.read(1 << 20):
                out.write(chunk)
                done += len(chunk)
                if total:
                    print(f"\rDownloading {label}... {done * 100 // total}%", end="", file=sys.stderr, flush=True)
    print(file=sys.stderr)


def _sha256(path: Path) -> str:
    import hashlib

    h = hashlib.sha256()
    with path.open("rb") as f:
        while chunk := f.read(1 << 20):
            h.update(chunk)
    return h.hexdigest()


def _fetch_verified(url: str, sha256: str, dest: Path, label: str) -> None:
    _fetch(url, dest, label)
    if _sha256(dest) != sha256:
        dest.unlink()
        raise RuntimeError(f"The {label} download was corrupted. Run the download again.")


def _remove_old_models() -> None:
    """Earlier versions used Parakeet (400 to 670 MB on disk); free that space."""
    for old in (paths.data_dir() / "models").glob("sherpa-onnx-*"):
        if old.is_dir():
            size = sum(f.stat().st_size for f in old.rglob("*") if f.is_file())
            shutil.rmtree(old, ignore_errors=True)
            print(f"Removed the old Parakeet model {old.name} ({size // 1_000_000} MB)", file=sys.stderr)


def download() -> Path:
    """Download and verify the model and the engine. Prints progress to stderr."""
    d = model_dir()
    d.mkdir(parents=True, exist_ok=True)

    part = d / f"{MODEL_FILE}.part"
    _fetch_verified(MODEL_URL, MODEL_SHA256, part, "speech model")
    part.replace(d / MODEL_FILE)

    tag = _engine_tag()
    wheel = d / f"engine-{tag}.whl.part"
    _fetch_verified(ENGINE_URL.format(version=ENGINE_VERSION, tag=tag), ENGINE_SHA256[tag], wheel, "speech engine")
    with zipfile.ZipFile(wheel) as zf:
        data = zf.read(f"needle/{ENGINE_FILE}")
    tmp = d / f"{ENGINE_FILE}.part"
    tmp.write_bytes(data)
    tmp.replace(d / ENGINE_FILE)
    wheel.unlink()

    _remove_old_models()
    return d


def to_16k_float(pcm: array, rate: int) -> array:
    """16-bit samples at any rate -> floats in [-1, 1] at 16 kHz.

    The recorder asks for 16 kHz, so this only resamples for mics that refuse it.
    Whole multiples (32, 48 kHz) are averaged; other rates are linearly interpolated.
    """
    if rate == SAMPLE_RATE:
        return array("f", (s / 32768 for s in pcm))
    if rate % SAMPLE_RATE == 0:
        n = rate // SAMPLE_RATE
        scale = 1 / (32768 * n)
        return array("f", (sum(pcm[i:i + n]) * scale for i in range(0, len(pcm) - n + 1, n)))
    step = rate / SAMPLE_RATE
    out = array("f")
    last = len(pcm) - 1
    pos = 0.0
    while pos < last:
        i = int(pos)
        frac = pos - i
        out.append((pcm[i] * (1 - frac) + pcm[i + 1] * frac) / 32768)
        pos += step
    return out


class Transcriber:
    """Loads the model once. The engine holds one model per process and isn't thread-safe."""

    def __init__(self, language: str = "", keywords=()):
        if not is_downloaded():
            raise FileNotFoundError("Speech model not found. Run: whistleflux download")
        language = (language or "").strip().lower()
        if language and language not in LANGUAGES:
            raise ValueError(f"Unsupported speech language {language!r}; use one of: {', '.join(LANGUAGES)}")
        if isinstance(keywords, str):
            keywords = keywords.split(",")
        words = [k.strip() for k in keywords if k.strip()]
        self._language = language.encode() if language else None
        self._keywords = "\n".join(words).encode() if words else None

        lib = ctypes.CDLL(str(model_dir() / ENGINE_FILE))
        lib.needle_load.argtypes = [ctypes.c_char_p, ctypes.c_uint64]
        lib.needle_load.restype = ctypes.c_int
        lib.needle_transcribe.argtypes = [
            ctypes.POINTER(ctypes.c_float), ctypes.c_int, ctypes.c_char_p, ctypes.c_char_p,
            ctypes.c_int, ctypes.c_char_p, ctypes.c_int,
        ]
        lib.needle_transcribe.restype = ctypes.c_int
        lib.needle_last_error.argtypes = []
        lib.needle_last_error.restype = ctypes.c_char_p
        self._lib = lib
        data = (model_dir() / MODEL_FILE).read_bytes()
        if lib.needle_load(data, len(data)) < 0:
            raise RuntimeError(f"Couldn't load the speech model: {self._error()}")

    def _error(self) -> str:
        msg = self._lib.needle_last_error()
        return msg.decode("utf-8", "replace") if msg else "unknown error"

    def transcribe(self, pcm: array, sample_rate: int) -> str:
        """pcm: 16-bit mono samples at any sample rate."""
        samples = to_16k_float(pcm, sample_rate)
        if not samples:
            return ""
        # The JSON reply is about 4 KB per second of audio at most.
        out = ctypes.create_string_buffer(64 * 1024 + len(samples) // SAMPLE_RATE * 4096)
        ptr = (ctypes.c_float * len(samples)).from_buffer(samples)
        n = self._lib.needle_transcribe(ptr, len(samples), self._language, self._keywords, 0, out, len(out))
        if n < 0:
            raise RuntimeError(f"Transcription failed: {self._error()}")
        return json.loads(out.value.decode("utf-8", "replace"))["text"].strip()


def from_config(cfg: dict) -> Transcriber:
    s = cfg["speech"]
    return Transcriber(s.get("language", ""), s.get("keywords", ()))
