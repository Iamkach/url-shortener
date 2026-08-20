# Assignment: Agentic Software Engineering System — URL Shortener

Source: `Assignment Agentic-Proficient Software Engineer.pdf`

## 1. Objective

Build a working prototype that transforms a requirement into a reviewable engineering
outcome using an **agentic execution model**. Demonstrate requirement understanding,
task decomposition, multi-step execution, and output generation/validation. Focus on
end-to-end SDLC automation with **controlled autonomy**.

## 2. Scenario

Build a URL shortener service from scratch with core APIs, analytics, and reliability
features. Complete and improve it over 2-3 days using AI assistance (Copilot/Claude/etc.)
while **demonstrating engineering judgment**.

> The URL shortener is the payload/vehicle. The thing actually being evaluated is the
> **agentic orchestration layer** used to build it.

## 3. Scope

- Greenfield scenarios (new systems/features)
- Brownfield scenarios (enhancements, refactors, bug fixes)
- Test and documentation improvements
- Well-defined and ambiguous requirements

## 4. Core Requirements

1. **Requirement Understanding** — Interpret intent, identify ambiguity, normalize into
   a clear engineering problem.
2. **Task Decomposition** — Convert high-level requirements into actionable tasks with
   dependencies and sequencing.
3. **Codebase Reasoning (Brownfield)** — Identify impacted modules/services/APIs/data
   flows and demonstrate architectural understanding.
4. **Workflow Orchestration (Critical Differentiator)** — Design and implement an
   agentic orchestration layer that coordinates the full SDLC lifecycle across
   requirements, architecture/design, implementation, testing, documentation, and
   release readiness. Must demonstrate **non-linear, stateful execution with
   governance**, not simple linear task chaining. Specifically the orchestration must:
   - Use an explicit **dependency graph** with entry/exit gates
   - Support **sequential and parallel paths** with synchronization
   - **Preserve cross-stage context** and decision lineage
   - Enforce **human approval checkpoints** for high-impact actions
   - Include **bounded retries, fallback, rollback, and safe-stop** controls
   - Embed **policy guardrails** for security, compliance, and change control
   - Provide **audit-grade observability and traceability**
   - Track **reliability metrics**: success rate, retry/rollback frequency, MTTR,
     end-to-end latency
   - **Dynamically re-plan** when upstream outputs change, while maintaining
     governance and controlled agent autonomy
5. **Engineering Output Generation** — Production-quality code, API/schema
   definitions, unit/integration tests, and supporting documentation with clean design
   and maintainability.
6. **Validation and Risk Control** — Identify risks/trade-offs/failure scenarios and
   define validation and safety guardrails.
7. **Controlled Autonomy** — Agents execute multi-step work; humans provide oversight,
   approvals, and final quality control.
8. **Final Engineering Summary** — Plan/rationale, artifacts, risks/trade-offs/
   validation, assumptions, and limitations.

## 5. Deliverables

- [ ] Working prototype (runnable end-to-end)
- [ ] Architecture overview (components, orchestration model, control flow, key
      decisions)
- [ ] Three scenarios — greenfield, brownfield, ambiguous — each showing
      decomposition, orchestration, and validation
- [ ] Setup instructions
- [ ] Testing approach, limitations, and trade-offs

## 6. Evaluation Criteria

- Effectiveness of agentic orchestration
- Architecture/system design quality
- Depth of decomposition and execution quality
- Realism/quality of outputs
- Validation and risk management rigor
- Clarity and defensibility of decisions
- Core engineering principles: modular, testable, reliable, secure, scalable code with
  safe change management
- Engineering judgment

## 7. Expectation

Treat as production-grade engineering work. Demonstrate strong design fundamentals,
lifecycle orchestration capability, output ownership, and defensible reasoning.

> Principle: Agents execute under defined autonomy boundaries; humans own oversight,
> approvals, and final quality.

## 8. Open Decisions (to confirm before build)

- [ ] Tech stack for the URL shortener (language/framework/DB)
- [ ] Orchestration layer implementation approach — build a real lightweight DAG /
      state-machine engine in code (recommended, since this is called the "critical
      differentiator") vs. a documented-only process
- [ ] Repo initialization (git init in this directory)
- [ ] Choice of the three demo scenarios (greenfield / brownfield / ambiguous feature)
