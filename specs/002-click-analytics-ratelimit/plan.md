# Plan 002 — Click Analytics + Rate Limiting (Technical Design)

**Depends on:** `specs/002-click-analytics-ratelimit/spec.md`.

## 1. New Components

```
domain/       ClickEvent (JPA entity)
repository/   ClickEventRepository
service/      ClickRecordingService, RateLimiter
api/          AnalyticsController, RateLimitExceededException
config/       RateLimitInterceptor + WebMvcConfig (interceptor registration)
```

## 2. Data Model

`ClickEvent`:
| field | type | notes |
|---|---|---|
| `id` | `Long` (auto-increment) | primary key |
| `shortCode` | `String`, indexed | which link was clicked (no FK to keep the write path simple/decoupled) |
| `clickedAt` | `Instant` | |

Kept separate from `ShortUrl` (spec.md B-table rationale): every click would otherwise be
a write to the same row `POST /api/urls` reads/writes, adding contention on hot links.

## 3. Click Recording — Async, Off the Redirect Path

`RedirectController` calls `ClickRecordingService.recordAsync(shortCode)`
(`@Async`, a `@EnableAsync`-backed `SimpleAsyncTaskExecutor` is sufficient at this scale)
*after* building the redirect response, so a slow or failing analytics write can never
delay or break the `302`. `AnalyticsController` computes `totalClicks` via
`ClickEventRepository.countByShortCode`, `lastAccessedAt` via
`findTopByShortCodeOrderByClickedAtDesc`.

**Trade-off:** fire-and-forget async recording means a click can theoretically be lost
if the process crashes between the redirect and the async write landing. Accepted for
this prototype — analytics is best-effort, not a source of truth requiring
exactly-once delivery (spec.md B4 explicitly prioritizes redirect latency).

## 4. Rate Limiting — In-Memory Token Bucket per IP

`RateLimiter`: `ConcurrentHashMap<String ip, Bucket>`, each `Bucket` holding
`capacity` tokens (`app.rate-limit.capacity`, default 20), refilled at
`app.rate-limit.refill-per-minute` (default 20/min), consumed one-per-request,
synchronized per-bucket (not globally) to avoid a contention bottleneck.

`RateLimitInterceptor` (`HandlerInterceptor`) applies this only to
`POST /api/urls`, registered via `WebMvcConfig`. On exhaustion, throws
`RateLimitExceededException`, mapped by `GlobalExceptionHandler` to `429`.

**Trade-off (recorded, not hidden):** in-memory means the bucket resets on restart and
isn't shared across instances. Acceptable for a single-instance prototype; a real
multi-instance deployment would need Redis or similar — noted as a known limitation in
`docs/testing-and-tradeoffs.md`, not solved here (spec.md, out of scope).

## 5. API Contracts (additions)

```
GET /api/urls/{code}/analytics
  Response: 200 { "shortCode", "totalClicks": number, "lastAccessedAt": ISO-8601|null }
  Errors:   404 -- unknown code

POST /api/urls  (existing endpoint, now rate-limited)
  Errors (new): 429 { "message": "Rate limit exceeded, try again later" }
```

## 6. Testing Approach

- `RateLimiterTest`: unit-tests bucket exhaustion/refill in isolation (no Spring context).
- `ClickRecordingServiceTest`: unit test with mocked repository.
- `AnalyticsControllerIntegrationTest`: MockMvc, drives a redirect then asserts the
  analytics endpoint reflects it (accepts async recording may need a short poll/await).
- `RateLimitIntegrationTest`: MockMvc, fires `capacity + 1` requests, asserts the last
  one is `429`.
