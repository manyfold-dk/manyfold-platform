# ADR 0031: Pod Rebalancing Strategy

## Table of Contents

- [Status](#status)
- [Context](#context)
- [Decision](#decision)
- [Rationale](#rationale)
- [Consequences](#consequences)
- [References](#references)

## Status

Accepted

## Context

The cloud cluster has 2 worker nodes (called worker-1 and worker-2 below),
both of the same small size. Most workloads are
single-replica deployments (ArgoCD components, Tekton webhook, Slack bot,
token broker, Prometheus, Tempo, Loki, etc.).

On 2026-05-09 the cluster experienced a cascading failure:

- worker-1 reached 93% of its allocatable memory in requests, with 316% memory
  limit overcommit. worker-2 sat at 23%.
- The kernel began thrashing — major page faults at ~630/sec on worker-1.
- Liveness/readiness probes timed out across many pods on worker-1.
  `argocd-repo-server` crashlooped, leaving every ArgoCD application stuck
  in `Unknown` sync state. Tekton's GitHub webhook listener returned 5xx,
  failing pipeline triggers. Velero's daily backup ran `PartiallyFailed`.
- Recovery required manual eviction of 8 stateless pods to worker-2.

The imbalance was created by ordinary scheduling decisions over time —
once worker-1 had room and worker-2 was new, the scheduler kept landing
new pods on worker-1 until it was packed. There is no built-in mechanism
to revisit those decisions.

Options considered:

1. **Pod topology spread constraints** — only helps for multi-replica
   workloads. Most of our deployments are single-replica.
2. **Pod anti-affinity** — explicit per-deployment rules. Requires editing
   many Helm values; brittle as deployments change.
3. **Right-size resource requests only** — the scheduler trusts requests,
   not actual usage. Improves *initial* placement but doesn't recover from
   stale decisions or sudden churn.
4. **Add a third worker node** — relieves pressure but doesn't address the
   imbalance mechanism; same packing pattern would re-emerge under load.
5. **[Kubernetes Descheduler](https://github.com/kubernetes-sigs/descheduler)**
   — runs periodically, evicts pods that violate balance/affinity rules so
   kube-scheduler re-places them.

## Decision

We will deploy the **Kubernetes Descheduler** as an ArgoCD-managed CronJob
in the cloud cluster, configured with the `LowNodeUtilization` plugin.

Configuration (see `platform/components/descheduler/values-cloud.yaml`):

- `kind: CronJob`, schedule `*/10 * * * *` — every 10 minutes
- Thresholds: under-utilized below 30% (CPU/memory/pods), target above 60%
- `DefaultEvictor` configured to allow eviction of PVC-backed pods (the
  `PodsWithPVC` protection is **not** enabled) — Hetzner Cloud volumes
  detach/reattach across worker nodes within the same zone, so volume-bound
  pods (Prometheus, Tempo) can rebalance
- `DefaultEvictor` `labelSelector` excludes Tekton build pods
  (`tekton.dev/taskRun` `DoesNotExist`). Build pods run multi-minute CI
  steps; an eviction mid-build destroys the work and fails the pipeline
  rather than rescheduling useful progress. The exclusion applies to every
  strategy because `DefaultEvictor` gates all of them
- Standard plugin set: `RemoveDuplicates`, `RemovePodsHavingTooManyRestarts`,
  `RemovePodsViolatingNodeAffinity`, `RemovePodsViolatingNodeTaints`,
  `RemovePodsViolatingInterPodAntiAffinity`,
  `RemovePodsViolatingTopologySpreadConstraint`, `LowNodeUtilization`

Sync wave `-3`, alongside `metrics-server`. Namespace: `kube-system`.

## Rationale

- **Mechanical balance with no per-deployment changes.** Most of our
  workloads are single-replica, so topology spread and anti-affinity do
  not help on their own. The descheduler rebalances the cluster
  *regardless* of whether individual deployments declare scheduling hints.
- **Recovers from drift.** Topology spread and anti-affinity influence
  *initial* placement. They do not revisit decisions once a node is
  cordoned, drained, or rebooted. The descheduler does.
- **Threshold-based, not surprise-driven.** With a 30/60% band the
  descheduler stays quiet under normal conditions and only acts when one
  node is materially under-loaded relative to the other. This prevents
  perpetual eviction churn.
- **Hetzner CSI compatibility.** Volumes are zone-bound and `ReadWriteOnce`,
  but they detach and reattach to a different worker on pod migration. PVC
  pods can therefore participate in rebalancing — a precondition for not
  enabling `PodsWithPVC` protection in `DefaultEvictor`.
- **Compatible with future right-sizing.** `LowNodeUtilization` evaluates
  resource *requests*, so improving requests on the heaviest workloads
  (Prometheus, Tempo, Keycloak) compounds the descheduler's accuracy
  rather than competing with it.

## Consequences

### Positive

- Worker-1 / worker-2 stay within ~30 percentage points of each other on
  memory requests; no more thrash-driven cascading restarts.
- New deployments do not require explicit `topologySpreadConstraints` or
  anti-affinity rules to land evenly.
- Existing scheduling drift (e.g. after a worker reboot) is corrected
  automatically within the next 10-minute window.
- Detects and evicts crashlooping pods (`RemovePodsHavingTooManyRestarts`,
  threshold 100) — useful safety net when a node is failing locally.

### Negative

- Periodic eviction noise: pods on the over-loaded worker will restart on
  a different worker. With single-replica deployments this is a brief
  unavailability (5–30 s) every time the descheduler triggers a rebalance.
- Adds a system component to operate. New ArgoCD app to keep in sync;
  CronJob success rate must be visible (ServiceMonitor enabled).
- `PodsWithPVC` eviction triggers Hetzner CSI detach/reattach. Each
  migration takes ~30–60 s of pod downtime. Acceptable for our workloads;
  PodDisruptionBudgets must be added if any future workload has a stricter
  SLA.

### Neutral

- Right-sizing of `requests` for the heavy hitters (Prometheus, Tempo,
  Keycloak) is still useful and tracked separately. The descheduler does
  not remove that need; it makes the scheduler less likely to make a bad
  decision in the first place when requests are accurate.
- Kept as cloud-only for now. The local Kind cluster is single-node, so
  rebalancing is meaningless there.

  > Note (2026-09-27): the local kind cluster is retired, see [ADR-0054's amendment of 2026-09-27](0054-public-platform-and-private-instance-repositories.md#amendment-2026-09-27-no-demo-tree-throwaway-cloud-instances-instead).

- Tekton build pods are exempt from eviction. They are ephemeral CI jobs,
  not long-lived workloads, so excluding them does not affect the
  steady-state balance the descheduler maintains.

## References

- [Kubernetes Descheduler](https://github.com/kubernetes-sigs/descheduler)
- [Descheduler Helm chart](https://kubernetes-sigs.github.io/descheduler/)
- [LowNodeUtilization plugin](https://github.com/kubernetes-sigs/descheduler#lownodeutilization)
- [ADR 0014: Cloud Provider Selection](0014-cloud-provider-selection.md)
- [ADR 0015: Kubernetes Distribution](0015-kubernetes-distribution.md)
- ADR 0023: Self-Healing Platform
