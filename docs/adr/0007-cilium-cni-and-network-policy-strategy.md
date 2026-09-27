# ADR 0007: Cilium CNI and Network Policy Strategy

## Table of Contents

- [Status](#status)
- [Context](#context)
- [Decision](#decision)
- [Rationale](#rationale)
- [Implementation Details](#implementation-details)
- [Consequences](#consequences)
- [Alternatives Considered](#alternatives-considered)
- [Implementation Phases](#implementation-phases)
- [References](#references)
- [Notes](#notes)

## Status

Accepted

## Context

The Manyfold Platform requires a Container Network Interface (CNI) plugin to provide pod networking in Kubernetes. Beyond basic connectivity, we need network policy enforcement for security isolation and observability for debugging network issues.

### Requirements

1. **Pod Networking**: Must provide reliable pod-to-pod and pod-to-service communication
2. **Network Policies**: Must support Kubernetes NetworkPolicy for workload isolation
3. **Observability**: Must provide visibility into network flows for debugging and security monitoring
4. **Local Development**: Must work reliably on local Kind clusters with Podman
5. **Production Path**: Must be suitable for both local development and future production use
6. **L7 Filtering**: Desirable to have HTTP-aware policies for API-level security

### CNI Plugins Evaluated

| Plugin | Performance | Network Policies | L7 Support | Observability | Kind Support |
|--------|-------------|------------------|------------|---------------|--------------|
| **Cilium** | Excellent (eBPF) | Excellent | Yes | Hubble | Excellent |
| **Calico** | Good | Excellent | Partial | Limited | Good |
| **Kindnet** | Basic | None | No | None | Native |
| **Weave** | Good | Good | No | Limited | Good |
| **Flannel** | Good | None | No | None | Good |

## Decision

We will use **Cilium** as the CNI plugin with **Hubble** for network observability.

### Configuration

- **Cilium Version**: 1.18.6 (LTS)
- **Installation Method**: Helm chart via `setup-cilium.sh`
- **IPAM Mode**: Kubernetes (uses cluster pod CIDR)
- **Hubble Components**: Relay + UI enabled
- **Hubble Metrics**: DNS, drop, TCP, flow, ICMP, HTTP

### Network Policy Strategy

1. **Default Stance**: Allow all traffic initially (no default-deny)
2. **Progressive Hardening**: Add policies as services mature
3. **Policy Types**: Use CiliumNetworkPolicy for advanced features, standard NetworkPolicy for portability
4. **L7 Policies**: Reserved for API endpoints requiring path/method filtering

## Rationale

### Why Cilium?

**eBPF-Based Performance**:
- Cilium uses eBPF (extended Berkeley Packet Filter) for datapath processing
- Operates at kernel level without user-space proxies for L3/L4
- Significantly faster than iptables-based CNIs at scale
- Lower latency and CPU overhead for network operations

**Comprehensive Network Policies**:
- Full Kubernetes NetworkPolicy support
- CiliumNetworkPolicy (CNP) for advanced features:
  - L7 (HTTP/gRPC/Kafka) filtering
  - DNS-based policies
  - Cross-cluster policies
  - Identity-based security (not just IP-based)

**Hubble Observability**:
- Real-time network flow visibility
- Service dependency mapping
- Policy decision auditing (allowed/dropped)
- Metrics for Prometheus integration
- Web UI for visual flow analysis

**Kind Cluster Compatibility**:
- Official Cilium documentation covers Kind installation
- Works with `disableDefaultCNI: true` in Kind config
- Tested and supported configuration

**Production-Ready**:
- CNCF Graduated project (highest maturity level)
- Used by major enterprises (Google, AWS, Azure, Datadog)
- Active development with regular releases
- Strong community and commercial support (Isovalent)

### Why Not Other CNIs?

**Kindnet (Kind Default)**:
- Extremely basic - only provides connectivity
- No network policy support whatsoever
- No observability features
- Not suitable for security-conscious deployments
- Rejected: Insufficient for learning and production use

**Calico**:
- Excellent network policy support
- Good performance (though iptables-based by default)
- Less comprehensive observability (no built-in flow visualization)
- Would require separate observability tooling
- Rejected: Cilium provides better integrated observability

**Flannel**:
- Simple and reliable for basic networking
- No network policy support (requires Calico addon)
- No observability
- Rejected: Missing critical security features

**Weave**:
- Good encryption support
- Adequate network policies
- Declining community momentum
- Rejected: Less active development, weaker observability

### Why Hubble?

**Integrated Solution**:
- Hubble is built into Cilium, not a separate tool
- Shares the same eBPF datapath for zero-overhead observation
- Policy decisions visible in the same UI as flows

**Debugging Capabilities**:
- See exactly why traffic was dropped
- Trace flows across services
- Identify misconfigured policies before they cause outages

**Security Auditing**:
- Audit trail of network decisions
- Detect unexpected communication patterns
- Identify services that should be isolated

### Network Policy Strategy Rationale

**Why Not Default-Deny?**
- Local development should be friction-free initially
- Default-deny breaks connectivity until policies are written
- Better to add policies progressively as understanding grows
- Production can adopt stricter defaults when ready

**Why CiliumNetworkPolicy Over Standard NetworkPolicy?**
- Standard NetworkPolicy is portable but limited (L3/L4 only)
- CiliumNetworkPolicy enables L7 filtering (HTTP paths, methods)
- Can use both: standard for basic isolation, Cilium for advanced
- Example: Restrict `/admin` endpoints to specific sources

**L3/L4 vs L7 Policy Guidance**:

| Use Case | Policy Type | Reason |
|----------|-------------|--------|
| Basic pod isolation | L3/L4 (CiliumNetworkPolicy) | Fast, no proxy overhead |
| Namespace isolation | L3/L4 (CiliumNetworkPolicy) | Sufficient granularity |
| API path restrictions | L7 (CiliumNetworkPolicy) | Requires HTTP awareness |
| Rate limiting by endpoint | L7 (CiliumNetworkPolicy) | Needs request inspection |

## Implementation Details

> Note (2026-09-27): the local kind cluster is retired, see [ADR-0054's amendment of 2026-09-27](0054-public-platform-and-private-instance-repositories.md#amendment-2026-09-27-no-demo-tree-throwaway-cloud-instances-instead); the Kind configuration, the local Cilium values and the `infrastructure/clusters/local/` files in this section went with it, and the cloud configuration stands.

### Kind Cluster Configuration

```yaml
# infrastructure/clusters/local/kind/cluster-config-cilium.yaml
networking:
  disableDefaultCNI: true  # Required for Cilium
  podSubnet: "10.244.0.0/16"
  serviceSubnet: "10.96.0.0/12"
```

### Cilium Helm Values

#### Local (Kind) Configuration

For Kind clusters, kube-proxy replacement **must be enabled** for hostPort to work. The ingress
controller uses hostPort to bind ports 80/443 on the node, which requires Cilium's eBPF datapath.

```bash
# Get API server address (required for kube-proxy replacement)
API_SERVER_IP=$(kubectl get endpoints kubernetes -o jsonpath='{.subsets[0].addresses[0].ip}')
API_SERVER_PORT=$(kubectl get endpoints kubernetes -o jsonpath='{.subsets[0].ports[0].port}')

helm upgrade --install cilium cilium/cilium \
    --version 1.18.6 \
    --namespace kube-system \
    --set operator.replicas=1 \
    --set hubble.relay.enabled=true \
    --set hubble.ui.enabled=true \
    --set hubble.metrics.enableOpenMetrics=true \
    --set hubble.metrics.enabled="{dns,drop,tcp,flow,icmp,http}" \
    --set ipam.mode=kubernetes \
    --set kubeProxyReplacement=true \
    --set k8sServiceHost="${API_SERVER_IP}" \
    --set k8sServicePort="${API_SERVER_PORT}" \
    --set hostPort.enabled=true \
    --set nodePort.enabled=true
```

**Why kube-proxy replacement is required locally:**
- Kind uses `extraPortMappings` to expose node ports to the host
- The Cilium Gateway uses `hostNetwork` mode to bind ports 80/443 on the host
- Without kube-proxy replacement, Cilium doesn't handle hostPort via eBPF
- The CNI portmap plugin doesn't work correctly with Cilium's CNI chain
- Reference: [Cilium issue #31168](https://github.com/cilium/cilium/issues/31168)

#### Cloud Configuration

For cloud deployments (EKS, GKE, AKS), kube-proxy replacement is **recommended** for performance
but not strictly required. Cloud environments typically use LoadBalancer services instead of hostPort.

```bash
helm upgrade --install cilium cilium/cilium \
    --version 1.18.6 \
    --namespace kube-system \
    --set operator.replicas=1 \
    --set hubble.relay.enabled=true \
    --set hubble.ui.enabled=true \
    --set hubble.metrics.enableOpenMetrics=true \
    --set hubble.metrics.enabled="{dns,drop,tcp,flow,icmp,http}" \
    --set ipam.mode=kubernetes \
    --set kubeProxyReplacement=true \
    --set k8sServiceHost="KUBERNETES_API_ENDPOINT" \
    --set k8sServicePort="443"
```

**Key differences from local:**
- `k8sServiceHost`: Use the cloud provider's API server endpoint (e.g., EKS endpoint URL)
- `hostPort.enabled`: Not required - use LoadBalancer instead of hostPort
- May need additional cloud-specific settings (e.g., ENI mode for EKS)

#### Configuration Comparison

| Setting | Local (Kind) | Cloud | Reason |
|---------|--------------|-------|--------|
| `kubeProxyReplacement` | `true` (required) | `true` (recommended) | Local: hostPort; Cloud: performance |
| `k8sServiceHost` | Node IP from endpoints | Cloud API endpoint | Different API server access |
| `hostPort.enabled` | `true` (required) | `false` (optional) | Cloud uses LoadBalancer |
| `nodePort.enabled` | `true` (required with hostPort) | `true` | General service support |

### Example L3/L4 Policy (Backend Isolation)

```yaml
apiVersion: cilium.io/v2
kind: CiliumNetworkPolicy
metadata:
  name: backend-isolation
  namespace: website
spec:
  endpointSelector:
    matchLabels:
      app: website-backend
  ingress:
    - fromEndpoints:
        - matchLabels:
            io.cilium.gateway/owning-gateway-name: main
      toPorts:
        - ports:
            - port: "8080"
    - fromEndpoints:
        - matchLabels:
            app: website-frontend
      toPorts:
        - ports:
            - port: "8080"
    - fromEntities:
        - host  # Allow kubelet health checks
      toPorts:
        - ports:
            - port: "8080"
```

### Example L7 Policy (HTTP Path Filtering)

```yaml
apiVersion: cilium.io/v2
kind: CiliumNetworkPolicy
metadata:
  name: backend-l7-policy
  namespace: website
spec:
  endpointSelector:
    matchLabels:
      app: website-backend
  ingress:
    - fromEndpoints:
        - matchLabels:
            io.cilium.gateway/owning-gateway-name: main
      toPorts:
        - ports:
            - port: "8080"
          rules:
            http:
              - method: "GET"
                path: "/api/.*"
              - method: "GET"
                path: "/health/.*"
```

### Hubble Access

```bash
# Via ingress (with --with-ingress)
http://localhost/hubble/

# Via port-forward
kubectl port-forward -n kube-system svc/hubble-ui 12000:80
# Then open http://localhost:12000
```

### Directory Structure

```
infrastructure/clusters/local/
├── kind/
│   ├── cluster-config.yaml          # Default (kindnet)
│   └── cluster-config-cilium.yaml   # Cilium CNI
├── manifests/
│   └── cilium-policies/
│       ├── README.md
│       ├── backend-isolation.yaml   # L3/L4 example
│       └── backend-l7-policy.yaml   # L7 example
└── scripts/
    └── setup-cilium.sh              # Install/uninstall/status
```

## Consequences

### Positive

- **Enhanced Security**: Network policies enable defense-in-depth isolation
- **Deep Observability**: Hubble provides unprecedented network visibility
- **Production Parity**: Same CNI works locally and in production
- **Future-Proof**: eBPF is the direction Kubernetes networking is heading
- **L7 Capabilities**: Can implement API-level security without service mesh
- **Performance**: eBPF datapath is faster than iptables at scale
- **Learning Platform**: Excellent for understanding Kubernetes networking

### Negative

- **Complexity**: More complex than kindnet (additional component to manage)
- **Resource Overhead**: Cilium agent runs on each node (~256MB RAM)
- **Bootstrap Dependency**: Must be installed before other pods can start
- **Helm Requirement**: Requires Helm for installation (additional tool)
- **Learning Curve**: CiliumNetworkPolicy has its own syntax to learn
- **Environment-Specific Config**: Local and cloud require different Cilium settings (see Helm Values section)

### Mitigations

- **Complexity**: Automated via `setup-cilium.sh` script and cluster.sh flag
- **Resources**: Acceptable for learning platform; production can tune limits
- **Bootstrap**: Handled by cluster.sh installing Cilium before other components
- **Helm**: Already commonly used; documented in prerequisites
- **Learning**: Example policies provided with documentation

## Alternatives Considered

### Stay with Kindnet + Separate Observability

- **Pros**: Simpler, no CNI configuration needed
- **Cons**: No network policies, would need separate tool for observability
- **Decision**: Rejected - missing critical security features

### Calico + Prometheus/Grafana for Observability

- **Pros**: Mature, well-documented, good policy support
- **Cons**: No integrated flow visualization, requires assembling multiple tools
- **Decision**: Rejected - Cilium+Hubble provides better integrated experience

### Service Mesh (Istio/Linkerd) Instead of CNI Policies

- **Pros**: L7 everywhere, mTLS, advanced traffic management
- **Cons**: Heavy overhead, complexity far exceeds current needs
- **Decision**: Rejected - Cilium L7 policies sufficient for current scope
- **Note**: Can add service mesh later if needed; Cilium compatible with both

### Cilium Without Hubble

- **Pros**: Slightly lower resource usage
- **Cons**: Loses primary observability benefit
- **Decision**: Rejected - Hubble is key differentiator

## Implementation Phases

### Phase 1: Foundation (Complete)
- [x] Create Kind config with `disableDefaultCNI: true`
- [x] Implement `setup-cilium.sh` script
- [x] Add `--with-cilium` flag to `cluster.sh`
- [x] Enable Hubble relay and UI
- [x] Create example network policies
- [x] Add Hubble ingress for UI access

### Phase 2: Hardening (Future)
- [ ] Define baseline policies for all namespaces
- [ ] Implement default-deny with explicit allows
- [ ] Add Hubble metrics to Prometheus
- [ ] Create Grafana dashboards for network visibility
- [ ] Document policy testing procedures

### Phase 3: Production (Future)
- [ ] Evaluate Cilium cluster mesh for multi-cluster
- [ ] Implement Cilium encryption (WireGuard)
- [ ] Add Cilium network policy CRD validation
- [ ] Integrate policy changes into CI/CD pipeline

## References

- [Cilium Documentation](https://docs.cilium.io/)
- [Cilium Kind Installation](https://docs.cilium.io/en/stable/gettingstarted/k8s-install-default/)
- [Hubble Documentation](https://docs.cilium.io/en/stable/gettingstarted/hubble/)
- [CiliumNetworkPolicy Reference](https://docs.cilium.io/en/stable/security/policy/)
- [Cilium L7 Policy Examples](https://docs.cilium.io/en/stable/security/policy/language/#http)
- [eBPF Introduction](https://ebpf.io/)
- Local implementation: `infrastructure/clusters/local/scripts/setup-cilium.sh`
- Example policies: `infrastructure/clusters/local/manifests/cilium-policies/`

## Notes

This decision was made during Phase 1 of platform development (January 2026). The choice of Cilium positions the platform for advanced networking scenarios while providing immediate value through Hubble observability. Revisit if:

- Resource constraints make Cilium overhead problematic
- A service mesh (Istio/Linkerd) is adopted that provides equivalent features
- Cilium complexity outweighs benefits for team size
