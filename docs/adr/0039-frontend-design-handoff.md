# ADR-0039: Frontend Design Handoff — Claude Design ↔ Claude Code

* Status: Proposed
* Date: 2026-05-31
* Decider: Thomas
* Supersedes: none
* Cross-ref: ADR-0004 (application stack), ADR-0018 (identity management),
  ADR-0033 (multi-tenancy & tenant isolation), ADR-0037 (tenant image
  build/publish/deploy, private), ADR-0038 (platform baseline & tenant alignment,
  private); the frontend design brief template (private); the tenant repository
  scaffold's seed frontend `CONVENTIONS.md` (private).

## Table of Contents

- [Status](#status)
- [Context](#context)
- [Decision](#decision)
  - [Precedence Ladder](#precedence-ladder)
  - [The Handoff Loop](#the-handoff-loop)
  - [RACI](#raci)
  - [Operating Rules](#operating-rules)
- [Rationale](#rationale)
- [Consequences](#consequences)
- [References](#references)

## Status

Proposed (2026-05-31). Triggered by the first frontend that will be designed with
an external design tool (Claude Design) and integrated by Claude Code: the
**internal portal of the first tenant**, which today has only a Quarkus backend
and no frontend. This ADR defines the handoff *before* that frontend is built so the
process is in place, not retrofitted.

## Context

Frontend work now involves two distinct AI actors plus the platform owner:

- **Claude Design** — a visual/UX design tool that generates mockups and component
  code (Vue 3 / Vite / TypeScript). It works in its own sandbox; it **cannot** see
  the `manyfold-platform` or tenant repos, cannot run the build/CI, cannot merge,
  and is not bound by our ADRs.
- **Claude Code** — runs inside the platform and tenant repos. It can see both
  sides, knows the ADRs, owns CI/GitOps/deploy, and is the only actor that can
  press merge.
- **The platform owner** — accountable for taste, scope, and standards.

Three facts make an explicit handoff necessary:

1. **The conventions Claude Design must match are not where it is pointed.** They
   live in `manyfold-platform` (`apps/website/frontend` and the frontend of an
   internal, platform-owned application, the *internal reference app*), and the two
   apps have already drifted (Vue 3.4 vs 3.5, Pinia vs none, Vitest vs Playwright,
   Prettier-config vs none). There is no shared frontend baseline yet — `libs/` is
   empty, the tenant scaffold ships only CI, and the shared baseline repository
   (ADR-0038) is Java-only.
2. **Tenant repos must not depend on `manyfold-platform` (ADR-0033/0037).** So
   conventions cannot be imported — they must be transferred as a document and,
   over time, as shared artifacts published through the shared baseline repository
   (public).
3. **Left unmanaged, Claude Design output becomes a third silo.** Without a gate it
   will reasonably-but-wrongly introduce a component library, `@keycloak/keycloak-js`,
   `axios`, or SSR — none of which match the real platform patterns.

Without a defined owner per decision, design output flows straight into repos and
either violates standards or silently sets new ones.

## Decision

Adopt the principle **standards flow down; design flows up through a gate.**
Claude Design never writes to a platform or tenant repo's `main`; its output is a
*proposal* that Claude Code normalizes against the platform contract before merge.
Claude Design is **never Accountable** for a frontend outcome — it cannot see the
target repo, run the build, or merge, so it cannot answer for production.

### Precedence Ladder

When sources conflict, the left wins:

```
ADRs  >  shared baseline  >  reference apps (internal reference app)  >  the design brief  >  Claude Design output
```

The **only** way Claude Design changes something to its left is by Claude Code
*promoting* it upward (into the shared baseline and/or an ADR) — never by it
leaking into a repo. Reference-app precedence is **the internal reference app first**
(internal, role-gated, OIDC, Danish, mobile); `apps/website/frontend` is consulted only for
its Prettier config and Playwright setup.

### The Handoff Loop

| Phase | Owner | Output |
|-------|-------|--------|
| **0 · Contract** | Claude Code | A filled-in frontend design brief (from the private brief template): constrained prompt, hand-extracted tokens/snippets from the internal reference app, primitive spec, and the **forbidden list**. |
| **1 · Design** | Claude Design | Design in a **sandbox** (playground/prototype branch, mirroring the internal reference app's `design-playground.html`/`ux-playground.html`). Markup, tokens, states, interaction. |
| **2 · Conformance + integrate** | Claude Code | Run the gate (forbidden-deps scan, token→`@theme` mapping, type the primitives, wire real oauth2-proxy auth, lint/test/build), then integrate into the scaffold. |
| **3 · Promote** | Claude Code | Lift anything reusable **up into the shared baseline**, not copied per-app. Open/adjust ADRs. |
| **4 · Approve + ship** | Owner | Sign off, merge, deploy. Renovate/CI provide ongoing drift detection. |

Phase 0 is Claude Code's accountability: a "bad design output" is almost always an
incomplete brief, not a Claude Design failure.

### RACI

`R` = does the work · `A` = owns/signs off (one per row) · `C` = consulted ·
`I` = informed · `RA` = does it *and* owns it. CD = Claude Design, CC = Claude Code,
You = owner.

**Standards & contract**

| Item | CD | CC | You |
|------|----|----|-----|
| Frontend standards & ADRs (stack, auth model, baseline scope) | C | R | **A** |
| Design brief / guardrails / forbidden-list (the contract) | C | R | **A** |
| Extracting & hand-feeding reference tokens/snippets to CD | I | **RA** | I |

**Design — CD's turf**

| Item | CD | CC | You |
|------|----|----|-----|
| Brand identity & visual language | R | C | **A** |
| Token *values* (palette, type scale, spacing) | R | C | **A** |
| UX, interaction, layout, information architecture | R | C | **A** |
| Primitive components — visual/markup | R | C | **A** |

**Engineering & integration — CC's turf**

| Item | CD | CC | You |
|------|----|----|-----|
| Token *schema* (`@theme` naming/format) | C | **RA** | I |
| Primitives — typed props, a11y, tests, prod-readiness | C | **RA** | I |
| Auth (oauth2-proxy, `auth.ts` store, role guards) | I | **RA** | I |
| API/data layer, routing impl, role-gating wiring | C | **RA** | I |
| i18n / Danish-localization decision (DA-only vs DA/EN) | C | R | **A** |
| CI/CD, GitOps, deploy packaging (Quarkus `META-INF/resources`) | — | **RA** | I |

**Governance & flow**

| Item | CD | CC | You |
|------|----|----|-----|
| Conformance gate (ingest output, forbidden-deps check, lint/test/build) | I | **RA** | I |
| Promote good patterns → shared baseline | C | R | **A** |
| Final merge to tenant/platform repo & deploy | I | R | **A** |
| Scope / roadmap / feature set | C | C | **RA** |

One-line summary: **You** own taste, scope, and standards; **Claude Code** owns
everything that touches code, auth, CI, and merge-readiness; **Claude Design** is
Responsible for the visual/UX layer and Consulted on feasibility — never
Accountable.

### Operating Rules

1. **Sandbox-first, always.** Claude Design output lands in a playground/prototype,
   never on a repo's `main`. The internal reference app's `*-playground.html` files
   are the established drop-zone pattern; formalize them as the Claude Design surface.
2. **Promote, don't copy.** A genuinely better primitive or token set goes *up* into
   the shared baseline so all apps inherit it — converting design innovation into a
   standard instead of a fourth silo.
3. **Conformance gate = definition of done.** Design output is "done" only when it
   passes Claude Code's checklist: no forbidden deps, tokens mapped to `@theme`,
   primitives typed + accessible, real auth wired, lint/test/build green. Same
   preventive + detective shape as ADR-0038, applied to the frontend.

## Rationale

- **Visibility asymmetry decides accountability.** Only Claude Code can see the
  target repo, run CI, and merge; making Claude Design Accountable would assign
  ownership to an actor that cannot verify or ship the outcome.
- **The contract is the transfer mechanism.** Tenant isolation (ADR-0033) forbids
  importing platform code, so a written brief + (later) baseline-published artifacts
  are the only lawful way to carry conventions into a tenant repo.
- **Promote-don't-copy is the anti-drift lever.** Three Vue apps is the threshold at
  which silo duplication starts to bite; routing reuse through the shared baseline
  (ADR-0038) keeps the apps from diverging further.
- **Reusing ADR-0038's model keeps governance consistent.** Preventive (the brief /
  forbidden-list) plus detective (conformance + Renovate) is the same pattern already
  accepted for the backend baseline.

## Consequences

### Positive

- Every frontend handoff starts from the same contract instead of re-negotiating.
- Design innovation has a defined path into shared standards, not into a new silo.
- Clear single-owner accountability per decision; no ambiguous "the AI did it".
- Forbidden-list prevents the common wrong-but-plausible choices (UI lib,
  `keycloak-js`, `axios`, SSR) before they reach a repo.

### Negative

- Adds a Phase-0 authoring step and a Phase-2 conformance step Claude Code must run
  every time — design output is never merged as-is.
- Realizing "promote, don't copy" depends on the shared baseline gaining frontend
  artifacts (reusable Node/Vite workflow, shared tsconfig/eslint/prettier/Tailwind
  preset, auth composable), which ADR-0038 currently defers — tracked as follow-up.

### Neutral

- Codifies a division of labor that already implicitly exists; it does not change the
  mandated stack (ADR-0004) or the tenant isolation model (ADR-0033).
- The brief template and seed `CONVENTIONS.md` live in `manyfold-platform` but are
  written to be copied into tenant repos, consistent with the CI scaffold pattern.

## References

- ADR-0004 (application stack), ADR-0018 (identity / OIDC), ADR-0033 (multi-tenancy &
  isolation), ADR-0037 (tenant image build/publish/deploy, private), ADR-0038
  (platform baseline & alignment, private).
- The frontend design brief template (private) — the Phase-0 contract template.
- The tenant repository scaffold's seed frontend `CONVENTIONS.md` (private) — seed
  conventions for tenant frontends.
- Reference apps: the internal reference app's frontend (primary, private),
  `apps/website/frontend` (secondary).
