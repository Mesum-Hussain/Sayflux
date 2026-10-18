"""Microphone recording, silence check and feedback beeps."""

import logging
import math
import wave
from array import array
from pathlib import Path

log = logging.getLogger(__name__)

SAMPLE_RATE = 16000  # what the model wants; avoids resampling


class Recorder:
    def __init__(self):
        self._stream = None
        self._chunks: list[bytes] = []
        self.sample_rate = SAMPLE_RATE
        self.level = 0.0  # loudness of the latest chunk, 0..1, for the overlay

    def start(self) -> None:
        import sounddevice as sd

        self._chunks = []
        self.level = 0.0

        def callback(data, frames, time, status):
            chunk = bytes(data)
            self._chunks.append(chunk)
            self.level = loudness(chunk)

        try:
            self._stream = sd.RawInputStream(
                samplerate=SAMPLE_RATE, channels=1, dtype="int16", callback=callback
            )
            self.sample_rate = SAMPLE_RATE
        except sd.PortAudioError:
            # Some devices refuse 16 kHz; record at their native rate instead.
            rate = int(sd.query_devices(kind="input")["default_samplerate"])
            log.info("Mic doesn't support 16 kHz, recording at %d Hz", rate)
            self._stream = sd.RawInputStream(
                samplerate=rate, channels=1, dtype="int16", callback=callback
            )
            self.sample_rate = rate
        self._stream.start()

    def stop(self) -> array:
        if self._stream is not None:
            self._stream.stop()
            self._stream.close()
            self._stream = None
        pcm = array("h")
        pcm.frombytes(b"".join(self._chunks))
        self._chunks = []
        return pcm


def loudness(chunk: bytes) -> float:
    """RMS level of 16-bit audio mapped to 0..1: about -50 dB (room) to -10 dB (loud speech)."""
    pcm = array("h")
    pcm.frombytes(chunk)
    if not pcm:
        return 0.0
    rms = math.sqrt(sum(s * s for s in pcm) / len(pcm))
    if rms < 1:
        return 0.0
    db = 20 * math.log10(rms / 32768)
    return min(1.0, max(0.0, (db + 50) / 40))


def peak_db(pcm: array) -> float:
    if not pcm:
        return -math.inf
    peak = max(max(pcm), -min(pcm))
    return 20 * math.log10(peak / 32768) if peak else -math.inf


def read_wav(path: Path) -> tuple[array, int]:
    """Read a 16-bit WAV file, keeping only the first channel."""
    with wave.open(str(path), "rb") as w:
        if w.getsampwidth() != 2:
            raise ValueError("Only 16-bit WAV files are supported")
        channels = w.getnchannels()
        pcm = array("h")
        pcm.frombytes(w.readframes(w.getnframes()))
        return pcm[::channels], w.getframerate()


def beep(freq: float, ms: int = 70, volume: float = 0.2) -> None:
    """Play a short sine tone. Never raises: a missing speaker shouldn't break dictation."""
    try:
        import sounddevice as sd

        rate = 44100
        n = rate * ms // 1000
        fade = max(1, n // 5)
        tone = array("h")
        for i in range(n):
            env = min(1.0, i / fade, (n - i) / fade)
            tone.append(int(32767 * volume * env * math.sin(2 * math.pi * freq * i / rate)))
        with sd.RawOutputStream(samplerate=rate, channels=1, dtype="int16") as out:
            out.write(tone.tobytes())
    except Exception as e:
        log.debug("Beep failed: %s", e)
