# Plan — the orchestrator invokes an agent to do node work (Claude Code as the default executor)

> Supersedes the earlier A–F "redo" plan (executor seam + `llm` mode + autonomous runner), which is
> implemented and green. This plan builds on that seam.

## Context

The Schwab interview flagged that the **deployed orchestrator, during a run, never invokes a
model/agent to do the SDLC work a node represents** — read code, write a design, generate code,
write tests. The only executor path shipped was `manual` / `scripted`, so every node's work product
was hand-fed. INT-A: *"you're basically mocking the response from the agents… where is the
intelligence coming in?"* For a role about *agents that read COBOL and build apps*, an orchestrator
with zero model calls doesn't exercise the core competency.

The first redo pass added an `llm` executor (`LlmNodeExecutor` + `AnthropicChatPort`) that makes a
direct Messages API call per node. That closes the "zero model calls" gap narrowly, but the model
only returns text — it never reads the repo, edits files, or runs tests, so a node's "work" still
isn't real engineering.

**This change:** the executor seam gains an `agent` executor that spawns **Claude Code in headless
mode** as the node's worker — real tools (Read, Edit, Bash), real `mvn test`, real commits — and
consumes its structured result through the *same* `complete`/`fail`/exit-gate path every other
executor uses. It is **agent- and LLM-agnostic**: the agent CLI (`claude` → `codex` → a local
agent) is swappable by config, and the `llm` executor gains an OpenAI-compatible provider (local
Ollama / LM Studio / vLLM). Claude Code is the project default. Governance is unchanged and is now
*enforced on the agent* by an always-on `PreToolUse` hook: inside this repo, product code may only
be changed through an orchestrator run.

**Intended outcome:** one real feature — the QR-code endpoint (`specs/004`) — built end to end by
the orchestrator driving Claude Code (design → implementation → parallel testing + documentation,
two human approval gates), with the audit log + metrics exported as
`docs/scenario-runs/004-autonomous-agent.json` as the concrete rebuttal to "where is the
intelligence coming in?"

## Decisions locked with the user

1. **Live run on a new feature branch** `sdlc-run/004-autonomous-agent`; the agent commits per node
   there; human reviews/merges after.
2. **`PreToolUse` hook is always on.** Any Claude Code session in this repo editing product code
   (`url-shortener-service/src/**`, `orchestrator/src/**`, `specs/**`) must be inside an orchestrator
   run (`ORCH_RUN_ID` set) whose current node's stage permits that path. Docs/meta always exempt:
   `CLAUDE.md`, `README.md`, `.gitignore`, `docs/**/*.md`.
3. **LLM path generalized** — keep Anthropic, add an OpenAI-compatible `ChatPort`, select by
   `orchestrator.executor.llm.provider`.
4. **Live feature = QR-code endpoint** — repurpose `specs/004-autonomous-llm/` →
   `specs/004-autonomous-agent/` (feature unchanged: `GET /api/urls/{code}/qr` PNG, 200/404/410).

## Architecture

### A. New `agent` executor (agent-agnostic) — `orchestrator/.../engine/executor/`

| File | Role |
|---|---|
| `AgentInvocationPort.java` | one-method seam mirroring `ChatPort`: `AgentInvocationResult invoke(AgentInvocationTask task)` — keeps `AgentNodeExecutor` unit-testable with no subprocess. |
| `AgentInvocationTask.java` | record: `runId`, `nodeId`, `StageType stage`, `String prompt`, `List<String> allowedTools`, `List<String> allowedPaths`, `Path workingDir`, `Duration timeout`. |
| `AgentInvocationResult.java` | record: `int exitCode`, `String stdout`, `String stderr`, `boolean timedOut`. |
| `ClaudeCliAgentPort.java` | production impl, `@ConditionalOnProperty(prefix="orchestrator.executor", name="mode", havingValue="agent")`. Renders the process from `ExecutorProperties.Agent` (templated command + args), sets env `ORCH_RUN_ID/ORCH_NODE_ID/ORCH_NODE_STAGE/ORCH_ALLOW_PATHS`, feeds the prompt via stdin or arg, enforces `timeout`, captures stdout. Default command: `claude -p --output-format json --permission-mode acceptEdits --add-dir {repoDir} --allowedTools {allowedTools}`. Inject a `ProcessRunner` seam so tests never spawn. |
| `AgentNodeExecutor.java` | `NodeExecutor`, `ID = "agent"`, same `@ConditionalOnProperty` as above. Per-run call budget (reuse the `LlmNodeExecutor` pattern). Builds the stage prompt, calls the port, pulls the final assistant message out of the `--output-format json` envelope, hands the text to `NodeResultParser`. Non-zero exit / timeout / exception / unparseable → `NodeExecutionResult.fail(...)`. |
| `NodeResultParser.java` | extracted from `LlmNodeExecutor` (`extractJsonObject` + status/artifacts/notes parsing); `llm` and `agent` share exactly one parser. |

