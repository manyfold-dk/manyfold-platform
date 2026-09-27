# ADR 0023: Self-Healing Platform Architecture

## Table of Contents

- [Status](#status)
- [Context](#context)
- [Decision](#decision)
- [Rationale](#rationale)
- [Consequences](#consequences)
- [Implementation](#implementation)
- [References](#references)

## Status

Accepted

## Context

The platform generates alerts via Alertmanager when issues occur (pod failures, high resource usage, service degradation). Currently, these alerts fire to Slack and require manual human intervention. This creates:

1. **Response latency** - Hours or days before someone notices and acts on alerts
2. **Alert fatigue** - Many alerts require the same routine remediation
3. **Inconsistent responses** - Different operators may handle the same issue differently
4. **Off-hours gaps** - Issues occurring at night or weekends remain unaddressed

Common issues that could be automatically remediated:

| Issue | Manual Action | Frequency |
|-------|--------------|-----------|
| Pod CrashLoopBackOff | Delete pod to restart | Weekly |
| Deployment stuck | Rollout restart | Monthly |
| Memory pressure | Pod restart | Weekly |
| Stale connections | Service restart | Occasional |

## Decision

Implement a **self-healing platform** with the following components:

### 1. Alert Capture

Alertmanager forwards alerts to the website backend via webhook, where they are stored in-memory for correlation and remediation decisions.

### 2. Layered Health Model

Implement a layered health view spanning:
- **Infrastructure** - Node health, resource utilization
- **Cluster** - Kubernetes components, core services
- **Platform** - ArgoCD, Tekton, observability stack
- **Pipelines** - CI/CD success rates
- **Applications** - Individual service health via deep ping

### 3. Deep Health API

Applications expose `/api/v1/health/deep` endpoints that report:
- Self-health status
- Dependency health (databases, storage, external services)
- Performance indicators

### 4. Remediation Service

Automated remediation for known issues with:
- **Cooldown periods** - Prevent flapping (5 minutes between attempts)
- **Max attempts** - Stop after 3 failures in an hour
- **Audit logging** - Track all remediation actions
- **Safe actions only** - Pod restart, deployment rollout (no data-destructive operations)

### 5. Dashboard Consolidation

"Mother of Dashboards" providing single-pane-of-glass visibility across all layers with drill-down to detailed dashboards.

## Rationale

### Why Self-Healing vs. Just Alerting

| Aspect | Alert-Only | Self-Healing |
|--------|-----------|--------------|
| Response time | Minutes to hours | Seconds |
| Consistency | Varies by operator | Uniform |
| Coverage | Business hours | 24/7 |
| Toil | High (manual restarts) | Low (automated) |

### Why Layered Health Model

Traditional monitoring shows individual metrics. The layered model:
1. Groups related components logically
2. Enables root cause identification (infrastructure → application)
3. Provides appropriate detail at each level
4. Supports automated remediation at the correct layer

### Why Deep Health API vs. Simple Liveness Probes

| Aspect | Liveness Probe | Deep Health API |
|--------|----------------|-----------------|
| Scope | Self only | Self + dependencies |
| Information | Binary (up/down) | Structured health data |
| Use case | Kubernetes restarts | Informed remediation |
| Alerting | Basic | Rich context |

### Safety Constraints

The remediation service is intentionally limited:

**Allowed actions:**
- Pod deletion (controller recreates)
- Deployment rollout restart
- StatefulSet rolling restart

**Explicitly forbidden:**
- PVC deletion
- Namespace deletion
- Secret/ConfigMap modification
- Node operations
- Any data-destructive action

## Consequences

### Positive

- **Faster recovery** - Issues fixed in seconds vs. hours
- **Reduced toil** - Fewer manual interventions
- **Consistent responses** - Same remediation every time
- **Better visibility** - Unified health dashboard
- **Learning opportunity** - Remediation history informs improvements

### Negative

- **Complexity** - More components to maintain
- **False positives** - Automated actions on wrong diagnosis
- **Masking issues** - May hide underlying problems
- **Testing burden** - Must validate remediation logic

### Mitigation

- **Cooldown limits** prevent runaway remediation
- **Audit logging** tracks all automated actions
- **Human escalation** for repeated failures
- **Conservative scope** - only safe actions automated

## Implementation

### Components

| Component | File | Purpose |
|-----------|------|---------|
| Alert Webhook | `AlertWebhookResource.java` | Receive Alertmanager alerts |
| Alert Store | `AlertStoreService.java` | In-memory alert storage |
| Health Aggregation | `HealthAggregationService.java` | Combine all health sources |
| Layered Health API | `LayeredHealthResource.java` | Expose unified health endpoint |
| Remediation Service | `RemediationService.java` | Execute safe remediations |
| Remediation API | `RemediationResource.java` | Trigger/view remediations |
| Kubernetes Client | `KubernetesHealthClient.java` | Query cluster state |
| Prometheus Client | `PrometheusHealthClient.java` | Query metrics |
| Auto Remediation | `AutoRemediationService.java` | Scheduled alert-to-remediation trigger |
| RBAC | `apps/website/base/rbac.yaml` | ServiceAccount + ClusterRole for backend |
| Health Dashboard | `HealthView.vue` | Frontend layered health visualization |

### Dashboards

| Dashboard | Purpose |
|-----------|---------|
| Mother of Dashboards | Unified overview with drill-down links |
| Infrastructure Health | Node and resource utilization |
| Platform Health | ArgoCD, Tekton, observability status |

### API Endpoints

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/api/v1/health` | GET | Full layered health |
| `/api/v1/health/summary` | GET | Simplified health status |
| `/api/v1/health/deep` | GET | App deep health with dependencies |
| `/api/v1/alerts/webhook` | POST | Alertmanager webhook receiver |
| `/api/v1/alerts` | GET | Active alerts |
| `/api/v1/remediation/pod/restart` | POST | Restart a pod |
| `/api/v1/remediation/deployment/restart` | POST | Restart deployment |
| `/api/v1/remediation/summary` | GET | Remediation activity summary |
| `/api/v1/alerts/summary` | GET | Alert count by severity |

### Configuration

Alertmanager configured to send alerts to backend:

```yaml
alertmanager:
  config:
    receivers:
      - name: 'backend-webhook'
        webhook_configs:
          - url: 'http://website-backend.website.svc:8080/api/v1/alerts/webhook'
            send_resolved: true
    route:
      routes:
        - receiver: 'backend-webhook'
          matchers:
            - severity=~"warning|critical"
          continue: true
```

## References

- [ADR 0013: Observability Stack](0013-observability-stack.md)
- [Kubernetes Self-Healing Patterns](https://kubernetes.io/docs/concepts/workloads/controllers/)
- [Google SRE Book - Automation](https://sre.google/sre-book/automation-at-google/)
