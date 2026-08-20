# Spec 002 — Click Analytics + Rate Limiting (Brownfield)

**Scenario type:** Brownfield — enhances the existing `url-shortener-service` built in
Spec 001. This spec's `requirements` node output explicitly identifies impacted code,
per the assignment's "codebase reasoning" requirement.

## 1. Requirement Understanding

**Raw ask:** "...with core APIs, **analytics**, and **reliability** features."
Spec 001 deferred both; this spec delivers them as an enhancement to the existing
codebase rather than a rewrite.

**Normalized problem:** Two independent, additive capabilities on top of the existing
service:
1. Record every successful redirect as a click event, and expose an aggregate view.
2. Protect `POST /api/urls` from abuse with a per-client rate limit.

**Ambiguities identified and resolved:**

| # | Ambiguity | Resolution | Rationale |
|---|---|---|---|
| B1 | What counts as a "click"? Only successful redirects, or also 404s on unknown codes? | Only successful redirects (existing code, not expired) | 404s aren't traffic *to* the destination; conflating them would pollute per-link analytics |
| B2 | What analytics fields are needed? | `totalClicks`, `lastAccessedAt`; per-click timestamp log capped/summarized, not a full raw event API | Keeps scope to what's asked ("analytics"), avoids building a full event-query API nobody requested |
| B3 | Rate limit scope — per IP, per API key, global? | Per client IP, token bucket, applied to `POST /api/urls` only | No auth model exists yet (spec.md 001, A5), so IP is the only available identity signal; write path is the one worth protecting from abuse, not reads |
| B4 | Does recording a click block the redirect response? | No — click recording must not add latency/failure risk to the redirect path (spec.md 001 NFR) | A redirect is on the user's critical path; analytics is not |

## 2. Codebase Reasoning (Impacted Modules)

Existing code from Spec 001 that this spec touches or depends on:

| Module | Change |
|---|---|
| `RedirectController` (`GET /{code}`) | Must trigger click recording after a successful resolve, without adding latency to the response |
| `UrlShortenerService.resolve()` | Reused as-is; click recording is a new, separate call, not folded into `resolve()`, so Spec 001's unit tests for `resolve()` remain valid unchanged |
| `UrlController` (`POST /api/urls`) | Gains a rate-limit check before delegating to the service |
| `ShortUrl` entity / `ShortUrlRepository` | Unchanged — click data lives in a new `ClickEvent` entity, not bolted onto `ShortUrl`, to avoid a write-amplifying update on every click |
| `GlobalExceptionHandler` | Gains a mapping for the new rate-limit-exceeded exception |

New code: `ClickEvent` entity, `ClickEventRepository`, `ClickRecordingService`,
`AnalyticsController`, `RateLimiter` (in-memory token bucket), `RateLimitInterceptor`.

## 3. User Stories & Acceptance Criteria

**US-1: View click analytics for a short link**
- AC1: `GET /api/urls/{code}/analytics` → `200` with `shortCode`, `totalClicks`,
  `lastAccessedAt` (nullable if never clicked).
- AC2: Unknown code → `404`.
- AC3: A `GET /{code}` redirect increments `totalClicks` and updates `lastAccessedAt`
  for that code (observable via AC1's endpoint).

**US-2: Rate limiting on link creation**
- AC1: A client under the configured limit (default: 20 requests/minute, see
  `application.yml` `app.rate-limit`) creates links normally.
- AC2: A client exceeding the limit receives `429 Too Many Requests` with a
  descriptive message.
- AC3: The limit is tracked independently per client IP.

## 4. Out of Scope (this spec)

- Per-referrer / geographic breakdown of clicks (raw fields not requested)
- Distributed rate limiting (single-instance in-memory bucket is acceptable for this
  prototype; a multi-instance deployment would need a shared store — recorded as a
  known limitation, not solved here)
- Rate limiting on read paths (`GET /{code}`, `GET /api/urls/{code}`)
