# Change Management

Narrative background for significant changes to this repo — the *why* behind a set of PRs,
written for someone (a reviewer, a recruiter, a new contributor) who is looking at the code
cold and wants the context before reading the diff.

One file per change, numbered. Each covers: the problem, what changed, the decisions and
their alternatives, the rollout (which PRs), how to run the result, and what was deliberately
left out.

| # | Change | Status |
|---|---|---|
| [0001](autonomous-agent-executor/0001-agent-executor.md) | Agent executor — the orchestrator invokes Claude Code per node | PRs open; live run pending |
| [0001 review](autonomous-agent-executor/0001-agent-executor-pr-review.md) | PR review of the agent-executor stack | All 5 findings fixed on their originating phase branches; 2 follow-ups open (test coverage for the fixes; a pre-existing flaky test) |
