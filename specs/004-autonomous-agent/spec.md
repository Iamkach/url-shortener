# Spec 004 — QR Code Endpoint (built end to end by the orchestrator's `agent` executor)

**Scenario type:** Well-scoped enhancement, built end to end by the orchestrator driving **Claude
Code** (the `agent` executor) per node — design → implementation → parallel testing + documentation
— with the two human approval gates (`requirements`, `release_readiness`) still real stops. The
agent CLI is swappable by config (`ORCH_AGENT_CMD`); Claude Code is the project default. The feature
is deliberately small and mechanical so each node has concrete engineering to do; the deliverable is
the exported audit trail + metrics as the concrete answer to *"where is the intelligence coming
in?"* — a deployed orchestrator that invokes an agent to read the repo, write code, run `mvn test`,
and commit.

## 1. Requirement Understanding

**Raw ask:** "Let people get a QR code image for a short link."

**Normalized:**
- New endpoint `GET /api/urls/{code}/qr` on `url-shortener-service`.
- Returns `image/png`, HTTP `200`, a QR code that encodes the short URL
  (`{baseUrl}/{code}`), for any existing, resolvable code.
- `404` if the code does not exist. `410 Gone` if the code is soft-expired, consistent with
  the redirect path (spec 003, C3).
- No new persistence, no rate limiting on this read path, no analytics side effect.

**Assumptions:**
- A1 — a small, dependency-light QR library is acceptable (e.g. ZXing `core`); no external
  service call.
- A2 — default image size (~256×256) is fine; no size query parameter in scope.
- A3 — the QR encodes the same absolute short URL the redirect would send, derived from the
  existing base-URL configuration.

## 2. How this scenario is run

Driven through the orchestrator in **autonomous `agent` mode** — each non-gate node spawns
Claude Code (`claude -p`) as its worker, which does real edits + `mvn test` + `git commit`:

```
git switch -c sdlc-run/004-autonomous-agent
cd orchestrator
ORCHESTRATOR_EXECUTOR_MODE=agent mvn spring-boot:run       # needs `claude` logged in (or ANTHROPIC_API_KEY)
# then, via the /sdlc-run skill (or by hand against http://localhost:8081):
POST /runs   { "workflowDefinitionId": "sdlc-autonomous", "createdBy": "redo-004", "autonomous": true,
               "initialContext": { "feature": "qr-endpoint" } }
#  -> requirements lands AWAITING_APPROVAL
POST /runs/{id}/nodes/requirements/approve   { "approver": "...", "artifacts": { "specPath": "specs/004-autonomous-agent/spec.md" } }
#  -> design -> implementation (commits) -> testing || documentation, each run by a `claude -p` child,
#     the two children concurrent on nodeExecutorPool; the PreToolUse hook confines each to its stage paths
#  -> release_readiness lands AWAITING_APPROVAL
POST /runs/{id}/nodes/release_readiness/approve   { "approver": "..." }
GET  /runs/{id}/audit + /metrics   > ../docs/scenario-runs/004-autonomous-agent.json  (hand-assembled like 001-003)
```

The two human approval gates are real stops — an autonomous run does not self-approve.

## 3. Acceptance

- Run reaches `COMPLETED`; `GET /runs/{id}/metrics` counts it.
- The audit trail contains `NODE_COMPLETED` events with `actor: AGENT` whose `rationale`
  carries the agent's own notes for `design` / `implementation` / `testing` / `documentation`,
  and `implementation` records a real `commit` sha.
- `mvn test` stays green after the run (this scenario is a live run, not part of the automated
  suite).
- The `GET /api/urls/{code}/qr` endpoint returns a scannable PNG for a live code and the
  documented `404` / `410` for the error paths.
- Optional swap check: re-run one node with `ORCH_AGENT_CMD=<other-cli>` to show the command is
  not hard-coded.
