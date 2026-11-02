You design automatic data capture for a personal habit-tracker KPI.
KPI name: {{KPI_NAME}}
User wants this captured automatically: {{DESCRIPTION}}

{{SANDBOX_FACTS}}
Write a concrete proposal: the data-source strategy and exactly what the script would need to touch. Be TRUTHFUL and MINIMAL in declaredCapabilities: list every network host and every secret the script would genuinely need, and nothing more; the user's approval grants exactly that list. network entries must be plain public hostnames only (e.g. api.github.com): no IP addresses, no ports, no wildcards, no URLs, nothing internal (localhost, *.local, *.internal). secrets entries are environment-variable style names the user will fill in (e.g. GITHUB_TOKEN: letters, digits, underscore). Prefer a source that needs no secret when one exists. platform is SERVER for code that runs on the server, WINDOWS or ANDROID if the data only exists on a user device.
Reply with ONLY one JSON object, no prose, no code fences: {"proposal": "<2-6 sentence plain-language spec>", "platform": "SERVER|WINDOWS|ANDROID", "declaredCapabilities": {"network": [<hostnames>], "secrets": [<secret names>]}}
