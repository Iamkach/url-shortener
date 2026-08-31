# Executor seam — a from-scratch walkthrough

A beginner-oriented explanation of the pluggable `NodeExecutor` / autonomous-run work:
what the repo is, what was asked, what got built, and how to use it.

---

## 0. The assignment framing (read this first)

The take-home has **two layers**, and it matters which one is the deliverable:

1. **Build the orchestrator** — an agentic SDLC engine. *This is the part that is graded.*
2. **Build the URL shortener *through* the orchestrator** — as features `specs/001-*`, `002-*`,
   `003-*`, each one driven through the pipeline rather than hand-coded directly. The shortener is
   "the payload / vehicle" — proof the orchestrator can actually carry real work.

The repo already reflects this:

| Piece | Role |
| --- | --- |
| `orchestrator/` | the engine — the graded deliverable |
| `url-shortener-service/` | the product built through it |
| `specs/001-*`, `002-*`, `003-*` | one spec per feature; each was run through the orchestrator |
| `docs/scenario-runs/001…003` | exported audit logs proving each feature went `requirements → design → implementation → {testing, documentation} → release_readiness` |

**Where the gap was.** Those three runs went through the orchestrator *structurally*, but at each
node the "agent work" (write the design, write the code) was **simulated** — a human or script
POSTed the artifact back via `/complete`. The orchestrator drove the process; it never called a
model to do a node's work. The interviewers pushed on exactly this: "where is the intelligence
coming in?"

**What the executor-seam work adds.** It makes "built through the orchestrator" literally true for
a new feature. A node can now name `executor: llm`, and on an `autonomous: true` run the engine
calls a real model to produce that node's artifact — no human callback. `specs/004-autonomous-llm/`
is the demonstration: a small additional URL-shortener feature (a QR-code endpoint) built by a
fully autonomous orchestrator run, with `docs/scenario-runs/004-autonomous-llm.json` as evidence.

So this is **not a separate task** — it closes the loop on the original one. Spec 004 is the URL
shortener being extended *through the orchestrator* again, this time with a real agent doing each
node's work instead of a stand-in. Everything below is the machinery that makes that possible.

---

## 1. Background — what this repo is

Two separate Spring Boot apps in one Maven repo:

| Module | Port | What it is |
| --- | --- | --- |
| `orchestrator` | 8081 | An engine that runs a software-delivery pipeline as a **DAG / state machine**: `requirements → design → implementation → {testing, documentation} → release_readiness`. Each box is a "node." |
| `url-shortener-service` | 8080 | A normal URL shortener. It's just the *thing being built* by the pipeline — the interesting code is the orchestrator. |

The orchestrator's design philosophy is **"controlled autonomy"**: the engine decides *when* a
node may run, tracks its state, enforces approval gates and policy rules, and handles retry /
rollback. It does **not** do the node's actual work (writing the design doc, writing code, etc.).

Originally, the "work" arrived one way only: the engine set a node to `RUNNING`, and then
**something outside** (a human, a script, curl) had to POST back
`/runs/{id}/nodes/{nodeId}/complete` with the resulting artifacts. There was no AI anywhere in it.

---

## 2. What was asked

This came out of feedback on a job-interview take-home. The interviewers' main criticism: for a
role about *AI agents that read old code and build apps*, the orchestrator **never makes a single
model call**. It looked risk-averse on the core skill. Plus several verbal-answer gaps (concurrency
story, identity resolution, authorization model).

The request, in two parts:

1. **Code:** add a real, opt-in AI path — a model call that plugs into the existing pipeline —
   *without* breaking the deterministic, network-free test suite or making AI the default.
2. **Prep:** write out the missing interview talk-tracks.

The plan was approved, then implemented.

---

## 3. What is implemented

### The core idea: a pluggable "executor" seam

A new interface sits between "engine dispatches a node" and "engine hears back":

```text
NodeExecutor  →  execute(request)  →  returns COMPLETE + artifacts,  or  FAIL + reason
```

Three implementations:

| Executor | When it runs | What it does |
| --- | --- | --- |
| **`manual`** | **default, always** | No-op. Engine waits for the external REST callback, exactly like before. All 15 original tests use this. |
| **`scripted`** | tests only | Returns canned artifacts. Lets the autonomous flow be tested with zero network. |
| **`llm`** | only when `mode=llm` + an API key is present | `LlmNodeExecutor` builds a prompt from the node's stage + upstream context, calls the **real Anthropic Messages API** via `AnthropicChatPort`, parses the JSON response into artifacts. |

Key guarantee: whichever executor runs, the result is fed **back through the same
`engine.complete()` / `engine.fail()` methods** a human would have called. So policy gates,
approval gates, retry, fallback, rollback, the audit log, and metrics are **identical in every
mode**. The AI cannot bypass governance.

### How an "autonomous" run works

- New `autonomous: true` flag on a run.
- When the engine dispatches a non-`manual` node, it fires a Spring event. After the transaction
  commits, `NodeDispatchListener` picks it up, runs the executor on a background thread pool, and
  feeds the result back.
