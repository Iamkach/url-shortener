# Spec 001 — Core URL Shortener (Greenfield)

**Scenario type:** Greenfield — new system, no prior codebase to reason about.
**Orchestrator run:** `sdlc-standard` workflow, node `requirements` (this document is that
node's output artifact, `specPath`).

## 1. Requirement Understanding

**Raw ask:** "Build a URL shortener service from scratch with core APIs, analytics, and
reliability features."

**Normalized problem:** For this first slice, deliver the *core* transactional path only —
create a short link for a long URL, and resolve a short link back to its target. Analytics
and reliability (rate limiting) are deliberately deferred to Spec 002, which brownfield-
extends this service, so each spec has a clean, reviewable diff.

**Ambiguities identified and resolved (assumptions):**

| # | Ambiguity | Resolution | Rationale |
|---|---|---|---|
| A1 | Short code length/alphabet? | Base62 `[0-9a-zA-Z]`, 7 chars, sequence-derived | ~3.5T addresses at 7 chars; sortable-ish, no collision retries needed (see plan.md trade-off) |
| A2 | What happens on an invalid/malformed long URL? | Reject with `400` at creation time; validate `http(s)://` scheme + parseable URL | Fail fast, cheap to check, avoids storing garbage |
| A3 | Does a short code expire by default? | No — `expiresAt` is optional; omitted = never expires | Matches common shortener UX (bit.ly, etc.); expiry semantics get first-class treatment in Spec 003 |
| A4 | What HTTP status does a resolved redirect use? | `302 Found` (temporary redirect) | Keeps the link live for analytics in Spec 002; a `301` would let browsers cache past our control |
| A5 | Is the short code guessable/sequential a security concern here? | Out of scope for this spec; sequence-derived codes are acceptable for a prototype | No auth/ownership model yet — added complexity isn't justified until there's something to protect |

## 2. User Stories & Acceptance Criteria

**US-1: Shorten a URL**
- As a client, I POST a long URL and get back a short code + full short URL.
- AC1: `POST /api/urls {"longUrl": "https://example.com/very/long/path"}` → `201`, body
  includes `shortCode`, `shortUrl`, `longUrl`, `createdAt`.
- AC2: `longUrl` missing, blank, or not a well-formed `http(s)` URL → `400` with a
  descriptive message.
- AC3: Two requests for the same `longUrl` produce two independent short codes (no
  dedup in this spec — dedup is a reasonable future enhancement, not assumed here).

**US-2: Resolve a short link**
- As a client, I GET a short code and get redirected to the original URL.
- AC1: `GET /{code}` for an existing, non-expired code → `302` with `Location` header set
  to the original `longUrl`.
- AC2: `GET /{code}` for an unknown code → `404`.

**US-3: Inspect a short link's metadata**
- As a client, I can fetch metadata without triggering a redirect.
- AC1: `GET /api/urls/{code}` → `200` with `shortCode`, `longUrl`, `createdAt`,
  `expiresAt` (nullable).
- AC2: Unknown code → `404`.

## 3. Out of Scope (this spec)

- Click analytics / access logging (Spec 002)
- Rate limiting (Spec 002)
- Custom aliases, expiry enforcement semantics (Spec 003)
- AuthN/AuthZ, per-user link ownership
- Persistence beyond the process lifetime (in-memory H2 per the platform-level decision)

## 4. Non-Functional Requirements

- Unit + integration test coverage for validation, code generation, and both endpoints.
- No blocking I/O in the redirect path beyond the single DB lookup.
- API documented via OpenAPI (springdoc), reachable at `/swagger-ui.html`.
