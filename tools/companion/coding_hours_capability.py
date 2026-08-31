#!/usr/bin/env python3
"""
HabitTracker companion — M9 reference push capability: coding-hours via active-window tracking.

Windows-only (uses GetForegroundWindow/GetWindowTextW). Polls the currently-focused window title
on a timer, accumulates time spent on windows matching a configurable list of "coding" substrings
(VS Code, JetBrains IDEs, terminals, ...), and writes the accumulated hours into the user's Drive
mailbox as a `kind: "kpi-value"` payload — using mailbox_writer.write_kpi_value(), the ONLY
companion function this script calls. It deliberately never imports pair.py directly and never
reads the local pairing config: mailbox_writer.py is the scoped seam that owns the raw Drive
refresh token (see that module's docstring for the risk-posture note on what this boundary does
and does not guarantee).

This is a HAND-WRITTEN reference capability (M9), not one produced by the generation pipeline
(M7/M8, not yet built) — see docs/designs/kpi-tracking-agent.md.

Window-detection logic (get_focused_window_title_win32) and accumulation logic (TimeAccumulator,
run_tracking_loop) are separated so the accumulation logic can be unit-tested on any platform by
injecting a fake "get focused window" callable — see test_coding_hours_capability.py. Only the
actual Win32 call is skipped on non-Windows platforms.

Usage (see README.md for the Task Scheduler setup this is meant to run under):
    python coding_hours_capability.py --run-seconds 28800 --kpi-name "Coding Hours"
"""
import argparse
import sys
import time
from datetime import datetime
from pathlib import Path

# The ONLY companion import in this module. mailbox_writer owns the raw Drive refresh token —
# this script never sees it, never imports pair.py, and never reads the local config file.
from mailbox_writer import write_kpi_value

if sys.platform == "win32":
    import ctypes

DEFAULT_CONFIG_PATH = Path(__file__).parent / "coding_hours_config.json"
DEFAULT_POLL_INTERVAL_SECONDS = 30
# If the gap between two consecutive polls exceeds this, treat it as idle/sleep/lock and don't
# credit it to either bucket — avoids counting hours while the laptop was asleep or the process
# was suspended.
DEFAULT_MAX_GAP_SECONDS = 120


# ── Config ───────────────────────────────────────────────────────────────────────────────────

def load_coding_window_patterns(config_path=DEFAULT_CONFIG_PATH):
    """Reads the small user-editable JSON config of window-title substrings that count as
    'coding'. Returns them lower-cased for case-insensitive matching."""
    config_path = Path(config_path)
    if not config_path.exists():
        raise FileNotFoundError(f"Coding-hours config not found at {config_path} — see README.md")
    import json
    data = json.loads(config_path.read_text())
    return [p.lower() for p in data.get("codingWindowSubstrings", [])]


def is_coding_window(window_title, patterns):
    """True if window_title contains any of patterns (case-insensitive substring match)."""
    if not window_title:
        return False
    lowered = window_title.lower()
    return any(p in lowered for p in patterns)


# ── Accumulation logic (pure, platform-independent, fully unit-testable) ───────────────────────

class TimeAccumulator:
    """Consumes a stream of (timestamp_seconds, window_title) samples and sums seconds spent on
    coding vs. non-coding windows. The interval BETWEEN two consecutive samples is credited to
    whichever bucket the FIRST sample's window belongs to (poll-and-credit-forward) — standard
    for a fixed-interval sampler. A gap larger than max_gap_seconds is treated as idle/sleep and
    contributes to neither bucket."""

    def __init__(self, patterns, max_gap_seconds=DEFAULT_MAX_GAP_SECONDS):
        self.patterns = patterns
        self.max_gap_seconds = max_gap_seconds
        self.coding_seconds = 0.0
        self.other_seconds = 0.0
        self._last_ts = None
        self._last_title = None

    def add_sample(self, timestamp, window_title):
        if self._last_ts is not None:
            gap = timestamp - self._last_ts
            if 0 < gap <= self.max_gap_seconds:
                if is_coding_window(self._last_title, self.patterns):
                    self.coding_seconds += gap
                else:
                    self.other_seconds += gap
            # gap <= 0 (clock went backwards / duplicate sample) or gap > max_gap_seconds
            # (idle/sleep/suspended) contributes nothing to either bucket.
        self._last_ts = timestamp
        self._last_title = window_title

    @property
    def coding_hours(self):
        return self.coding_seconds / 3600.0

    @property
    def total_tracked_hours(self):
        return (self.coding_seconds + self.other_seconds) / 3600.0


# ── Window detection (Windows-only real implementation) ────────────────────────────────────────

