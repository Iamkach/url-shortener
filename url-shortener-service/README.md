# url-shortener-service

Core URL shortener product. See `specs/001-core-url-shortener/` (core shorten/redirect),
`specs/002-click-analytics-ratelimit/` (analytics + rate limiting),
`specs/003-custom-alias-expiry/` (custom aliases + expiry enforcement), and
`specs/004-autonomous-agent/` (QR-code endpoint) for the requirements and design this
module implements.

## Run

```
mvn -pl url-shortener-service -am spring-boot:run
```

Starts on `http://localhost:8080`. In-memory H2 database (data does not persist across
restarts); console at `http://localhost:8080/h2-console` (JDBC URL
`jdbc:h2:mem:urlshortener`, user `sa`, no password).

OpenAPI/Swagger UI: `http://localhost:8080/swagger-ui.html`.

## API

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/urls` | Shorten a URL. Body: `{"longUrl": "...", "expiresAt"?: "ISO-8601", "customAlias"?: "my-brand"}`. Rate-limited (see below) |
| `GET` | `/api/urls/{code}` | Fetch metadata for a short code (no redirect). Still returns `200` even if the link has expired (soft-expire) |
| `GET` | `/api/urls/{code}/analytics` | `{"shortCode", "totalClicks", "lastAccessedAt"}` |
| `GET` | `/api/urls/{code}/qr` | 256×256 PNG (`image/png`) QR code encoding the short URL; `404` if the code is unknown, `410 Gone` if expired. Read path — not rate-limited, no analytics side effect. See [`docs/qr-code-endpoint.md`](../docs/qr-code-endpoint.md) |
| `GET` | `/{code}` | Resolve and `302` redirect to the original URL; `410 Gone` if expired; records a click (async, off the response path) |

## Custom Aliases & Expiry

`customAlias` (optional) is used verbatim as the short code instead of the generated
Base62 one. Rules (see `specs/003-custom-alias-expiry/spec.md` for the full ambiguity-
resolution writeup):
- Must match `[a-zA-Z0-9_-]{1,64}`, else `400`.
- Reserved words (`api`, `urls`, `swagger-ui`, `v3`, `h2-console`, `actuator`,
  `favicon.ico`, `robots.txt`) are blocked, `400` — they'd otherwise shadow real routes.
- Already in use → `409 Conflict`.

`expiresAt` is enforced at read time (no background sweep): once passed, `GET /{code}`
returns `410 Gone` instead of redirecting, but the row and its analytics stay readable
via `GET /api/urls/{code}` (soft-expire).

## Rate Limiting

`POST /api/urls` is protected by an in-memory, per-client-IP token bucket
(`app.rate-limit.capacity` / `app.rate-limit.refill-per-minute` in `application.yml`,
default 20/min). Exceeding it returns `429 Too Many Requests`. Read paths are not
rate-limited.

**Known limitation:** the bucket is in-memory and per-instance — it resets on restart
and isn't shared across multiple instances of this service. Acceptable for this
prototype; a real multi-instance deployment would need a shared store (e.g. Redis). See
`specs/002-click-analytics-ratelimit/plan.md` §4.

## Example

```bash
curl -s -X POST http://localhost:8080/api/urls \
  -H 'Content-Type: application/json' \
  -d '{"longUrl":"https://example.com/some/very/long/path"}'
# => {"shortCode":"0004","shortUrl":"http://localhost:8080/0004", ...}

curl -i http://localhost:8080/0004   # 302 redirect

curl -s http://localhost:8080/api/urls/0004/qr -o qr.png   # 256x256 PNG QR code
```

## Test

```
mvn -pl url-shortener-service -am test
```

56 tests: unit tests for Base62 encoding, URL/alias validation, and the token-bucket rate
limiter; service-layer tests against mocked repositories; and `@SpringBootTest`/MockMvc
integration tests for shorten/redirect, analytics (polls with Awaitility past the async
click-recording write), rate-limit enforcement, custom-alias collision/reserved-word
handling, and expiry enforcement (soft-expire boundary case), all against a real
(in-memory) H2 instance.
