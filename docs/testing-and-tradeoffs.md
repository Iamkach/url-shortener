# Testing Approach, Limitations & Trade-offs

## 1. Testing Approach

**117 automated tests total** (`mvn test` from the repo root), no manual-only coverage
claimed anywhere in this write-up.

### `orchestrator` — 61 tests (15 core governance + 46 executor)

| Class | Focus |
|---|---|
| `WorkflowDefinitionTest` | Pure unit tests: cycle detection, dangling-dependency detection, transitive-downstream computation. No Spring context. |
| `WorkflowEngineDiamondTest` | `@SpringBootTest` against a dedicated `test-diamond.yaml` fixture (A → {B, C} → D, C has a fallback, D is a human gate): parallel dispatch + join synchronization, retry exhaustion → rollback, approval rejection → rollback, fallback triggering, dynamic re-plan (`invalidate`), pause/resume safe-stop. |
| `WorkflowEnginePolicyAndSdlcTest` | Entry-gate denial (`test-gate.yaml`), exit-gate denial on missing artifact, and a full `sdlc-standard` happy path asserting namespaced context propagation across every stage. |
| `MetricsServiceTest` | Retry/rollback counts and aggregate success rate computed correctly from a real run's audit trail. |
| `NodeExecutorRegistryTest` | Executor selection: node-level `executor:` overrides global mode, fallback to `manual` when a requested executor has no bean, `agent` resolves when its bean is present, `validate()` rejects an unknown executor name. |
| `NodeResultParserTest` | The worker-reply contract shared by `llm` and `agent`: status/artifact extraction, fence/prose tolerance, malformed / unknown-status / null → `fail`. |
| `LlmNodeExecutorTest` | Prompt assembly carries upstream namespaced context; model-call exception → `fail`; per-run budget exhaustion → `fail`. Uses a fake `ChatPort` — no network. |
| `AgentNodeExecutorTest` | Fake `AgentInvocationPort`: complete-envelope parse + context threading + stage → tools/paths derivation; non-zero exit / timeout / port exception / unparseable → `fail`; budget exhaustion → `fail`; DOCUMENTATION stage gets narrower tools + its own paths. No subprocess. |
| `ClaudeCliAgentPortTest` | Fake `ProcessRunner`: `argsTemplate` `{repoDir}`/`{allowedTools}` rendering, `ORCH_*` env export, prompt via stdin vs trailing arg, `timedOut`/`exitCode` passthrough. |
| `OpenAiCompatibleChatPortTest` | In-process `com.sun.net.httpserver` stub: OpenAI-shaped request body, bearer token from the configured env var, HTTP 500 → `RuntimeException`, missing `base-url` rejected. |
| `ChatPortProviderSelectionTest` | `ApplicationContextRunner`: `provider=anthropic` (default) → `AnthropicChatPort`; `openai-compatible` selects the other and excludes Anthropic; no `ChatPort` bean outside `mode=llm`. |
| `WorkflowEngineAutonomousTest` | `@SpringBootTest` driving `test-autonomous.yaml` end to end with a deterministic `ScriptedNodeExecutor`: non-gate nodes execute with no REST callback, both human gates still block, and a scripted first-attempt failure still drives `RETRY_ATTEMPTED` → recovery. |
| `WorkflowEngineAgentParallelTest` | `@SpringBootTest` + `test-agent-parallel.yaml` + a latched fake `AgentInvocationPort`: `testing` and `documentation` must meet at a 2-party `CyclicBarrier` (serial execution → the first never clears it → node fails), proving real concurrency on `nodeExecutorPool`; both human gates still block; audit carries `Actor.AGENT`. |
| `OrchGuardHookTest` | Shells the real `.claude/hooks/orch_guard.py` with crafted stdin+env: deny product path with no `ORCH_RUN_ID`, allow `CLAUDE.md` always, deny out-of-stage path in a run, allow in-stage path. `assumeTrue`-skips if no `python`. |

These are deliberately **engine tests against synthetic fixtures**, not just "the three
scenarios happened to work." The synthetic fixtures let retry/rollback/fallback/replan
be exercised deterministically and repeatedly, independent of whether a given URL-
shortener scenario happens to hit those paths.

### `url-shortener-service` — 56 tests

Unit tests (no Spring context) for `Base62Codec`, `UrlValidator` (including alias
validation), `RateLimiter` (bucket exhaustion/refill/independence). Service-layer tests
against mocked repositories for `UrlShortenerService` and `ClickRecordingService`.
`@SpringBootTest` + MockMvc integration tests per controller against a real (in-memory)
H2 instance, including: shorten/redirect round-trip, 400/404/409/410/429 error paths,
async click-recording verified via Awaitility polling (not a fixed sleep), and rate-limit
enforcement isolated in its own Spring context (distinct `@SpringBootTest` properties) so
it doesn't share bucket state with other integration tests.

### End-to-end (not unit-testable, done manually)

Each of the three scenarios was driven through a **live** orchestrator instance via curl
against its real REST API — not simulated. `docs/scenario-runs/*.json` is the exported
audit log + metrics from those actual runs, committed as evidence.

## 2. Two Real Bugs the Tests Caught

Documented in detail in `docs/architecture.md` §6. Summary: both were in
`WorkflowEngine`'s completion/dispatch logic around fallback nodes, both would have
silently produced runs stuck in `RUNNING` forever, and both were caught by the
orchestrator's own test suite before any scenario was run against the fixed code —
exactly the failure mode automated testing exists to catch on generated/agent-authored
logic.

## 3. Known Limitations (stated, not hidden)

| Limitation | Why it's acceptable here | What would change it |
|---|---|---|
| Rate limiter is in-memory, per-instance | Single-instance prototype | Redis or similar shared store for multi-instance deployment |
| Click recording is fire-and-forget async | Redirect latency matters more than exactly-once analytics for a prototype | A durable queue (outbox pattern) if click data needed to be a source of truth |
| Short codes are sequence-derived (enumerable) | No auth/ownership model exists yet, so guessability isn't a live risk | Random/hashed codes once links can be private or owned |
| Expiry is soft (checked at read time), no purge job | No traffic volume to justify pre-computation in a prototype; keeps analytics queryable | A scheduled sweep if storage growth became a concern |
| Orchestrator has no persistence across restarts (H2 in-memory) | Matches the platform-level decision for the whole system | A real Postgres-backed deployment for production use |
| No auth/authz anywhere in the system | Out of scope per every spec's explicit "Out of Scope" section | Would need to be designed before any of the above enumerability/ownership trade-offs could be revisited |
| Rollback ordering approximates reverse-topological order via completion timestamp, not a true graph reversal | Sufficient for the DAG shapes used here (shallow, few branches); a mis-ordered compensation is still logged and inspectable via the audit trail | A dedicated reverse-topological sort if compensation ordering became safety-critical |

## 4. Risks Considered and Deliberately Not Solved

- **Fabricated conflict resolution in rollback**: two branches racing to trigger rollback
  simultaneously is prevented by the per-run lock (`WorkflowEngine.lockFor`), but this is
  an in-process lock — it would not hold across multiple orchestrator instances. Fine for
  a single-instance prototype; would need distributed locking or a single-writer queue
  otherwise.
- **Policy gate DSL is intentionally small** (`requireContext` / `requireArtifact` /
  `denyIfContext`). A production policy engine would likely want a real expression
  language or OPA/Rego integration; the small DSL here is enough to prove the mechanism
  (gates that can actually block a transition) without over-building.
