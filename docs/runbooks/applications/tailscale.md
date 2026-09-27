# Tailscale Operations

Operational procedures for Tailscale Kubernetes Operator.

**Placeholders.** Replace each placeholder with the value of your installation before you run a command:

| Placeholder | Value |
|---|---|
| `<cloud-kubeconfig>` | The path of the kubeconfig file for the cloud cluster |
| `<domain>` | The public apex domain of the installation. The private admin hosts are `auth-admin.<domain>` and `argocd.<domain>` |
| `<tailnet-domain>` | The MagicDNS domain of the tailnet. The Tailscale admin console shows it on the DNS page |
| `<resolver-ip>` | The fixed ClusterIP of the `private-admin-dns` Service: `spec.clusterIP` in `platform/resources/cloud/private-admin-dns/service.yaml` |
| `<tailscale-oauth-file>` | The name of the SOPS-encrypted file in `platform/resources/cloud/secrets/` that holds the operator OAuth credentials |
| `<cloud-env-file>` | The path of the local, untracked file with the cloud credentials |

## Table of Contents

- [Overview](#overview)
- [OAuth Token Rotation](#oauth-token-rotation)
- [Split DNS For Private Admin Domains](#split-dns-for-private-admin-domains)
- [Troubleshooting](#troubleshooting)
- [References](#references)

## Overview

The Tailscale Kubernetes Operator provides secure access to internal cluster services via the tailnet. It uses OAuth credentials to authenticate with the Tailscale API.

**Key Information:**
- Operator namespace: `tailscale`
- OAuth secret: `operator-oauth`
- Tailnet: `<tailnet-domain>`
- Token expiry: read the date of the current OAuth client in the Tailscale admin console

## OAuth Token Rotation

The OAuth client credentials must be rotated before they expire.

### Pre-Action Checklist

- [ ] Verify current token expiry date in Tailscale admin console
- [ ] Ensure you have access to the Tailscale admin console
- [ ] Ensure you have the SOPS age key configured locally

### Procedure

#### Step 1: Create New OAuth Client

1. Go to [Tailscale Admin Console](https://login.tailscale.com/admin/settings/oauth)
2. Click **Generate OAuth client**
3. Configure:
   - Description: `k8s-operator-YYYY-MM` (include date for tracking)
   - Scopes: **Devices Core**, **Auth Keys**, **Services** (all write)
   - Tags: `tag:k8s-operator`
4. Copy the **Client ID** and **Client Secret**

#### Step 2: Update Local Credentials

```bash
# Update <cloud-env-file> with new credentials
cd /path/to/manyfold-platform
vim <cloud-env-file>

# Update these values:
# TAILSCALE_OAUTH_CLIENT_ID=<new-client-id>
# TAILSCALE_OAUTH_CLIENT_SECRET=<new-client-secret>
```

#### Step 3: Re-encrypt the Secret

```bash
cd platform/resources/cloud/secrets

# Decrypt, update, and re-encrypt
sops <tailscale-oauth-file>

# In the editor, update:
#   client_id: <new-client-id>
#   client_secret: <new-client-secret>
# Save and exit
```

#### Step 4: Deploy Updated Secret

```bash
# Commit and push
git add platform/resources/cloud/secrets/<tailscale-oauth-file>
git commit -m "chore(tailscale): rotate OAuth credentials

Token expires: YYYY-MM-DD
Previous token: <old-client-id>
New token: <new-client-id>"
git push

# Sync the secrets in ArgoCD
kubectl --kubeconfig <cloud-kubeconfig> get app cloud-secrets -n argocd -o jsonpath='{.status.sync.status}'

# Force sync if needed
argocd app sync cloud-secrets --server argocd.<domain>
```

#### Step 5: Restart Operator

```bash
# Delete the operator pod to pick up new credentials
kubectl --kubeconfig <cloud-kubeconfig> delete pod -n tailscale -l app.kubernetes.io/name=tailscale-operator

# Wait for new pod to be ready
kubectl --kubeconfig <cloud-kubeconfig> wait --for=condition=ready pod -n tailscale -l app.kubernetes.io/name=tailscale-operator --timeout=120s
```

#### Step 6: Verify

```bash
# Check operator logs for successful authentication
kubectl --kubeconfig <cloud-kubeconfig> logs -n tailscale -l app.kubernetes.io/name=tailscale-operator --tail=20

# Should see: "AuthLoop: state is Running; done"

# Verify proxy pods are running
kubectl --kubeconfig <cloud-kubeconfig> get pods -n tailscale

# Test a service via Tailscale
curl -s https://grafana-cluster.<tailnet-domain> | head -5
```

#### Step 7: Delete Old OAuth Client

1. Go to [Tailscale Admin Console](https://login.tailscale.com/admin/settings/oauth)
2. Find the old OAuth client
3. Click **Revoke** to delete it

### Alternative: Rotate via Tailscale API

If you have API access, you can automate rotation:

```bash
# The OAuth client itself can be used to create API keys
# First, get an access token using the OAuth credentials
source <cloud-env-file>

# Create access token (valid for 1 hour)
ACCESS_TOKEN=$(curl -s -X POST "https://api.tailscale.com/api/v2/oauth/token" \
  -u "${TAILSCALE_OAUTH_CLIENT_ID}:${TAILSCALE_OAUTH_CLIENT_SECRET}" \
  -d "grant_type=client_credentials" | jq -r '.access_token')

# List current OAuth clients (requires additional scope)
curl -s -H "Authorization: Bearer ${ACCESS_TOKEN}" \
  "https://api.tailscale.com/api/v2/tailnet/-/keys"
```

Note: Creating new OAuth clients via API requires additional scopes that may not be available. Manual rotation via the admin console is recommended.

## Split DNS For Private Admin Domains

Private admin hosts such as `auth-admin.<domain>` and the private path for `argocd.<domain>` depend on a tailnet-only split-DNS nameserver in the cluster.

**Current resolver shape:**
- Resolver namespace: `private-admin-dns`
- Resolver service: `private-admin-dns`
- Resolver IP: `<resolver-ip>`
- Connector: `private-admin-dns`
- Private Gateway hostname target: `private-admin-gateway.<tailnet-domain>`

### Procedure

1. Verify the cluster pieces are healthy:

```bash
kubectl --kubeconfig <cloud-kubeconfig> get application -n argocd private-admin-gateway private-admin-dns private-admin-routes
kubectl --kubeconfig <cloud-kubeconfig> get gateway -n private-admin-gateway
kubectl --kubeconfig <cloud-kubeconfig> get connector private-admin-dns
kubectl --kubeconfig <cloud-kubeconfig> get svc private-admin-dns -n private-admin-dns -o wide
```

2. Add the split-DNS nameserver in the Tailscale admin console:
   - Open `https://login.tailscale.com/admin/dns`
   - `Add nameserver`
   - choose `Custom`
   - enter `<resolver-ip>`
   - enable `Restrict to search domain`
   - enter `<domain>`

3. If the Connector route is not auto-approved by ACL policy, approve the advertised route for `private-admin-dns` in the Tailscale admin console before testing.

4. Verify from a tailnet device:

```bash
nslookup auth-admin.<domain>
nslookup argocd.<domain>
```

Expected:
- both names are answered by `<resolver-ip>`
- both names resolve to `private-admin-gateway.<tailnet-domain>`

### Important rollout note

Do not switch Keycloak to `hostname-admin=https://auth-admin.<domain>` and do not narrow the public ArgoCD route until the split-DNS step above has been verified from a tailnet client.

## Troubleshooting

### Operator Shows "NeedsLogin" State

**Symptom:** Operator logs show `LocalBackend state is NeedsLogin`

**Cause:** OAuth credentials are invalid or expired

**Fix:**
1. Verify credentials in `operator-oauth` secret are correct
2. Check token hasn't expired in Tailscale admin console
3. Rotate credentials following the procedure above

### Tag Permission Error

**Symptom:** `"requested tags [tag:k8s] are invalid or not permitted"`

**Cause:** ACL policy doesn't grant the OAuth client permission to use tags

**Fix:**
1. Go to Tailscale Admin Console → Access Controls
2. Ensure `tagOwners` includes:
   ```json
   "tagOwners": {
     "tag:k8s-operator": [],
     "tag:k8s": ["tag:k8s-operator"]
   }
   ```
3. Ensure OAuth client has `tag:k8s-operator` permission

### Proxy Pods Not Creating

**Symptom:** Tailscale Ingress resources exist but no proxy StatefulSets

**Cause:** Operator not processing Ingresses

**Fix:**
1. Check operator logs for errors
2. Verify Ingress has `ingressClassName: tailscale`
3. Check backend service exists and has endpoints

### Connector Route Stuck Pending Approval

**Symptom:** `kubectl get connector private-admin-dns` shows a created connector, but tailnet clients still cannot reach `<resolver-ip>`

**Cause:** The advertised `/32` subnet route has not been auto-approved by Tailscale ACL policy

**Fix:**
1. Open Tailscale Admin Console → Machines
2. Find the `private-admin-dns-*` connector device
3. Approve the advertised route `<resolver-ip>/32`
4. Re-run `nslookup auth-admin.<domain>` from a tailnet device

### TLS Certificate Errors

**Symptom:** Connection errors or "no SNI ServerName"

**Cause:** HTTPS not enabled on tailnet or certificates not provisioned

**Fix:**
1. Enable HTTPS in Tailscale Admin Console → DNS → HTTPS Certificates
2. Wait for certificates to be provisioned (check proxy pod logs)
3. Use full hostname with proper SNI (e.g., `https://grafana-cluster.<tailnet-domain>`)

## References

- [Tailscale Kubernetes Operator](https://tailscale.com/kb/1236/kubernetes-operator)
- [Tailscale OAuth Clients](https://tailscale.com/kb/1215/oauth-clients)
- [ADR-0022: Remote Access Strategy](../../adr/0022-remote-access-strategy.md)
