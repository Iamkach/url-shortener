# Plan 003 — Custom Aliases & Expiry Enforcement (Technical Design)

**Depends on:** `specs/003-custom-alias-expiry/spec.md` (human-resolved ambiguities C1-C3).

## 1. Changes by Component

**`UrlValidator`** — add:
```java
private static final Set<String> RESERVED = Set.of(
    "api", "urls", "swagger-ui", "v3", "h2-console", "actuator", "favicon.ico", "robots.txt");
private static final Pattern ALIAS_PATTERN = Pattern.compile("[a-zA-Z0-9_-]{1,64}");

void validateAlias(String alias) {
    if (alias == null) return; // optional field
    if (!ALIAS_PATTERN.matcher(alias).matches())
        throw new IllegalArgumentException("customAlias must match [a-zA-Z0-9_-]{1,64}");
    if (RESERVED.contains(alias.toLowerCase()))
        throw new IllegalArgumentException("customAlias '" + alias + "' is reserved");
}
```

**`UrlShortenerService.create(longUrl, expiresAt, customAlias)`** — when `customAlias` is
present: validate it, check `repository.findByShortCode(alias).isEmpty()` (else throw
`AliasAlreadyExistsException` → `409`), set `shortCode` directly instead of deriving it
from the generated id. When absent, behavior is byte-for-byte the Spec 001 path — this
is an additive branch, not a rewrite, so Spec 001's service tests keep passing unchanged.

**`RedirectController.redirect()`** — after `service.resolve(code)`, check
`entity.getExpiresAt() != null && entity.getExpiresAt().isBefore(Instant.now())`; if so,
throw `LinkExpiredException` → mapped to `410` by `GlobalExceptionHandler`, instead of
building the redirect. Metadata endpoint (`UrlController.get`) does **not** get this
check (spec.md US-2 AC3 — soft-expire keeps metadata readable).

**New exceptions:** `AliasAlreadyExistsException` (409), `LinkExpiredException` (410).

## 2. API Contract (additions)

```
POST /api/urls
  Request adds: "customAlias"?: string
  Errors add: 409 -- alias already in use
              400 -- alias reserved or fails charset/length validation

GET /{code}
  Errors add: 410 -- link's expiresAt has passed (Location header omitted)
```

## 3. Why Not a Background Expiry Sweep (trade-off, tied to C3)

Rejected in favor of check-at-read-time because: (a) a scheduler is unwarranted
complexity for a prototype with no traffic to justify pre-computation, (b) soft-expire
means there is no "expired row" state to sweep — the row is valid forever from a storage
point of view, only the redirect behavior changes, and (c) it keeps click-history/
analytics (spec 002) queryable past expiry, which a hard-delete would destroy.

## 4. Testing Approach

- `UrlValidatorTest` additions: alias charset/reserved-word cases.
- `UrlShortenerServiceTest` additions: custom alias happy path, collision → exception.
- `UrlControllerIntegrationTest` additions: custom alias end-to-end, 409 on collision,
  400 on reserved word.
- `RedirectControllerIntegrationTest` additions: expired link → 410; metadata endpoint
  on the same expired link still → 200 (soft-expire boundary case).
