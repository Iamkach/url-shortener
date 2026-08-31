# Tasks 004 — QR Code Endpoint (autonomous agent run)

Dependency-ordered, tagged by SDLC stage. On the autonomous run the `design`–`documentation`
stages are performed by the `agent` executor (a `claude -p` child per node); a human still
approves `requirements` and `release_readiness`.

| # | Stage | Task | Depends on |
|---|---|---|---|
| 1 | requirements | Approve `spec.md` at the `requirements` gate with `specPath` artifact | — |
| 2 | design | Produce the QR design (this `plan.md` + `tasks.md`, or the agent's equivalent) as `designPath` | 1 |
| 3 | implementation | Add ZXing deps; `QrCodeService.pngFor`; `QrController` `GET /api/urls/{code}/qr` (`200` png / `404` / `410`); `mvn -pl url-shortener-service test` green; `git commit` → `commit` artifact | 2 |
| 4 | testing | `QrCodeServiceTest` (decode round-trip) + `QrControllerIntegrationTest` (200/404/410); `mvn -pl url-shortener-service test` green; write a report under `docs/scenario-runs/` → `testReport` artifact | 3 |
| 5 | documentation | Add the endpoint row to `README.md` / `docs/` API tables → `docsPath` artifact | 3 |
| 6 | release_readiness | Human approves release; export audit + metrics to `docs/scenario-runs/004-autonomous-agent.json` | 4, 5 |

## Note

The endpoint code is the point this time — the deliverable is a real feature built by the
orchestrator driving Claude Code, plus the exported run proving the `agent` executor works end
to end (real edits, real `mvn test`, real commit, structured result through the same gate).
Land whatever the `implementation` node produces and keep `mvn test` green.
