# Executor seam — a from-scratch walkthrough

A beginner-oriented explanation of the pluggable `NodeExecutor` work: what the repo is, what was
asked, what got built, and how to use it.

---

## 0. The assignment framing (read this first)

The take-home has **two layers**, and it matters which one is the deliverable:

1. **Build the orchestrator** — an agentic SDLC engine. *This is the part that is graded.*
2. **Build the URL shortener *through* the orchestrator** — as features `specs/001-*`, `002-*`,
   `003-*`, each one driven through the pipeline rather than hand-coded directly. The shortener is
   "the payload / vehicle" — proof the orchestrator can actually carry real work.

**Where the gap was.** Scenarios 001–003 went through the orchestrator *structurally*, but at each
node the "agent work" (write the design, write the code, run the tests) was **simulated** — a human
or script POSTed the artifact back via `/complete`. The deployed orchestrator, during a run, never
invoked a model or an agent to *do* a node's work. The interviewers pushed on exactly this: *"you're
basically mocking the response from the agents… where is the intelligence coming in?"*

**What this work adds — an executor that invokes a real agent per node.** A node's work now reaches
it through a pluggable `NodeExecutor`, chosen globally by `orchestrator.executor.mode`:

| Mode | What runs a node's work |
|---|---|
| **`manual`** (default) | nothing — the engine waits for an external REST callback, exactly like 001–003. All core engine tests use this: zero network, deterministic. |
| **`scripted`** | canned artifacts, tests only. |
| **`llm`** | one Messages API call per node (`LlmNodeExecutor`). Provider-agnostic: Anthropic by default, or any OpenAI-compatible `/chat/completions` server. The model returns text only — it never touches the repo. |
| **`agent`** | **spawns Claude Code headless (`claude -p`) as the node's worker** — real tools (Read, Edit, Bash), real `mvn test`, real `git commit`. Agent-agnostic: the CLI is `ORCH_AGENT_CMD` (→ `codex`, a local agent). This is the project's answer to "where is the intelligence coming in?" |

Whichever executor runs, its result is fed **back through the same `engine.complete()` /
`engine.fail()`** a human would have called. Policy gates, approval gates, retry, fallback,
rollback, the audit log, and metrics are **identical in every mode**. The agent cannot bypass
governance — and an always-on `PreToolUse` hook (`.claude/hooks/orch_guard.py`) enforces the
converse: inside this repo, product code may only be changed from within an orchestrator run.

**Spec 004** (`specs/004-autonomous-agent/`, a QR-code endpoint) is the demonstration: one real
feature built end to end by the orchestrator driving Claude Code, with
`docs/scenario-runs/004-autonomous-agent.json` as evidence.

---

## 1. Background — what this repo is

Two separate Spring Boot apps in one Maven repo:

| Module | Port | What it is |
| --- | --- | --- |
| `orchestrator` | 8081 | An engine that runs a software-delivery pipeline as a **DAG / state machine**: `requirements → design → implementation → {testing, documentation} → release_readiness`. Each box is a "node." |
| `url-shortener-service` | 8080 | A normal URL shortener — the *thing being built* by the pipeline. |

The orchestrator's design philosophy is **"controlled autonomy"**: the engine's core decides *when*
a node may run, tracks its state, enforces approval gates and policy rules, and handles
retry / rollback. It does **not** do the node's actual work — that reaches the node through a
`NodeExecutor`.

---

## 2. What was asked

Feedback on the interview take-home: for a role about *AI agents that read old code and build
apps*, the deployed orchestrator **never invokes a model or agent** to do a node's work. The
request:

1. **Code:** make the deployed orchestrator actually invoke an agent per node — real repo reads,
   real edits, real tests — *without* breaking the deterministic, network-free test suite or making
   AI the default. Keep it agent- and LLM-agnostic.
2. **Prep:** write out the missing interview talk-tracks.

---

## 3. What is implemented

### The seam

```text
NodeExecutor  →  execute(request)  →  returns COMPLETE + artifacts,  or  FAIL + reason
```

`request` carries the node definition (stage, gates) plus a read-only snapshot of the run's
accumulated, namespaced context (`nodeId.artifactKey`), so an executor can see upstream artifacts.

