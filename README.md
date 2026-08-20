# URL Shortener — Agentic SDLC System

Built for the "Agentic-Proficient Software Engineer" assignment. The payload is a URL
shortener; the thing actually being demonstrated is the **orchestration layer** (see
`docs/architecture.md`) that coordinated building it across three spec-driven scenarios.

## Repository Layout

```
orchestrator/            DAG/state-machine SDLC orchestration engine (port 8081)
url-shortener-service/   The URL shortener product (port 8080)
specs/                   spec.md / plan.md / tasks.md per feature, spec-driven
docs/
  architecture.md          components, orchestration model, control flow, key decisions
  engineering-summary.md   plan/rationale, risks, assumptions, limitations
  testing-and-tradeoffs.md testing approach, limitations, trade-offs
  scenario-runs/           exported orchestrator audit logs + metrics, one per scenario
```

## Prerequisites

- Java 17 (JDK)
- Maven 3.9+

## Build & Test Everything

```bash
mvn test
```

Runs both modules: 15 tests in `orchestrator`, 56 in `url-shortener-service` (71 total).

## Run

Each module is an independent Spring Boot app:

```bash
# Terminal 1 — the orchestration engine
mvn -pl orchestrator spring-boot:run
# -> http://localhost:8081  (GET /workflows to see loaded definitions)

# Terminal 2 — the URL shortener product
mvn -pl url-shortener-service spring-boot:run
# -> http://localhost:8080  (Swagger UI at /swagger-ui.html)
```

Each has its own in-memory H2 database (no persistence across restarts) and its own
`README.md` with API details (`url-shortener-service/README.md`).

## Driving a Scenario Through the Orchestrator

The three feature specs (`specs/001-*`, `specs/002-*`, `specs/003-*`) were each built by
starting a real `sdlc-standard` run against the orchestrator and reporting each stage's
completion via its REST API — not just documented as a process. Example:

```bash
curl -X POST http://localhost:8081/runs -H 'Content-Type: application/json' \
  -d '{"workflowDefinitionId":"sdlc-standard","initialContext":{},"createdBy":"you"}'
# -> note the returned run id

curl -X POST http://localhost:8081/runs/{runId}/nodes/requirements/approve \
  -H 'Content-Type: application/json' \
  -d '{"approver":"you","rationale":"...","artifacts":{"specPath":"..."}}'

# design/implementation/testing/documentation report via .../complete
# release_readiness is a second human-approval gate via .../approve

curl http://localhost:8081/runs/{runId}/audit     # full audit trail
curl http://localhost:8081/runs/{runId}/metrics   # per-run reliability metrics
curl http://localhost:8081/metrics                # aggregate across all runs
```

`docs/scenario-runs/*.json` holds the actual exported run/audit-log/metrics from each of
the three scenarios below, as evidence rather than a claim.

## The Three Scenarios

| Spec | Type | What it demonstrates |
|---|---|---|
| `specs/001-core-url-shortener` | Greenfield | New system built from an explicit requirement-normalization pass (5 documented assumptions), through the full orchestrator DAG including its two human-approval gates |
| `specs/002-click-analytics-ratelimit` | Brownfield | Enhancement to 001's codebase with an explicit impacted-module table (spec.md §2) before any code changed |
| `specs/003-custom-alias-expiry` | Ambiguous | A deliberately underspecified ask; 3 real clarifying questions were put to a human via `AskUserQuestion` *before* design started, and the resolutions are traceable through spec → plan → implementation |

## Testing Approach, Limitations & Trade-offs

See `docs/testing-and-tradeoffs.md`.

## Engineering Summary

See `docs/engineering-summary.md` for the plan/rationale, risks/trade-offs, assumptions,
and limitations across the whole system.
