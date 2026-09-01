# `.claude/hooks/` — always-on edit governance

## `orch_guard.py`

A `PreToolUse` hook wired in `.claude/settings.json` for `Edit | Write | MultiEdit | NotebookEdit`.
It enforces the repo's one rule: **product code changes only through an orchestrator run.**

### Decision table

| Situation | Decision |
|---|---|
| Path is docs/meta — `CLAUDE.md`, `README.md`, `.gitignore`, `docs/**/*.md`, any root `*.md` | **allow** (always) |
| `ORCH_RUN_ID` unset **and** path under `url-shortener-service/src/**`, `orchestrator/src/**`, or `specs/**` | **deny** — "change it through an orchestrator run" |
| `ORCH_RUN_ID` unset and path elsewhere | allow |
| `ORCH_RUN_ID` set and path matches this node's `ORCH_ALLOW_PATHS` globs | allow |
| `ORCH_RUN_ID` set and path outside `ORCH_ALLOW_PATHS` | **deny** — names the stage and its allowed globs |

`ORCH_RUN_ID`, `ORCH_NODE_STAGE`, and `ORCH_ALLOW_PATHS` are exported by the orchestrator's
`agent` executor (`ClaudeCliAgentPort`) when it spawns Claude Code as a node's worker. A human
running a stage by hand can export the same vars to work inside the guard.

### It is intentionally always on

This is not a toggle. The point of the orchestrator is that governance (policy gates, approval
gates, retry/rollback, audit) is the *only* path to product code. A hook that a session could
switch off would defeat that. If you need to hand-edit product code, either:

- edit docs/meta instead (always allowed), or
- start an orchestrator run and let the `agent` executor make the change under a node's stage, or
- for a genuine break-glass fix, export `ORCH_RUN_ID=manual-override` +
  `ORCH_ALLOW_PATHS=<glob>` in your shell and note why in the commit.

### Working alongside it

- `mvn test`, `git`, and `curl` are pre-allowed in `.claude/settings.json` so a stage agent can
  build, commit, and call the orchestrator API without prompts.
- The hook only inspects `tool_input.file_path`; `Bash` edits (`sed`, `>`) are not covered — don't
  route around the guard with them.
- Full rationale: `docs/executor-seam-walkthrough.md`.
