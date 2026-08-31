# Scenario 004 — TESTING stage report

**Feature:** qr-endpoint (`GET /api/urls/{code}/qr`)
**Spec:** `specs/004-autonomous-agent/spec.md`
**Design:** `specs/004-autonomous-agent/plan.md`
**Implementation commit:** `89712cfdae976565b8dc1724c2b636008a2ff90e`
**Pipeline node:** `testing` (stage TESTING)

## What was added

`url-shortener-service/src/test/java/com/urlshortener/service/api/QrCodeEndpointIntegrationTest.java`
— 5 `@SpringBootTest` + `MockMvc` cases exercising the new PNG QR endpoint end to end:

| Test | Asserts |
|---|---|
| `qr_returnsScannablePngEncodingTheAbsoluteShortUrl` | `200`, `Content-Type: image/png`, PNG magic bytes, 256×256 image, and — decoding the PNG back with ZXing `core` (`RGBLuminanceSource` + `QRCodeReader`) — the QR payload equals `http://localhost:8080/{shortCode}` |
| `qr_worksForACustomAlias` | Same, for a spec-003 `customAlias` code; QR encodes the alias URL |
| `qr_returns404ForUnknownCode` | Unknown code → `404` (shared `NoSuchElementException` path) |
| `qr_returns410ForSoftExpiredCode_butMetadataStaysReadable` | Soft-expired code → `410 Gone` on `/qr`, while `GET /api/urls/{code}` metadata still returns `200` (spec 003, C3) |
| `qr_stillWorksWhenExpiresAtIsInTheFuture` | Non-expired `expiresAt` → `200` and a decodable QR |

The happy-path tests decode the rendered image rather than only checking the byte header, so a
regression that produced a valid-but-wrong PNG (wrong URL, wrong size) would fail.

## Test run

Command: `mvn -pl url-shortener-service test`

```
Tests run: 61, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

56 pre-existing + 5 new. All offline: no network, no subprocess. Green on first full run after
adding the new class.
