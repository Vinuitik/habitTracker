# Prompt for the Windows runner's Claude: KPI Prototype 1

Paste everything below the line into a Relay chat on the Windows machine. Run it after ActivityWatch has collected about a day of data.

---

You're running a prototype for the habitTracker KPI-sensors design. The spec is "Prototype 1" in docs/designs/kpi-sensors.md of the habitTracker repo; if that repo isn't here, follow this prompt alone. Work in a new folder `%USERPROFILE%\kpi-proto\`. Don't install anything without asking me first. Report back in under 20 lines.

1. **Check ActivityWatch.** Run `curl http://localhost:5600/api/0/buckets`. If it isn't running, tell me how to install it (activitywatch.net plus the browser extension) and stop.
2. **Snapshot.** Export yesterday's events (local midnight to midnight) from the `aw-watcher-window_*`, `aw-watcher-web-*` and `aw-watcher-afk_*` buckets into `inputs\aw.json`.
3. **Write the collector.** Write `collector.py`. Rules:
   - Python stdlib only.
   - Usage: `python collector.py --inputs DIR --from ISO --to ISO`.
   - It reads only DIR.
   - It prints JSON `{"value": minutes, "unit": "min", "evidence": [up to 20 rows: title/url, minutes]}`.
   - Metric: minutes of YouTube watched while not AFK. Count web events with a youtube.com URL and the browser in focus. Subtract AFK overlap. Don't double count.
4. **Test it on fixtures.** Write `fixtures\` with 2 cases: an empty day → 0, and a hand-made day with a known answer. Run them and show pass/fail.
5. **Run it sandboxed.**
   - **If `wsl` exists:** inside WSL, run `bwrap --unshare-all --ro-bind /usr /usr` (plus the lib symlinks) `--ro-bind <inputs> /in` and the collector.
   - **Otherwise:** tell me, and run it unsandboxed with `--inputs` on the copy only.
   - **Either way:** prove the network is blocked inside the sandbox. A `urllib` call to example.com must fail.
6. **Report.** Give me:
   - the value and the top 5 evidence rows
   - the fixture results
   - which sandbox was used, and whether the network was blocked
   - anything about the ActivityWatch data shape that surprised you

   I'll compare the value with my own estimate.
