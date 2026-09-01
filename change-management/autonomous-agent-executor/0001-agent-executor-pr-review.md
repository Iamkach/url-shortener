# 0001 — Agent executor: PR review

**Reviews:** [0001 — Agent executor](0001-agent-executor.md)
**Scope of review:** `git diff main...HEAD` on the agent-executor stack (PRs #1–#10) plus this
change record.
**Method:** `/code-review` (single pass, high effort). Findings below are ordered most-severe
first. Line numbers are as of the review commit.
**Verdict:** two regressions (#1, #2) should be fixed before merge; #3–#5 are latent issues
that are safe to file as follow-ups.

**Status (update):** all five findings are fixed. Each fix landed on the phase branch that
introduced the code it touches, so it merges as part of that PR rather than as a trailing
patch:

| # | Fix commit | Lands on |
|---|---|---|
| 1 | `5788e48` restore `@Transactional` on 3-arg `startRun` | PR #1 (executor seam) |
| 2 | `46ce5e7` drain stdout/stderr before writing stdin | PR #3 (agent executor) |
| 3 | `46ce5e7` bound the per-run call-budget maps | PR #3 (agent executor) |
| 4 | `32d336b` `orch_guard.py` repo-root anchor + fail-closed | PR #5 (governance hook) |
| 5 | `163ae16` `NodeExecutorRegistry` duplicate-id merge | PR #1 (executor seam) |

The fix commits are code-only — they add no new test coverage. The pre-existing suite is
unchanged and green: **61 tests in `orchestrator`, 117 total.** Extending the suite for the
subdirectory/absolute-path hook case, the duplicate-id path, and the budget-map cap is left
as a follow-up (see the disposition table).

Note: `WorkflowEngineAutonomousTest` is intermittently flaky under full-suite load (a 15 s
awaitility gate on a shared Spring context + executor pool) — it reproduces on a pristine
checkout and passes on every solo re-run, so it is unrelated to these fixes. Tracked as its
own follow-up below.

---

## Findings

### 1. `startRun(id, ctx, createdBy)` lost `@Transactional` — runs with no transaction  ✅ FIXED

`orchestrator/.../engine/WorkflowEngine.java` — the 3-arg `startRun` overload

The 3-arg `startRun` overload used to carry `@Transactional`. It became unannotated and
reached the transactional 4-arg method only through `this.startRun(..., false)`:

```java
public WorkflowRunEntity startRun(String workflowDefinitionId, Map<String, String> initialContext, String createdBy) {
    return startRun(workflowDefinitionId, initialContext, createdBy, false);   // self-invocation
}

@Transactional
public WorkflowRunEntity startRun(String workflowDefinitionId, Map<String, String> initialContext,
                                 String createdBy, boolean autonomous) { ... }
```

Spring's transaction proxy cannot intercept a self-call, so the whole initialisation body —
`runRepo.save`, one `nodeRepo.save` per node, the `RUN_STARTED` audit write, and
`dispatchReady(...)` — executed **with no active transaction**.

**Consequences**
- Initialisation is no longer atomic. A failure partway through the node-creation loop
  persists a half-initialised run (run row + some node rows, no others).
- Any `@TransactionalEventListener(AFTER_COMMIT)` event published during initial dispatch is
  silently dropped — there is no transaction to commit. On an `autonomous` run this is the
  `NodeDispatchListener` path, so the first dispatched non-`manual` node may never be picked
  up.

**Reach:** every non-autonomous external caller and every existing engine test uses this
overload.

**Resolution** (`5788e48`): `@Transactional` restored on the 3-arg overload. The external
call now enters through the proxy and opens a transaction; the inner self-call to the 4-arg
method runs within it (`Propagation.REQUIRED`). Initialisation is atomic again and the
`AFTER_COMMIT` dispatch events fire. Covered by the existing
`WorkflowEnginePolicyAndSdlcTest` / `WorkflowEngineDiamondTest` engine tests.

---

### 2. stdin written before the stdout/stderr drain threads start — deadlock risk  ✅ FIXED

`orchestrator/.../engine/executor/DefaultProcessRunner.java`, `run(...)`

The class javadoc says *"stdout and stderr are drained on separate threads (a full pipe
buffer would otherwise deadlock the child)"*, but the code wrote the entire prompt to stdin
and closed it (a blocking call) **before** `readStream(...)` started the drains.

If the spawned agent CLI writes more than the OS pipe buffer (~64 KB on most platforms) to
stdout/stderr before it has consumed all of stdin, the child blocks on its stdout write while
the parent blocks on `os.write(stdin)` → deadlock until the 900 s timeout force-kills the
process tree. `claude -p` emitting a large streamed/verbose payload is exactly this shape.

**Resolution** (`46ce5e7`): the two `readStream(...)` drains are now started immediately
after `pb.start()`, before the stdin block. The stdin write/close itself is unchanged — a
plain synchronous `os.write(...)` in try-with-resources — because with both drains already
running the child can never wedge on a full output pipe while the parent is mid-write.
`waitFor`, the timeout kill, and `safeJoin` are untouched. The class javadoc was updated to
say the drains start before stdin is touched.

---

### 3. Per-run call-budget maps are never evicted — unbounded growth  ✅ FIXED

`orchestrator/.../engine/executor/AgentNodeExecutor.java` and `LlmNodeExecutor.java`

Both executors tracked the per-run agent/LLM call budget in a `ConcurrentHashMap` keyed by
`runId` (`callsPerRun.merge(runId, 1, Integer::sum)`). Nothing removed the entry when a run
reached a terminal state, so a long-lived orchestrator process in `agent`/`llm` mode
accumulated one permanent entry per run it had ever served.

Low urgency for the assignment (process is short-lived), but a real leak in any persistent
deployment.

**Resolution** (`46ce5e7`): in each executor the `callsPerRun` field is replaced in place by
`Collections.synchronizedMap` wrapping an access-order `LinkedHashMap` whose
`removeEldestEntry` returns `size() > MAX_TRACKED_RUNS` (`MAX_TRACKED_RUNS = 10_000`). The
map now self-evicts its least-recently-touched run once the cap is reached; the
`callsPerRun.merge(...)` call sites and the budget-exhaustion `fail(...)` behaviour are
unchanged. No shared helper class — the same small idiom is inlined in both executors, and
the now-unused `ConcurrentHashMap` imports were removed. An already-evicted run that reports
again simply restarts its budget from zero (fail-safe: it never bypasses the budget).

---

### 4. `orch_guard.py` fails open when Claude Code is started from a subdirectory  ✅ FIXED

`.claude/hooks/orch_guard.py`, `repo_relative(...)`

`repo_relative(...)` anchored on `os.getcwd()`. If a session is started from
`url-shortner/orchestrator/` and edits `../url-shortener-service/src/main/.../Foo.java`, the
computed relative path (`../url-shortener-service/src/main/...`) matches none of the
`GOVERNED` globs (`url-shortener-service/src/**`, …), so `main()` fell through to
`emit('allow', '...outside the governed trees')` — an unguarded edit to product code with no
`ORCH_RUN_ID`. The same fail-open happened for an absolute path on a different Windows drive
(`os.path.relpath` raises `ValueError`, and the code fell back to the raw path).

This weakens the "the orchestrator is the only path to product code" guarantee that the
change record relies on.

**Resolution** (`32d336b`): a module-level `REPO_ROOT` is derived from the hook script's own
location — `os.path.dirname` three times up from `<repo-root>/.claude/hooks/orch_guard.py` —
so it no longer depends on the working directory. `repo_relative(...)` anchors on `REPO_ROOT`
and returns `None` when the path cannot be placed inside the root: a `ValueError` from
`relpath` (different Windows drive), or a result that is `".."` / `"../"`-prefixed (escapes
the root). `main()` treats a `None` result as `deny` — placed before the `ALWAYS_EXEMPT`
check, so an unresolvable path fails closed. Pure standard library; no `git` call, no
`subprocess`. The existing `OrchGuardHookTest` still passes; it was **not** extended to cover
the new subdirectory / different-drive / escape cases (follow-up).

---

### 5. `NodeExecutorRegistry` startup crash on a duplicate executor id  ✅ FIXED (tolerate-and-log)

`orchestrator/.../engine/executor/NodeExecutorRegistry.java`, constructor

`Collectors.toMap(NodeExecutor::id, Function.identity())` throws
`IllegalStateException: Duplicate key ...` if two `NodeExecutor` beans ever report the same
`id()`. Today the `manual` / `llm` / `agent` beans are mutually exclusive via
`@ConditionalOnProperty` so it cannot fire in the shipped config, but any future
non-conditional executor bean (or a dropped condition) turns an id collision into an opaque
context-startup failure.

**Resolution** (`163ae16`): `Collectors.toMap` is given a merge function (the class is
already `@Slf4j`) that logs
`"Duplicate NodeExecutor id '{}' ({} and {}); keeping the first"` with the colliding id and
both implementing class names, then returns the first entry deterministically. `toMap` with a
merge function yields a plain `HashMap`, which is fine here — `byId` is only ever read via
`get`. This is the review's *"merge function that logs and keeps one deterministically"*
option; the alternative (fail fast on a duplicate) was **not** taken, so a wiring mistake
degrades to a warning + first-wins rather than stopping the boot. No test was added for the
duplicate path (follow-up).

---

## Not findings (checked, no action)

- Governance is unchanged: no executor path bypasses `PolicyEngine`, retry, fallback, or
  rollback. Every agent failure mode still degrades to `fail(...)`.
- `manual` remains the default; no new bean is instantiated by the default boot.
- The offline test suite (117 tests) is untouched and green.

---

## Disposition

| # | Finding | Original action | State |
|---|---|---|---|
| 1 | `startRun` missing `@Transactional` | Fix before merge | ✅ Fixed — `@Transactional` restored on the 3-arg overload (`5788e48`, PR #1) |
| 2 | stdin written before drains start | Fix before merge | ✅ Fixed — drains start right after `pb.start()`, before the stdin write (`46ce5e7`, PR #3) |
| 3 | Call-budget maps never evicted | Follow-up issue | ✅ Fixed — each map is now a synchronized access-order `LinkedHashMap` capped at 10 000 runs (`46ce5e7`, PR #3) |
| 4 | `orch_guard.py` fails open from a subdir | Follow-up issue | ✅ Fixed — `REPO_ROOT` from the script's own path + `None`→deny on any unresolvable path (`32d336b`, PR #5) |
| 5 | Registry crash on duplicate executor id | Follow-up issue | ✅ Fixed (tolerate-and-log) — `toMap` merge function warns and keeps the first (`163ae16`, PR #1) |
| — | Fix commits added no test coverage | New follow-up | ⏳ Open — add tests for the subdir/different-drive hook cases, the duplicate-id path, and the budget-map cap |
| — | `WorkflowEngineAutonomousTest` flaky under full-suite load (pre-existing) | New follow-up | ⏳ Open — passes on solo re-run; needs a longer await budget or a dispatch-settled latch |
