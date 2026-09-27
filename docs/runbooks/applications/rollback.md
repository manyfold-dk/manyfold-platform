# Rollback Application Deployments

Procedures for rolling back failed or problematic deployments.

**Placeholders.** Replace each placeholder with the value of your installation before you run a command:

| Placeholder | Value |
|---|---|
| `<domain>` | The public apex domain of the installation. The website serves its health endpoint below the apex domain |
| `<namespace>`, `<name>`, `<app-name>`, `<label>` | The Kubernetes objects and the ArgoCD Application that you roll back |

## Table of Contents

- [Rollback Methods](#rollback-methods)
- [Method 1: Kubernetes Native Rollback](#method-1-kubernetes-native-rollback)
- [Method 2: Git Revert (Recommended)](#method-2-git-revert-recommended)
- [Method 3: ArgoCD Sync to Revision](#method-3-argocd-sync-to-revision)
- [Rollback Scenarios](#rollback-scenarios)
  - [Scenario 1: Bad Image Deployed](#scenario-1-bad-image-deployed)
  - [Scenario 2: Configuration Change Broke App](#scenario-2-configuration-change-broke-app)
  - [Scenario 3: Entire Release Needs Rollback](#scenario-3-entire-release-needs-rollback)
  - [Scenario 4: Rollback Single File](#scenario-4-rollback-single-file)
- [Rollback Verification](#rollback-verification)
- [Preventing Future Issues](#preventing-future-issues)
- [Emergency Procedures](#emergency-procedures)
- [Related](#related)

## Rollback Methods

| Method | Speed | Scope | Best For |
|--------|-------|-------|----------|
| **Kubernetes rollback** | Instant | Single deployment | Quick fix |
| **Git revert** | Fast | Any change | GitOps-compliant |
| **ArgoCD sync to revision** | Fast | Application | Specific commit |

## Method 1: Kubernetes Native Rollback

Fastest option for deployment issues:

```bash
# View rollout history
kubectl rollout history deployment/<name> -n <namespace>

# Rollback to previous revision
kubectl rollout undo deployment/<name> -n <namespace>

# Rollback to specific revision
kubectl rollout undo deployment/<name> -n <namespace> --to-revision=2

# Verify rollback
kubectl rollout status deployment/<name> -n <namespace>
```

> **Note:** This creates drift from Git. ArgoCD will show OutOfSync. Either:
> - Update Git to match (commit the old image tag)
> - Let ArgoCD sync back (undoing the rollback)

## Method 2: Git Revert (Recommended)

GitOps-compliant rollback:

```bash
# Find the commit to revert
git log --oneline -10

# Revert the problematic commit
git revert <commit-hash>

# Push to trigger ArgoCD sync
git push
```

For multiple commits:
```bash
# Revert range (oldest first)
git revert --no-commit <old-commit>..<new-commit>
git commit -m "revert: rollback deployment changes"
git push
```

## Method 3: ArgoCD Sync to Revision

Sync application to a specific Git commit:

```bash
# Via ArgoCD CLI
argocd app sync <app-name> --revision <commit-hash>

# Via UI
# 1. Open application in ArgoCD
# 2. Click SYNC
# 3. Select "REVISION" and enter commit hash
```

To keep at specific revision:
```yaml
# Edit application to pin revision
spec:
  source:
    targetRevision: <commit-hash>  # Instead of 'main'
```

## Rollback Scenarios

### Scenario 1: Bad Image Deployed

Pods are crashing with the new image.

1. **Quick fix** - Kubernetes rollback:
   ```bash
   kubectl rollout undo deployment/website-backend -n website
   ```

2. **Permanent fix** - Git revert:
   ```bash
   git revert <image-update-commit>
   git push
   ```

### Scenario 2: Configuration Change Broke App

New ConfigMap or environment variables causing issues.

1. **Identify the change**:
   ```bash
   git log --oneline -10 -- infrastructure/clusters/cloud/manifests/<app>/
   ```

2. **Revert**:
   ```bash
   git revert <config-change-commit>
   git push
   ```

### Scenario 3: Entire Release Needs Rollback

Multiple commits need reverting.

1. **Find last working commit**:
   ```bash
   git log --oneline -20
   # Identify commit before the release
   ```

2. **Option A: Revert range**:
   ```bash
   git revert --no-commit <last-good>..<current>
   git commit -m "revert: rollback release X"
   git push
   ```

3. **Option B: Reset and force push** (use carefully):
   ```bash
   git reset --hard <last-good-commit>
   git push --force
   ```

### Scenario 4: Rollback Single File

Only one manifest needs reverting.

```bash
# Restore file from previous commit
git checkout <commit-hash> -- path/to/file.yaml

# Commit
git add path/to/file.yaml
git commit -m "revert: restore file.yaml to working version"
git push
```

## Rollback Verification

After any rollback:

1. **Check pods**:
   ```bash
   kubectl get pods -n <namespace>
   kubectl rollout status deployment/<name> -n <namespace>
   ```

2. **Check ArgoCD**:
   ```bash
   argocd app get <app-name>
   # Should be Synced and Healthy
   ```

3. **Test application**:
   ```bash
   curl https://<domain>/api/health/live
   ```

4. **Check logs**:
   ```bash
   kubectl logs -n <namespace> -l app=<label> --tail=50
   ```

## Preventing Future Issues

### Before Deploying

1. **Test in staging** (if available)
2. **Review changes carefully**
3. **Check deployment readiness probes**
4. **Have rollback plan ready**

### Progressive Rollouts

Consider implementing:
- Canary deployments
- Blue-green deployments
- Feature flags

### Monitoring

Set up alerts for:
- Pod restart rate
- Error rate increase
- Latency spikes

```bash
# Quick health check
kubectl get pods -n <namespace> | grep -v Running
kubectl logs -n <namespace> -l app=<label> | grep -i error | tail -20
```

## Emergency Procedures

### If ArgoCD is Down

Apply directly with kubectl:

```bash
# Get previous manifest from Git
git show <previous-commit>:path/to/deployment.yaml | kubectl apply -f -
```

### If Cluster is Partially Down

Focus on critical services:

```bash
# Priority 1: Core infrastructure (Cilium Gateway is managed by Cilium — restart if needed)
kubectl rollout restart deployment/cilium-operator -n kube-system

# Priority 2: Observability (for debugging)
kubectl rollout undo deployment/grafana -n observability

# Priority 3: Applications
kubectl rollout undo deployment/website-backend -n website
```

## Related

- [deploy.md](deploy.md) - Deployment procedures
- [debug.md](debug.md) - Debugging applications
- [../disaster-recovery/restore-backup.md](../disaster-recovery/restore-backup.md) - Data restore
