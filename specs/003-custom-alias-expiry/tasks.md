# Tasks 003 — Custom Aliases & Expiry Enforcement

```
[requirements] spec.md, incl. C1-C3 ambiguity resolutions (human-approved)   — DONE
      |
[design] plan.md                                                             — DONE
      |
[implementation]
  T1. UrlValidator.validateAlias() (reserved list + charset/length regex)
  T2. AliasAlreadyExistsException, LinkExpiredException
  T3. CreateUrlRequest.customAlias field
  T4. UrlShortenerService.create(): custom-alias branch -- depends on T1-T3
  T5. RedirectController: expiry check -> 410 -- depends on T2
  T6. GlobalExceptionHandler: map new exceptions -> 409 / 410 -- depends on T2
      |
[testing] (depends on implementation commit)
  T7. UrlValidatorTest additions (alias cases)
  T8. UrlShortenerServiceTest additions (custom alias happy path + collision)
  T9. UrlControllerIntegrationTest additions (custom alias E2E, 409, 400)
  T10. RedirectControllerIntegrationTest additions (410 on expiry, metadata
       still 200 on the same expired link)
      |
[documentation] (parallel with testing, depends on implementation commit)
  T11. README: customAlias field, reserved list, 409/410 behavior
      |
[release_readiness] (depends on testing + documentation)
  Human approval gate.
```
