Write a RUNNER collector for this approved KPI capture plan. It runs on the user's own computer inside a sandbox. Implement ONLY what the plan approves.
KPI name: {{KPI_NAME}}
User request: {{DESCRIPTION}}
Approved plan: {{PROPOSAL}}
Approved source types: {{SOURCES}}

Rules (a hub check rejects anything else):
- NO network. python 3 STANDARD LIBRARY ONLY (json, datetime, os, pathlib, sqlite3, csv, re, collections, argparse, ...). No socket, urllib.request, http, ssl, subprocess, ctypes, importlib, __import__, exec, eval.
- Invoked as: python3 collector.py --inputs /in --from <ISO-8601 with offset> --to <ISO-8601 with offset>. Count only what falls in [from, to).
- Inputs are read-only snapshots the runner prepared; nothing else on the machine is visible:
  - source {"type":"activitywatch","buckets":["window","web","afk"]} (list only the buckets you need) → /in/aw/<bucket>.json = JSON array of ActivityWatch events [{"timestamp": "<ISO UTC>", "duration": <seconds>, "data": {...}}]. window data: {"app","title"}; web data: {"url","title","audible","incognito","tabCount"}; afk data: {"status": "afk"|"not-afk"}. A file may be missing or [] when there was no activity.
  - source {"type":"file","glob":"<absolute or ~/ path glob, no **>","why":"<one sentence>"} → copies under /in/files/<source index>/<path below the glob's fixed directory>. Never glob secrets (.ssh, .env, keyrings, browser Cookies / Login Data / key4.db / logins.json ...).
- Print ONE line of JSON on stdout: {"value": <number>, "unit": "<short unit, e.g. min, count>", "evidence": [{"label": "<what>", "value": "<how much>"}, ...]} with up to 20 evidence rows that explain the number (largest first). An empty or missing input means value 0, not an error.
- manifest: {"schedule": "daily 00:10", "window": "1d", "sources": [...], "unit": "<unit>", "aggregation": "sum"|"last"|"max"}. Use schedule "daily HH:MM" with window "1d" only.
- fixtures: at least 2 cases under fixtures/<case>/ with inputs/... files (same layout as /in) and expected.json = {"value": <number>, "tolerance"?: <number>, "from"?: "<ISO>", "to"?: "<ISO>"} (without from/to every event in the fixture counts). One case MUST be an empty day (no events / empty files) expecting 0. Keep fixtures small and realistic.
{{PREVIOUS_FAILURE}}Reply with ONLY one JSON object, no prose, no code fences: {"manifest": {...}, "files": {"collector.py": "<full python source>", "fixtures/<case>/inputs/aw/window.json": "<file text>", "fixtures/<case>/expected.json": "<file text>", ...}}
