# Original Planning Note (superseded)

This file captured the initial open decisions before the build started. All of them are
now resolved and the actual plan lives in the places listed below — kept here only for
history.

- Tech stack: Java 21 → **Java 17** (only JDK available), Spring Boot, H2 in-memory — see
  `README.md` and `pom.xml`.
- Orchestration approach: **real DAG/state-machine engine in code** — see
  `docs/architecture.md` and `orchestrator/`.
- Repo initialization: done — `git init`, see commit history.
- Three demo scenarios: **greenfield / brownfield / ambiguous** — see `specs/001-*`,
  `specs/002-*`, `specs/003-*` and `docs/scenario-runs/*.json`.

See `README.md` for setup, `docs/architecture.md` for design, `docs/engineering-
summary.md` for the full rationale/risks/assumptions writeup.
