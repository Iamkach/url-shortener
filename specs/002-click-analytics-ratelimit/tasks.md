# Tasks 002 — Click Analytics + Rate Limiting

```
[requirements] spec.md (this feature, incl. impacted-module analysis)        — DONE
      |
[design] plan.md (this feature)                                              — DONE
      |
[implementation]
  T1. ClickEvent entity + ClickEventRepository
  T2. ClickRecordingService (@Async recordAsync, plus count/lastAccessed queries)
  T3. Wire RedirectController to call recordAsync after building the redirect
      response -- depends on T2
  T4. RateLimiter (token bucket) + RateLimitExceededException
  T5. RateLimitInterceptor + WebMvcConfig registration on POST /api/urls
      -- depends on T4
  T6. AnalyticsController (GET /api/urls/{code}/analytics) -- depends on T2
  T7. GlobalExceptionHandler: map RateLimitExceededException -> 429
      -- depends on T4
      |
[testing] (depends on implementation commit)
  T8. RateLimiterTest (unit, no Spring context)
  T9. ClickRecordingServiceTest (unit, mocked repository)
  T10. AnalyticsControllerIntegrationTest
  T11. RateLimitIntegrationTest
      |
[documentation] (parallel with testing, depends on implementation commit)
  T12. README updates: new endpoints, rate-limit config, known limitations
      |
[release_readiness] (depends on testing + documentation)
  Human approval gate.
```
