# ADR 0024: Jump Server and Devcontainer in Cluster

## Table of Contents

- [Status](#status)
- [Context](#context)
- [Decision](#decision)
- [Resource Requirements](#resource-requirements)
- [Implementation](#implementation)
- [Rationale](#rationale)
- [Mobile Access](#mobile-access)
- [Alternatives Considered](#alternatives-considered)
- [Consequences](#consequences)
- [References](#references)

## Status

Implemented (Jump Server with Tailscale SSH + Mosh)

## Context

The cloud Kubernetes cluster currently requires external access via kubectl and kubeconfig files. While this works, there are scenarios where having in-cluster access to Kubernetes tooling would be valuable:

1. **Emergency access**: When external network access is unavailable or problematic
2. **Latency-sensitive operations**: Running tools directly in-cluster avoids network round-trips
3. **Web-based access**: Accessing cluster tools from any device with a browser (via Tailscale)
4. **Debugging**: Running diagnostic tools with direct access to cluster networking
5. **Development**: Potentially running the full devcontainer in-cluster for cloud development

The repository already defines a comprehensive devcontainer with all required development tools. We need to evaluate:

1. A lightweight jump server for administrative tasks (k9s, kubectl)
2. Full devcontainer deployment for development workflows

### Current Cluster Constraints

| Resource | Status |
|----------|--------|
| Worker nodes | Two small shared-vCPU cloud servers |
| Server quota | At the project's server limit - cannot add more nodes |
| Total worker capacity | Small: every workload shares a few vCPU and a few GB of RAM per node |
| Current utilization | More than half of worker RAM in use during normal operation |

## Decision

We will implement **two deployment options** to serve different use cases:

### Option A: Lightweight Jump Server (Recommended for Admin Tasks)

A minimal Alpine-based pod with k9s, kubectl, and essential CLI tools for cluster administration.

### Option B: Full Devcontainer (Optional for Development)

The complete development environment from `.devcontainer/` deployed as a StatefulSet with persistent storage.

### Key Choices

| Aspect | Jump Server | Full Devcontainer |
|--------|-------------|-------------------|
| Base image | Pre-built GHCR image (Alpine ~400MB) | Ubuntu 24.04 (~2.5GB) |
| Tools | kubectl, k9s, helm, ttyd, claude CLI, tailscale, mosh | All dev tools (Java, Node, Maven, etc.) |
| Access method | Tailscale SSH/Mosh + ttyd web terminal | kubectl exec + Tailscale Ingress |
| Persistence | 10Gi PVC for `/home/jump` and the Tailscale state (see amendment below) | PVC for home directory |
| Always running | Yes | On-demand |
| Image build | Tekton pipeline (automatic) | Manual |

## Resource Requirements

### Jump Server (Lightweight)

| Resource | Request | Limit | Notes |
|----------|---------|-------|-------|
| CPU | 50m | 500m | Mostly idle, burst for k9s rendering |
| Memory | 128Mi | 768Mi | Tailscale + Claude CLI require headroom |
| Storage | 10Gi (`hcloud-volumes`) | None | Home directory and Tailscale state; the pod is otherwise ephemeral |
| **Image size** | ~400MB | | Alpine + kubectl + k9s + helm + ttyd + claude CLI + tailscale + mosh |

**Cluster impact**: Negligible. Can run on any node with minimal footprint.

### Full Devcontainer

| Resource | Request | Limit | Notes |
|----------|---------|-------|-------|
| CPU | 500m | 2000m | Java/Node compilation needs bursts |
| Memory | 1Gi | 4Gi | JVM + Node dev servers + IDE overhead |
| Storage (PVC) | 20Gi | - | Maven repo, pnpm store, workspace |
| **Image size** | ~3GB | | Full Ubuntu + JDK + Node + all CLIs |

**Cluster impact**: Significant. Requires dedicated scheduling consideration.

### Devcontainer Component Breakdown

| Component | Memory Usage | Notes |
|-----------|--------------|-------|
| JDK 25 + Quarkus dev | 512MB - 1.5GB | Hot reload adds overhead |
| Node.js + Vite | 200MB - 500MB | HMR server memory |
| k9s | 50MB - 100MB | TUI rendering |
| Shell + CLI tools | 50MB | Base overhead |
| IDE server (if used) | 500MB - 1GB | VS Code server or similar |
| **Total typical** | 1.5GB - 3.5GB | |

### Storage Requirements for Devcontainer

| Volume | Size | Purpose |
|--------|------|---------|
| Home directory | 5Gi | User configs, shell history |
| Maven repository | 5Gi | Cached dependencies |
| pnpm store | 3Gi | Node module cache |
| Workspace | 5Gi | Source code, build artifacts |
| **Total** | ~18-20Gi | |

## Implementation

### Jump Server Deployment

The jump server uses a **pre-built Docker image** pushed to GHCR. A multi-stage Dockerfile in `platform/resources/cloud/jump-server/Dockerfile` downloads all tools at build time, eliminating runtime downloads and init-container fragility.

A dedicated Tekton pipeline (`jump-server`) builds and pushes the image automatically when files in `platform/resources/cloud/jump-server/` change.

```yaml
# platform/resources/cloud/jump-server/deployment.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: jump-server
  namespace: tools
spec:
  replicas: 1
  selector:
    matchLabels:
      app.kubernetes.io/name: jump-server
  template:
    spec:
      serviceAccountName: jump-server
      imagePullSecrets:
        - name: ghcr-credentials
      containers:
        - name: jump
          image: ghcr.io/manyfold-dk/manyfold-platform/jump-server:<tag>
          resources:
            requests:
              cpu: 50m
              memory: 128Mi
            limits:
              cpu: 500m
              memory: 512Mi
```

### CI/CD Pipeline

The Tekton pipeline follows the standard pattern:

```
cancel-superseded → clone → buildah → update-manifests → notify-slack
```

- **Trigger**: GitHub webhook with path filter on `platform/resources/cloud/jump-server/`
- **Image**: `ghcr.io/manyfold-dk/manyfold-platform/jump-server:<branch>-<sha>`
- **GitOps**: Pipeline commits the new image tag, ArgoCD syncs the deployment

### Jump Server RBAC

```yaml
# platform/resources/cloud/jump-server/rbac.yaml
apiVersion: v1
kind: ServiceAccount
metadata:
  name: jump-server
  namespace: tools
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRoleBinding
metadata:
  name: jump-server-admin
subjects:
  - kind: ServiceAccount
    name: jump-server
    namespace: tools
roleRef:
  kind: ClusterRole
  name: cluster-admin  # Or create a custom role with limited permissions
  apiGroup: rbac.authorization.k8s.io
```

### Tailscale Ingress for Web Terminal

```yaml
# platform/resources/cloud/jump-server/tailscale-ingress.yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: jump-server
  namespace: tools
  annotations:
    tailscale.com/funnel: "false"
spec:
  ingressClassName: tailscale
  rules:
    - host: jump-server
      http:
        paths:
          - path: /
            pathType: Prefix
            backend:
              service:
                name: jump-server-ttyd  # Web terminal service
                port:
                  number: 7681
```

### Full Devcontainer StatefulSet

```yaml
# platform/resources/cloud/devcontainer/statefulset.yaml
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: devcontainer
  namespace: tools
spec:
  serviceName: devcontainer
  replicas: 0  # Scale up on-demand
  selector:
    matchLabels:
      app: devcontainer
  template:
    metadata:
      labels:
        app: devcontainer
    spec:
      serviceAccountName: devcontainer
      containers:
        - name: dev
          image: <registry>/<devcontainer-image>:<tag>
          command: ["/bin/zsh", "-c", "sleep infinity"]
          resources:
            requests:
              cpu: 500m
              memory: 1Gi
            limits:
              cpu: 2000m
              memory: 4Gi
          volumeMounts:
            - name: home
              mountPath: /home/vscode
            - name: workspace
              mountPath: /workspace
          ports:
            - containerPort: 8080  # Quarkus
            - containerPort: 5173  # Vite
  volumeClaimTemplates:
    - metadata:
        name: home
      spec:
        accessModes: ["ReadWriteOnce"]
        storageClassName: hcloud-volumes
        resources:
          requests:
            storage: 10Gi
    - metadata:
        name: workspace
      spec:
        accessModes: ["ReadWriteOnce"]
        storageClassName: hcloud-volumes
        resources:
          requests:
            storage: 10Gi
```

### Directory Structure

```
platform/
├── argocd/cloud/applications/
│   ├── jump-server.yaml           # ArgoCD Application
│   └── devcontainer.yaml          # ArgoCD Application (optional)
└── resources/cloud/
    ├── jump-server/
    │   ├── kustomization.yaml
    │   ├── Dockerfile             # Multi-stage build with all tools
    │   ├── namespace.yaml
    │   ├── deployment.yaml
    │   ├── rbac.yaml
    │   ├── service.yaml
    │   └── ingress.yaml           # Tailscale ingress
    └── devcontainer/
        ├── kustomization.yaml
        ├── statefulset.yaml
        ├── rbac.yaml
        ├── service.yaml
        └── tailscale-ingress.yaml

infrastructure/tekton/overlays/cloud/
├── pipelines/
│   └── jump-server-pipeline.yaml  # Build pipeline
└── triggers/
    └── jump-server-trigger-template.yaml  # Webhook trigger
```

## Rationale

### Why two options?

| Use Case | Best Option | Why |
|----------|-------------|-----|
| Quick kubectl/k9s access | Jump server | Instant startup, minimal resources |
| Emergency debugging | Jump server | Always available, low overhead |
| Full development session | Devcontainer | All tools, persistent state |
| Tekton pipeline debugging | Jump server | Read-only inspection sufficient |

### Why Alpine for jump server?

- **Minimal footprint**: ~50MB vs ~2.5GB for full devcontainer
- **Fast startup**: Seconds vs minutes
- **Security**: Smaller attack surface
- **Resource efficient**: Leaves capacity for actual workloads

### Why StatefulSet for devcontainer?

- **Persistent storage**: Retains Maven cache, pnpm store between restarts
- **Stable identity**: Consistent PVC binding
- **Scale to zero**: Don't pay resources when not in use

### Why Tailscale for access?

- Already deployed and working (ADR-0022)
- Secure access without public exposure
- Works from any network location
- HTTPS with valid certificates

## Mobile Access

The jump server supports multiple access methods from mobile devices via Tailscale.

### Option 1: Native SSH/Mosh (Recommended)

Tailscale SSH is enabled directly on the jump server, allowing native terminal access:

```bash
# From Blink Shell or any SSH client on your tailnet
ssh jump        # Standard SSH
mosh jump       # Persistent mobile session (survives network switches)
```

**Implementation details:**
- Tailscale runs in userspace networking mode (no kernel TUN device required)
- Auth key with `tag:k8s-operator` stored as Kubernetes secret
- Device registers as `jump` on the tailnet
- Default user: `user` (uid 1000), `root` also available
- Tailscale ACL allows SSH for `tag:k8s-operator` devices

### Option 2: Browser-based (ttyd)

Access `https://jump-cluster.<tailnet>` from Safari on iPhone. No additional apps needed beyond Tailscale.

### Recommended iOS Apps

| App | Price | Model | Best For |
|-----|-------|-------|----------|
| **Blink Shell** | $19.99/year | Subscription | iTerm2 users - native terminal feel, Mosh support |
| Termius | Free / $10/mo | Freemium | Cross-platform sync, SFTP, basic SSH free |
| Prompt 3 | $20 | One-time | Apple ecosystem, Shortcuts integration |
| Secure ShellFish | Free / $10 | Freemium | Files app integration, one-time Pro upgrade |

### Recommendation

**For iTerm2 users prioritizing terminal feel**: [Blink Shell](https://blink.sh) offers the closest experience to a desktop terminal with native Mosh support. However, note the annual subscription model has received mixed reviews from users who preferred the previous one-time purchase option. It's open source (GPL3), so you can compile it yourself if desired.

**For one-time purchase**: [Prompt 3](https://panic.com/prompt/) by Panic offers a polished experience with good Apple ecosystem integration.

**For free/casual use**: [Termius](https://termius.com/) free tier or [Secure ShellFish](https://secureshellfish.app/) provide solid basic SSH without payment.

### Why Mosh Matters for Mobile

Mosh (Mobile Shell) is particularly valuable for iPhone access:

- Sessions survive network switches (WiFi → cellular)
- Reconnects automatically after phone sleep
- Works better on high-latency mobile connections
- Start a session at home, commute, resume where you left off

Both Blink Shell and Prompt 3 support Mosh. Termius requires the paid tier for Mosh.

## Alternatives Considered

### Web-based IDE (Code Server, Gitpod)

| Aspect | In-cluster terminal | Web IDE |
|--------|---------------------|---------|
| Resource usage | Low (jump) / Medium (dev) | High |
| Setup complexity | Low | Medium-High |
| Feature richness | Terminal only | Full IDE |
| Latency | Minimal | IDE rendering overhead |

**Verdict**: Terminal access sufficient for most admin tasks. Web IDE adds complexity without clear benefit for a single-user platform.

### Traditional SSH Instead of Tailscale SSH

| Aspect | Tailscale SSH | Traditional SSH |
|--------|---------------|-----------------|
| Auth | Tailscale identity | SSH keys |
| Setup | Zero (uses existing tailnet) | SSH daemon, key management |
| Audit | Tailscale logs | SSH logs |
| Mobile | Native Mosh support | Requires SSH key on device |

**Verdict**: Tailscale SSH provides the best of both worlds - native SSH/Mosh access with identity-based auth and no key management.

### Dedicated VM Instead of In-Cluster

| Aspect | In-cluster | Dedicated VM |
|--------|------------|--------------|
| Cost | Included | Additional (~€4/month) |
| Network access | Native cluster networking | Requires kubeconfig |
| Management | ArgoCD managed | Manual |
| Server quota | Uses existing capacity | Blocked (at limit) |

**Verdict**: In-cluster avoids additional costs and server quota issues.

## Consequences

### Positive

- Emergency cluster access available without external network
- Zero additional infrastructure cost
- Managed via GitOps like everything else
- Secure access via Tailscale (no public exposure)
- Full devcontainer option enables cloud development workflows

### Negative

- Jump server consumes (minimal) cluster resources
- Full devcontainer requires significant resources when active
- Hetzner volume costs for devcontainer PVCs (~€2/month for 20Gi)
- Need to build and maintain devcontainer image in GHCR

### Neutral

- Jump server should not affect cluster performance (< 1% resources)
- Devcontainer designed for on-demand use (scale to zero)
- Both options accessible only via Tailscale (same security model as other tools)

## Amendment 2026-08-23: Authenticated web terminal, scoped SA, persistent home

### Authentication and authorisation

The ADR documented Tailscale as the only boundary in front of the ttyd web terminal, and
bound the ServiceAccount to `cluster-admin`. The 2026-06-10 solution top-down review
recorded that as a high finding: an unauthenticated cluster-admin shell reachable by
anything on a flat tailnet.

Two changes close it:

- ttyd requires basic auth, from the SOPS-encrypted `jump-server-credentials` Secret,
  injected as `TTYD_CREDENTIAL`. The entrypoint fails closed and refuses to start without
  it. ttyd also drops to uid 1000, so the web terminal shares one tmux server with SSH and
  mosh instead of running a second one as root.
- The ServiceAccount binds to a scoped `jump-server-operator` ClusterRole: cluster-wide
  read for debugging, targeted write for restart-by-delete and rollout restart. No Secret
  read at any scope, no RBAC write, no `escalate`, `impersonate`, or `bind`.

Two implementation notes, both found by server-side dry-run rather than by reading:

- The core API group is enumerated resource by resource instead of `resources: ["*"]`.
  RBAC is additive and has no deny rule, so `"*"` in the core group would have granted
  cluster-wide Secret read and defeated the change.
- The ClusterRoleBinding was renamed from `jump-server-admin` to `jump-server-operator`.
  `roleRef` is immutable, so repointing the existing binding in place is rejected by the
  API server; the rename lets ArgoCD prune the old binding and create the scoped one.

The liveness and readiness probes moved from `httpGet /` to `tcpSocket`. Authenticated
ttyd answers an unauthenticated `GET /` with 401, and kubelet counts only 200-399 as probe
success, so the original probes would have crash-looped the container.

Residual risk: the Tailscale SSH and mosh paths still authenticate by tailnet identity and
are unchanged by this amendment. The flat tailnet ACL grant is owned outside this
repository, by the private solution umbrella repository.

### Persistent home directory and Tailscale state

The jump server was originally stateless, with `/home/jump` on an `emptyDir`. Shell
history, `k9s` configuration, and the `claude` CLI credential cache were therefore
destroyed on every pod restart, which happens on each image bump.

The jump server now mounts a 10Gi `hcloud-volumes` PVC at `/home/jump`. Three consequences
follow, and they change the operational semantics recorded above:

- The Deployment uses `strategy: Recreate`, because the volume is ReadWriteOnce. Image
  bumps therefore involve a short outage instead of a rolling update.
- The PVC carries `argocd.argoproj.io/sync-options: Prune=false`. The ArgoCD application
  has `prune: true` and the StorageClass has `reclaimPolicy: Delete`, so without the
  annotation a manifest removal would destroy the volume.
- The Claude CLI credential cache now persists with the rest of the home directory. That
  is acceptable only because the authentication and authorisation changes above landed in
  the same release; it would not have been under the previous unauthenticated
  cluster-admin configuration.

`/var/lib/tailscale` moved onto the same volume as a `subPath`. The original design
re-registered the node from `TS_AUTHKEY` on every start, which was found on 2026-08-23 to
be actively harmful rather than merely wasteful: each restart claimed a new tailnet
hostname, the tailnet had accumulated 19 dead `jump-N` devices, and `ssh jump` had been
resolving to a node offline since 2026-05-23 while the live pod answered to `jump-18`.
Worse, the auth key had expired, so `tailscale up` failed and -- under `set -e` -- killed
the entrypoint before ttyd started. The entrypoint no longer treats a Tailscale failure as
fatal, because the web terminal reaches the pod through the operator-managed Tailscale
Ingress and does not depend on this pod's own `tailscaled`.

A tmux session on the jump server still dies with its pod. The durable asset is the home
directory, not the session. The terminal-stack modernisation plan of 2026-08-23 (private)
holds the full survival matrix.


## References

- [ADR-0022: Remote Access Strategy](0022-remote-access-strategy.md) - Tailscale setup
- [ADR-0009: Development Environment Strategy](0009-development-environment-strategy.md)
- [k9s](https://k9scli.io/) - Kubernetes TUI
- [ttyd](https://tsl0922.github.io/ttyd/) - Web-based terminal
- [Blink Shell](https://blink.sh) - iOS terminal with Mosh support (subscription)
- [Prompt 3](https://panic.com/prompt/) - iOS SSH client by Panic (one-time purchase)
- [Termius](https://termius.com/) - Cross-platform SSH client (freemium)
- [Secure ShellFish](https://secureshellfish.app/) - iOS SSH with Files integration (freemium)
- [Mosh](https://mosh.org/) - Mobile shell for persistent connections
- [Hetzner Cloud Volumes](https://docs.hetzner.com/cloud/volumes/overview)
- [Devcontainer Specification](https://containers.dev/)
