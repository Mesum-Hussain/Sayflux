"""Rule-based clean-up matches the cases shared with the Android app."""

import json
import unittest
from pathlib import Path

from whistleflux.cleanup import clean

CASES = json.loads((Path(__file__).parents[1] / "shared" / "cleanup-cases.json").read_text())["cases"]


class CleanupTest(unittest.TestCase):
    def test_shared_cases(self):
        for case in CASES:
            with self.subTest(text=case["in"]):
                self.assertEqual(clean(case["in"]), case["out"])


if __name__ == "__main__":
    unittest.main()
