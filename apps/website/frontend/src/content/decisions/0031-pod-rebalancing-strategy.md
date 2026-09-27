---
number: "0031"
series: Platform
title: Pod rebalancing strategy
status: Accepted
date: 2026-05-09
summary: One worker reached 93% memory requests while the other sat at 23%, and the cluster fell over. Five options weighed; the Kubernetes Descheduler chosen, with build pods exempt.
changes: Node names and sizes, the list of internal workloads and the path to the configuration are removed. Two cross-references that pointed at the wrong records are corrected. A dated note is added under Neutral.
---

## Status

Accepted

## Context

The cloud cluster has two worker nodes of equal size. Most workloads are
single-replica deployments: the Argo CD components, the CI webhook listener, and
the metrics, tracing and logging back ends among them.

On 2026-05-09 the cluster experienced a cascading failure:

- worker-1 reached 93% memory requests and 316% memory limit overcommit.
  worker-2 sat at 23%.
- The kernel began thrashing -- major page faults at about 630 per second on
  worker-1.
- Liveness and readiness probes timed out across many pods on worker-1. The
  Argo CD repo server crashlooped, leaving every Argo CD application stuck in
  `Unknown` sync state. The CI webhook listener returned 5xx, failing pipeline
  triggers. The daily Velero backup ran `PartiallyFailed`.
- Recovery required manual eviction of 8 stateless pods to worker-2.

The imbalance was created by ordinary scheduling decisions over time -- once
worker-1 had room and worker-2 was new, the scheduler kept landing new pods on
worker-1 until it was packed. There is no built-in mechanism to revisit those
decisions.

Options considered:

1. **Pod topology spread constraints** -- only helps for multi-replica
   workloads. Most of our deployments are single-replica.
2. **Pod anti-affinity** -- explicit per-deployment rules. Requires editing
   many Helm values; brittle as deployments change.
3. **Right-size resource requests only** -- the scheduler trusts requests, not
   actual usage. Improves *initial* placement but doesn't recover from stale
   decisions or sudden churn.
4. **Add a third worker node** -- relieves pressure but doesn't address the
   imbalance mechanism; the same packing pattern would re-emerge under load.
5. **[Kubernetes Descheduler](https://github.com/kubernetes-sigs/descheduler)**
   -- runs periodically, evicts pods that violate balance or affinity rules so
   kube-scheduler re-places them.

## Decision

We will deploy the **Kubernetes Descheduler** as an Argo CD-managed CronJob in
the cloud cluster, configured with the `LowNodeUtilization` plugin.

Configuration:

- `kind: CronJob`, running every 10 minutes
- Thresholds: under-utilized below 30% (CPU, memory, pods), target above 60%
- `DefaultEvictor` configured to allow eviction of PVC-backed pods (the
  `PodsWithPVC` protection is **not** enabled) -- Hetzner Cloud volumes detach
  and reattach across worker nodes within the same zone, so volume-bound pods
  (Prometheus, Tempo) can rebalance
- `DefaultEvictor` `labelSelector` excludes Tekton build pods
  (`tekton.dev/taskRun` `DoesNotExist`). Build pods run multi-minute CI steps;
  an eviction mid-build destroys the work and fails the pipeline rather than
  rescheduling useful progress. The exclusion applies to every strategy because
  `DefaultEvictor` gates all of them
- Standard plugin set: `RemoveDuplicates`, `RemovePodsHavingTooManyRestarts`,
  `RemovePodsViolatingNodeAffinity`, `RemovePodsViolatingNodeTaints`,
  `RemovePodsViolatingInterPodAntiAffinity`,
  `RemovePodsViolatingTopologySpreadConstraint`, `LowNodeUtilization`

## Rationale

- **Mechanical balance with no per-deployment changes.** Most of our workloads
  are single-replica, so topology spread and anti-affinity do not help on their
  own. The descheduler rebalances the cluster *regardless* of whether individual
  deployments declare scheduling hints.
- **Recovers from drift.** Topology spread and anti-affinity influence *initial*
  placement. They do not revisit decisions once a node is cordoned, drained, or
  rebooted. The descheduler does.
- **Threshold-based, not surprise-driven.** With a 30/60% band the descheduler
  stays quiet under normal conditions and only acts when one node is materially
  under-loaded relative to the other. This prevents perpetual eviction churn.
- **Hetzner CSI compatibility.** Volumes are zone-bound and `ReadWriteOnce`, but
  they detach and reattach to a different worker on pod migration. PVC pods can
  therefore participate in rebalancing -- a precondition for not enabling
  `PodsWithPVC` protection in `DefaultEvictor`.
- **Compatible with future right-sizing.** `LowNodeUtilization` evaluates
  resource *requests*, so improving requests on the heaviest workloads
  (Prometheus, Tempo, Keycloak) compounds the descheduler's accuracy rather than
  competing with it.

## Consequences

### Positive

- The two workers stay within about 30 percentage points of each other on memory
  requests; no more thrash-driven cascading restarts.
- New deployments do not require explicit `topologySpreadConstraints` or
  anti-affinity rules to land evenly.
- Existing scheduling drift (for example after a worker reboot) is corrected
  automatically within the next 10-minute window.
- Detects and evicts crashlooping pods (`RemovePodsHavingTooManyRestarts`,
  threshold 100) -- a useful safety net when a node is failing locally.

### Negative

- Periodic eviction noise: pods on the over-loaded worker will restart on a
  different worker. With single-replica deployments this is a brief
  unavailability (5 to 30 seconds) every time the descheduler triggers a
  rebalance.
- Adds a system component to operate. A new Argo CD app to keep in sync; CronJob
  success rate must be visible (ServiceMonitor enabled).
- `PodsWithPVC` eviction triggers Hetzner CSI detach and reattach. Each
  migration takes 30 to 60 seconds of pod downtime. Acceptable for our
  workloads; PodDisruptionBudgets must be added if any future workload has a
  stricter SLA.

### Neutral

- Right-sizing of `requests` for the heavy hitters (Prometheus, Tempo, Keycloak)
  is still useful and tracked separately. The descheduler does not remove that
  need; it makes the scheduler less likely to make a bad decision in the first
  place when requests are accurate.
- Kept as cloud-only for now. The local Kind cluster is single-node, so
  rebalancing is meaningless there.

  > Note (2026-09-27): the local kind cluster is retired, see ADR 0054's amendment of 2026-09-27 (not published).

- Tekton build pods are exempt from eviction. They are ephemeral CI jobs, not
  long-lived workloads, so excluding them does not affect the steady-state
  balance the descheduler maintains.

## References

- [Kubernetes Descheduler](https://github.com/kubernetes-sigs/descheduler)
- [Descheduler Helm chart](https://kubernetes-sigs.github.io/descheduler/)
- [LowNodeUtilization plugin](https://github.com/kubernetes-sigs/descheduler#lownodeutilization)
- [ADR 0014: Cloud provider selection](/decisions/0014-cloud-provider-selection)
- ADR 0015: Kubernetes distribution (not published)
- ADR 0023: Self-healing platform architecture (not published)
