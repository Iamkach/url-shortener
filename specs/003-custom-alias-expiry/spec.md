# Spec 003 — Custom Aliases & Expiry Enforcement (Ambiguous Requirement)

**Scenario type:** Ambiguous — the raw ask ("let users customize/brand their short links
with expiry") under-specifies several behaviors with real design consequences. This spec
documents the questions surfaced, the human's resolutions, and the resulting design —
demonstrating requirement-normalization on a genuinely ambiguous input, not just a
well-defined one (Spec 001) or a well-scoped enhancement (Spec 002).

## 1. Requirement Understanding

**Raw ask:** "Let users customize/brand their short links with expiry."

**Why this is ambiguous (as originally stated):**
- "Customize/brand" doesn't say what happens on a naming collision.
- Nothing says whether a custom alias could accidentally shadow a real application route.
- "With expiry" doesn't say what an expired link *does* when visited — Spec 001 already
  accepts an `expiresAt` field but never enforces it (spec.md 001, A3); this spec is
  where enforcement semantics actually get decided.

**Questions surfaced to the human before design work started, and the resolutions
given:**

| # | Question | Resolution | Rationale |
|---|---|---|---|
| C1 | Custom alias already taken — reject or overwrite? | **Reject, `409 Conflict`** | No auth/ownership model exists to legitimize "last writer wins"; predictable failure mode over silent takeover |
| C2 | Should some aliases be blocked as reserved words? | **Yes — fixed blocklist** | A custom alias at `/api` or `/swagger-ui` would shadow a real route and break the application; a small fixed list is cheap insurance |
| C3 | What does `GET /{code}` do once `expiresAt` has passed? | **`410 Gone`, row kept (soft-expire)** | Checked at read time — no scheduler/background job needed for a prototype; click history and metadata stay inspectable via the analytics endpoint (spec 002) instead of being destroyed |

These are recorded as decisions, not assumptions — they were explicitly put to a human
reviewer (the `requirements` node's approval gate) rather than picked unilaterally.

## 2. Codebase Reasoning (Impacted Modules)

| Module | Change |
|---|---|
| `CreateUrlRequest` | Gains an optional `customAlias` field |
| `UrlShortenerService.create()` | Branches: custom alias supplied → validate against reserved list + uniqueness, use as-is; otherwise → existing Base62-derived path (spec 001) is unchanged |
| `UrlValidator` | Gains `validateAlias(String)` — reserved-word + charset/length checks |
| `RedirectController.redirect()` | Gains an expiry check before building the `302`; returns `410` instead when `expiresAt` has passed |
| `UrlController.get()` (metadata) | Also expiry-aware: an expired link's metadata is still readable (soft-expire — only the *redirect* is blocked), but the response should be able to reflect the state (see AC below) |
| `GlobalExceptionHandler` | Gains a mapping for alias-collision (`409`) and expired-link (`410`) exceptions |

## 3. User Stories & Acceptance Criteria

**US-1: Create a link with a custom alias**
- AC1: `POST /api/urls {"longUrl": "...", "customAlias": "my-brand"}` → `201`, `shortCode`
  is exactly `"my-brand"`.
- AC2: Alias already in use → `409` with a descriptive message (C1).
- AC3: Alias on the reserved list (`api`, `urls`, `swagger-ui`, `h2-console`, `actuator`,
  `favicon.ico`, `robots.txt`) → `400` (C2).
- AC4: Alias fails basic charset validation (must be `[a-zA-Z0-9_-]{1,64}`) → `400`.
- AC5: No `customAlias` supplied → falls back to Spec 001's Base62-derived code
  unchanged.

**US-2: Expired links stop redirecting**
- AC1: `GET /{code}` where `expiresAt` is in the past → `410 Gone` (C3), no `Location`
  header.
- AC2: `GET /{code}` where `expiresAt` is null or in the future → unchanged Spec 001
  behavior (`302`).
- AC3: `GET /api/urls/{code}` (metadata) on an expired link still returns `200` with the
  data intact — soft-expire means the record isn't deleted, only the redirect is blocked.

## 4. Out of Scope (this spec)

- Editing/deleting an existing link (no auth/ownership model — deferred)
- Extending an expired link's `expiresAt` (same reason)
- A background sweep job to purge expired rows (explicitly rejected — see C3)
