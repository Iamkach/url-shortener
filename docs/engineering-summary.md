# Final Engineering Summary

## 1. Plan & Rationale

The assignment states plainly that the URL shortener is the vehicle and the
orchestration layer is what's graded. The build order followed from that: the
orchestrator was designed and fully tested (15 core governance tests, in isolation, against
synthetic fixtures) **before** any URL-shortener feature work started, so the engine's
governance guarantees (parallel sync, retry/fallback/rollback, safe-stop, re-plan, policy
gates, audit trail, metrics) were proven independent of whatever the product ended up
needing. A later addition — a pluggable `NodeExecutor` seam (`docs/architecture.md` §3.1) with an
`agent` executor that spawns Claude Code (`claude -p`) as the node's worker, a
provider-agnostic `llm` executor, an autonomous run mode, and an always-on `PreToolUse`
edit-governance hook — added 46 more executor tests without changing that core; all beans are
`@ConditionalOnProperty` and `manual` execution stays the default, so the governance suite
stays deterministic and network-free.

Everything after that followed a **spec-driven** loop, repeated for each of the three
required scenario types:

1. Write `spec.md` — normalize the raw ask, surface and resolve ambiguities (asking the
   human via `AskUserQuestion` for the genuinely ambiguous scenario, not guessing).
2. Write `plan.md` — technical design, explicit trade-offs, and (for brownfield) an
   impacted-module table before touching existing code.
3. Write `tasks.md` — dependency-ordered task decomposition, tagged by which
   orchestrator SDLC stage each task belongs to.
4. Implement, test, document.
5. Start a **real** orchestrator run and drive it through requirements-approval →
   design → implementation → parallel testing/documentation → release-approval, via the
   orchestrator's actual REST API — not a description of what a run would look like.
6. Export and commit the run's audit log + metrics as evidence.

This closes the loop the assignment asks for: requirement understanding → task
decomposition → orchestrated execution → validation, for real, three times, across
greenfield/brownfield/ambiguous.

## 2. Artifacts Produced

- **Working prototype**: two independently runnable Spring Boot services
  (`orchestrator`, `url-shortener-service`), 117 automated tests, `mvn test` green.
- **Orchestration engine**: `orchestrator/` — DAG/state-machine, human gates, retry/
  fallback/rollback, safe-stop, dynamic re-plan, policy guardrails, audit trail,
  reliability metrics. See `docs/architecture.md`.
- **Three scenarios**, each with `spec.md` + `plan.md` + `tasks.md` + implementation +
  tests + a committed, real orchestrator run export:
  - `specs/001-core-url-shortener` (greenfield)
  - `specs/002-click-analytics-ratelimit` (brownfield)
  - `specs/003-custom-alias-expiry` (ambiguous)
- **Documentation**: `README.md` (setup), `docs/architecture.md`, `docs/testing-and-
  tradeoffs.md`, this file, plus a per-module `README.md` for the product service.
- **Commit history**: every stage of every scenario is its own commit (scaffold →
  engine → per-scenario implementation → per-scenario tests/docs → per-scenario evidence
  export), giving a reviewable decision-lineage trail independent of the orchestrator's
  own audit log.

## 3. Risks, Trade-offs & Validation

Full table in `docs/testing-and-tradeoffs.md` §3-4. Highlights:

- **Two real logic bugs** in the orchestration engine were caught by its own test suite
  before any product scenario ran against them (fallback-node dispatch gating, and
  fallback-satisfied run completion) — direct evidence the "validation and risk control"
  requirement was substantively exercised, not just asserted.
- **Deliberate, stated trade-offs**, not hidden gaps: sequence-derived (enumerable)
  short codes, in-memory single-instance rate limiting, fire-and-forget async click
  recording, soft-expire instead of a purge job, no auth model anywhere. Each has a
  documented reason and a documented "what would change it."
- **Validation performed**: 117 unit/integration tests; three live orchestrator runs with
  exported, committed audit logs; the orchestrator's own aggregate `/metrics` endpoint
  confirmed correct across all three runs (3/3 completed, 100% success rate, consistent
  retry/rollback counts).

## 4. Assumptions

Assumption-by-assumption detail lives in each spec's own table (`specs/*/spec.md` §1),
rather than restated generically here — that's where they're traceable to the exact
requirement they resolve. Cross-cutting assumptions:

- Single-instance deployment is acceptable for a prototype (drives the H2 and rate-
  limiter decisions).
- No authentication/authorization model is in scope (drives the short-code enumerability
  and link-ownership trade-offs).
- "Controlled autonomy" means the orchestrator's *core* dispatches and governs; the actual
  work is done by a pluggable `NodeExecutor` and reported back through the same
  `complete`/`fail` + gates. In `manual` mode (scenarios 001–003) that worker is a human or
  an agent driving the REST API; in `agent` mode (spec 004) the orchestrator spawns Claude
  Code per node. Either way the engine is never itself the thing writing code or tests.

## 5. Limitations

See `docs/testing-and-tradeoffs.md` §3 for the full table. In one line: this is a
single-instance prototype with no auth, no distributed locking, and a deliberately small
policy-gate DSL — every one of those is a stated, reasoned scope boundary, not an
oversight.
