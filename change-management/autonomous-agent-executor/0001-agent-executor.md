# 0001 — Agent executor: the orchestrator invokes Claude Code per node

**Status:** implementation PRs open (stacked on `main`); the live end-to-end run is the last
step and is not yet done.
**Scope:** `orchestrator/` only. Zero changes to `url-shortener-service/` product code or to
the engine core (`WorkflowEngine`, `PolicyEngine`, `MetricsService`, domain entities).

---

## 1. Summary

The orchestrator is a DAG/state-machine SDLC engine: it walks
`requirements → design → implementation → {testing, documentation} → release_readiness`,
enforcing gates, approvals, retry/fallback/rollback, an audit trail and metrics. Its design
principle is *controlled autonomy* — the engine coordinates, it never does a node's work
itself.

Before this change, the only way a node's work reached the engine was an external REST
callback (`POST /runs/{id}/nodes/{nodeId}/complete`) — a human or a script hand-fed every
artifact. **The deployed orchestrator, during a run, never invoked a model or an agent to
actually do a node's work.**

This change adds an **`agent` executor** that spawns **Claude Code** (`claude -p`, headless)
as the worker for each non-gate node: it reads the repo, edits files, runs `mvn test`, and
`git commit`s, then returns a structured result that flows back through the *same*
`complete`/`fail` + gate path every other executor uses. It also generalises the existing
direct-model path to be provider-agnostic, and adds an always-on edit-governance hook.

Nothing about governance changes. `manual` stays the default, so the deterministic,
network-free test suite is untouched.

---

## 2. Background — why this was done

The change was prompted by feedback on a technical interview take-home. The reviewer's core
criticism, in their words:

> *"you're basically mocking the response from the agents… where is the intelligence coming
> in?"*

For a role centred on **agents that read legacy code and build applications**, an
orchestration engine that makes **zero model calls during a run** doesn't exercise the core
competency. The three shipped scenarios (`specs/001`–`003`) went through the pipeline
*structurally* — real runs, real gates, real audit logs — but the "agent work" at each node
was simulated.