- Human approval gates (`requirements`, `release_readiness`) **still block** and wait for a real
  person to POST `/approve`. Autonomy stops at governance.
- Parallel branches (`testing` + `documentation`) run their model calls concurrently; the per-run
  lock serializes their callbacks.

### Files (all currently uncommitted)

- **New engine code:** `orchestrator/src/main/java/.../engine/executor/` (11 files — the interface,
  request/result records, 3 executors, registry, dispatch listener, config, Anthropic adapter)
- **Touched:** `WorkflowEngine` (fires the event), `NodeDefinition` (+`executor:` field),
  `WorkflowDefinition` (validates it), `WorkflowRunEntity` / `StartRunRequest` / `RunController`
  (+`autonomous` flag), `pom.xml` (+`anthropic-java` dependency), `application.yml`
  (+`orchestrator.executor.*` config)
- **New workflow:** `orchestrator/src/main/resources/workflows/sdlc-autonomous.yaml` — same DAG as
  `sdlc-standard`, but the 4 non-gate stages say `executor: llm`
- **New tests (+12, total 83):** `NodeExecutorRegistryTest`, `LlmNodeExecutorTest`,
  `WorkflowEngineAutonomousTest` + `ScriptedNodeExecutor` helper + `test-autonomous.yaml`
- **Docs reframed:** `architecture.md`, `README.md`, `CLAUDE.md`, `INTERVIEW-SCRIPT.md`,
  `engineering-summary.md`, `testing-and-tradeoffs.md`
- **Scaffolded, not run:** `specs/004-autonomous-llm/` (a QR-code endpoint feature, meant to be
  built *by* an autonomous LLM run as evidence)
- **Interview prep doc** (outside this repo):
  `f:\Job Search\...\charles-schwab\round-schwab-client-redo.md`

---

## 4. How to use it

### Run the existing tests (no API key, no network)

```bash
mvn test
```

83 green. The orchestrator is still `manual` by default — nothing changed for existing behavior.

### Run the orchestrator normally (manual mode, unchanged)

```bash
cd orchestrator && mvn spring-boot:run
# GET http://localhost:8081/workflows  → now lists BOTH sdlc-standard and sdlc-autonomous
```

Drive a run by hand exactly as before: `POST /runs`, then
`POST /runs/{id}/nodes/{nodeId}/complete` / `/approve`.

### Run a real autonomous AI pipeline (needs a key, costs a few cents)

```bash
cd orchestrator
ANTHROPIC_API_KEY=sk-ant-...  \
ORCHESTRATOR_EXECUTOR_MODE=llm  \
ORCHESTRATOR_LLM_MODEL=claude-sonnet-5  \
mvn spring-boot:run
```

Then:

```bash
# 1. start an autonomous run of the LLM-wired workflow
curl -XPOST localhost:8081/runs -H 'Content-Type: application/json' -d '{
  "workflowDefinitionId": "sdlc-autonomous",
  "createdBy": "you",
  "autonomous": true,
  "initialContext": {}
}'
# → returns a runId

# 2. the run auto-stops at the first human gate (requirements). Approve it:
curl -XPOST localhost:8081/runs/{runId}/nodes/requirements/approve \
  -H 'Content-Type: application/json' \
  -d '{"approver":"you","rationale":"looks good","artifacts":{"specPath":"specs/004-autonomous-llm/spec.md"}}'

# 3. design → implementation → testing → documentation now run themselves via real model calls.
#    Watch progress:
curl localhost:8081/runs/{runId}
curl localhost:8081/runs/{runId}/audit     # NODE_COMPLETED events carry the model's notes

# 4. approve the final gate:
curl -XPOST localhost:8081/runs/{runId}/nodes/release_readiness/approve \
  -H 'Content-Type: application/json' -d '{"approver":"you","rationale":"ship it"}'

# 5. save evidence:
curl localhost:8081/runs/{runId}/audit   > docs/scenario-runs/004-autonomous-llm.json
curl localhost:8081/runs/{runId}/metrics
```

### Turn on the LLM for just one node instead of globally

In any workflow YAML, add `executor: llm` (or `scripted`) to a single node. That overrides the
global `mode`. Unknown values are rejected at startup.

### Relevant config (`application.yml`)

```yaml
orchestrator:
  executor:
    mode: ${ORCHESTRATOR_EXECUTOR_MODE:manual}     # manual (default) | llm
    llm:
      model: ${ORCHESTRATOR_LLM_MODEL:claude-opus-5}  # claude-sonnet-5 for a cheaper demo
      max-model-calls-per-run: 12                      # budget; exceeding it fails the node
      max-output-tokens: 4096
```

---

## 5. What's left

Only the **live run** (Workstream D) — it needs a real `ANTHROPIC_API_KEY` and produces
`docs/scenario-runs/004-autonomous-llm.json`. Everything else is done, tested, and green.
Nothing is committed to git yet.