**Stage prompts** (in `AgentNodeExecutor`, same spirit as `LlmNodeExecutor.systemPrompt` but
instructing *real work*), e.g. IMPLEMENTATION → "Read `specs/004-autonomous-agent/plan.md` +
`tasks.md`. Make the changes in `url-shortener-service/src/main`. Run
`mvn -pl url-shortener-service test` until green. `git add` + `git commit` on the current branch.
Finish by printing ONLY `{\"status\":\"complete\",\"artifacts\":{\"commit\":\"<sha>\"},\"notes\":\"…\"}`."
Every prompt names its exit-gate artifact key explicitly (existing convention).

**Result hand-back:** child prints the JSON object last; `NodeResultParser` tolerates fences/prose.
`commit` = real `git rev-parse HEAD`; `testReport` = path to a report file the child writes under
`docs/scenario-runs/`; `designPath`/`docsPath` = files it created.

**Conditional-bean note:** like `llm` today, `agent` is a whole-run mode — `@ConditionalOnProperty
(mode=agent)`. `sdlc-autonomous.yaml` nodes therefore drop `executor:` and rely on
`ORCHESTRATOR_EXECUTOR_MODE=agent`. (A per-node `executor: agent` under a different global mode is
out of scope; the registry already falls back to `manual` with a warning.)

### B. `llm` executor made provider-agnostic

- `ExecutorProperties.Llm` gains `provider` (`anthropic` default | `openai-compatible`), `baseUrl`,
  `apiKeyEnv` (default `ANTHROPIC_API_KEY`).
- `AnthropicChatPort` — keep; add `@ConditionalOnProperty(name="orchestrator.executor.llm.provider",
  havingValue="anthropic", matchIfMissing=true)` alongside the existing `mode=llm` guard.
- `OpenAiCompatibleChatPort` — new, `provider=openai-compatible`. Plain `POST
  {baseUrl}/chat/completions` via `java.net.http.HttpClient` (no new dependency), `Authorization:
  Bearer ${apiKeyEnv}`, returns `choices[0].message.content`. Covers Ollama / LM Studio / vLLM /
  Codex-style servers.
- Exactly one `ChatPort` bean active at a time; `LlmNodeExecutor` unchanged (depends on the
  interface).

### C. Registry / definition

- `WorkflowDefinition.KNOWN_EXECUTORS` += `"agent"`.
- `NodeExecutorRegistry` unchanged; add a test for `agent` resolution.

### D. Autonomous pickup + real DAG parallelism (already wired — verify + test)

`NodeDispatchListener` already filters manual/non-autonomous synchronously and submits the rest to
`nodeExecutorPool` (`ThreadPoolTaskExecutor` core 2 / max 4), feeding results back through
`engine.complete/fail`. When `implementation` completes, `dispatchReady` moves **both** `testing`
and `documentation` to `RUNNING` in one pass → two `NodeDispatchedEvent`s → two concurrent
`claude -p` children; the per-run lock serializes only their callbacks.

- Stage allow-paths keep the two children on disjoint trees (`testing` → `src/test/**` +
  `docs/scenario-runs/**`; `documentation` → `docs/**` + `README.md`).
- New `WorkflowEngineAgentParallelTest` (`@SpringBootTest`): a fake `AgentInvocationPort` that
  latches until both `testing` and `documentation` are simultaneously mid-`invoke`, then releases;
  assert both ran, run completes, `requirements`/`release_readiness` still blocked at
  `AWAITING_APPROVAL`.

### E. Governance — always-on `PreToolUse` hook (committed `.claude/`)

- `.claude/settings.json` — `hooks.PreToolUse` matcher `Edit|Write|MultiEdit|NotebookEdit` →
  `command` runs `.claude/hooks/orch_guard.py`. `permissions.allow` += `Bash(mvn:*)`,
  `Bash(git:*)`, `Bash(curl:*)`.
