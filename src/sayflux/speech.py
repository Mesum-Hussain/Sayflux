"""Local speech-to-text with NVIDIA Parakeet TDT 0.6B v3 via sherpa-onnx.

Two packagings of the same model are supported (config: [speech] model):
- "redux" (default): Moondream's Parakeet Redux, every encoder weight -1, 0 or +1.
  169 MB download, 400 MB on disk. Same words as the original in our tests, but
  weaker with loud background noise.
- "parakeet-v3-int8": the original model in 8-bit. 490 MB download, 670 MB on disk.
"""

import hashlib
import logging
import os
import shutil
import sys
import tarfile
import urllib.error
import urllib.request
from array import array
from dataclasses import dataclass
from pathlib import Path

from sayflux import paths

log = logging.getLogger(__name__)


@dataclass(frozen=True)
class Model:
    name: str  # folder inside the archive, and on disk
    url: str
    sha256: str  # of the archive
    encoder: str
    decoder: str
    joiner: str

    @property
    def files(self) -> tuple[str, ...]:
        return (self.encoder, self.decoder, self.joiner, "tokens.txt")


MODELS = {
    "redux": Model(
        name="sherpa-onnx-nemo-parakeet-redux",
        url="https://huggingface.co/Codyfederer/sherpa-onnx-nemo-parakeet-redux/resolve/main/"
        "sherpa-onnx-nemo-parakeet-redux.tar.bz2",
        sha256="4f1b06857fa85abab4d7fb14b644a6a98f2e241d92f9e5227f6719e0050417c4",
        encoder="encoder.onnx",
        decoder="decoder.onnx",
        joiner="joiner.onnx",
    ),
    "parakeet-v3-int8": Model(
        name="sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8",
        url="https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/"
        "sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8.tar.bz2",
        sha256="5793d0fd397c5778d2cf2126994d58e9d56b1be7c04d13c7a15bb1b4eafb16bf",
        encoder="encoder.int8.onnx",
        decoder="decoder.int8.onnx",
        joiner="joiner.int8.onnx",
    ),
}
DEFAULT_MODEL = "redux"


def get_model(cfg: dict) -> Model:
    key = cfg["speech"].get("model") or DEFAULT_MODEL
    if key not in MODELS:
        raise ValueError(f"Unknown speech model {key!r}; use one of: {', '.join(MODELS)}")
    return MODELS[key]


def model_dir(model: Model) -> Path:
    return paths.data_dir() / "models" / model.name


def is_downloaded(model: Model) -> bool:
    return all((model_dir(model) / name).is_file() for name in model.files)


def _fetch(url: str, dest: Path) -> None:
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
                    print(f"\rDownloading speech model... {done * 100 // total}%", end="", file=sys.stderr, flush=True)
    print(file=sys.stderr)


def _sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        while chunk := f.read(1 << 20):
            h.update(chunk)
    return h.hexdigest()


def download(model: Model) -> Path:
    """Download, verify and unpack the model. Prints progress to stderr."""
    models = model_dir(model).parent
    models.mkdir(parents=True, exist_ok=True)
    archive = models / f"{model.name}.tar.bz2.part"

    _fetch(model.url, archive)
    print("Checking...", file=sys.stderr)
    if _sha256(archive) != model.sha256:
        archive.unlink()
        raise RuntimeError("The download was corrupted. Run the download again.")
    print("Unpacking...", file=sys.stderr)

    staging = models / f"{model.name}.staging"
    shutil.rmtree(staging, ignore_errors=True)
    wanted = {f"{model.name}/{name}" for name in model.files}
    with tarfile.open(archive, "r:bz2") as tar:
        for member in tar:
            member.name = member.name.removeprefix("./")
            if member.isfile() and member.name in wanted:
                try:
                    tar.extract(member, staging, filter="data")
                except TypeError:  # Python < 3.11.4 has no filter; names are checked above
                    tar.extract(member, staging)
    if not all((staging / name).is_file() for name in wanted):
        shutil.rmtree(staging, ignore_errors=True)
        raise RuntimeError(f"Model archive is missing expected files; delete {archive} and retry")
    shutil.rmtree(model_dir(model), ignore_errors=True)
    (staging / model.name).rename(model_dir(model))
    shutil.rmtree(staging, ignore_errors=True)
    archive.unlink()
    return model_dir(model)


class Transcriber:
    def __init__(self, model: Model, threads: int = 0):
        import sherpa_onnx

        if not is_downloaded(model):
            raise FileNotFoundError("Speech model not found. Run: sayflux download")
        if threads <= 0:
            threads = max(1, min(4, (os.cpu_count() or 2) // 2))
        d = model_dir(model)
        self._recognizer = sherpa_onnx.OfflineRecognizer.from_transducer(
            encoder=str(d / model.encoder),
            decoder=str(d / model.decoder),
            joiner=str(d / model.joiner),
            tokens=str(d / "tokens.txt"),
            model_type="nemo_transducer",
            num_threads=threads,
        )

    def transcribe(self, pcm: array, sample_rate: int) -> str:
        """pcm: 16-bit mono samples. Any sample rate; sherpa-onnx resamples to 16 kHz."""
        samples = array("f", (s / 32768 for s in pcm))
        stream = self._recognizer.create_stream()
        stream.accept_waveform(sample_rate, samples)
        self._recognizer.decode_stream(stream)
        return stream.result.text.strip()