An earlier iteration (see PR #1, "Pluggable executor seam") added the seam and an `llm`
executor that makes one Anthropic Messages API call per node. That narrowly closed the "zero
model calls" gap, but the model only returns *text* — it never reads the repo, edits a file,
or runs a test. A node's "work" still wasn't real engineering.

**This change makes "built through the orchestrator" literally true**: one real feature (a
QR-code endpoint, `specs/004-autonomous-agent/`) built end to end by the orchestrator driving
Claude Code, with the exported audit log + metrics as the concrete answer to *"where is the
intelligence coming in?"*

---

## 3. What changed

### 3.1 The `agent` executor (primary)

- `AgentNodeExecutor` (`id = "agent"`) builds a stage-specific prompt instructing *real work*
  (e.g. IMPLEMENTATION: read the plan, edit `url-shortener-service/src/main`, `mvn test` until
  green, `git commit`, print the commit sha), calls `AgentInvocationPort`, and parses the
  child's result JSON with the shared `NodeResultParser`.
- `AgentInvocationPort` is a one-method seam (mirrors the existing `ChatPort`) so the executor
  is unit-testable with **no subprocess**.
- `ClaudeCliAgentPort` is the production implementation: it renders a process from config
  (`command` + `argsTemplate` with `{repoDir}` / `{allowedTools}` placeholders), exports
  `ORCH_RUN_ID` / `ORCH_NODE_ID` / `ORCH_NODE_STAGE` / `ORCH_ALLOW_PATHS`, feeds the prompt on
  stdin, and runs it through a `ProcessRunner` seam (`DefaultProcessRunner` = real
  `ProcessBuilder` with stream draining + a hard timeout that force-kills the process tree).
- **Agent-agnostic:** the CLI is `ORCH_AGENT_CMD` — `claude` by default, swappable to `codex`
  or a local agent with no code change.
- Every failure mode (non-zero exit, timeout, thrown exception, unparseable output) degrades
  to a normal `fail(...)`, so the engine's retry/fallback/rollback ladder stays in charge.
  A per-run agent-call budget bounds cost.

### 3.2 `llm` executor made provider-agnostic ("keep the API option open")

- `orchestrator.executor.llm.provider` selects the `ChatPort`: `anthropic` (default,
  `AnthropicChatPort`) or `openai-compatible` (`OpenAiCompatibleChatPort` — plain
  `POST {baseUrl}/chat/completions`, `Authorization: Bearer ${apiKeyEnv}`; covers Ollama /
  LM Studio / vLLM / hosted OpenAI-style servers). No new dependency.
- `ChatPortConfig` guarantees exactly one `ChatPort` bean, and none outside `mode=llm`.

### 3.3 Always-on edit governance (`.claude/`, committed)

- `.claude/hooks/orch_guard.py` — a `PreToolUse` hook on `Edit|Write|MultiEdit|NotebookEdit`.
  Docs/meta (`CLAUDE.md`, `README.md`, `.gitignore`, `docs/**/*.md`, root `*.md`) always
  allowed; edits under `url-shortener-service/src/**`, `orchestrator/src/**`, `specs/**`
  **denied** unless `ORCH_RUN_ID` is set and the path matches the node stage's
  `ORCH_ALLOW_PATHS`. This enforces the converse of controlled autonomy: a Claude Code session
  in this repo can't change product code *except* from inside an orchestrator run.
- `.claude/agents/sdlc-testing.md`, `sdlc-documentation.md` — constrained stage profiles for a
  human running those stages by hand.
- `.claude/skills/sdlc-run/` — a thin harness (`SKILL.md` + `orch.py`) that starts an
  autonomous run and relays the two approval gates to a human. It does **not** do node work.

### 3.4 Spec 004 repurposed

`specs/004-autonomous-llm/ → specs/004-autonomous-agent/`. Feature unchanged
(`GET /api/urls/{code}/qr` → PNG, `200`/`404`/`410`); framing moved from "an `llm` call per
node" to "the orchestrator drives Claude Code per node".

### 3.5 Not touched

The engine core, all product code, and PR #1's `manual` / `llm` / autonomous behaviour and
tests. All new beans are `@ConditionalOnProperty` — the default `manual` boot instantiates
none of them, needs no `claude` CLI and no API key.

---

## 4. Design decisions

| Decision | Alternatives considered | Why |
|---|---|---|
| Claude Code (`claude -p`) is the **primary** executor; direct API is the fallback | Only a direct API call per node | The API path returns text; it can't read the repo, edit files, or run tests. Spawning Claude Code makes a node's work *real engineering*, which is the whole point of the criticism this addresses. |
| `agent` reuses the existing `NodeExecutor` seam — **no new engine code** | A dedicated agent-execution subsystem | Governance (gates, retry, fallback, rollback, audit, metrics) then applies identically and for free; the agent result is just another `complete`/`fail`. |
| Agent- and LLM-agnostic by config (`ORCH_AGENT_CMD`, `llm.provider`) | Hard-code `claude` / Anthropic | The interview was about agent capability in general; a swappable CLI + an OpenAI-compatible provider prove the design isn't vendor-locked and support a fully local setup. |
| `manual` stays the default; `agent`/`llm` are `@ConditionalOnProperty` | Make `agent` the default for `sdlc-autonomous` | Keeps the 15 core engine tests deterministic and network-free; `mvn test` needs no CLI, no key, no subprocess. |
| Governance enforced *on the agent* by an always-on `PreToolUse` hook | Trust the agent's prompt / a reviewable-after-the-fact audit | A hook that a session could switch off would defeat the "orchestrator is the only path to product code" guarantee. |
| Everything except the live run is offline and in `mvn test` | One big live-run PR | Each slice is independently reviewable and leaves the build green; the billed, non-deterministic live run is isolated as the final step. |

Locked with the requester up front: live run on a dedicated branch
`sdlc-run/004-autonomous-agent`; `PreToolUse` hook always on; LLM path generalised
(Anthropic + OpenAI-compatible); live feature = the QR-code endpoint.

---

## 5. Rollout — the stacked PRs

Implemented as a stack of small PRs, each green on `mvn test`, each building on the last.

| PR | Branch | Phase | Contents | Tests |
|---|---|---|---|---|
| #1 | `feature/executor-seam-llm-autonomous` | (pre-plan) | The pluggable `NodeExecutor` seam: `manual` / `llm` executors + autonomous runner | 27 orch |
| #2 | `feature/agent-executor` | 1 | Extract shared `NodeResultParser`; `ExecutorProperties` gains `llm.provider` + the `agent` block; `KNOWN_EXECUTORS += agent` | 33 orch |
| #3 | `feature/agent-executor-phase2` | 2 | `agent` executor: `AgentInvocationPort` / `Task` / `Result`, `ProcessRunner` + `DefaultProcessRunner`, `ClaudeCliAgentPort`, `AgentNodeExecutor` | 48 orch |
| #4 | `feature/agent-executor-phase3` | 3 | Provider-agnostic `llm`: `OpenAiCompatibleChatPort`, `ChatPortConfig`, lazy Anthropic client | 56 orch |
| #5 | `feature/agent-executor-phase4` | 4 | `WorkflowEngineAgentParallelTest` — latched fake port proves `testing ∥ documentation` concurrency; human gates still block | 57 orch |
| #6 | `feature/agent-executor-phase5` | 5 | Always-on `PreToolUse` hook + committed `.claude/` (hook, agent profiles, `/sdlc-run` skill); `sdlc-autonomous.yaml` drops per-node `executor:` | 61 orch |
| #8 | `feature/agent-executor-phase6` | 6 | `git mv specs/004-autonomous-llm → 004-autonomous-agent`; spec/plan/tasks reframed | 61 orch |
| #9 | `feature/agent-executor-phase7` | 7 | Docs reframed around the agent executor (`architecture.md`, `executor-seam-walkthrough.md`, `README.md`, `CLAUDE.md`, testing/summary docs) | 61 orch |
| — | this change record + README auth section | — | `change-management/`, README "Execution modes & authentication" | 61 orch |

Final total: **117 tests** (61 orchestrator + 56 url-shortener-service), all offline.

### Compatibility notes for reviewers

- The stack is based on PR #1. Phases 3 and 5 edit `AnthropicChatPort.java` and
  `sdlc-autonomous.yaml` (files PR #1 created), so **if PR #1 is revised in review the stack
  needs a rebase**. Nothing is broken; it's the normal coupling of a stacked feature.
- **Manual step:** `.claude/settings.json` (the `PreToolUse` matcher + a `Bash(mvn|git|curl)`
  allowlist) is not committed — the authoring environment blocked writing it. The hook script
  is committed and directly tested (`OrchGuardHookTest`); it is inert until that file is added
  by hand. Content is in PR #5's description.
- `INTERVIEW-SCRIPT.md` is gitignored (personal prep) and is updated locally only.

---

## 6. How to run it

### Primary path — Claude Code (`agent` mode)

```bash
git switch -c sdlc-run/004-autonomous-agent
cd orchestrator
ORCHESTRATOR_EXECUTOR_MODE=agent mvn spring-boot:run
```

Prerequisite: the **`claude` CLI installed and logged in**. A Claude subscription login
(`claude login`) is sufficient — **the orchestrator never reads an API key in this mode**; it
launches `claude -p` as a subprocess and that subprocess uses the CLI's own auth. (If
`ANTHROPIC_API_KEY` is set in the shell, the `claude` CLI will switch itself to API billing —
leave it unset to stay on your subscription.)

Then, via the `/sdlc-run` skill or by hand:

```bash
# start an autonomous run
curl -XPOST localhost:8081/runs -H 'Content-Type: application/json' -d '{
  "workflowDefinitionId":"sdlc-autonomous","createdBy":"you","autonomous":true,
  "initialContext":{"feature":"qr-endpoint"}}'                                  # -> runId

# approve the first human gate
curl -XPOST localhost:8081/runs/{runId}/nodes/requirements/approve \
  -H 'Content-Type: application/json' \
  -d '{"approver":"you","rationale":"ok","artifacts":{"specPath":"specs/004-autonomous-agent/spec.md"}}'

# design -> implementation (commits) -> testing || documentation run as claude -p children
curl localhost:8081/runs/{runId}/audit          # NODE_COMPLETED, actor AGENT, child notes

# approve the release gate
curl -XPOST localhost:8081/runs/{runId}/nodes/release_readiness/approve \
  -H 'Content-Type: application/json' -d '{"approver":"you","rationale":"ship it"}'

# evidence
curl localhost:8081/runs/{runId}/audit    > docs/scenario-runs/004-autonomous-agent.json
curl localhost:8081/runs/{runId}/metrics
```

### Fallback path — direct API (`llm` mode)

```bash
cd orchestrator
ANTHROPIC_API_KEY=sk-ant-... ORCHESTRATOR_EXECUTOR_MODE=llm mvn spring-boot:run
# or a local server:
ORCH_LLM_PROVIDER=openai-compatible ORCH_LLM_BASE_URL=http://localhost:11434/v1 \
  ORCHESTRATOR_EXECUTOR_MODE=llm mvn spring-boot:run
```

This path **does** need `ANTHROPIC_API_KEY` (or the OpenAI-compatible URL + key) because the
orchestrator makes the model call itself — no CLI in the loop. It returns text only; it does
not touch the repo.

### Default — no change

```bash
cd orchestrator && mvn spring-boot:run     # manual mode; GET /workflows still works, no key needed
```

---

## 7. What's deferred

The **live run** — it needs `claude` logged in, makes real (billed) calls, is not part of
`mvn test`, and produces `docs/scenario-runs/004-autonomous-agent.json`. Everything else is
committed, tested, and green.
