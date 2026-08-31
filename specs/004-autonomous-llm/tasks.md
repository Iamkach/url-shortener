# Tasks 004 — QR Code Endpoint (autonomous run)

Dependency-ordered, tagged by SDLC stage. On the autonomous run the `design`–`documentation`
stages are performed by `LlmNodeExecutor`; a human still approves `requirements` and
`release_readiness`.

| # | Stage | Task | Depends on |
|---|---|---|---|
| 1 | requirements | Approve `spec.md` at the `requirements` gate with `specPath` artifact | — |
| 2 | design | Produce the QR design (this `plan.md`, or the model's equivalent) as `designPath` | 1 |
| 3 | implementation | Add ZXing deps; `QrCodeService.pngFor`; `QrController` `GET /api/urls/{code}/qr` (`200` png / `404` / `410`); commit → `commit` artifact | 2 |
| 4 | testing | `QrCodeServiceTest` (decode round-trip) + `QrControllerIntegrationTest` (200/404/410); `mvn test` green → `testReport` artifact | 3 |
| 5 | documentation | Add the endpoint row to `CLAUDE.md` / `README.md` API tables → `docsPath` artifact | 3 |
| 6 | release_readiness | Human approves release; export audit + metrics to `docs/scenario-runs/004-autonomous-llm.json` | 4, 5 |

## Note

The code for this feature is optional — the scenario's deliverable is the exported
orchestrator run proving `LlmNodeExecutor` works end to end. If the model's `implementation`
node produces the endpoint, land it and keep `mvn test` green; if only the run evidence is
captured, record that in the engineering summary.
