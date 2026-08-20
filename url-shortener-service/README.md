# url-shortener-service

Core URL shortener product. See `specs/001-core-url-shortener/` (core shorten/redirect)
and `specs/002-click-analytics-ratelimit/` (analytics + rate limiting) for the
requirements and design this module implements.

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
| `POST` | `/api/urls` | Shorten a URL. Body: `{"longUrl": "...", "expiresAt"?: "ISO-8601"}`. Rate-limited (see below) |
| `GET` | `/api/urls/{code}` | Fetch metadata for a short code (no redirect) |
| `GET` | `/api/urls/{code}/analytics` | `{"shortCode", "totalClicks", "lastAccessedAt"}` |
| `GET` | `/{code}` | Resolve and `302` redirect to the original URL; records a click (async, off the response path) |

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
```

## Test

```
mvn -pl url-shortener-service -am test
```

32 tests: unit tests for Base62 encoding, URL validation, and the token-bucket rate
limiter; service-layer tests against mocked repositories; and `@SpringBootTest`/MockMvc
integration tests for shorten/redirect, analytics (polls with Awaitility past the async
click-recording write), and rate-limit enforcement, all against a real (in-memory) H2
instance.
