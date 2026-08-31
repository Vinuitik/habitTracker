"""
Unit tests for the M9 coding-hours capability's window-matching and time-accumulation logic
(coding_hours_capability.py).

Everything here is platform-independent: the actual Win32 GetForegroundWindow call
(get_focused_window_title_win32) is exercised only under unittest.skipUnless(sys.platform ==
"win32", ...) so it never runs on this Linux dev box; every other test drives the logic through
injected fakes (a fake window-title stream, a fake clock) with no OS calls at all.

Run with:  python3 -m unittest test_coding_hours_capability -v
"""
import sys
import unittest

import coding_hours_capability as chc


CODING_PATTERNS = [p.lower() for p in ["Visual Studio Code", "Windows Terminal", "PyCharm"]]


class IsCodingWindowTest(unittest.TestCase):

    def test_matches_substring_case_insensitively(self):
        self.assertTrue(chc.is_coding_window("main.py - habitTracker - Visual Studio Code", CODING_PATTERNS))
        self.assertTrue(chc.is_coding_window("WINDOWS TERMINAL", CODING_PATTERNS))
        self.assertTrue(chc.is_coding_window("pycharm — habitTracker", CODING_PATTERNS))

    def test_no_match_for_unrelated_window(self):
        self.assertFalse(chc.is_coding_window("Inbox - Gmail - Google Chrome", CODING_PATTERNS))

    def test_empty_or_none_title_is_never_coding(self):
        self.assertFalse(chc.is_coding_window("", CODING_PATTERNS))
        self.assertFalse(chc.is_coding_window(None, CODING_PATTERNS))


class TimeAccumulatorTest(unittest.TestCase):

    def test_sums_coding_and_other_seconds_across_a_sample_sequence(self):
        acc = chc.TimeAccumulator(CODING_PATTERNS, max_gap_seconds=120)
        samples = [
            (0, "main.py - Visual Studio Code"),
            (30, "main.py - Visual Studio Code"),
            (60, "Inbox - Gmail - Chrome"),
            (90, "Inbox - Gmail - Chrome"),
            (120, "main.py - Visual Studio Code"),
        ]
        for ts, title in samples:
            acc.add_sample(ts, title)

        # Each interval is credited to the title seen at the START of the interval:
        # [0,30]=coding, [30,60]=coding, [60,90]=other, [90,120]=other -> 60s coding, 60s other.
        self.assertAlmostEqual(acc.coding_seconds, 60.0)
        self.assertAlmostEqual(acc.other_seconds, 60.0)
        self.assertAlmostEqual(acc.total_tracked_hours, 120.0 / 3600.0)

    def test_gap_larger_than_max_gap_is_treated_as_idle_and_not_counted(self):
        acc = chc.TimeAccumulator(CODING_PATTERNS, max_gap_seconds=120)
        acc.add_sample(0, "Visual Studio Code")
        acc.add_sample(10_000, "Visual Studio Code")  # e.g. the laptop slept overnight
        self.assertEqual(acc.coding_seconds, 0.0)
        self.assertEqual(acc.other_seconds, 0.0)

    def test_gap_within_max_gap_is_still_counted(self):
        acc = chc.TimeAccumulator(CODING_PATTERNS, max_gap_seconds=120)
        acc.add_sample(0, "Visual Studio Code")
        acc.add_sample(100, "Visual Studio Code")
        self.assertAlmostEqual(acc.coding_seconds, 100.0)

    def test_coding_hours_property_converts_seconds_to_hours(self):
        # Gap must stay within max_gap_seconds or it's treated as idle (see the dedicated idle
        # test above) — use a generous max_gap here since this test is only about the
        # seconds-to-hours conversion.
        acc = chc.TimeAccumulator(CODING_PATTERNS, max_gap_seconds=3600)
        acc.add_sample(0, "Visual Studio Code")
        acc.add_sample(3600, "Visual Studio Code")
        self.assertAlmostEqual(acc.coding_hours, 1.0)

    def test_zero_or_negative_gap_contributes_nothing(self):
        acc = chc.TimeAccumulator(CODING_PATTERNS)
        acc.add_sample(100, "Visual Studio Code")
        acc.add_sample(100, "Visual Studio Code")  # duplicate timestamp: gap == 0
        acc.add_sample(50, "Visual Studio Code")   # out of order: negative gap
        self.assertEqual(acc.coding_seconds, 0.0)
        self.assertEqual(acc.other_seconds, 0.0)

    def test_single_sample_accumulates_nothing_yet(self):
        acc = chc.TimeAccumulator(CODING_PATTERNS)
        acc.add_sample(0, "Visual Studio Code")
        self.assertEqual(acc.coding_seconds, 0.0)
        self.assertEqual(acc.other_seconds, 0.0)