### The `agent` executor (the headline)

- `AgentNodeExecutor` (`id = "agent"`) builds a **stage prompt** that instructs *real work* —
  e.g. IMPLEMENTATION: *"Read `specs/004-autonomous-agent/plan.md` + `tasks.md`. Make the changes
  in `url-shortener-service/src/main`. Run `mvn -pl url-shortener-service test` until green.
  `git add` + `git commit`. Finish by printing ONLY `{"status":"complete","artifacts":{"commit":"<sha>"},"notes":"…"}`."*
- It calls `AgentInvocationPort` (a one-method seam mirroring `ChatPort`, so the executor is
  unit-testable with no subprocess). The production impl `ClaudeCliAgentPort` renders a process
  from config and runs it via a `ProcessRunner` seam:

  ```
  claude -p --output-format json --permission-mode acceptEdits --add-dir <repoDir> --allowedTools <tools>
  ```

  with env `ORCH_RUN_ID` / `ORCH_NODE_ID` / `ORCH_NODE_STAGE` / `ORCH_ALLOW_PATHS` exported for the
  governance hook, the stage prompt on stdin, and a hard timeout.
- The child prints a result JSON object last; `NodeResultParser` (shared with `llm`) tolerates
  fences/prose and pulls out `status` / `artifacts` / `notes`. Non-zero exit, timeout, exception,
  or unparseable output all become a normal `fail(...)` — the engine's retry/rollback ladder stays
  in charge.
- Per-run agent-call budget (`max-agent-calls-per-run`), same pattern as `LlmNodeExecutor`.

### `llm` is provider-agnostic

`orchestrator.executor.llm.provider` selects the `ChatPort`: `anthropic` (default,
`AnthropicChatPort`) or `openai-compatible` (`OpenAiCompatibleChatPort` — plain
`POST {baseUrl}/chat/completions`, `Authorization: Bearer ${apiKeyEnv}`; covers Ollama / LM Studio /
vLLM). Exactly one `ChatPort` bean is active; `LlmNodeExecutor` depends only on the interface.

### How an "autonomous" run works

- `autonomous: true` on `POST /runs`.
- When the engine dispatches a non-`manual` node it fires a Spring event. After the transaction
  commits, `NodeDispatchListener` runs the executor on a bounded pool (`nodeExecutorPool`, core 2 /
  max 4) and feeds the result back.
- When `implementation` completes, `dispatchReady` moves **both** `testing` and `documentation` to
  `RUNNING` in one pass → two `claude -p` children run concurrently, confined by their stage's
  `ORCH_ALLOW_PATHS` to disjoint trees; the per-run lock serializes only their callbacks.
- Human approval gates (`requirements`, `release_readiness`) **still block**. Autonomy stops at
  governance.

### Governance hook (`.claude/`, committed)

- `.claude/hooks/orch_guard.py` — a `PreToolUse` hook (wired in `.claude/settings.json`) on
  `Edit | Write | MultiEdit | NotebookEdit`. Docs/meta (`CLAUDE.md`, `README.md`, `.gitignore`,
  `docs/**/*.md`, root `*.md`) always allowed; edits under `url-shortener-service/src/**`,
  `orchestrator/src/**`, `specs/**` **denied** unless `ORCH_RUN_ID` is set and the path matches the
  node stage's `ORCH_ALLOW_PATHS`.
- `.claude/agents/sdlc-testing.md`, `sdlc-documentation.md` — constrained stage profiles for a human
  running those stages by hand.
- `.claude/skills/sdlc-run/` — a thin harness (`SKILL.md` + `orch.py`) that starts a run, relays
  the two approval gates via `AskUserQuestion`, and exports the evidence JSON. It does **not** do
  node work.

### Config (`application.yml`)

