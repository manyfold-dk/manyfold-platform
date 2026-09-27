# ADR-0050: Agent Instruction Baseline & Session Altitudes

* Status: Accepted
* Date: 2026-08-23
* Decider: Thomas
* Supersedes: none
* Cross-ref: ADR-0038 (platform baseline & tenant alignment, private -- this ADR applies its
  model to agent instructions), ADR-0011 (git worktree strategy), ADR-0026 (developer workflow
  strategy), ADR-0033 (multi-tenancy & tenant isolation); the shared baseline repository
  (`baseline-agent/`) and the operator's personal-layer repository (both private); the
  agent-instruction consolidation design of 2026-05-31 and the agent cockpit design of
  2026-08-23 (both private, in the shared baseline repository).

## Table of Contents

- [Status](#status)
- [2026-09-05 Amendment](#2026-09-05-amendment)
- [Context](#context)
- [Decision](#decision)
- [Rationale](#rationale)
- [Consequences](#consequences)
- [References](#references)

## Status

Accepted (2026-08-23). Records two decisions that were taken and implemented before this ADR
was written: the vendored agent-instruction baseline (designed 2026-05-31, rolled out through
2026-06-20, `agent-1.x`) and the session-altitude model with the `Private/` cockpit (designed
and implemented 2026-08-23, a later `agent-1.x` release). The 2026-05-31 design reserved this ADR as its
last rollout step.

## 2026-09-05 Amendment

The published `agent-2` major release amends runtime loading and handoff details through
the shared baseline repository's ADR-0001 (private; its public version is
[`estate-baseline` ADR 0002](https://github.com/manyfold-dk/estate-baseline/blob/main/docs/adr/0002-shared-agent-policy-and-runtime-adapters.md)).
The original decision below remains the history of `agent-1.x`; the following rules govern
the amended behavior. Vendor/conformance, tenant isolation and write-set altitude boundaries
remain unchanged.

| Area | Current decision |
|---|---|
| Shared instructions | `POLICY.md` owns common clauses. Claude and Codex entry files contain equal inner policy blocks and independent runtime adapters; complete files need not match. |
| Global loading | Bootstrap owns both global entry points. Each consults the baseline-owned `~/.config/manyfold/agent-policy.md` only when no baseline policy block is loaded. Eager global imports are retired. |
| Skill discovery | Repo Codex links resolve into that repo's `.claude/skills`. Platform operations remain platform-owned. Common global workflow links are retained as a documented compatibility exception. |
| Handoff | Use messaging and execution capabilities actually exposed by the active runtime. The original tool names below are historical examples, not required APIs. Mailbox remains available for cross-runtime peers and requested audit trails. |
| Workflows | Apply task-sized planning and review within existing authorization. Branches still require explicit user intent. Optional process plugins cannot override baseline policy. |

All seven consumers passed conformance after publication. See the release record (private,
in the shared baseline repository) and the implementation evidence (a private review in the
umbrella repository). Runtime compatibility and broader evaluation limits remain in the
follow-up plan of 2026-09-05 (private).

## Context

Claude Code and Codex read their instructions from files in the repository they start in:
`CLAUDE.md` / `AGENTS.md`, `.claude/rules/`, `.claude/skills/`, `.claude/agents/`,
`.claude/settings.json`. By May 2026 every Manyfold repo maintained these independently. Seven
files existed in both `manyfold-platform` and the operations-agent repository and all seven had
diverged; the umbrella and the first tenant had no shared conventions at all; the operator's
personal layer (`~/.claude`, hooks, launchers) was not reproducible on a new machine.

ADR-0038 (private) had just established the shared baseline repository as the pull-based, secret-free source of
truth for the *tech stack*, with a vendor-and-conformance model and a deviation-ADR escape
hatch. The same problem shape applied to agent instructions.

A second problem surfaced in August 2026. The harness equips a session only for the directory
it starts in: a session in a repo root sees that repo's rules, skills, agents, and permissions;
a session in the umbrella repo sees nothing of its siblings (they are not subdirectories); a
session in the parent directory `~/Developer/Private/` sees every sibling's `CLAUDE.md`, rules,
and skills lazily, but had no configuration of its own. Sessions were being started in the
umbrella for cross-repo work, which is the one place that is blind to the other repos. No repo
told its agent what to do when a task needed a change in a sibling, and cross-repo handoff ran
on a custom filesystem mailbox written before the harnesses offered session messaging.

## Decision

1. **The shared baseline repository's `baseline-agent/` is the single source of truth for
   universal agent assets** -- house rules (`CLAUDE.baseline.md` / `AGENTS.baseline.md`), `rules/`, the generic
   cores of `skills/`, and `agents/`. Consumers pull them with `scripts/agent/vendor.sh
   --profile <app|docs>`, which copies the profile's files into `.claude/` and replaces a
   marker-delimited block in `CLAUDE.md` and `AGENTS.md`; `scripts/agent/check.sh` reports
   drift against the pinned `VERSION`, suppressible by an `accepted` deviation ADR in the
   consumer (the ADR-0038 contract). Coupled skills (`dev-workflow`, `verification-loop`) ship
   as a generic core plus a repo-owned, never-vendored `environment.md` overlay. Only
   universal, non-sensitive assets enter the baseline; operator-internal rules, skills, and
   agents stay in their repos.
2. **Three session altitudes, routed by the shape of the write set.** Single-repo work starts
   in that repo's root. Cross-repo reading, coupled-and-sequential cross-repo writes, and
   derived artefacts computed from estate-wide state start in the **cockpit**:
   `~/Developer/Private/`, the parent of all repos, which is not a git repository.
   Independent cross-repo work with a contract between two repos runs as one repo session per
   repo. A repo session that discovers it needs a sibling change stops and says what the
   sibling needs; it does not edit `../<sibling>`. The umbrella repo is a content repo like any
   other, not a cockpit.
3. **The cockpit and the personal layer are versioned in the operator's personal-layer
   repository** (private).
   `install.sh` symlinks `workspace/` into `~/Developer/Private/` and the personal files into
   `~/.claude` / `~/Developer`, seeds `~/.codex/config.toml`, and sources the session launchers
   from `~/.zshrc`; it is fail-closed with `--check`, `--adopt`, `--force` (timestamped backup),
   and `--uninstall`. The cockpit is itself a `docs`-profile baseline consumer.
4. **Every repo carries the solution map and the escalation rule**, vendored as the `Working
   across repositories` section of the house-rules block (added in the `agent-1.x` line). The
   map names the five solution repos with sanitised one-line purposes. A tenant on the `app`
   profile therefore learns the repo names; that exposure is accepted explicitly (the tenant
   already named the platform and umbrella repositories; the operations-agent repository is
   the only new name).
5. **Cross-repo handoff uses the harnesses' native messaging.** Claude Code `ListAgents` +
   `SendMessage` (with `notify_when_idle`), `claude --bg` to start a worker in another repo,
   `codex queue` / `codex exec -C` on the Codex side. The `agent-mailbox` skill is demoted to
   the one uncovered path (a Codex session handing work to an already running Claude session)
   and to cases that require an on-disk audit trail.

## Rationale

- **Same model as ADR-0038, same tooling shape.** Vendor + conformance + deviation ADR is
  already understood by the tenants and by the scheduled conformance workflow; agent assets
  get it for free. Marker blocks rather than `@import` because Codex's `AGENTS.md` has no
  import directive and an exact-match block is checkable.
- **The cockpit must be the parent directory, not the umbrella.** Verified against the
  harness: child `CLAUDE.md`, rules, and skills load lazily from a parent cwd (skills surface as
  `<repo>:<skill>`), while siblings never load from a sibling. `--add-dir` grants file access
  and, with an env flag, `CLAUDE.md` and rules -- but never skills, agents, or permissions -- so
  it is strictly weaker than starting in the parent.
- **Write-set routing, not topic routing.** Whether work is "platform" or "tenant" does not
  determine which session can do it safely; whether its writes land in one repo or several
  does. Derived artefacts (the plan portfolio) are the one single-repo write the cockpit keeps,
  because their inputs span every repo and only the cockpit can verify them.
- **Native messaging replaces the mailbox** because it is zero-maintenance and per-session
  permission boundaries are enforced by the harness. The mailbox's remaining value -- the
  Codex-to-running-Claude direction and a durable log -- is real but rare, so it stays as a
  fallback rather than being deleted.
- **The personal-layer repository rather than the umbrella** for the cockpit files because the cockpit is a
  machine-layout concern (it assumes siblings on disk), which is the boundary the 2026-05-31
  design already reserved for the personal layer; the solution map reaches the cockpit the same
  way it reaches every repo, by vendoring.

## Consequences

- Every consumer pins a `baseline-agent` version; `check.sh` runs in the scheduled
  `baseline-conformance` workflow and reports drift (no CI gating yet, as in ADR-0038).
- Editing a vendored file inside a consumer is drift. The fix is a baseline change plus a
  `VERSION` bump and re-vendor (the cockpit `baseline-sync` skill), or a deviation ADR.
- `environment.md` overlays are the only repo-specific part of `dev-workflow` and
  `verification-loop`; a consumer without one (the operations-agent repository, until
  2026-08-23) runs the generic text.
- An agent-operated private repository (ADR-0049, private) carries the house-rules block but is
  not a vendored consumer (no `.claude/` assets wanted in an agent-operated repo); its block is
  updated by hand and is outside conformance. A personal utilities repository is outside the
  baseline entirely.
- The personal `~/.claude/settings.json` is a symlink into the personal-layer repository; harness
  settings changes (`/model`, `/config`) show up as a diff in that repo and are committed
  there. A harness update that replaces the symlink with a plain file is reported by
  `install.sh --check` and absorbed with `--adopt`.
- `~/.codex/config.toml` is app-managed; the personal-layer installer seeds it once and only enforces
  the trusted-project invariant afterwards.
- This repo's `CLAUDE.md` no longer lists sibling repos itself; the vendored map does, and the
  split of the agent-operated repository (ADR-0049, private) remains the one repo-specific
  note.
- The pre-baseline advisory documents `docs/CLAUDE-CODE-RECOMMENDATIONS*.md` (May 2026) are
  historical: they contain no multi-repo guidance and predate the vendored assets. They move
  under `docs/claude-code/` as archived references.

## References

- The agent-instruction consolidation design of 2026-05-31 (private, shared baseline repository)
- The agent cockpit and personal-layer design of 2026-08-23 (private, shared baseline repository)
- The shared baseline repository's `baseline-agent/` -- payload, `profiles.yaml`, `VERSION`
- The shared baseline repository's `scripts/agent/{vendor.sh,check.sh,global-install.sh}`
- The personal-layer repository's `README.md` -- installer contract; its `workspace/CLAUDE.md`
  -- the cockpit routing table (both private)
- ADR-0038 (private), [ADR-0011](0011-git-worktree-strategy.md),
  [ADR-0026](0026-developer-workflow-strategy.md), [ADR-0033](0033-multi-tenancy-model-and-tenant-isolation.md)
