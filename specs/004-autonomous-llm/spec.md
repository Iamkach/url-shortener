# Spec 004 — QR Code Endpoint (Autonomous LLM-driven orchestrator run)

**Scenario type:** Well-scoped enhancement, executed through the orchestrator's **autonomous
`llm` execution mode** instead of a human driving the REST API by hand. The feature is
deliberately small and mechanical so the model has concrete code to produce at each stage;
the point of this scenario is to exercise `LlmNodeExecutor` end to end and export the
resulting audit trail as evidence that the engine's executor seam works with a real model.

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

Driven through the orchestrator in **autonomous mode** against the real Anthropic API:

```
cd orchestrator
ANTHROPIC_API_KEY=... ORCHESTRATOR_EXECUTOR_MODE=llm ORCHESTRATOR_LLM_MODEL=claude-sonnet-5 \
  mvn spring-boot:run
# then, against http://localhost:8081
POST /runs   { "workflowDefinitionId": "sdlc-autonomous", "createdBy": "redo-004", "autonomous": true }
#  -> requirements lands AWAITING_APPROVAL
POST /runs/{id}/nodes/requirements/approve   { "approver": "...", "artifacts": { "specPath": "specs/004-autonomous-llm/spec.md" } }
#  -> design, implementation, testing, documentation each run via LlmNodeExecutor with no further callbacks
#  -> release_readiness lands AWAITING_APPROVAL
POST /runs/{id}/nodes/release_readiness/approve   { "approver": "..." }
GET  /runs/{id}/audit    > ../docs/scenario-runs/004-autonomous-llm.json (audit + nodes + metrics, hand-assembled like 001-003)
```

The two human approval gates are real stops — an autonomous run does not self-approve.

## 3. Acceptance

- Run reaches `COMPLETED`; `GET /runs/{id}/metrics` counts it.
- The audit trail contains `NODE_COMPLETED` events with `actor: AGENT` whose `rationale`
  carries the model's own notes for `design` / `implementation` / `testing` / `documentation`.
- `mvn test` stays green (this scenario is a live run, not part of the automated suite).
- The `GET /api/urls/{code}/qr` endpoint returns a scannable PNG for a live code and the
  documented `404` / `410` for the error paths.
