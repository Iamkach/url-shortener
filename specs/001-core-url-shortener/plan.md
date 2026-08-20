# Plan 001 — Core URL Shortener (Technical Design)

**Orchestrator run:** node `design`, output artifact `designPath` = this document.
**Depends on:** `specs/001-core-url-shortener/spec.md` (context key `requirements.specPath`).

## 1. Module & Package Layout

`url-shortener-service` (existing Spring Boot module), package `com.urlshortener.service`:

```
domain/       ShortUrl (JPA entity)
repository/   ShortUrlRepository (Spring Data JPA)
service/      UrlShortenerService, Base62Codec, UrlValidator
api/          UrlController, RedirectController, dto/*, GlobalExceptionHandler
config/       (none needed yet)
```

## 2. Data Model

`ShortUrl`:
| field | type | notes |
|---|---|---|
| `id` | `Long` (auto-increment) | primary key; **also the seed for the short code** (see §3) |
| `shortCode` | `String`, unique, indexed | derived from `id` via Base62 |
| `longUrl` | `String` | validated at creation |
| `createdAt` | `Instant` | set on save |
| `expiresAt` | `Instant`, nullable | not enforced in this spec (see spec.md A3) |

## 3. Short Code Generation — Decision & Trade-off

**Chosen: sequence-derived Base62.** Persist the row first (DB assigns an auto-increment
`id`), then compute `shortCode = base62(id)`. Padded/left-aligned to a minimum of 4 chars
for a less-predictable look, no practical minimum otherwise.

**Rejected alternative: random 7-char code + collision retry loop.** Considered because it
avoids leaking a monotonically-increasing id (enumerable short codes). Rejected for this
spec because:
- It requires a retry-on-collision loop (unbounded worst case, extra DB round-trips as the
  table fills), which is unnecessary complexity for a prototype's core path.
- Guessability is explicitly out of scope here (spec.md A5); revisit if/when an auth model
  is introduced.

This is a recorded, defensible trade-off — not an oversight.

## 4. API Contracts

```
POST /api/urls
  Request:  { "longUrl": string, "expiresAt"?: ISO-8601 string }
  Response: 201 { "shortCode": string, "shortUrl": string, "longUrl": string,
                  "createdAt": ISO-8601, "expiresAt": ISO-8601|null }
  Errors:   400 { "message": string }  -- invalid/missing longUrl

GET /{code}
  Response: 302, Location: <longUrl>
  Errors:   404  -- unknown code

GET /api/urls/{code}
  Response: 200 { "shortCode", "longUrl", "createdAt", "expiresAt" }
  Errors:   404  -- unknown code
```

`app.base-url` (application.yml) is prefixed onto `shortCode` to build `shortUrl`.

## 5. Validation Rules (`UrlValidator`)

- Reject null/blank.
- Must parse as `java.net.URI` with scheme `http` or `https` and a non-blank host.
- No length cap beyond a generous sanity bound (2048 chars) to avoid pathological input.

## 6. Error Handling

`GlobalExceptionHandler` (`@RestControllerAdvice`) maps:
- `IllegalArgumentException` (validation failures) → `400`
- `NoSuchElementException` (unknown code) → `404`

## 7. Risks / Trade-offs Carried Forward

- **Risk:** sequential ids make short codes enumerable. **Mitigation deferred**: acceptable
  now (no sensitive data behind links); would need random/hashed codes before handling
  private links.
- **Risk:** no dedup means the same long URL can accumulate many short codes.
  **Mitigation deferred**: acceptable for a prototype; a unique index on `longUrl` would
  change semantics (first-writer-wins) and isn't asked for.
