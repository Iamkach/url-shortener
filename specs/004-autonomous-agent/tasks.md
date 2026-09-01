# Tasks 004 — QR Code Endpoint

```
[requirements] spec.md (raw ask + assumptions A1-A3)   — human approval gate, DONE
      |
[design] plan.md                                       — DONE (artifacts.designPath)
      |
[implementation]  (agent: real edits + `mvn test` + `git commit`)
  T1. url-shortener-service/pom.xml: add com.google.zxing:core 3.5.3
  T2. service/QrCodeGenerationException (unchecked)
  T3. service/QrCodeRenderer @Component: pngFor(String) -> ZXing BitMatrix ->
      BufferedImage 256x256 -> ImageIO PNG bytes; wrap Writer/IO errors in T2
  T4. UrlShortenerService.resolveUnexpired(code): resolve() + spec-003 soft-expire
      check -> LinkExpiredException  -- depends on nothing new
  T5. RedirectController.redirect(): use resolveUnexpired(code), drop the inline
      expiry check (behavior unchanged)  -- depends on T4
  T6. UrlController: GET /{code}/qr (produces image/png) -> resolveUnexpired +
      QrCodeRenderer.pngFor(baseUrl + "/" + code); inject QrCodeRenderer
      -- depends on T3, T4
      -> `mvn -pl url-shortener-service test` green, then commit
      |
[testing]  (depends on implementation commit; parallel with documentation)
  T7. QrCodeRendererTest: PNG magic header, 256x256 via ImageIO.read,
      ZXing QRCodeReader round-trips the encoded text (scannable)
  T8. UrlControllerIntegrationTest additions: /qr happy path -> 200 + image/png +
      PNG signature; unknown code -> 404
  T9. Expiry case: past-expiry code -> /qr 410, metadata endpoint still 200
      (add to RedirectControllerIntegrationTest or UrlControllerIntegrationTest)
      -> full `mvn test` green
      |
[documentation]  (depends on implementation commit; parallel with testing)
  T10. README + docs: new row in the API table
       (GET /api/urls/{code}/qr -> 200 image/png, 404, 410 Gone);
       note ZXing core dependency, 256x256 default, no rate limit / no click
  T11. docs/architecture.md §6 decisions table: "QR rendered with zxing:core
       only (no zxing:javase), hand-rolled BitMatrix->PNG"
      |
[release_readiness]  (depends on testing + documentation)
  Human approval gate. Export GET /runs/{id}/audit + /metrics ->
  docs/scenario-runs/004-autonomous-agent.json
```

## Acceptance (from spec §3)

- Run reaches `COMPLETED`; audit trail has `NODE_COMPLETED` / `actor: AGENT` rationale for
  design / implementation / testing / documentation; `implementation` records a real commit sha.
- `mvn test` green after the run.
- `GET /api/urls/{code}/qr` returns a scannable PNG for a live code, `404` for an unknown code,
  `410` for a soft-expired code.
