# QR Code Endpoint — `GET /api/urls/{code}/qr`

Spec: [`specs/004-autonomous-agent/spec.md`](../specs/004-autonomous-agent/spec.md) ·
Design: [`specs/004-autonomous-agent/plan.md`](../specs/004-autonomous-agent/plan.md) ·
Implementation: commit `89712cf`

An additive read-path feature on `url-shortener-service`: return a scannable PNG QR code
that encodes the absolute short URL for an existing, unexpired short code. No new
persistence, no rate limiting on this read path, and no analytics side effect (a scan of
the resulting QR later hits `GET /{code}` and is counted there like any other click).

## Contract

| | |
|---|---|
| Method / path | `GET /api/urls/{code}/qr` |
| Path param | `code` — an existing short code (generated Base62 or a custom alias) |
| Success | `200 OK`, `Content-Type: image/png`, body = a 256×256 PNG QR code encoding `{app.base-url}/{code}` |
| `404 Not Found` | no short URL exists for `code` (`NoSuchElementException` → `GlobalExceptionHandler`) |
| `410 Gone` | the code exists but is soft-expired (`expiresAt` in the past) — consistent with the redirect path (spec 003, C3) |

The QR encodes exactly the same absolute URL the `302` redirect would send
(`app.base-url` + `/` + the stored short code), so scanning it is equivalent to following
the short link.

### Examples

```bash
# Create a short URL
curl -s -X POST http://localhost:8080/api/urls \
  -H 'Content-Type: application/json' \
  -d '{"longUrl":"https://example.com/some/very/long/path"}'
# => {"shortCode":"0004","shortUrl":"http://localhost:8080/0004", ...}

# Fetch its QR code as a PNG
curl -s http://localhost:8080/api/urls/0004/qr -o qr.png
file qr.png            # => PNG image data, 256 x 256, 8-bit/color RGB

# Unknown code
curl -i http://localhost:8080/api/urls/nope/qr      # => 404

# Expired code (created with a past expiresAt)
curl -i http://localhost:8080/api/urls/exp123/qr    # => 410 Gone
```

## Implementation

| Component | Role |
|---|---|
| `UrlController.qr(code)` | new handler mapped to `/{code}/qr`, `produces = image/png`; resolves the code via `resolveUnexpired`, renders the PNG, returns `ResponseEntity<byte[]>` with `Content-Type: image/png` |
| `UrlShortenerService.resolveUnexpired(code)` | `resolve(code)` plus the spec-003 soft-expire check (`410` via `LinkExpiredException`). Now shared by the redirect path and the QR path — `RedirectController` was refactored onto it, removing its inline expiry check |
| `QrCodeRenderer` (`@Component`) | `pngFor(String text)` → fixed 256×256 PNG. Uses ZXing **`core` only**: `QRCodeWriter` produces a `BitMatrix`, which is walked pixel-by-pixel into a `BufferedImage` and written with `ImageIO` (no `zxing:javase` dependency). Margin = 1 module, UTF-8. |
| `QrCodeGenerationException` | unchecked; wraps ZXing `WriterException` / `IOException` from the renderer |
| `pom.xml` | adds `com.google.zxing:core:3.5.3` (assumption A1 — small, dependency-light, no external service call) |

### Design decisions (from `plan.md`)

- **ZXing `core` only, no `zxing:javase`** — `javase` pulls in extra image-I/O helpers the
  service doesn't need; hand-walking the `BitMatrix` into a `BufferedImage` is ~10 lines and
  keeps the dependency surface minimal.
- **Fixed 256×256, no `size` query param** (assumption A2) — out of scope for this spec; a
  size parameter can be added later without breaking the contract.
- **Reuses `resolveUnexpired`** rather than duplicating the expiry check — one soft-expire
  rule, enforced identically on every read path that "acts on" the link (redirect, QR).
- **No rate limiting** — read path, consistent with `GET /api/urls/{code}` and
  `GET /api/urls/{code}/analytics`.
