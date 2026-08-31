# Plan 004 — QR Code Endpoint

## Codebase Reasoning (brownfield impact)

| Module | Change | Risk |
|---|---|---|
| `service/` | New `QrCodeService` — encode a string to a PNG byte[] via ZXing. Pure, no state. | Low — new class, no existing code touched |
| `api/` | New `QrController` (`GET /api/urls/{code}/qr`) — resolve the code through the existing `UrlShortenerService`, reuse its not-found / soft-expire handling, hand the URL to `QrCodeService`, return `ResponseEntity<byte[]>` with `Content-Type: image/png`. | Low — additive controller; the resolve path already returns the right errors |
| `pom.xml` (service) | Add `com.google.zxing:core` + `com.google.zxing:javase`. | Low — small, well-known, no transitive weight |
| config | Reuse the existing base-URL property the redirect path uses to build the absolute short URL. | None |

No entity, repository, migration, rate-limit, or analytics changes. The redirect path and
all existing endpoints are untouched.

## Design

1. `QrCodeService.pngFor(String text, int size)` → `byte[]`, using ZXing `QRCodeWriter` +
   `MatrixToImageWriter`. Deterministic; unit-tested by decoding the PNG back and asserting
   the payload round-trips.
2. `QrController.qr(code)`:
   - look up the code via `UrlShortenerService` (same call the metadata endpoint uses);
   - `404` if absent (existing exception → `GlobalExceptionHandler`);
   - `410` if `expiresAt` is in the past (mirror the redirect path's soft-expire check);
   - otherwise build `{baseUrl}/{code}` and return `QrCodeService.pngFor(url, 256)`.
3. Tests: `QrCodeServiceTest` (round-trip decode), `QrControllerIntegrationTest` (`200` +
   `image/png` for a live code, `404` unknown, `410` expired) via MockMvc + H2, matching the
   existing integration-test style.

## Orchestration

This plan is itself the `design` artifact the autonomous run's `design` node is expected to
produce (or something equivalent the agent generates). Executed via `sdlc-autonomous` with
`ORCHESTRATOR_EXECUTOR_MODE=agent` — the `design` node's `claude -p` child writes/updates this
file and `tasks.md` (its `ORCH_ALLOW_PATHS` is `specs/**/plan.md,specs/**/tasks.md,docs/**`),
`implementation` edits `url-shortener-service/src/**` and commits, `testing` and `documentation`
run concurrently on disjoint trees. See `spec.md` §2. Evidence exported to
`docs/scenario-runs/004-autonomous-agent.json`.
