# ADR 0021: Feature Flags Service

## Table of Contents

- [Status](#status)
- [Context](#context)
- [Decision](#decision)
- [Rationale](#rationale)
- [Consequences](#consequences)
- [Implementation](#implementation)
- [References](#references)

## Status

Proposed

**Note (2026-06-12):** Demoted from Accepted. The Flipt decision was never implemented --
no deployment, manifests, or consumers exist. Re-promote only when an implementation plan
exists.

## Context

The platform needs a feature flags service to enable:

1. **Gradual rollouts** - Release features to a percentage of users incrementally
2. **Environment toggles** - Enable/disable features per environment (dev, staging, prod)
3. **A/B testing** - Run experiments comparing feature variants
4. **Kill switches** - Quickly disable features without deployment
5. **User targeting** - Target features by user ID, segments, or custom attributes

### Requirements

| Requirement | Priority |
|-------------|----------|
| Self-hosted deployment | Must have |
| Management UI | Must have |
| User/segment targeting | Must have |
| OpenFeature compatibility | Must have |
| Audit logging | Must have |
| GitOps-friendly | Nice to have |
| Minimal infrastructure | Nice to have |

### Scale

- Small scale (<1K daily users)
- Personal platform engineering lab
- Cost-conscious (prefer free/open source)

## Decision

Use **Flipt** as the feature flags service.

### Why Flipt

Flipt is a 100% open-source, self-hosted feature flag solution built in Go. It offers:

- Single binary deployment with zero external dependencies
- Git-native storage (flags stored as code in Git repositories)
- Built-in UI for flag management
- OpenFeature support (41% official coverage - highest among open-source options)
- Audit logging included in the free version
- Multiple authentication options (OIDC, JWT, Kubernetes service tokens)

## Rationale

### Options Evaluated

| Criteria | Flipt | Flagsmith | Unleash |
|----------|-------|-----------|---------|
| **License** | 100% open source | Open core | Open core |
| **Deployment** | Single Go binary | Python + PostgreSQL + Redis | Node.js + PostgreSQL + Redis |
| **OpenFeature** | 41% official coverage | 29% official coverage | 0% official coverage |
| **Audit logging** | Free | Paid tier only | Paid tier only |
| **GitOps native** | Yes | No | No |
| **UI quality** | Good | Excellent | Good |
| **Resource needs** | Minimal | Medium | Medium |
| **User targeting** | Yes | Yes (with traits) | Yes (with strategies) |

### Why Not Flagsmith

Flagsmith has an excellent UI and remote config capabilities, but:
- Audit logging requires paid tier
- Requires PostgreSQL + Redis infrastructure
- Open core model with feature restrictions

### Why Not Unleash

Unleash is mature with a plugin ecosystem, but:
- Zero official OpenFeature support
- Audit logging requires paid tier
- Requires PostgreSQL + Redis infrastructure
- Higher resource requirements

### Key Differentiators for Flipt

1. **Git-native storage** - Feature flags stored in Git, version controlled, fits existing ArgoCD GitOps workflow
2. **OpenFeature leadership** - Best-in-class support for the vendor-neutral standard
3. **Audit logging included** - No paywall for compliance features
4. **Minimal infrastructure** - No database required when using Git storage
5. **Truly open source** - No "open core" restrictions on features

## Consequences

### Positive

- **Zero database dependency** - Can use Git-native storage, reducing infrastructure complexity
- **GitOps alignment** - Flags as code in Git repositories, auditable via Git history
- **OpenFeature ready** - Vendor-neutral SDKs, easy to switch providers if needed
- **Cost effective** - 100% free, no surprise paywalls
- **Lightweight** - Minimal resource footprint for small-scale deployment
- **Built-in observability** - OpenTelemetry and Prometheus metrics integration

### Negative

- **Simpler UI** - Less polished than Flagsmith's interface
- **No built-in A/B test analytics** - Requires external analytics for experiment evaluation
- **Smaller community** - Less ecosystem compared to Unleash/Flagsmith
- **Git storage learning curve** - Team needs to understand Git-based flag management

### Neutral

- **v2 is relatively new** - Git-native storage is the v2 approach, maturing rapidly

## Implementation

### Phase 1: Infrastructure

1. Deploy Flipt via Helm chart to cloud cluster
2. Configure Git-native storage with platform repository
3. Set up OIDC authentication (when IAM is implemented)
4. Configure Prometheus ServiceMonitor for metrics

### Phase 2: Integration

1. Integrate OpenFeature SDK with backend (Java/Quarkus)
2. Integrate OpenFeature SDK with frontend (Vue/TypeScript)
3. Create initial feature flags for testing
4. Document flag management workflow

### Phase 3: Operations

1. Create Grafana dashboard for flag metrics
2. Add alerting for flag evaluation errors
3. Document runbook for common operations
4. Establish flag naming conventions and lifecycle

### Storage Configuration

```yaml
# Git-native storage (recommended)
storage:
  type: git
  git:
    repository: https://github.com/manyfold-dk/manyfold-platform.git
    ref: main
    directory: platform/feature-flags
```

### Helm Values (Example)

```yaml
# values-cloud.yaml
replicaCount: 1

resources:
  requests:
    cpu: 100m
    memory: 128Mi
  limits:
    cpu: 250m
    memory: 256Mi

# Git-native storage
config:
  storage:
    type: git
    git:
      repository: "https://github.com/manyfold-dk/manyfold-platform.git"
      ref: "main"
      directory: "platform/feature-flags"

# Prometheus metrics
metrics:
  enabled: true
  serviceMonitor:
    enabled: true
```

### SDK Integration (Examples)

**Java/Quarkus Backend:**
```java
// OpenFeature with Flipt provider
OpenFeatureAPI api = OpenFeatureAPI.getInstance();
api.setProvider(new FliptProvider("http://flipt.flipt.svc:8080"));

Client client = api.getClient();
boolean enabled = client.getBooleanValue("new-feature", false);
```

**Vue/TypeScript Frontend:**
```typescript
// OpenFeature with Flipt provider
import { OpenFeature } from '@openfeature/web-sdk';
import { FliptWebProvider } from '@flipt-io/flipt-openfeature-provider';

OpenFeature.setProvider(new FliptWebProvider('https://flipt.<domain>'));

const client = OpenFeature.getClient();
const enabled = await client.getBooleanValue('new-feature', false);
```

## References

- [Flipt Documentation](https://docs.flipt.io/)
- [Flipt GitHub](https://github.com/flipt-io/flipt)
- [Flipt Helm Charts](https://helm.flipt.io/)
- [OpenFeature](https://openfeature.dev/)
- [Flipt OpenFeature Provider](https://github.com/flipt-io/flipt-openfeature-providers)
- Issue #66: Feature Flags Service (private repository)
