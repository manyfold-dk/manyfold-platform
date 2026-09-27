# Deploy Applications

Procedures for deploying applications to the cloud cluster.

**Placeholders.** Replace each placeholder with the value of your installation before you run a command:

| Placeholder | Value |
|---|---|
| `<domain>` | The public apex domain of the installation. The ArgoCD UI is at `argocd.<domain>` |
| `<app>`, `<app-name>` | The directory name of the application under `apps/`, and the name of its ArgoCD Application |
| `<workflow>` | The file name of the deploy workflow of the application in `.github/workflows/`. The name ends with `-blacksmith.yml` |

## Table of Contents

- [Deployment Methods](#deployment-methods)
- [GitOps Deployment (Recommended)](#gitops-deployment-recommended)
- [CI Deployment](#ci-deployment)
- [Image Updates](#image-updates)
- [Rollback](#rollback)
- [Deployment Checklist](#deployment-checklist)
- [Troubleshooting](#troubleshooting)
- [Related](#related)

## Deployment Methods

| Method | Best For | Mechanism |
|--------|----------|-----------|
| **GitOps (ArgoCD)** | Production deployments | Push to Git → ArgoCD syncs |
| **GitHub Actions + Blacksmith** | CI/CD builds | Workflow builds, commits tags, ArgoCD syncs |
| **Manual kubectl** | Emergency/debugging | Direct apply (not recommended) |

## GitOps Deployment (Recommended)

### How It Works

1. Developer pushes code/manifest changes to Git
2. GitHub webhook notifies ArgoCD (instant sync)
3. ArgoCD detects changes and syncs resources
4. Kubernetes applies the new state

### Deploy a New Application

1. **Create ArgoCD Application manifest**:
   ```yaml
   # platform/argocd/cloud/applications/my-app.yaml
   apiVersion: argoproj.io/v1alpha1
   kind: Application
   metadata:
     name: my-app
     namespace: argocd
   spec:
     project: default
     source:
       repoURL: https://github.com/manyfold-dk/manyfold-platform.git
       targetRevision: main
       path: infrastructure/clusters/cloud/manifests/my-app
     destination:
       server: https://kubernetes.default.svc
       namespace: my-app
     syncPolicy:
       automated:
         prune: true
         selfHeal: true
       syncOptions:
         - CreateNamespace=true
   ```

2. **Add to kustomization**:
   ```bash
   # Edit platform/argocd/cloud/applications/kustomization.yaml
   # Add: - my-app.yaml to resources
   ```

3. **Create application manifests**:
   ```
   infrastructure/clusters/cloud/manifests/my-app/
   ├── deployment.yaml
   ├── service.yaml
   ├── ingress.yaml
   └── kustomization.yaml
   ```

4. **Commit and push**:
   ```bash
   git add .
   git commit -m "feat: add my-app deployment"
   git push
   ```

5. **ArgoCD syncs automatically** (webhook triggers instant sync)

### Update Existing Application

1. **Update manifests** in `infrastructure/clusters/cloud/manifests/<app>/`

2. **Commit and push**:
   ```bash
   git add .
   git commit -m "update: change X in my-app"
   git push
   ```

3. **Monitor sync**:
   ```bash
   argocd app get <app-name>
   # Or check ArgoCD UI: https://argocd.<domain>
   ```

### Force Sync

If automatic sync doesn't trigger:

```bash
# Via CLI
argocd app sync <app-name>

# Via kubectl
kubectl patch application <app-name> -n argocd --type merge \
  -p '{"metadata":{"annotations":{"argocd.argoproj.io/refresh":"hard"}}}'
```

## CI Deployment

GitHub Actions on Blacksmith runners build the cloud app images. No cluster runs a CI engine.

### Trigger a Workflow

```bash
gh workflow run website-blacksmith.yml
gh workflow run slack-bot-blacksmith.yml
gh workflow run jump-server-blacksmith.yml
gh workflow run <workflow>   # Every other app: its *-blacksmith.yml file in .github/workflows/
```

### CI Flow

1. Clone source from GitHub
2. Run app-specific backend/frontend checks as separate jobs
3. Build and push the deployable image to GHCR
4. Commit the new image tag to Git manifests
5. ArgoCD detects the commit and syncs

### Deployment Notifications

Blacksmith deploy jobs post successful deployments to `#deployments` when the
GitHub repository secret `SLACK_WEBHOOK_DEPLOYMENTS_URL` is configured. The
Slack message includes the GitHub Actions workflow name and run link so it is
clear that GitHub Actions/Blacksmith was the deploy engine.

### CI Engine Switch

Each cloud app has a source-controlled switch in `infrastructure/ci/`:

```text
CI_MODE=blacksmith
DEPLOY_ENGINE=blacksmith
```

> **Warning:** Do not set `CI_MODE=tekton` or `DEPLOY_ENGINE=tekton`. The Tekton pipelines are retired.
> With `CI_MODE=tekton`, no engine builds the app.
> With `DEPLOY_ENGINE=tekton`, no engine commits the image tag.

### Monitor a Workflow

```bash
# List recent runs
gh run list --workflow <workflow> --limit 5

# Check the status of a run
gh run view <run-id>

# Show the logs of the failed steps
gh run view <run-id> --log-failed
```

## Image Updates

### Automatic (CI)

The deploy job of the workflow commits the image tags to the manifests:
- `apps/website/overlays/cloud/backend-deployment.yaml`
- `apps/website/overlays/cloud/kustomization.yaml`
- `apps/slack-bot/overlays/cloud/`
- `platform/resources/cloud/jump-server/deployment.yaml`
- For every other app: the `manifest-dir` (or `MANIFEST_DIR`) of its deploy workflow, usually `apps/<app>/overlays/cloud/`

### Manual Image Update

1. **Update deployment manifest**:
   ```yaml
   spec:
     containers:
       - name: backend
         image: ghcr.io/manyfold-dk/manyfold-platform/website-backend:main-abc1234  # New tag
   ```

2. **Commit and push**

## Rollback

See [rollback.md](rollback.md) for rollback procedures.

## Deployment Checklist

Before deploying:

- [ ] Code reviewed and tested
- [ ] Image builds successfully
- [ ] Manifests valid (lint with `kubectl apply --dry-run=client`)
- [ ] Resource requests/limits appropriate
- [ ] Health checks configured
- [ ] Secrets configured (if needed)

After deploying:

- [ ] ArgoCD shows Synced and Healthy
- [ ] All pods Running
- [ ] Health endpoints responding
- [ ] Logs clean (no errors)
- [ ] Metrics appearing in Grafana

## Troubleshooting

### Application not syncing

```bash
# Check application status
argocd app get <app-name>

# Check for sync errors
kubectl describe application <app-name> -n argocd
```

### Pods not starting

```bash
# Check pod status
kubectl get pods -n <namespace>
kubectl describe pod <pod-name> -n <namespace>

# Check events
kubectl get events -n <namespace> --sort-by='.lastTimestamp'
```

### Image pull errors

```bash
# Verify imagePullSecrets
kubectl get deployment <name> -n <namespace> -o yaml | grep -A5 imagePullSecrets

# Check secret exists
kubectl get secret ghcr-credentials -n <namespace>
```

## Related

- [rollback.md](rollback.md) - Rollback procedures
- [debug.md](debug.md) - Debugging applications
- [../cluster/debug-networking.md](../cluster/debug-networking.md) - Network issues