def get_focused_window_title_win32():
    """Real Win32 call: GetForegroundWindow + GetWindowTextW via ctypes. Standard approach, no
    special OS permissions needed. Only ever exercised on win32 — tests inject a fake poller
    instead of calling this on Linux/macOS dev boxes."""
    if sys.platform != "win32":
        raise RuntimeError("get_focused_window_title_win32() only works on Windows")
    user32 = ctypes.windll.user32
    hwnd = user32.GetForegroundWindow()
    length = user32.GetWindowTextLengthW(hwnd)
    buf = ctypes.create_unicode_buffer(length + 1)
    user32.GetWindowTextW(hwnd, buf, length + 1)
    return buf.value


# ── Tracking loop (dependency-injected for testability) ────────────────────────────────────────

def run_tracking_loop(get_window_title, patterns, poll_interval_seconds=DEFAULT_POLL_INTERVAL_SECONDS,
                       stop_after_seconds=None, sleep_fn=time.sleep, now_fn=time.time,
                       on_flush=None, flush_interval_seconds=None):
    """Polls get_window_title() every poll_interval_seconds and accumulates into a
    TimeAccumulator. Runs until stop_after_seconds elapses (None = run until the process is
    killed, e.g. by Task Scheduler at end of day). If on_flush and flush_interval_seconds are
    given, on_flush(accumulator) is called periodically (e.g. to write a running total to the
    mailbox every couple of hours, reducing data loss if the process dies before the final
    write) — see main()'s --flush-interval-seconds.

    get_window_title, sleep_fn, and now_fn are all injectable so this loop is fully testable
    with a fake clock and a fake window poller, with no real timing or Win32 calls involved.
    """
    accumulator = TimeAccumulator(patterns)
    start = now_fn()
    last_flush = start
    while stop_after_seconds is None or (now_fn() - start) < stop_after_seconds:
        title = get_window_title()
        accumulator.add_sample(now_fn(), title)
        if on_flush and flush_interval_seconds and (now_fn() - last_flush) >= flush_interval_seconds:
            on_flush(accumulator)
            last_flush = now_fn()
        sleep_fn(poll_interval_seconds)
    return accumulator


def write_end_of_day_value(accumulator, kpi_name, date_str, timeout_seconds=20):
    """Calls the scoped wrapper — the ONLY place this script touches the mailbox-write path."""
    return write_kpi_value(kpi_name, date_str, round(accumulator.coding_hours, 2),
                            timeout_seconds=timeout_seconds)


# ── CLI ──────────────────────────────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--kpi-name", default="Coding Hours",
                         help="Exact KPI name as configured in the web app (must already exist)")
    parser.add_argument("--config", default=str(DEFAULT_CONFIG_PATH),
                         help="Path to the coding-window-substrings JSON config")
    parser.add_argument("--poll-interval", type=int, default=DEFAULT_POLL_INTERVAL_SECONDS,
                         help="Seconds between focused-window polls")
    parser.add_argument("--run-seconds", type=int, default=None,
                         help="Stop after this many seconds (omit to run until killed — e.g. "
                              "scheduled to start each morning and stopped by Task Scheduler at "
                              "a fixed end-of-day time)")
    parser.add_argument("--flush-interval-seconds", type=int, default=None,
                         help="If set, writes a running total to the mailbox this often in "
                              "addition to the final write, so a crash doesn't lose the whole "
                              "day's data")
    args = parser.parse_args()

    if sys.platform != "win32":
        print("coding_hours_capability.py only runs on Windows (uses GetForegroundWindow).",
              file=sys.stderr)
        sys.exit(1)

    patterns = load_coding_window_patterns(args.config)

    def flush(acc):
        today = datetime.now().strftime("%Y-%m-%d")
        try:
            write_end_of_day_value(acc, args.kpi_name, today)
            print(f"[flush] wrote {acc.coding_hours:.2f} coding hours for {today}")
        except (RuntimeError, TimeoutError) as exc:
            print(f"[flush] mailbox write failed (will retry at next flush/exit): {exc}", file=sys.stderr)

    accumulator = run_tracking_loop(
        get_focused_window_title_win32, patterns,
        poll_interval_seconds=args.poll_interval,
        stop_after_seconds=args.run_seconds,
        on_flush=flush if args.flush_interval_seconds else None,
        flush_interval_seconds=args.flush_interval_seconds,
    )

    today = datetime.now().strftime("%Y-%m-%d")
    write_end_of_day_value(accumulator, args.kpi_name, today)
    print(f"Wrote {accumulator.coding_hours:.2f} coding hours for {today} "
          f"(of {accumulator.total_tracked_hours:.2f} tracked hours).")


if __name__ == "__main__":
    main()
