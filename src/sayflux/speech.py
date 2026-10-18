"""Local speech-to-text with NVIDIA Parakeet TDT 0.6B v3 (int8 ONNX, via sherpa-onnx)."""

import logging
import os
import shutil
import sys
import tarfile
import urllib.request
from array import array
from pathlib import Path

from sayflux import paths

log = logging.getLogger(__name__)

MODEL_NAME = "sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8"
MODEL_URL = (
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/"
    f"{MODEL_NAME}.tar.bz2"
)
MODEL_FILES = ("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt")


def model_dir() -> Path:
    return paths.data_dir() / "models" / MODEL_NAME


def is_downloaded() -> bool:
    return all((model_dir() / name).is_file() for name in MODEL_FILES)


def download() -> Path:
    """Download and unpack the model (~490 MB). Prints progress to stderr."""
    models = model_dir().parent
    models.mkdir(parents=True, exist_ok=True)
    archive = models / f"{MODEL_NAME}.tar.bz2.part"

    def progress(blocks: int, block_size: int, total: int) -> None:
        if total > 0:
            pct = min(100, blocks * block_size * 100 // total)
            print(f"\rDownloading speech model... {pct}%", end="", file=sys.stderr, flush=True)

    urllib.request.urlretrieve(MODEL_URL, archive, progress)
    print("\nUnpacking...", file=sys.stderr)

    staging = models / f"{MODEL_NAME}.staging"
    shutil.rmtree(staging, ignore_errors=True)
    wanted = {f"{MODEL_NAME}/{name}" for name in MODEL_FILES}
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
    shutil.rmtree(model_dir(), ignore_errors=True)
    (staging / MODEL_NAME).rename(model_dir())
    shutil.rmtree(staging, ignore_errors=True)
    archive.unlink()
    return model_dir()


class Transcriber:
    def __init__(self, threads: int = 0):
        import sherpa_onnx

        if not is_downloaded():
            raise FileNotFoundError("Speech model not found. Run: sayflux download")
        if threads <= 0:
            threads = max(1, min(4, (os.cpu_count() or 2) // 2))
        d = model_dir()
        self._recognizer = sherpa_onnx.OfflineRecognizer.from_transducer(
            encoder=str(d / "encoder.int8.onnx"),
            decoder=str(d / "decoder.int8.onnx"),
            joiner=str(d / "joiner.int8.onnx"),
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