```yaml
orchestrator:
  executor:
    mode: ${ORCHESTRATOR_EXECUTOR_MODE:manual}   # manual | scripted | llm | agent
    agent:
      command: ${ORCH_AGENT_CMD:claude}
      args-template: >-
        ${ORCH_AGENT_ARGS:-p --output-format json --permission-mode acceptEdits
        --add-dir {repoDir} --allowedTools {allowedTools}}
      prompt-via: ${ORCH_AGENT_PROMPT_VIA:stdin}   # stdin | arg
      working-dir: ${ORCH_AGENT_WORKDIR:..}
      timeout-seconds: ${ORCH_AGENT_TIMEOUT:900}
      max-agent-calls-per-run: 12
      stage-paths:
        DESIGN: "specs/**/plan.md,specs/**/tasks.md,docs/**"
        IMPLEMENTATION: "url-shortener-service/src/**,specs/**/tasks.md"
        TESTING: "url-shortener-service/src/test/**,docs/scenario-runs/**"
        DOCUMENTATION: "docs/**,README.md,url-shortener-service/**/*.md"
    llm:
      provider: ${ORCH_LLM_PROVIDER:anthropic}     # anthropic | openai-compatible
      base-url: ${ORCH_LLM_BASE_URL:}
      api-key-env: ${ORCH_LLM_KEY_ENV:ANTHROPIC_API_KEY}
      model: ${ORCHESTRATOR_LLM_MODEL:claude-opus-5}
      max-model-calls-per-run: 12
      max-output-tokens: 4096
```

`agent` and the two `ChatPort`s are `@ConditionalOnProperty` — in the default `manual` boot none of
them are instantiated, so no `claude` CLI or API key is needed for `mvn test` or a normal run.

---

## 4. How to use it

### Run the tests (no API key, no network, no subprocess)

```bash
mvn test          # 117 green: 61 orchestrator, 56 url-shortener-service
```

`OrchGuardHookTest` shells the real hook script; it skips itself if no `python` is on the box.

### Run the orchestrator normally (manual mode, unchanged)

```bash
cd orchestrator && mvn spring-boot:run
# GET http://localhost:8081/workflows  → lists sdlc-standard AND sdlc-autonomous
```

### Run a real autonomous agent pipeline (needs `claude` logged in; billed)

```bash
git switch -c sdlc-run/004-autonomous-agent
cd orchestrator
ORCHESTRATOR_EXECUTOR_MODE=agent mvn spring-boot:run
```

Then, via the `/sdlc-run` skill (or by hand):

```bash
# 1. start an autonomous run
curl -XPOST localhost:8081/runs -H 'Content-Type: application/json' -d '{
  "workflowDefinitionId": "sdlc-autonomous", "createdBy": "you", "autonomous": true,
  "initialContext": { "feature": "qr-endpoint" }
}'                                             # → runId

# 2. approve the first human gate
curl -XPOST localhost:8081/runs/{runId}/nodes/requirements/approve \
  -H 'Content-Type: application/json' \
  -d '{"approver":"you","rationale":"looks good","artifacts":{"specPath":"specs/004-autonomous-agent/spec.md"}}'

# 3. design → implementation (commits) → testing ∥ documentation now run themselves,
#    each a `claude -p` child. Watch:
curl localhost:8081/runs/{runId}
curl localhost:8081/runs/{runId}/audit         # NODE_COMPLETED, actor AGENT, child notes

# 4. approve the final gate
curl -XPOST localhost:8081/runs/{runId}/nodes/release_readiness/approve \
  -H 'Content-Type: application/json' -d '{"approver":"you","rationale":"ship it"}'

# 5. evidence
curl localhost:8081/runs/{runId}/audit    > docs/scenario-runs/004-autonomous-agent.json
curl localhost:8081/runs/{runId}/metrics
```

### Swap the agent CLI or the LLM provider

```bash
ORCH_AGENT_CMD=codex ORCHESTRATOR_EXECUTOR_MODE=agent mvn spring-boot:run          # different agent
ORCH_LLM_PROVIDER=openai-compatible ORCH_LLM_BASE_URL=http://localhost:11434/v1 \
  ORCHESTRATOR_EXECUTOR_MODE=llm mvn spring-boot:run                               # local model
```

---

## 5. What's left

Only the **live run** — it needs `claude` logged in (or an `ANTHROPIC_API_KEY`), makes real,
billed model calls, and produces `docs/scenario-runs/004-autonomous-agent.json`. It is not part of
`mvn test`. Everything else is committed, tested, and green.
