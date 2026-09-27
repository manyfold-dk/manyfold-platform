# ADR 0005: ArgoCD and Crossplane Responsibility Split

## Table of Contents

- [Status](#status)
- [Context](#context)
- [Decision](#decision)
- [Rationale](#rationale)
- [Implementation Details](#implementation-details)
- [Consequences](#consequences)
- [Alternatives Considered](#alternatives-considered)
- [References](#references)
- [Notes](#notes)
- [Amendment 2026-06-05: self-service is tenant-authored claims behind admission](#amendment-2026-06-05-self-service-is-tenant-authored-claims-behind-admission)

## Status

Accepted

## Context

The Manyfold Platform requires both application deployment automation and cloud infrastructure provisioning. Two complementary tools have emerged as standards in the cloud-native ecosystem:

- **ArgoCD**: A GitOps continuous delivery tool for Kubernetes
- **Crossplane**: A Kubernetes-native infrastructure provisioning framework

Both tools operate on Kubernetes and follow declarative, Git-driven workflows, but they serve different purposes. This ADR establishes clear boundaries for when to use each tool.

### Key Considerations

1. **Separation of Concerns**: Application deployment vs infrastructure provisioning are distinct responsibilities
2. **GitOps Principles**: All changes should be version-controlled and auditable
3. **Kubernetes-Native**: Both tools use Kubernetes as the control plane
4. **Developer Experience**: Clear ownership and workflows for different resource types
5. **Blast Radius**: Infrastructure changes have different risk profiles than application deployments

## Decision

We will use **ArgoCD for application and platform deployments** and **Crossplane for cloud infrastructure provisioning**, with clear responsibility boundaries:

### ArgoCD Responsibilities

ArgoCD manages all resources that run **within** Kubernetes clusters:

- **Application Workloads**: Deployments, Services, ConfigMaps, Secrets, Ingresses
- **Platform Components**: Observability stack, ingress controllers, cert-manager, service mesh
- **Kubernetes-Native Resources**: CRDs, RBAC, NetworkPolicies, PodSecurityPolicies
- **Helm Charts and Kustomize**: Application packaging and environment overlays
- **Namespace Management**: Application and team namespace creation

### Crossplane Responsibilities

Crossplane manages all resources that exist **outside** Kubernetes clusters (cloud provider resources):

- **Compute**: VMs, managed Kubernetes clusters, container registries
- **Storage**: Object storage (S3/GCS), block storage, file storage
- **Databases**: Managed databases (RDS, Cloud SQL), Redis, message queues
- **Networking**: VPCs, subnets, security groups, load balancers, DNS records
- **Identity**: IAM roles, service accounts, managed identities
- **Secrets Management**: External secrets stores (AWS Secrets Manager, HashiCorp Vault)

### Boundary Definition

| Resource Type | Tool | Rationale |
|---------------|------|-----------|
| Pod, Deployment, StatefulSet | ArgoCD | Kubernetes workloads |
| Service, Ingress | ArgoCD | In-cluster networking |
| ConfigMap, Secret | ArgoCD | Application configuration |
| Helm releases | ArgoCD | Application packaging |
| Kustomize overlays | ArgoCD | Environment customization |
| Platform operators | ArgoCD | In-cluster platform components |
| RDS/Cloud SQL | Crossplane | Managed cloud database |
| S3/GCS buckets | Crossplane | Cloud object storage |
| VPC/Subnets | Crossplane | Cloud networking |
| IAM roles | Crossplane | Cloud identity |
| DNS records | Crossplane | External DNS management |
| Managed Kubernetes | Crossplane | Cluster provisioning |

## Rationale

### Why Separate Tools?

**Different Lifecycles**:
- Applications deploy frequently (multiple times per day)
- Infrastructure changes are infrequent and require more careful review
- Separation allows different approval workflows and rollback strategies

**Different Expertise**:
- Application developers focus on workload manifests
- Platform engineers focus on infrastructure provisioning
- Clear tool boundaries map to team responsibilities

**Risk Isolation**:
- Application deployment failures affect single services
- Infrastructure changes can impact entire environments
- Separation limits blast radius of mistakes

### Why ArgoCD for Applications?

**Kubernetes-First Design**:
- Purpose-built for Kubernetes application delivery
- Excellent visualization of application state and sync status
- Native support for Helm, Kustomize, and plain manifests

**Developer Experience**:
- Web UI for application status and troubleshooting
- Automatic sync with configurable policies
- Rollback capabilities with deployment history

**Ecosystem Integration**:
- Notifications for Slack, Teams, webhooks
- SSO integration for team access control
- Extensive plugin ecosystem

### Why Crossplane for Infrastructure?

**Kubernetes-Native Control Plane**:
- Uses familiar Kubernetes APIs and patterns
- Infrastructure defined as Kubernetes CRDs
- Leverages existing Kubernetes RBAC and namespaces

**Composition and Abstraction**:
- Composite Resource Definitions (XRDs) create platform-specific abstractions
- Teams request resources through simplified APIs
- Platform team controls underlying implementation

**Multi-Cloud Support**:
- Consistent API across AWS, GCP, Azure
- Provider-agnostic resource definitions
- Enables cloud portability and hybrid scenarios

**GitOps Compatible**:
- Crossplane manifests stored in Git alongside applications
- ArgoCD can sync Crossplane resources (meta-layer)
- Full audit trail for infrastructure changes

### Why Not Terraform?

Terraform was considered but Crossplane was chosen for:

- **Kubernetes-Native**: No separate state management or CLI workflows
- **Self-Healing**: Continuous reconciliation like Kubernetes controllers
- **Developer Self-Service**: Teams create resources via Kubernetes APIs
- **Unified Tooling**: Single control plane for apps and infrastructure

Terraform remains valuable for bootstrapping (cluster creation, initial Crossplane setup) but day-2 operations use Crossplane.

### Integration Pattern

ArgoCD and Crossplane work together in a layered approach:

```
Git Repository
     │
     ├── apps/              → ArgoCD syncs application manifests
     ├── platform/          → ArgoCD syncs platform components
     └── infrastructure/
         ├── crossplane/    → ArgoCD syncs Crossplane claims
         └── bootstrap/     → Terraform for initial cluster setup
```

1. **ArgoCD watches Git** for all Kubernetes manifests
2. **ArgoCD syncs Crossplane claims** (infrastructure requests) to the cluster
3. **Crossplane provisions cloud resources** based on claims
4. **Applications reference provisioned resources** via connection secrets

## Implementation Details

### ArgoCD Configuration

```yaml
# Application-level ArgoCD Application
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: website
  namespace: argocd
spec:
  project: default
  source:
    repoURL: https://github.com/org/manyfold-platform
    path: apps/website/overlays/production
  destination:
    server: https://kubernetes.default.svc
    namespace: website
  syncPolicy:
    automated:
      prune: true
      selfHeal: true
```

### Crossplane Configuration

```yaml
# Database claim (platform abstraction)
apiVersion: database.example.com/v1alpha1
kind: PostgreSQLInstance
metadata:
  name: website-db
  namespace: website
spec:
  parameters:
    storageGB: 20
    version: "15"
  compositionSelector:
    matchLabels:
      provider: aws
      environment: production
  writeConnectionSecretToRef:
    name: website-db-connection
```

### Directory Structure

```
infrastructure/
├── crossplane/
│   ├── providers/           # Cloud provider configurations
│   ├── compositions/        # Platform resource abstractions
│   └── claims/              # Environment-specific resource claims
│       ├── production/
│       └── staging/
└── bootstrap/               # Terraform for initial setup
    ├── kubernetes/          # Cluster provisioning
    └── crossplane/          # Crossplane installation
```

## Consequences

### Positive

- **Clear Ownership**: Distinct boundaries between application and infrastructure
- **GitOps Everywhere**: Both applications and infrastructure follow GitOps principles
- **Kubernetes-Native**: Single API paradigm for all resources
- **Self-Service**: Developers request infrastructure through familiar Kubernetes patterns
- **Audit Trail**: All changes tracked in Git with full history
- **Abstraction Layer**: Platform team controls implementation details via Compositions
- **Drift Detection**: Both tools continuously reconcile desired vs actual state

### Negative

- **Learning Curve**: Two tools to learn and operate
- **Complexity**: Additional abstraction layer with Crossplane Compositions
- **Resource Overhead**: Both ArgoCD and Crossplane controllers consume cluster resources
- **Debugging**: Troubleshooting spans multiple systems
- **Eventual Consistency**: Infrastructure provisioning is slower than in-cluster resources

### Mitigations

- Document common workflows and troubleshooting guides
- Start with simple Compositions, add abstraction as patterns emerge
- Use dedicated management cluster for ArgoCD and Crossplane in production
- Implement comprehensive observability for both tools
- Set appropriate timeouts and health checks for Crossplane resources

## Alternatives Considered

### Terraform Only

- Rejected: Not Kubernetes-native, separate state management, no continuous reconciliation
- Crossplane provides better developer experience and self-healing

### Flux Instead of ArgoCD

- Viable alternative with similar capabilities
- ArgoCD chosen for better UI and visualization
- Either tool works with Crossplane pattern

### Pulumi

- Rejected: Imperative programming model, not Kubernetes-native
- Crossplane's declarative CRDs align better with Kubernetes philosophy

### AWS Controllers for Kubernetes (ACK) / GCP Config Connector

- Rejected: Single-cloud lock-in
- Crossplane provides multi-cloud abstraction layer

### ArgoCD for Everything (Including Infrastructure)

- Rejected: ArgoCD manages Kubernetes resources, not cloud APIs
- Crossplane needed for external resource provisioning
- Note: ArgoCD does sync Crossplane manifests to cluster

## References

- [ArgoCD Documentation](https://argo-cd.readthedocs.io/)
- [Crossplane Documentation](https://crossplane.io/docs/)
- [Crossplane Composition Guide](https://docs.crossplane.io/latest/concepts/compositions/)
- [GitOps Principles](https://opengitops.dev/)
- [Kubernetes Control Plane Pattern](https://kubernetes.io/docs/concepts/architecture/controller/)

## Notes

This decision was made during initial platform architecture (January 2026). Revisit if:
- Crossplane complexity outweighs benefits for simple infrastructure needs
- Team strongly prefers Terraform workflows
- Single-cloud commitment makes provider-specific tools more appropriate
- ArgoCD limitations require alternative GitOps tooling

### Implementation (February 2026)

Initial implementation completed with:

- **Crossplane v2.1.4** installed via ArgoCD in both local and cloud clusters
- **ObjectBucket XRD** (`storage.manyfold.dk/v1alpha1`) as the platform storage API
- **Local**: `provider-nop` composition emits static SeaweedFS connection secrets
- **Cloud**: `provider-opentofu` compositions use inline HCL for Cloudflare R2 and Hetzner Object Storage
- **First consumers**: two buckets of one platform application, one on R2 and one on Hetzner

Key deviation from original design: No native Crossplane Cloudflare provider supports R2.
Used `provider-opentofu` with inline HCL as a bridge until native providers catch up.

## Amendment 2026-06-05: self-service is tenant-authored claims behind admission

The "Self-Service: developers request infrastructure through familiar Kubernetes patterns"
consequence is realised without weakening the multi-tenancy boundary: tenants author
namespaced Crossplane claims (capability #1: `storage.manyfold.dk/ObjectBucket`) in their
own repo, synced into their namespace by the operator-owned ApplicationSet. The operator
retains ownership of the XRD, the Composition, the ProviderConfig, the provider credentials,
and the OpenTofu state; a per-capability `ValidatingAdmissionPolicy` constrains what a tenant
may request. See platform ADR-0033 (Amendment 2026-06-05) for the pattern and guardrails.
