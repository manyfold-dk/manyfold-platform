---
number: "0004"
series: Solution
title: Single ops agent with constrained execution
status: Accepted
date: 2026-04-19
summary: A four-agent operations department was designed, reviewed twice and dropped. One agent instead, remediation only through scripts that enforce an approval matrix, and a gate before any second agent.
changes: The credential mechanism is replaced by one sentence. The server type and location, script names, the two remediation paths' components and links to private documents are removed.
---

- Status: Accepted (promoted 2026-04-19 after the Phase 1 gate drills passed)
- Date: 2026-04-12 (proposed); 2026-04-19 (accepted)
- Deciders: Thomas

## Context and problem statement

The Manyfold platform runs on a single Kubernetes cluster (Hetzner Helsinki)
operated by one person. Alertmanager sends alerts to a chat channel, but triage,
diagnosis, and remediation are entirely manual. As the platform grows, the time
between alert and response becomes a reliability risk, especially outside
working hours.

An earlier proposal designed a 4-agent operations department with Paperclip
orchestration. Two independent reviews converged on the same conclusion: the
decomposition was premature. It introduced multi-agent coordination complexity
before proving that a single agent could handle the workload, and it made the
fictional-persona boundary the primary axis instead of authority and control
surface.

## Decision drivers

- Reliability: reduce time-to-triage and time-to-remediation for common
  incidents
- Safety: all autonomous remediation must flow through constrained, auditable
  executor scripts
- Simplicity: one operator, one cluster -- complexity must be proportional to
  scale
- Independence: the ops agent must be able to function when the platform cluster
  is degraded
- Incrementalism: prove the core loop before adding agents, tools, or authority

## Alternatives considered

| Alternative | Why rejected |
|---|---|
| Original 4-agent department with Paperclip orchestration | Solves coordination before coordination is a proven bottleneck. Introduces 7 containers, PostgreSQL, 4 secret sets, and per-agent RBAC. Ops overhead likely exceeds automation benefit at one-operator scale. |
| Run the ops agent on the existing personal-assistant agent host | Entangles personal assistant and ops trust boundaries. A failure or credential compromise in one contaminates the other. Also couples the two lifecycles (restarting one would restart the other). |
| Temporal-backed workflow with no LLM agent | Good fit for deterministic runbooks, but most incidents require diagnosis (log reading, cross-resource correlation) that is what LLMs are genuinely good at. Temporal could complement the agent in Phase 3+ for long-running remediations. |
| Keep alerts fully manual -- no agent | Acceptable today but the goal is to learn the constrained-agent pattern for the product, not just for own use. The ops agent is also a dogfooding exercise. |
| Adopt Paperclip now as the orchestration layer for a single agent | Paperclip has matured since March 2026 and is a credible Phase 4 option if multi-agent is warranted. For a single agent it is dead weight -- the hard problems (safe action selection, policy enforcement, auditability) are upstream of the orchestrator. |

## Decision

Deploy a single persistent ops agent on a dedicated server outside the cluster,
using the OpenClaw framework. The agent handles SRE triage, platform monitoring
(read-only), and cost tracking through skills, not separate agents. All
remediation flows through constrained executor scripts that enforce a severity x
remediation-risk approval matrix. The agent's credentials are tiered so that it
keeps working, with shorter lifetime and narrower scope, when the cluster that
normally issues them is degraded.

Multi-agent expansion is gated on 60+ days of operational data and requires at
least 2 of 5 specific criteria to be met: concurrent overload, tool divergence,
quality degradation, isolation needs, or coordination bottleneck.

## Consequences

### Positive

- Validates the entire product thesis (constrained agent-driven ops) with
  minimal infrastructure (1 server, 1 agent, 1 Docker Compose stack)
- Severity x risk matrix enforces approval policies in code, not prompts
- Tiered credentials let the agent operate when the cluster is sick
- Skills replace agents: SRE, platform, and cost capabilities without
  coordination overhead
- Clear Phase 4 gate prevents premature multi-agent expansion

### Negative

- Single agent is a single point of failure (mitigated by a systemd watchdog and
  health checks)
- LLM reasoning for triage is non-deterministic (mitigated by reasoning
  stability checks and human approval for S2+)
- The degraded-mode credential path adds another secret to manage and rotate
- **Two parallel remediation paths** now exist and must be kept aligned over
  time: one inside the cluster, and one through the external ops host for when
  the cluster is degraded. They serve different failure domains and should NOT
  be merged. But the approval experience, approval semantics, and audit formats
  need shared vocabulary going forward.

### Neutral

- The personal-assistant agent remains untouched -- the ops agent is a parallel
  structure
- The "operations department" concept survives as a product vision but is not a
  deployment requirement for own infrastructure
