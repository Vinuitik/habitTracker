You are mapping the repository in your current working directory so that later agents can navigate it without re-reading everything. You can read files and launch subagents. You cannot run shell commands, so do not try to use Bash; use the file listing, search and read tools only. Do not modify any file.

Work in four steps.

## 1. Survey and split into subsystems

List the repository (skip `.git`, `node_modules`, `target`, `build`, `dist`, `vendor`, virtualenvs, generated and binary files). Read the top-level README / CLAUDE.md / build files if present. Split the code into 3 to 12 subsystems: cohesive areas a developer would change together (a backend module, a frontend area, an infra/deploy folder, a background worker, ...). Every source folder should belong to exactly one subsystem. For each subsystem decide its `name` (short, human) and `paths` (repo-relative folders or files, forward slashes).

## 2. One subagent per subsystem, in parallel

Launch ONE subagent per subsystem, all in the same turn so they run in parallel. Use model `haiku` for small or simple subsystems and `sonnet` for large or intricate ones; never a larger model. Give each subagent its subsystem name, its paths, the list of the other subsystems (name + paths) and these instructions:

> Read the files under your paths (skim large ones; read entry points, controllers, services, config fully). Do not modify anything. Return ONLY markdown notes in this exact style:
>
> - First line: `Files: A.java, b.py, ...` (the files that matter, basenames)
> - One `##` section per flow. Each flow is an arrow chain: `Entry (route/command/event) → ClassName.method() → OtherClass.method() → storage/external call`. Use real class and method names.
> - After any non-obvious step, a line `To change X: ClassName.method()` (or the env var / config key).
> - Mention every external service, env var and secret name the code uses.
> - Mark anything stubbed, TODO or missing as `[NOT IMPLEMENTED]`.
> - Where this subsystem calls or is called by another subsystem, say so with its name: `→ [other subsystem name]`.
> - End with `## Change Index`: a markdown table `| What | Where |`, one row per thing a developer might want to change, pointing at the exact class/method/env var.
> - No prose paragraphs, no restating code. Stay under about 300 lines.

## 3. Synthesize

When all subagents have returned, write an `index` in markdown:
- `# <repo name> map`, then one line per subsystem: `- **name** (paths) — one-sentence purpose`.
- `## Cross-subsystem links`: arrow chains for the main end-to-end flows that cross subsystems (e.g. `Web UI → API controller → service → DB`), naming the subsystems.
- `## Entry points`: how the app is started / deployed, main config files.

## 4. Commit SHA

Read `.git/HEAD`. If it contains `ref: refs/heads/<branch>`, read `.git/refs/heads/<branch>`; if that file does not exist, search `.git/packed-refs` for the line ending in `refs/heads/<branch>` and take its first word. If HEAD holds a 40-hex SHA directly, use it. If `.git` is missing, use an empty string.

## Output

Your final message must be ONLY one JSON object, with no code fence and no text before or after it:

{"commitSha": "<40-hex or empty>", "index": "<markdown from step 3>", "subsystems": [{"name": "<name>", "paths": ["<path>", "..."], "notes": "<markdown notes from that subagent>"}]}

Escape newlines and quotes correctly so the object parses as JSON. Include every subsystem, in the order of the index.
