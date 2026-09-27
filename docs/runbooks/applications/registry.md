# Registry Mirrors

Procedures for operating and troubleshooting the pull-through cache registries in the cloud cluster.

**Placeholders.** Replace each placeholder with the value of your installation before you run a command:

| Placeholder | Value |
|---|---|
| `<cloud-kubeconfig>` | The path of the kubeconfig file for the cloud cluster |
| `<internal-domain>` | The internal DNS zone of the mirror hosts. A mirror is at `<mirror-name>.<internal-domain>` |
| `<cloud-env-file>` | The path of the local, untracked file with the cloud credentials that `tofu` reads |

## Table of Contents

- [Overview](#overview)
- [Health Checks](#health-checks)
- [Common Issues](#common-issues)
- [Cache Management](#cache-management)
- [Adding a New Mirror](#adding-a-new-mirror)
- [Emergency Procedures](#emergency-procedures)
- [Related](#related)

## Overview

The cloud cluster uses pull-through cache registries to improve image pull performance and reliability. These run as Kubernetes Deployments and are accessed via HTTPS ingress.

| Component | Namespace | Purpose |
|-----------|-----------|---------|
| mirror-docker-io | registry-system | Docker Hub cache |
| mirror-ghcr-io | registry-system | GitHub Container Registry cache |
| mirror-quay-io | registry-system | Quay.io cache |
| mirror-registry-k8s-io | registry-system | Kubernetes registry cache |
| local-registry | registry-system | In-cluster built images |

## Health Checks

### Quick Status Check

```bash
# Set kubeconfig
export KUBECONFIG=<cloud-kubeconfig>

# Check all registry pods
kubectl get pods -n registry-system

# Expected: All pods Running 1/1
```

### Detailed Health Check

```bash
# Check pod status and restarts
kubectl get pods -n registry-system -o wide

# Check PVC storage usage
kubectl get pvc -n registry-system

# Check ingress and TLS
kubectl get ingress -n registry-system
kubectl get certificate -n registry-system

# Test API endpoints
curl -s -o /dev/null -w "%{http_code}" https://mirror-docker-io.<internal-domain>/v2/
# Expected: 200
```

### ArgoCD Application Status

```bash
cd infrastructure/clusters/cloud/scripts
./setup-argocd.sh status | grep -E "(registry|NAME)"
# Expected: registry    Synced    Healthy
```

## Common Issues

### Image Pull Failures

**Symptoms**: Pods stuck in `ImagePullBackOff` or `ErrImagePull`

**Diagnosis**:

```bash
# Check the failing pod
kubectl describe pod <pod-name> -n <namespace>

# Look for:
# - "failed to resolve reference" - DNS issue
# - "x509: certificate" - TLS issue
# - "unauthorized" - registry auth issue
# - "connection refused" - mirror pod down
```

**Resolution**:

1. **Mirror pod down**: Check and restart registry pod
   ```bash
   kubectl get pods -n registry-system
   kubectl rollout restart deployment/mirror-docker-io -n registry-system
   ```

2. **TLS issue**: Check certificate
   ```bash
   kubectl describe certificate registry-internal-tls -n registry-system
   # If not Ready, check cert-manager logs
   kubectl logs -n cert-manager -l app=cert-manager --tail=50
   ```

3. **DNS issue**: Verify external-dns created records
   ```bash
   dig mirror-docker-io.<internal-domain>
   kubectl logs -n external-dns -l app.kubernetes.io/name=external-dns --tail=50
   ```

### Mirror Pod CrashLooping

**Diagnosis**:

```bash
kubectl logs -n registry-system deployment/mirror-docker-io --previous
kubectl describe pod -n registry-system -l app=mirror-docker-io
```

**Common causes**:
- PVC not bound (storage issue)
- Invalid configuration
- OOM (increase memory limits)

**Resolution**:

```bash
# Check PVC status
kubectl get pvc -n registry-system

# If PVC pending, check CSI driver
kubectl get pods -n kube-system | grep hetzner-csi

# Restart the deployment
kubectl rollout restart deployment/mirror-docker-io -n registry-system
```

### Cache Returning Stale Images

**Symptoms**: Expected new image version not appearing

**Resolution**:

```bash
# Force re-pull from upstream by deleting cached layers
# This requires exec into the registry pod
kubectl exec -n registry-system deployment/mirror-docker-io -- \
  rm -rf /var/lib/registry/docker/registry/v2/repositories/<image-name>

# Or restart the deployment (clears memory cache)
kubectl rollout restart deployment/mirror-docker-io -n registry-system
```

## Cache Management

### Check Cache Size

```bash
# Check PVC usage
kubectl exec -n registry-system deployment/mirror-docker-io -- \
  du -sh /var/lib/registry

# Check all mirrors
for mirror in mirror-docker-io mirror-ghcr-io mirror-quay-io mirror-registry-k8s-io; do
  echo -n "$mirror: "
  kubectl exec -n registry-system deployment/$mirror -- du -sh /var/lib/registry 2>/dev/null || echo "N/A"
done
```

### Clear Mirror Cache

**Warning**: This will force re-download of all images from upstream.

```bash
# Delete specific image from cache
kubectl exec -n registry-system deployment/mirror-docker-io -- \
  rm -rf /var/lib/registry/docker/registry/v2/repositories/library/nginx

# Clear entire cache for a mirror.
# Quote the glob and run it with sh inside the pod. Without sh -c, the local shell expands the glob.
kubectl exec -n registry-system deployment/mirror-docker-io -- \
  sh -c 'rm -rf /var/lib/registry/docker/registry/v2/repositories/*'

# Trigger garbage collection
kubectl exec -n registry-system deployment/mirror-docker-io -- \
  registry garbage-collect /etc/docker/registry/config.yml
```

## Adding a New Mirror

### Prerequisites

- New upstream registry URL
- DNS record capability (external-dns)
- TLS certificate (cert-manager)

### Steps

1. **Update the registry base**:
   ```bash
   # Add the new Deployment and the new Service to:
   # platform/resources/cloud/registry/base/
   ```

2. **Update cloud ingress**:
   ```yaml
   # platform/resources/cloud/registry/registry-ingress.yaml
   # Add to tls.hosts, annotations, and rules
   ```

3. **Update Talos machine config**:
   ```hcl
   # infrastructure/clusters/cloud/bootstrap/talos.tf
   # Add new mirror to registries.mirrors block
   ```

4. **Apply changes**:
   ```bash
   # Push to Git - ArgoCD will sync registry resources
   git add . && git commit -m "feat(registry): add new-registry mirror"
   git push

   # Apply Talos config
   cd infrastructure/clusters/cloud/bootstrap
   source <cloud-env-file>
   tofu apply
   ```

5. **Verify**:
   ```bash
   kubectl get pods -n registry-system
   curl -I https://mirror-new-registry.<internal-domain>/v2/
   ```

## Emergency Procedures

### Bypass Mirrors (Direct Pull)

If mirrors are causing issues and you need to pull images directly:

**Note**: This requires Talos machine config changes which will temporarily disrupt the cluster.

1. **Remove mirror config from Talos** (temporary fix):
   - Comment out registry mirrors in `talos.tf`
   - Apply: `tofu apply`
   - Nodes will pull directly from upstream

2. **Restore mirrors after fixing**:
   - Uncomment registry mirrors
   - Apply: `tofu apply`

### Force Sync Registry App

```bash
# Force ArgoCD to sync registry resources
kubectl patch application registry -n argocd \
  --type merge \
  -p '{"operation": {"initiatedBy": {"username": "admin"}, "sync": {"prune": true}}}'

# Or via ArgoCD CLI
argocd app sync registry --force
```

### Recreate Registry Deployment

```bash
# Delete and let ArgoCD recreate
kubectl delete deployment mirror-docker-io -n registry-system

# ArgoCD will detect drift and recreate
# Or force sync
argocd app sync registry
```

## Related

- Cloud Registry Resources: `platform/resources/cloud/registry/README.md`
- [ADR 0008: Container Registry Strategy](../../adr/0008-container-registry-strategy.md)
- Cloud Bootstrap README: `infrastructure/clusters/cloud/bootstrap/README.md`
- [Troubleshooting Pods](debug.md)