class RunTrackingLoopTest(unittest.TestCase):
    """Drives the polling loop entirely through injected fakes: a scripted sequence of window
    titles and a fake (non-sleeping) clock, so this exercises the real loop control flow with no
    actual wall-clock waiting and no OS calls."""

    def _make_fake_clock(self):
        clock = {"t": 0.0}

        def now_fn():
            return clock["t"]

        def sleep_fn(seconds):
            clock["t"] += seconds

        return now_fn, sleep_fn

    def test_stops_after_configured_duration_and_accumulates_both_buckets(self):
        now_fn, sleep_fn = self._make_fake_clock()
        titles = iter(["Visual Studio Code"] * 3 + ["Chrome"] * 3)

        def fake_get_title():
            return next(titles, "Chrome")

        acc = chc.run_tracking_loop(
            fake_get_title, ["visual studio code"],
            poll_interval_seconds=10, stop_after_seconds=50,
            sleep_fn=sleep_fn, now_fn=now_fn,
        )
        self.assertGreater(acc.coding_seconds, 0)
        self.assertGreater(acc.other_seconds, 0)
        # Loop stopped once the fake clock reached stop_after_seconds.
        self.assertGreaterEqual(now_fn(), 50)

    def test_on_flush_called_periodically_with_running_accumulator(self):
        now_fn, sleep_fn = self._make_fake_clock()
        flush_calls = []

        acc = chc.run_tracking_loop(
            lambda: "Visual Studio Code", ["visual studio code"],
            poll_interval_seconds=10, stop_after_seconds=100,
            sleep_fn=sleep_fn, now_fn=now_fn,
            on_flush=lambda a: flush_calls.append(a.coding_seconds),
            flush_interval_seconds=30,
        )
        # Flushed at least twice over a 100s run with a 30s flush interval, and the flushed
        # snapshots reflect accumulation-in-progress (non-decreasing).
        self.assertGreaterEqual(len(flush_calls), 2)
        self.assertEqual(flush_calls, sorted(flush_calls))
        self.assertGreater(acc.coding_seconds, flush_calls[0])

    def test_no_flush_when_flush_interval_not_given(self):
        now_fn, sleep_fn = self._make_fake_clock()
        flush_calls = []

        chc.run_tracking_loop(
            lambda: "Visual Studio Code", ["visual studio code"],
            poll_interval_seconds=10, stop_after_seconds=50,
            sleep_fn=sleep_fn, now_fn=now_fn,
            on_flush=lambda a: flush_calls.append(a),
        )
        self.assertEqual(flush_calls, [])


class LoadCodingWindowPatternsTest(unittest.TestCase):

    def test_loads_and_lowercases_the_shipped_default_config(self):
        patterns = chc.load_coding_window_patterns()
        self.assertIn("visual studio code", patterns)
        self.assertTrue(all(p == p.lower() for p in patterns))

    def test_missing_config_file_raises_file_not_found(self):
        with self.assertRaises(FileNotFoundError):
            chc.load_coding_window_patterns("/nonexistent/path/coding_hours_config.json")


class Win32OnlyTest(unittest.TestCase):
    """The real Win32 call is only ever exercised on an actual Windows machine."""

    @unittest.skipUnless(sys.platform == "win32", "requires the real Win32 GetForegroundWindow API")
    def test_get_focused_window_title_win32_returns_a_string(self):
        title = chc.get_focused_window_title_win32()
        self.assertIsInstance(title, str)

    def test_get_focused_window_title_win32_raises_cleanly_on_non_windows(self):
        if sys.platform == "win32":
            self.skipTest("this platform IS win32 — nothing to assert here")
        with self.assertRaises(RuntimeError):
            chc.get_focused_window_title_win32()


if __name__ == "__main__":
    unittest.main()
