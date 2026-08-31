---
name: sdlc-run
description: Drive one feature end-to-end through the orchestrator's agent executor — start an autonomous run, relay the two human approval gates to the user, and export the audit + metrics as scenario-run evidence. Use when the user types /sdlc-run or asks to run a feature through the orchestrator.
trigger: /sdlc-run
---

# sdlc-run

A **thin harness**. It does not do any node's SDLC work — the orchestrator's `agent` executor
spawns Claude Code per node for that. This skill only starts the run, carries the approval gates to
a human, and collects evidence.

## Steps

1. **Ensure the orchestrator is up in agent mode.** If `GET http://localhost:8081/workflows` fails,
   start it in the background:
   `cd orchestrator && ORCHESTRATOR_EXECUTOR_MODE=agent mvn spring-boot:run` and health-poll
   `GET /workflows` until it lists `sdlc-autonomous`. Requires the `claude` CLI logged in (or
   `ANTHROPIC_API_KEY`).
2. **Branch.** `git switch -c sdlc-run/004-autonomous-agent` (skip if already on it).
3. **Start the run.**
   `python .claude/skills/sdlc-run/orch.py start --feature qr-endpoint` → prints the run id.
   (It POSTs `{"workflowDefinitionId":"sdlc-autonomous","autonomous":true,"createdBy":<user>,
   "initialContext":{"feature":"qr-endpoint"}}`.)
4. **Poll + gate.** `python .claude/skills/sdlc-run/orch.py poll <runId>` reports status. On
   `AWAITING_APPROVAL`, use **AskUserQuestion** (approve / reject) for that node. Then:
   - approve: `orch.py approve <runId> <nodeId> --by <user> [--artifact specPath=specs/004-autonomous-agent/spec.md]`
     (the `requirements` gate needs `specPath`).
   - reject: `orch.py reject <runId> <nodeId> --by <user> --reason "<why>"`.
   Between gates the `agent` executor runs design → implementation → testing ∥ documentation on its
   own; just keep polling.
5. **On a terminal status** (`COMPLETED` / `FAILED`), export evidence:
   `python .claude/skills/sdlc-run/orch.py evidence <runId> --out docs/scenario-runs/004-autonomous-agent.json`
   (pulls `GET /runs/{id}`, `/audit`, `/metrics` and writes the same shape as `001`–`003`).

## Constraints

- **Never approve or reject a gate without the user.** Always AskUserQuestion first.
- **Never edit product code yourself.** The `agent` executor + the `PreToolUse` guard own that.
- **Always re-derive state from `GET /runs/{id}`** — do not assume a node advanced.
