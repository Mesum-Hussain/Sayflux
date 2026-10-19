"""Gemini clean-up falls back to another model when the chosen one is unavailable."""

import io
import unittest
import urllib.error
from unittest import mock

from whistleflux import formatter


def _cfg(model: str) -> dict:
    return {"format": {"basic_cleanup": False, "enabled": True, "model": model, "api_key": "k",
                       "thinking_level": "", "timeout": 5, "instructions": ""}}


def _http_error(code: int) -> urllib.error.HTTPError:
    return urllib.error.HTTPError("url", code, "error", {}, io.BytesIO(b"{}"))


def _reply(text: str) -> dict:
    return {"candidates": [{"content": {"parts": [{"text": text}]}}]}


class FallbackTest(unittest.TestCase):
    def _run(self, model: str, answers: dict) -> tuple[str, list[str]]:
        """answers: model -> HTTP error code, or the text it replies with."""
        asked = []

        def fake_request(url, key, body, timeout):
            name = url.split("/models/")[1].split(":")[0]
            asked.append(name)
            answer = answers[name]
            if isinstance(answer, int):
                raise _http_error(answer)
            return _reply(answer)

        with mock.patch.object(formatter, "_request", fake_request):
            return formatter.format_text("raw words", _cfg(model)), asked

    def test_out_of_quota_uses_next_model(self):
        out, asked = self._run("gemini-3.5-flash", {"gemini-3.5-flash": 429, "gemini-3.5-flash-lite": "Clean words."})
        self.assertEqual(out, "Clean words.")
        self.assertEqual(asked, ["gemini-3.5-flash", "gemini-3.5-flash-lite"])

    def test_all_out_of_quota_types_raw_text(self):
        out, asked = self._run("gemini-3.5-flash-lite", {"gemini-3.5-flash-lite": 429, "gemini-3.1-flash-lite": 503})
        self.assertEqual(out, "raw words")
        self.assertEqual(asked, ["gemini-3.5-flash-lite", "gemini-3.1-flash-lite"])

    def test_bad_key_does_not_try_other_models(self):
        out, asked = self._run("gemini-3.5-flash-lite", {"gemini-3.5-flash-lite": 403})
        self.assertEqual(out, "raw words")
        self.assertEqual(asked, ["gemini-3.5-flash-lite"])


if __name__ == "__main__":
    unittest.main()
