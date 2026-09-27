# ADR 0013: Observability Stack

## Table of Contents

- [Status](#status)
- [Context](#context)
- [Decision](#decision)
- [Rationale](#rationale)
- [Consequences](#consequences)
- [Implementation](#implementation)
- [Alternatives Considered](#alternatives-considered)
- [Related Decisions](#related-decisions)
- [References](#references)

## Status

Accepted

## Context

The Manyfold Platform requires comprehensive observability covering metrics, logging, and distributed tracing. Phase 1 established Hubble for network observability (ADR-0007), but the platform needs full application and infrastructure observability to support debugging, performance analysis, and operational awareness.

### Requirements

1. **Metrics**: Collect and visualize metrics from Kubernetes, platform components, and applications
2. **Logging**: Aggregate logs from all pods with querying and correlation capabilities
3. **Tracing**: Distributed tracing for request flow analysis across services
4. **Alerting**: Proactive notification of issues via Slack
5. **Unified Interface**: Single pane of glass for all observability data
6. **GitOps Compatible**: All configuration managed declaratively via ArgoCD
7. **Local/Cloud Parity**: Same tooling locally and in cloud, with appropriate resource scaling
8. **Low Resource Footprint**: Must run alongside existing stack in 8GB Podman VM

### Tools Evaluated

| Category | Tool | Pros | Cons |
|----------|------|------|------|
| **Metrics** | Prometheus | Industry standard, PromQL, rich ecosystem | - |
| **Metrics** | Victoria Metrics | Lower resource usage | Less ecosystem support |
| **Visualization** | Grafana | Unified UI, extensive plugins | - |
| **Logging** | Loki | Grafana-native, label-based, efficient | Less powerful than Elasticsearch |
| **Logging** | Elasticsearch | Full-text search, mature | Heavy resource usage |
| **Tracing** | Tempo | Grafana-native, object storage, cost-effective | Younger project |
| **Tracing** | Jaeger | CNCF graduated, battle-tested | Needs Elasticsearch, separate UI |
| **Collector** | Alloy | Unified metrics/logs/traces, Grafana-native | Newer |
| **Collector** | Promtail + OTel | Mature, well-documented | Multiple components |

## Decision

We will implement the **Grafana LGTM stack** (Loki, Grafana, Tempo, Mimir/Prometheus) with **Alloy** as the unified collector.

### Stack Components

| Component | Tool | Purpose |
|-----------|------|---------|
| **Metrics Collection** | kube-prometheus-stack | Prometheus Operator + Grafana + Alertmanager |
| **Log Aggregation** | Loki | Log storage and querying |
| **Distributed Tracing** | Tempo | Trace storage and querying |
| **Unified Collector** | Alloy | Ships logs and traces from applications |
| **Visualization** | Grafana | Unified dashboards for metrics, logs, traces |
| **Alerting** | Alertmanager | Alert routing to Slack |

### Configuration Strategy

**Local (Kind) - Minimal Footprint:**
- 1-2 day retention for all data
- Single replicas for all components
- Monolithic mode for Loki and Tempo
- Filesystem storage (no object storage)
- Memory limits: Prometheus 512Mi, Grafana 256Mi, Loki 256Mi, Tempo 256Mi
- Core Kubernetes dashboards only
- Full alerting with Slack notifications

> Note (2026-09-27): the local kind cluster is retired, see [ADR-0054's amendment of 2026-09-27](0054-public-platform-and-private-instance-repositories.md#amendment-2026-09-27-no-demo-tree-throwaway-cloud-instances-instead); the local profile above and the local resource estimates below no longer apply.

**Cloud - Full-Featured:**
- 7-15 day retention
- Appropriate replicas for availability
- Distributed mode for Loki and Tempo where needed
- Object storage backend (S3/GCS)
- Higher memory limits based on workload
- All pre-built dashboards
- Comprehensive alert rules

### Access Pattern

All observability UIs accessible via ingress, consistent with existing platform services:

| Service | Local URL | Purpose |
|---------|-----------|---------|
| Grafana | `http://localhost/grafana/` | Unified dashboards, exploration |
| Prometheus | `http://localhost/prometheus/` | Direct PromQL queries, target status |
| Alertmanager | `http://localhost/alertmanager/` | Alert status, silences, routing |

This provides direct access for debugging while Grafana remains the primary interface.

### Application Instrumentation

**Quarkus Backend:**
- `quarkus-micrometer-registry-prometheus` for metrics at `/q/metrics`
- `quarkus-opentelemetry` for traces sent to Alloy
- Structured JSON logging to stdout

**Vue Frontend:**
- No server-side instrumentation (static files served by nginx)
- Client-side performance metrics via Web Vitals (Phase 5)

## Rationale

### Why kube-prometheus-stack?

**All-in-One Solution:**
- Prometheus Operator with CRDs (ServiceMonitor, PodMonitor, PrometheusRule)
- Pre-configured Grafana with 20+ Kubernetes dashboards
- Alertmanager with sensible default alerts
- kube-state-metrics and node-exporter included
- Versions tested together, reducing compatibility issues

**GitOps-Friendly:**
- ServiceMonitor resources live alongside application manifests
- Alert rules defined as PrometheusRule CRDs in Git
- All configuration is declarative Kubernetes resources

**Community Standard:**
- De facto standard for Kubernetes observability
- Extensive documentation and community support
- Easy to find solutions for common problems

### Why Grafana LGTM Stack?

**Unified Experience:**
- Single UI for metrics, logs, and traces
- Correlation between data types (click metric → see traces → see logs)
- Consistent query experience across data types
- Native integrations between components

**Grafana Alloy Benefits:**
- Single collector for logs and traces (replaces Promtail + OTel Collector)
- Native Prometheus remote-write for metrics
- Configuration as code (River language)
- Lower operational complexity than multiple collectors

**Log Collection Pattern (file-based):**
Alloy collects logs by reading directly from `/var/log/pods/` on each node using `loki.source.file`, filtered to local-node pods only. This is critical — the alternative `loki.source.kubernetes` component proxies every pod's logs through the Kubernetes apiserver as persistent HTTP CONNECT streams. In production this caused 700+ active connections pinned to one apiserver, consuming 1.8GB of memory and triggering node memory pressure alerts. File-based collection bypasses the apiserver entirely and is the standard pattern used by Promtail, Vector, and Fluent Bit.

**Cost-Effective at Scale:**
- Loki: Label-based indexing, only indexes metadata (not log content)
- Tempo: No indexing, uses object storage directly
- Both scale horizontally with object storage

### Why Tempo over Jaeger?

| Aspect | Tempo | Jaeger |
|--------|-------|--------|
| Storage | Object storage (cheap) | Elasticsearch/Cassandra (expensive) |
| Indexing | None (search by trace ID) | Full indexing |
| Grafana integration | Native | Plugin (less seamless) |
| Query language | TraceQL | Tag-based |
| Resource usage | Lower | Higher |
| Exemplars | Native (metric → trace) | Requires configuration |

Tempo's native Grafana integration enables clicking from a metric spike directly to related traces, which is valuable for debugging.

### Why Full Alerting Locally?

- Validates alert rules before cloud deployment
- Reuses existing Slack webhook from Tekton notifications
- Catches issues during local development
- Same alerting experience locally and in cloud

## Consequences

### Positive

- **Single Pane of Glass**: All observability in Grafana
- **Correlation**: Metrics → traces → logs in one click
- **GitOps Native**: All config as Kubernetes resources
- **Low Local Overhead**: Minimal footprint fits in 8GB VM
- **Cloud Ready**: Same tools scale to production
- **Active Ecosystem**: Grafana Labs actively develops all components

### Negative

- **Learning Curve**: Multiple query languages (PromQL, LogQL, TraceQL)
- **Grafana Dependency**: Heavily invested in Grafana ecosystem
- **Alloy Maturity**: Newer than Promtail, may have edge cases
- **Resource Overhead**: Even minimal setup adds ~1-2GB RAM

### Risks and Mitigations

| Risk | Mitigation |
|------|------------|
| Alloy instability | Can fall back to Promtail + OTel Collector |
| Local resource pressure | Aggressive retention limits, disable non-essential components |
| Alert fatigue | Start with minimal alerts, tune over time |
| Grafana ecosystem lock-in | All tools support open standards (OTLP, Prometheus) |
| Alloy memory overhead (~200Mi/node) | Acceptable for unified logs+traces in one DaemonSet. If scaling beyond 20 nodes, consider Vector for logs + Alloy for traces only |

## Implementation

### Phase 1: Metrics Foundation
1. Deploy kube-prometheus-stack via ArgoCD Application
2. Configure Grafana ingress at `/grafana`
3. Add ServiceMonitors for existing applications (backend, Hubble)
4. Configure Alertmanager with Slack webhook
5. Verify core Kubernetes dashboards

### Phase 2: Logging
1. Deploy Loki in monolithic mode
2. Deploy Alloy as DaemonSet for log collection (file-based via `/var/log/pods/`)
3. Configure Grafana datasource for Loki
4. Create basic log exploration dashboard
5. Add log-based alerts for errors

> **Lesson learned**: Initial deployment used `loki.source.kubernetes` which proxies logs through the apiserver. Switched to `loki.source.file` after discovering 700+ persistent CONNECT streams consuming 1.8GB on one apiserver (April 2026). See BEST-PRACTICES.md.

### Phase 3: Tracing
1. Deploy Tempo in monolithic mode
2. Configure Alloy to receive OTLP traces
3. Add `quarkus-opentelemetry` to backend
4. Configure Grafana datasource for Tempo
5. Enable exemplars for metric-to-trace correlation

### Phase 4: Refinement
1. Create application-specific dashboards
2. Define SLOs and SLI dashboards
3. Tune alert rules based on experience
4. Document runbooks for common alerts

### Phase 5: Frontend Observability
1. Integrate Web Vitals library in Vue frontend
2. Collect Core Web Vitals (LCP, FID, CLS, TTFB, INP)
3. Send metrics to backend endpoint for Prometheus ingestion
4. Create Grafana dashboard for frontend performance
5. Add alerts for Web Vitals degradation

### ArgoCD Application Structure

```
platform/
├── argocd/applications/
│   └── observability.yaml          # App-of-apps for observability
└── observability/
    ├── kustomization.yaml
    ├── namespace.yaml
    ├── prometheus-stack/
    │   ├── kustomization.yaml
    │   └── values.yaml             # Helm values for kube-prometheus-stack
    ├── loki/
    │   ├── kustomization.yaml
    │   └── values.yaml
    ├── tempo/
    │   ├── kustomization.yaml
    │   └── values.yaml
    ├── alloy/
    │   ├── kustomization.yaml
    │   └── values.yaml
    └── dashboards/
        └── *.json                  # Custom Grafana dashboards
```

### Resource Estimates (Local)

| Component | CPU Request | Memory Limit |
|-----------|-------------|--------------|
| Prometheus | 100m | 512Mi |
| Grafana | 100m | 256Mi |
| Alertmanager | 50m | 128Mi |
| Loki | 100m | 256Mi |
| Tempo | 100m | 256Mi |
| Alloy (per node) | 50m | 128Mi |
| **Total** | ~500m | ~1.5Gi |

## Alternatives Considered

### Victoria Metrics + Elasticsearch + Jaeger

- **Pros**: Lower metrics storage cost, powerful log search
- **Cons**: Three separate UIs, more operational complexity, higher log storage cost
- **Rejected**: Unified Grafana experience preferred

### Datadog / New Relic / Dynatrace

- **Pros**: Fully managed, excellent UX
- **Cons**: Cost, vendor lock-in, not self-hostable
- **Rejected**: Goal is to learn and control the stack

### OpenTelemetry Collector Instead of Alloy

- **Pros**: CNCF standard, vendor-neutral
- **Cons**: Additional component alongside Promtail, more configuration
- **Deferred**: Can revisit if Alloy proves insufficient

### Vector (Datadog) for Log Shipping

- **Pros**: Rust-based, very low memory (~20-50Mi vs Alloy ~200Mi), extremely fast, powerful VRL transforms
- **Cons**: No OTLP trace support (would still need Alloy for traces), separate tool to maintain, outside Grafana ecosystem
- **Deferred**: Not justified for a 5-node cluster where Alloy handles both logs and traces in one DaemonSet. Revisit if scaling to 20+ nodes where per-node memory savings compound.

### Fluent Bit for Log Shipping

- **Pros**: C-based (~15-30Mi), CNCF project, Loki output plugin, huge community
- **Cons**: Same dual-DaemonSet problem as Vector, more complex config syntax
- **Deferred**: Same reasoning as Vector — Alloy's unified collection wins at current scale.

## Related Decisions

- [ADR-0007: Cilium CNI and Network Policy Strategy](0007-cilium-cni-and-network-policy-strategy.md) - Hubble metrics integration
- [ADR-0004: Application Stack Tool Selection](0004-application-stack-tool-selection.md) - Quarkus observability extensions
- [ADR-0012: Local vs Cloud Environment Parity](0012-local-vs-cloud-environment-parity.md) - Resource scaling strategy
- [ADR-0023: Self-Healing Platform Architecture](0023-self-healing-platform.md) - Extends observability with automated remediation

## References

- [kube-prometheus-stack Helm Chart](https://github.com/prometheus-community/helm-charts/tree/main/charts/kube-prometheus-stack)
- [Grafana Loki Documentation](https://grafana.com/docs/loki/latest/)
- [Grafana Tempo Documentation](https://grafana.com/docs/tempo/latest/)
- [Grafana Alloy Documentation](https://grafana.com/docs/alloy/latest/)
- [Quarkus OpenTelemetry Guide](https://quarkus.io/guides/opentelemetry)
