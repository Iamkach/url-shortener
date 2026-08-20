# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this repo is

Two independently deployable Spring Boot services in one Maven multi-module repo, built for an
"Agentic-Proficient Software Engineer" assignment:

- **`orchestrator`** (port 8081) — a DAG/state-machine agentic SDLC orchestration engine. This is
  the part the assignment actually evaluates ("the URL shortener is the payload/vehicle").
- **`url-shortener-service`** (port 8080) — the product built *through* the orchestrator, across
  three specs (`specs/001-*`, `specs/002-*`, `specs/003-*`).

Both are stateless except for an in-memory H2 database (no persistence across restarts), and are
independently runnable and independently tested.

## Commands

Build/test everything from the repo root:
```bash
mvn test
```
Runs both modules: 15 tests in `orchestrator`, 56 in `url-shortener-service` (71 total).

Run a single module's tests:
```bash
mvn -pl orchestrator test
mvn -pl url-shortener-service test
```

Run a single test class/method (from within that module's directory):
```bash
cd url-shortener-service && mvn test -Dtest=UrlShortenerServiceTest
cd url-shortener-service && mvn test -Dtest=UrlShortenerServiceTest#someMethod
```

Run each service (must `cd` into the module first — running `spring-boot:run` from the repo root
with `-pl <module> -am` resolves the goal against the parent aggregator pom and fails with
"Unable to find a suitable main class"):
```bash
cd orchestrator && mvn spring-boot:run
# -> http://localhost:8081  (GET /workflows to see loaded definitions)

cd url-shortener-service && mvn spring-boot:run
# -> http://localhost:8080  (Swagger UI at /swagger-ui.html, H2 console at /h2-console)
```

## Repository layout

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

## Architecture: orchestrator

```
definition/   WorkflowDefinition, NodeDefinition (YAML-loaded, static, validated at startup
              for cycles/dangling deps via Kahn's algorithm), WorkflowDefinitionRegistry
domain/       WorkflowRunEntity, NodeExecutionEntity, AuditEventEntity (JPA — mutable
              runtime state + append-only audit trail)
engine/       WorkflowEngine (all orchestration logic), exceptions
policy/       PolicyEngine (gate DSL: requireContext / requireArtifact / denyIfContext),
              evaluated on every node's entry and exit
metrics/      MetricsService (success rate, retry/rollback frequency, MTTR, latency — all
              derived from the audit trail, nothing tracked separately)
api/          REST controllers + DTOs (the only way to interact with a run)
repository/   Spring Data JPA repositories
```

Workflow definitions live in `orchestrator/src/main/resources/workflows/*.yaml`
(`sdlc-standard.yaml` is the DAG used by all three scenarios: `requirements` (human gate) →
`design` → `implementation` → {`testing`, `documentation`} in parallel → `release_readiness`
(human gate)).

**Core principle — controlled autonomy:** the engine coordinates, it never executes. A node's
actual work (writing a spec, code, tests, docs) happens outside the JVM, by a human or an agent;
`WorkflowEngine` only decides *when* a node may run, tracks its state, and enforces governance via
`complete`/`fail`/`approve`/`reject` calls on its REST API. Never wire the engine up to call out to
do work directly — that inverts the design the whole test suite is built around.

**State machine behaviors to know before touching `WorkflowEngine`:**
- **Retry**: bounded by `maxRetries`; `fail()` moves the node to `RETRYING` and immediately
  redispatches while under budget.
- **Fallback**: a node can name a `fallbackNodeId`. It only becomes dispatch-eligible once its
  *primary* has actually `FAILED` (`isFallbackAwaitingTrigger`) — never just because its own
  `dependsOn` is satisfied. Getting this wrong was a real bug caught by
  `WorkflowEngineDiamondTest` (see `docs/architecture.md` §6); treat that test as a regression
  guard for this specific invariant.
- **Rollback**: on unrecoverable failure (retries exhausted with no fallback, approval rejection,
  or policy violation), `rollbackAndFailRun` walks completed `compensation: true` nodes in reverse
  and marks them `ROLLED_BACK`.
- **Dynamic re-planning**: `POST /runs/{id}/nodes/{nodeId}/invalidate` (only legal on a
  `COMPLETED` node) computes the transitive downstream set via BFS over `directDependents`, marks
  it `STALE`, strips those nodes' namespaced context (`nodeId.artifactKey`), and re-dispatches.
  Nodes outside that set — including already-completed sibling branches — must stay untouched.
- **Policy gates** (`entryGate`/`exitGate` in the YAML) are evaluated by `PolicyEngine` before a
  node starts or completes. A violation is a governance stop: immediate `FAILED`, no retry, no
  fallback, straight to rollback.

Full rationale and a sequence diagram of one end-to-end scenario run are in
`docs/architecture.md`.

## Architecture: url-shortener-service

```
domain/       ShortUrl, ClickEvent (JPA)
repository/   ShortUrlRepository, ClickEventRepository
service/      UrlShortenerService, Base62Codec, UrlValidator, ClickRecordingService, RateLimiter
api/          UrlController, RedirectController, AnalyticsController, GlobalExceptionHandler
config/       RateLimitInterceptor, WebMvcConfig
```

Built incrementally across the three specs, each an additive layer on the last (see
`specs/*/plan.md` §"Codebase Reasoning" in specs 002/003 for the explicit brownfield impact
analysis):
- **001** (greenfield): `ShortUrl`, sequence-derived Base62 codes, create/resolve/metadata
  endpoints.
- **002** (brownfield): `ClickEvent` — deliberately *not* a JPA relation to `ShortUrl`, to keep
  hot-path click writes off the read-heavy `ShortUrl` row — async click recording, in-memory
  token-bucket rate limiter on the write path (`POST /api/urls`) only.
- **003** (ambiguous, resolved with a human via `AskUserQuestion` before design): optional
  `customAlias` (validated against `[a-zA-Z0-9_-]{1,64}`, collision-checked → `409`,
  reserved-word-blocked → `400`), soft-expire enforcement (`expiresAt` checked only on the
  redirect path, returns `410 Gone`, row and analytics stay readable via the metadata endpoint).

### API

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/urls` | Shorten a URL. Body: `{"longUrl", "expiresAt"?, "customAlias"?}`. Rate-limited |
| `GET` | `/api/urls/{code}` | Metadata for a short code (no redirect); `200` even if expired (soft-expire) |
| `GET` | `/api/urls/{code}/analytics` | `{"shortCode", "totalClicks", "lastAccessedAt"}` |
| `GET` | `/{code}` | `302` redirect; `410 Gone` if expired; records a click async, off the response path |

Known, documented limitation: the rate limiter is in-memory and per-instance (resets on restart,
not shared across instances) — acceptable for this prototype, not a gap to silently "fix" without
discussion (`specs/002-*/plan.md` §4).

## Spec-driven workflow

Each feature under `specs/NNN-*/` has `spec.md` (requirements, including any ambiguity-resolution
writeup), `plan.md` (design + codebase-impact reasoning for brownfield changes), and `tasks.md`.
When extending `url-shortener-service`, check whether a relevant spec already documents the
decision (e.g. why click events aren't a JPA relation, why short codes are sequence-derived rather
than random) before re-deriving it — `docs/architecture.md` §6 has a table of these decisions and
the alternatives considered.