- `.claude/hooks/orch_guard.py` — reads the hook JSON on stdin (`tool_input.file_path`):
  1. Path is always-exempt (`CLAUDE.md`, `README.md`, `.gitignore`, `docs/**/*.md`, root `*.md`) →
     `allow`.
  2. `ORCH_RUN_ID` unset **and** path under a governed tree (`url-shortener-service/src/**`,
     `orchestrator/src/**`, `specs/**`) → `deny` ("governed repo: change `<path>` through an
     orchestrator run — see `docs/executor-seam-walkthrough.md` — or edit docs/meta directly").
  3. `ORCH_RUN_ID` set → path must match `ORCH_ALLOW_PATHS` (globs the executor exported for this
     node's stage) → else `deny` naming the stage + its globs.
  4. Otherwise `allow`.
  Output: `{"hookSpecificOutput":{"hookEventName":"PreToolUse","permissionDecision":"…",
  "permissionDecisionReason":"…"}}`.
- `.claude/hooks/README.md` — what it does, the docs/meta exemption, that it is intentionally
  always-on, how to work alongside it.
- `.claude/agents/sdlc-testing.md`, `.claude/agents/sdlc-documentation.md` — constrained subagent
  profiles (narrow `tools:`) for a human running those stages interactively; the `agent` executor
  passes the equivalent `--allowedTools` itself.

**Stage → allowed write globs** (single source of truth in `application.yml` under
`orchestrator.executor.agent.stage-paths`, read by `AgentNodeExecutor` for `ORCH_ALLOW_PATHS` and
`--allowedTools`):

| Stage | Allowed write globs |
|---|---|
| DESIGN | `specs/**/plan.md`, `specs/**/tasks.md`, `docs/**` |
| IMPLEMENTATION | `url-shortener-service/src/**`, `specs/**/tasks.md` |
| TESTING | `url-shortener-service/src/test/**`, `docs/scenario-runs/**` |
| DOCUMENTATION | `docs/**`, `README.md`, `url-shortener-service/**/*.md` |
| (always) | `CLAUDE.md`, `README.md`, `.gitignore`, `docs/**/*.md` |

REQUIREMENTS / RELEASE_READINESS are human gates — no agent writes.

### F. Thin harness skill (committed `.claude/skills/`)

`.claude/skills/sdlc-run/SKILL.md` + `.claude/skills/sdlc-run/orch.py`. It does **not** do node
work — the orchestrator's `agent` executor does. Its job:
1. Ensure orchestrator up (`cd orchestrator && ORCHESTRATOR_EXECUTOR_MODE=agent mvn spring-boot:run`
   in background; health-poll `GET /workflows`).
2. `git switch -c sdlc-run/004-autonomous-agent` (decision 1).
3. `POST /runs {workflowDefinitionId:"sdlc-autonomous", autonomous:true, createdBy:<user>,
   initialContext:{feature:"qr-endpoint"}}`.
4. Poll `GET /runs/{id}`. On `AWAITING_APPROVAL` → `AskUserQuestion` (approve/reject); on approve
   `POST /approve` (with `artifacts.specPath` for `requirements`); on reject `POST /reject`.
5. On terminal status → `GET /audit` + `/metrics`, hand-assemble
   `docs/scenario-runs/004-autonomous-agent.json` (same shape as 001–003).
6. `## Constraints`: never approve a gate without the user; never edit product code itself; always
   re-derive state from `GET /runs/{id}`.

Frontmatter: `name: sdlc-run`, `description`, `trigger: /sdlc-run`.

### G. Workflow YAML + config

- `sdlc-autonomous.yaml` — drop `executor: llm` from the 4 non-gate nodes (rely on global
  `mode=agent`); keep the two human gates; update the header comment.
- `application.yml` — replace the `executor:` block:

```yaml
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

- `ExecutorProperties` — add the `Agent` nested class + `provider/baseUrl/apiKeyEnv` on `Llm`.

### H. Spec 004 repurpose + live run

- `git mv specs/004-autonomous-llm specs/004-autonomous-agent`; rewrite `spec.md` intro ("built end
  to end by the orchestrator's `agent` executor driving Claude Code, swappable, human-approved at
  the two gates"); feature unchanged; align `plan.md` / `tasks.md` node tags.
- **Live run** (in scope): on `sdlc-run/004-autonomous-agent`, `ORCHESTRATOR_EXECUTOR_MODE=agent`,
  `/sdlc-run`, approve the two gates, agent children build the endpoint + tests + docs, export
  `docs/scenario-runs/004-autonomous-agent.json`. Prereqs: `claude` CLI logged in (or
  `ANTHROPIC_API_KEY`); Maven; real model calls, billed; **not** part of `mvn test`.

### I. Tests (offline — no network, no subprocess) — orchestrator 27 → ~40

| Test | Asserts |
|---|---|
| `NodeResultParserTest` | (moved from `LlmNodeExecutorTest`) fence/prose tolerance, malformed → null, status/artifacts extraction. |
| `AgentNodeExecutorTest` | fake `AgentInvocationPort`: complete/fail parse; non-zero exit → FAIL; `timedOut` → FAIL; budget exhaustion → FAIL; upstream namespaced context in the prompt; stage → allowedTools/paths derivation. |
| `ClaudeCliAgentPortTest` | arg-template rendering (`{repoDir}`/`{allowedTools}`), env vars set (`ORCH_ALLOW_PATHS` …), `prompt-via` stdin vs arg — via a fake `ProcessRunner`, no real process. |
| `OpenAiCompatibleChatPortTest` | against a `com.sun.net.httpserver` stub: request body shape, reads `choices[0].message.content`, bearer token from the configured env var. |
| `ChatPortProviderSelectionTest` | `provider=anthropic` → `AnthropicChatPort`; `openai-compatible` → the other; mutually exclusive. |
| `NodeExecutorRegistryTest` (extend) | node `executor: agent` resolves; unknown still rejected by `validate()`. |
| `WorkflowEngineAgentParallelTest` | latched fake port proves `testing` ∥ `documentation`; human gates still block; run completes; context carries all four artifacts. |
| `OrchGuardHookTest` | shells `.claude/hooks/orch_guard.py` with crafted stdin+env: deny product path when `ORCH_RUN_ID` unset; allow `CLAUDE.md` always; deny out-of-stage path in a run; allow in-stage path. |

Existing 15 core + 12 executor-seam tests unchanged (`manual` default; no `agent`/`llm` beans).

### J. Docs

- `docs/architecture.md` §3.1 / §3.1a — `agent` is the headline executor (orchestrator spawns
  Claude Code, real tools, governed by the hook), `llm` (provider-agnostic) the alt, `manual` the
  default/test mode. New sequence diagram: `dispatchReady → NodeDispatchedEvent → AgentNodeExecutor
  → claude -p (Edit/Bash/mvn, PreToolUse hook) → JSON → engine.complete → exit gate`; show the
  two-child fan-out at `implementation` completion.
- `docs/executor-seam-walkthrough.md` — rewrite §0 and §3 around "the deployed orchestrator invokes
  an agent per node"; document `agent.*` + `llm.provider` knobs; new run recipe with
  `ORCHESTRATOR_EXECUTOR_MODE=agent` + the branch step.
- `README.md` / `CLAUDE.md` — executor table (+`agent`); the committed `.claude/` (always-on hook,
  docs/meta exemption, how to work with it); test counts (~40 / ~96); the `/sdlc-run` skill.
- `INTERVIEW-SCRIPT.md` — rewrite "where's the intelligence coming in?": per-node agent invocation,
  Claude Code by default / any LLM or agent CLI by config, real edits+tests, structured result
  through the same gate; point at `docs/scenario-runs/004-autonomous-agent.json` + the governance
  hook; map onto the COBOL-modernization framing.
- `f:\Job Search\interview-prep\03-track-c2c\companies\charles-schwab\round-schwab-client-redo.md` —
  update script #1 to the agent-executor model + agent/LLM-agnostic config; tighten script #6 with
  the concrete analogue.

## Critical files

| File | Change |
|---|---|
| `orchestrator/.../engine/executor/AgentInvocationPort.java` + `AgentInvocationTask/Result.java` | new seam (mirrors `ChatPort`) |
| `orchestrator/.../engine/executor/ClaudeCliAgentPort.java` | new — spawns `claude -p`; env + timeout + stdout; `ProcessRunner` seam |
| `orchestrator/.../engine/executor/AgentNodeExecutor.java` | new `NodeExecutor` id `agent`; budget; stage prompt; `NodeResultParser` |
| `orchestrator/.../engine/executor/NodeResultParser.java` | extracted from `LlmNodeExecutor`; shared |
| `orchestrator/.../engine/executor/OpenAiCompatibleChatPort.java` | new `ChatPort` for local / OpenAI-style endpoints |
| `orchestrator/.../engine/executor/AnthropicChatPort.java` | provider `@ConditionalOnProperty` |
| `orchestrator/.../engine/executor/ExecutorProperties.java` | `Agent` nested props; `Llm.provider/baseUrl/apiKeyEnv` |
| `orchestrator/.../definition/WorkflowDefinition.java` | `KNOWN_EXECUTORS` += `agent` |
| `orchestrator/src/main/resources/application.yml` | `executor.agent.*`, `executor.llm.provider` |
| `orchestrator/src/main/resources/workflows/sdlc-autonomous.yaml` | non-gate nodes rely on global `mode=agent` |
| `orchestrator/src/test/java/.../engine/executor/*` + `engine/WorkflowEngineAgentParallelTest.java` | ~13 new/moved tests |
| `.claude/settings.json` | `PreToolUse` hook + `permissions.allow` |
| `.claude/hooks/orch_guard.py` + `README.md` | always-on edit-governance hook |
| `.claude/agents/sdlc-testing.md`, `sdlc-documentation.md` | constrained stage profiles |
| `.claude/skills/sdlc-run/SKILL.md` + `orch.py` | thin harness: start run, gate prompts, evidence export |
| `specs/004-autonomous-agent/*` (git mv from `004-autonomous-llm`) | repurposed spec |
| `docs/scenario-runs/004-autonomous-agent.json` | new — live-run evidence |
| `docs/architecture.md`, `docs/executor-seam-walkthrough.md`, `README.md`, `CLAUDE.md`, `INTERVIEW-SCRIPT.md` | reframe around the agent executor |
| `f:\Job Search\...\round-schwab-client-redo.md` | prep scripts #1 / #6 |

## Reuse (don't reinvent)

- `NodeExecutor` / `NodeExecutionRequest` / `NodeExecutionResult` — the `agent` executor implements
  the existing seam; **no new engine code**.
- `NodeDispatchListener` + `nodeExecutorPool` — autonomous pickup + bounded concurrency already
  handle the parallel fan-out; only add a test.
- `engine.complete` / `engine.fail` / `PolicyEngine` exit gates / retry / fallback / rollback /
  `MetricsService` — untouched; the agent result flows through them identically.
- `LlmNodeExecutor.extractJsonObject` + parse → extract to `NodeResultParser`, don't duplicate.
- `@ConditionalOnProperty` gating (from `LlmNodeExecutor` / `AnthropicChatPort`) — reuse for `agent`
  + provider selection so `manual` still boots with nothing extra.
- `ScheduleWakeup` / `AskUserQuestion` — the harness skill's poll-and-gate loop.

## Verification

1. **Offline build:** `mvn test` → existing 27 orchestrator + 56 service unchanged, + ~13 new
   orchestrator tests green; no network, no subprocess. ~96 total.
2. **Boots in default mode:** `cd orchestrator && mvn spring-boot:run` with no env → `GET /workflows`
   lists `sdlc-standard` + `sdlc-autonomous`; no `claude` CLI or API key needed.
3. **Hook is real:** with no `ORCH_RUN_ID`, an edit to `url-shortener-service/src/main/...` is
   blocked with the governance reason; an edit to `README.md` is allowed.
4. **Parallelism (offline):** `WorkflowEngineAgentParallelTest` proves `testing` ∥ `documentation`
   via the latched fake port.
5. **Provider swap (offline):** `orchestrator.executor.llm.provider=openai-compatible` + a stub
   server → `OpenAiCompatibleChatPortTest` green; Anthropic bean absent.
6. **Live agent run (in scope, manual, billed):**
   - `git switch -c sdlc-run/004-autonomous-agent`
   - `cd orchestrator && ORCHESTRATOR_EXECUTOR_MODE=agent mvn spring-boot:run`
   - `/sdlc-run` → approve `requirements` (supply `specPath`) and `release_readiness` when prompted
   - orchestrator spawns `claude -p` for design → implementation (commits) → testing ∥ documentation
     (two concurrent children)
   - result: new `GET /api/urls/{code}/qr` endpoint + tests + docs committed on the branch;
     `mvn -pl url-shortener-service test` green
   - `GET /runs/{id}/audit` shows `Actor.AGENT` `NODE_COMPLETED` with child `notes`; export
     `docs/scenario-runs/004-autonomous-agent.json`
   - optional swap check: re-run one node with `ORCH_AGENT_CMD=<other-cli>` to show the command is
     not hard-coded.
