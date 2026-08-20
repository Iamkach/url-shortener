# Tasks 001 — Core URL Shortener

Each task is tagged with the `sdlc-standard` orchestrator node it belongs to.
Sequencing = dependency order; tasks within the same node with no arrow between them can
be done in either order (not meaningfully parallel at this granularity).

```
[requirements] spec.md (this feature)                              — DONE
      |
[design] plan.md (this feature)                                     — DONE
      |
[implementation]
  T1. ShortUrl entity + ShortUrlRepository
  T2. Base62Codec (encode(long) -> String)
  T3. UrlValidator (validate(String longUrl))
  T4. UrlShortenerService (create, resolve, getMetadata) -- depends on T1-T3
  T5. UrlController (POST /api/urls, GET /api/urls/{code}) -- depends on T4
  T6. RedirectController (GET /{code}) -- depends on T4
  T7. GlobalExceptionHandler -- depends on T5, T6
      |
[testing] (depends on implementation commit)
  T8. Base62CodecTest, UrlValidatorTest (unit)
  T9. UrlShortenerServiceTest (unit, mocked repository)
  T10. UrlControllerIntegrationTest, RedirectControllerIntegrationTest
       (@SpringBootTest + MockMvc, real H2)
      |
[documentation] (parallel with testing, depends on implementation commit)
  T11. README setup/run instructions for url-shortener-service
  T12. OpenAPI annotations/descriptions on controllers
      |
[release_readiness] (depends on testing + documentation both complete)
  Human approval gate — reviews test results + docs before this scenario is
  considered shippable.
```

## Execution Notes

- T5/T6 both depend only on T4, and T8-T10 (testing) / T11-T12 (documentation) both
  depend only on "implementation commit" — this is what lets the orchestrator dispatch
  testing and documentation in parallel once implementation completes.
- `implementation`'s exit gate requires a `commit` artifact; `testing`'s exit gate
  requires a `testReport` artifact; `documentation`'s exit gate requires a `docsPath`
  artifact — enforced by the orchestrator's policy engine, not just convention.
