# GitHub Webhooks

Runbook for managing GitHub webhooks that integrate with the cloud cluster.

## Table of Contents

- [Placeholders](#placeholders)
- [Overview](#overview)
- [Quick Setup](#quick-setup)
- [Configured Webhooks](#configured-webhooks)
- [ArgoCD Webhook](#argocd-webhook)
- [Listing Webhooks](#listing-webhooks)
- [Troubleshooting](#troubleshooting)

## Placeholders

Replace these placeholders with the values of your installation:

| Placeholder | Value |
|-------------|-------|
| `<cloud-kubeconfig>` | The absolute path of the kubeconfig file for the cloud cluster |
| `<org>/<repo>` | The GitHub repository that ArgoCD watches |
| `<argocd-host>` | The public hostname of the ArgoCD UI |
| `<domain>` | The apex domain of the installation |

## Overview

The repository uses a GitHub webhook for **ArgoCD**. The webhook starts an immediate sync when a commit arrives. The webhook is faster than polling.

The setup script creates the webhook after the cluster bootstrap.

A second webhook to the Tekton Triggers event listener ran CI/CD pipelines until the Tekton decommission of 2026-06-14.
If the repository still has a webhook to a Triggers host, delete that webhook (see [Delete and Recreate Webhook](#delete-and-recreate-webhook)).

## Quick Setup

> **CAUTION:** The setup scripts in `infrastructure/clusters/cloud/scripts` do not read the
> placeholders. Each script sets the kubeconfig path in `KUBECONFIG_FILE`.
> `setup-github-webhooks.sh` also sets the repository and the webhook URL in `REPO` and
> `ARGOCD_URL`. Before you run a script, make sure that these variables hold
> the values of your installation. If they do not, change the variables first, or create the
> webhooks with the `gh api` commands in the sections below.

After cluster bootstrap and GitOps sync completes:

```bash
cd infrastructure/clusters/cloud/scripts

# Check current status
./setup-github-webhooks.sh status

# Create the webhook (idempotent)
./setup-github-webhooks.sh create
```

The script reads the secret from the cluster and creates the webhook via the GitHub API.

## Configured Webhooks

| Webhook | Endpoint | Purpose | Events |
|---------|----------|---------|--------|
| ArgoCD | `https://<argocd-host>/api/webhook` | Triggers ArgoCD sync | push |

## ArgoCD Webhook

ArgoCD webhook enables immediate sync on push (instead of waiting for poll interval).

### Kubernetes Secret

The webhook secret is stored in a SOPS-encrypted file in
`platform/resources/cloud/secrets/`.

### Create GitHub Webhook

```bash
# Get the webhook secret from cluster
ARGOCD_SECRET=$(kubectl --kubeconfig <cloud-kubeconfig> get secret argocd-webhook-secret -n argocd -o jsonpath='{.data.webhook\.secret}' | base64 -d)

# Create webhook
gh api repos/<org>/<repo>/hooks \
  --method POST \
  --input - << EOF
{
  "name": "web",
  "active": true,
  "events": ["push"],
  "config": {
    "url": "https://<argocd-host>/api/webhook",
    "content_type": "json",
    "secret": "${ARGOCD_SECRET}",
    "insecure_ssl": "0"
  }
}
EOF
```

Or manually via GitHub UI:
1. Go to: `https://github.com/<org>/<repo>/settings/hooks`
2. Click "Add webhook"
3. Payload URL: `https://<argocd-host>/api/webhook`
4. Content type: `application/json`
5. Secret: (get from Kubernetes secret above)
6. SSL verification: Enable
7. Events: Select "Just the push event"

## Listing Webhooks

```bash
# List all webhooks
gh api repos/<org>/<repo>/hooks --jq '.[] | {id, url: .config.url, events, active}'

# Check recent deliveries for a webhook
gh api repos/<org>/<repo>/hooks/<webhook-id>/deliveries --jq '.[0:5] | .[] | {delivered_at, status, status_code}'
```

## Troubleshooting

### Webhook Not Receiving Events

1. Check webhook is active:
   ```bash
   gh api repos/<org>/<repo>/hooks --jq '.[] | select(.config.url | contains("<domain>")) | {id, active}'
   ```

2. Check recent deliveries:
   ```bash
   gh api repos/<org>/<repo>/hooks/<id>/deliveries --jq '.[0]'
   ```

3. Redeliver a failed webhook:
   ```bash
   gh api repos/<org>/<repo>/hooks/<webhook-id>/deliveries/<delivery-id>/attempts --method POST
   ```

### Webhook Returns Error

| Status | Cause | Solution |
|--------|-------|----------|
| 401 | Invalid secret | Verify secret matches Kubernetes secret |
| 403 | Signature mismatch | Regenerate secret in both places |
| 404 | Endpoint not found | Check ingress and service are running |
| 502/503 | Backend unavailable | Check pods are running |

### Delete and Recreate Webhook

```bash
# Delete
gh api repos/<org>/<repo>/hooks/<webhook-id> --method DELETE

# Recreate (see sections above)
```
