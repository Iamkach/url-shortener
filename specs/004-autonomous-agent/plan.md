# Plan 004 — QR Code Endpoint (Technical Design)

**Depends on:** `specs/004-autonomous-agent/spec.md` (raw ask + assumptions A1-A3).

This is an additive read-path feature on `url-shortener-service`: one new endpoint that renders a
PNG QR code for an existing short code. No schema change, no new persistence, no rate limiting on
this path, no analytics side effect. Built by the orchestrator's `agent` executor per node.

## 1. Codebase Reasoning (brownfield impact)

- **`/api/urls/**` routing** — the QR endpoint sits under the existing `UrlController`
  (`@RequestMapping("/api/urls")`) as `GET /{code}/qr`. It does *not* collide with the redirect
  path (`GET /{code}` on `RedirectController`, no `/api/urls` prefix) or the metadata path
  (`GET /api/urls/{code}`).
- **Rate limiting** — `WebMvcConfig` registers `RateLimitInterceptor` on the *exact* path
  `/api/urls` only, so `GET /api/urls/{code}/qr` is naturally exempt. Nothing to change; the QR
  path being a read keeps it consistent with the other read paths (spec.md §1: "no rate limiting
  on this read path").
- **Resolve + soft-expire** — `UrlShortenerService.resolve(code)` already throws
  `NoSuchElementException` (→ `404` via `GlobalExceptionHandler`). The soft-expire rule
  (`expiresAt != null && expiresAt.isBefore(now)` → `LinkExpiredException` → `410`) currently
  lives inline in `RedirectController`. Spec.md requires the QR path to be "consistent with the
  redirect path (spec 003, C3)", so the check is extracted once (see §3) and reused, rather than
  copy-pasted.
- **Base URL** — the encoded content is the same absolute short URL the redirect target is built
  from: `${app.base-url} + "/" + code` (identical to `UrlResponse.from`). `UrlController` already
  injects `@Value("${app.base-url}")`.
- **Existing tests** — `resolve()` is untouched; `RedirectController`'s externally observable
  behavior is unchanged (same exception, same message shape). All 56 current
  `url-shortener-service` tests keep passing.

## 2. New Component — `QrCodeRenderer`

`service/QrCodeRenderer.java` — a `@Component`, the only new unit of logic.

```java
byte[] pngFor(String text)   // ZXing QRCodeWriter -> BitMatrix -> BufferedImage -> ImageIO PNG bytes
```

- Uses **ZXing `core` only** (assumption A1). `core` has no image I/O, so the `BitMatrix` is
  walked into a `BufferedImage` (`TYPE_INT_RGB`, black on white) by hand and written with
  `javax.imageio.ImageIO.write(img, "PNG", baos)` — this avoids pulling in `zxing:javase` and
  keeps the dependency surface to one jar.
- Fixed size **256×256** (assumption A2), `EncodeHintType.MARGIN = 1`, charset UTF-8. No size
  query parameter is in scope.
- `WriterException` / `IOException` are wrapped in an unchecked
  `QrCodeGenerationException extends RuntimeException` (falls through to Spring's default `500`);
  in practice a QR encode of a short absolute URL does not fail.

**New dependency** (`url-shortener-service/pom.xml`) — the parent pom has no ZXing dependency
management, so the version is pinned explicitly:

```xml
<dependency>
  <groupId>com.google.zxing</groupId>
  <artifactId>core</artifactId>
  <version>3.5.3</version>
</dependency>
```

## 3. Shared soft-expire check

Add to `UrlShortenerService`:

```java
/** resolve(), then enforce the spec-003 soft-expire rule on the redirect/QR read paths. */
@Transactional(readOnly = true)
public ShortUrl resolveUnexpired(String shortCode) {
    ShortUrl entity = resolve(shortCode);
    if (entity.getExpiresAt() != null && entity.getExpiresAt().isBefore(Instant.now())) {
        throw new LinkExpiredException("Short link '" + shortCode + "' expired at " + entity.getExpiresAt());
    }
    return entity;
}
```

- `RedirectController.redirect()` switches from `resolve(code)` + inline check to
  `resolveUnexpired(code)` (click recording stays in the controller, unchanged).
- The new QR handler calls `resolveUnexpired(code)` and never touches
  `ClickRecordingService` — spec.md §1: "no analytics side effect".

## 4. Endpoint handler (`UrlController`)

```java
@Operation(summary = "PNG QR code encoding the short URL for a code")
@GetMapping(value = "/{code}/qr", produces = MediaType.IMAGE_PNG_VALUE)
public ResponseEntity<byte[]> qr(@PathVariable String code) {
    ShortUrl entity = service.resolveUnexpired(code);
    byte[] png = qrCodeRenderer.pngFor(baseUrl + "/" + entity.getShortCode());
    return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(png);
}
```

`QrCodeRenderer` is constructor-injected alongside the existing `UrlShortenerService`
(class is `@RequiredArgsConstructor`).

## 5. API Contract (addition)

```
GET /api/urls/{code}/qr
  200  image/png   -- 256x256 PNG QR encoding "{app.base-url}/{code}", for any resolvable code
  404               -- code does not exist            (NoSuchElementException, existing mapping)
  410  Gone         -- code is soft-expired           (LinkExpiredException,  existing mapping)
  no request body, no query params, not rate limited, no click recorded
```

Error bodies for 404/410 are the existing `GlobalExceptionHandler` JSON envelope
(`{timestamp,status,error,message}`); only the `200` response is `image/png`.

## 6. Why Not …

- **`zxing:javase` / `MatrixToImageWriter`** — one convenience method (`BitMatrix` → PNG) is not
  worth a second jar; the hand-rolled `BufferedImage` loop is ~10 lines and uses only the JDK.
- **A `/qr` variant on the bare redirect path (`GET /{code}/qr`)** — kept under `/api/urls/**`
  with the other programmatic/metadata reads; the bare `/{code}` namespace is reserved for the
  browser-facing 302 redirect.
- **Caching / `ETag` / `Cache-Control`** — out of scope for the prototype; the QR is cheap to
  recompute and the short URL for a code never changes.
- **Data-URI / base64 JSON response** — spec.md fixes the contract as `image/png` bytes with
  `200`, which is what a browser `<img src>` or a scanner expects.

## 7. Testing Approach

- **`QrCodeRendererTest`** (unit) — `pngFor("http://localhost:8080/abc")` returns non-empty
  bytes with the PNG magic header; `ImageIO.read` back to a `BufferedImage` yields 256×256;
  round-trip decode with ZXing `QRCodeReader` returns the original text (proves "scannable").
- **`UrlControllerIntegrationTest`** additions (MockMvc) —
  - happy path: create a URL, `GET /api/urls/{code}/qr` → `200`, `Content-Type: image/png`,
    body starts with the PNG signature.
  - `GET /api/urls/doesnotexist/qr` → `404`.
- **`RedirectControllerIntegrationTest`** (or `UrlControllerIntegrationTest`) addition —
  create a URL with `expiresAt` in the past, `GET /api/urls/{code}/qr` → `410`; the metadata
  endpoint on the same code still → `200` (soft-expire boundary, unchanged).
- No new rate-limit test needed — the interceptor path pattern already excludes this route and
  `RateLimitIntegrationTest` covers the exact-path behavior.

## 8. artifacts

`artifacts.designPath = specs/004-autonomous-agent/plan.md`
