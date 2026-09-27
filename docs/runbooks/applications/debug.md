# Debug Applications

Procedures for debugging application issues in the cluster.

**Placeholders.** Replace each placeholder with the value of your installation before you run a command:

| Placeholder | Value |
|---|---|
| `<cloud-kubeconfig>` | The path of the kubeconfig file for the cloud cluster |
| `<domain>` | The public apex domain of the installation. The platform serves `grafana.<domain>`, `argocd.<domain>`, `auth.<domain>` and `auth-admin.<domain>` below the apex domain |
| `<realm>` | The Keycloak realm of the website |
| `<namespace>`, `<pod-name>`, `<name>`, `<label>`, `<port>` | The Kubernetes objects that you debug |

## Table of Contents

- [Quick Diagnostics](#quick-diagnostics)
- [Common Issues](#common-issues)
  - [Pod CrashLoopBackOff](#pod-crashloopbackoff)
  - [Pod ImagePullBackOff](#pod-imagepullbackoff)
  - [Pod Pending](#pod-pending)
  - [OOMKilled](#oomkilled)
  - [Service Not Reachable](#service-not-reachable)
  - [Slow Response Times](#slow-response-times)
- [Interactive Debugging](#interactive-debugging)
- [Application-Specific Debugging](#application-specific-debugging)
  - [Backend (Quarkus/Java)](#backend-quarkusjava)
  - [Frontend (nginx)](#frontend-nginx)
  - [Keycloak / OAuth2 Proxy Logout](#keycloak--oauth2-proxy-logout)
  - [Private Admin Domains / Split DNS](#private-admin-domains--split-dns)
- [Observability Tools](#observability-tools)
- [Collecting Debug Information](#collecting-debug-information)
- [Related](#related)

## Quick Diagnostics

### Check Application Status

```bash
# Pod status
kubectl get pods -n <namespace>
kubectl get pods -n <namespace> -o wide  # With node info

# Recent events
kubectl get events -n <namespace> --sort-by='.lastTimestamp' | tail -20

# Deployment status
kubectl rollout status deployment/<name> -n <namespace>
```

### Check Logs

```bash
# Current logs
kubectl logs -n <namespace> <pod-name>
kubectl logs -n <namespace> -l app=<label>  # All pods with label

# Previous container (after crash)
kubectl logs -n <namespace> <pod-name> --previous

# Follow logs
kubectl logs -n <namespace> <pod-name> -f

# With timestamps
kubectl logs -n <namespace> <pod-name> --timestamps
```

### Via Grafana/Loki

1. Open Grafana: `https://grafana.<domain>`
2. Go to Explore → Select Loki
3. Query: `{namespace="<namespace>", pod=~"<pod-pattern>.*"}`

## Common Issues

### Pod CrashLoopBackOff

**Symptoms:** Pod repeatedly crashes and restarts.

**Diagnosis:**
```bash
# Check exit code and reason
kubectl describe pod <pod-name> -n <namespace> | grep -A10 "Last State"

# Check logs from crashed container
kubectl logs <pod-name> -n <namespace> --previous
```

**Common Causes:**
- Application error on startup
- Missing configuration/secrets
- Resource limits too low
- Health check failing

### Pod ImagePullBackOff

**Symptoms:** Pod can't pull container image.

**Diagnosis:**
```bash
kubectl describe pod <pod-name> -n <namespace> | grep -A5 "Events"
```

**Resolutions:**
```bash
# Check image exists
docker pull <image-name>  # Or check GHCR

# Check imagePullSecrets
kubectl get deployment <name> -n <namespace> -o yaml | grep -A3 imagePullSecrets

# Verify secret exists and is correct
kubectl get secret <secret-name> -n <namespace> -o yaml
```

### Pod Pending

**Symptoms:** Pod stuck in Pending state.

**Diagnosis:**
```bash
kubectl describe pod <pod-name> -n <namespace> | grep -A10 "Events"
```

**Common Causes:**
- Insufficient resources (CPU/memory)
- Node selector/affinity can't be satisfied
- PVC not bound
- Too many pods on nodes

### OOMKilled

**Symptoms:** Container killed due to memory limit.

**Diagnosis:**
```bash
kubectl describe pod <pod-name> -n <namespace> | grep OOMKilled
kubectl top pod <pod-name> -n <namespace>
```

**Resolution:**
- Increase memory limit in deployment
- Fix memory leak in application
- Add JVM memory tuning (for Java apps)

### Service Not Reachable

**Diagnosis:**
```bash
# Check service and endpoints
kubectl get svc -n <namespace>
kubectl get endpoints -n <namespace>

# Verify pod labels match service selector
kubectl get svc <svc-name> -n <namespace> -o yaml | grep -A5 selector
kubectl get pods -n <namespace> --show-labels

# Test from another pod
kubectl run debug --image=busybox:1.28 --rm -it --restart=Never -- \
  wget -O- http://<service-name>.<namespace>.svc.cluster.local:<port>
```

### Slow Response Times

**Diagnosis:**
```bash
# Check resource usage
kubectl top pods -n <namespace>

# Check for resource throttling
kubectl describe pod <pod-name> -n <namespace> | grep -A5 "Resources"

# Check network
kubectl exec -n <namespace> <pod-name> -- curl -w "@-" -o /dev/null -s http://localhost:<port>/health <<'EOF'
     time_namelookup:  %{time_namelookup}s\n
        time_connect:  %{time_connect}s\n
     time_appconnect:  %{time_appconnect}s\n
    time_pretransfer:  %{time_pretransfer}s\n
       time_redirect:  %{time_redirect}s\n
  time_starttransfer:  %{time_starttransfer}s\n
                     ----------\n
          time_total:  %{time_total}s\n
EOF
```

## Interactive Debugging

### Exec into Running Container

```bash
kubectl exec -it -n <namespace> <pod-name> -- /bin/sh
# Or for containers with bash
kubectl exec -it -n <namespace> <pod-name> -- /bin/bash
```

### Debug with Ephemeral Container

For minimal containers without shell:

```bash
kubectl debug -it -n <namespace> <pod-name> --image=busybox:1.28 --target=<container-name>
```

### Deploy Debug Pod

```bash
kubectl run debug -n <namespace> --image=nicolaka/netshoot --rm -it --restart=Never -- bash

# Inside debug pod:
curl http://service-name:port
nslookup service-name
tcpdump -i any port 8080
```

## Application-Specific Debugging

### Backend (Quarkus/Java)

```bash
# Check Java heap
kubectl exec -n website <backend-pod> -- jcmd 1 GC.heap_info

# Thread dump
kubectl exec -n website <backend-pod> -- jcmd 1 Thread.print

# Enable debug logging (if configured)
kubectl set env deployment/website-backend -n website QUARKUS_LOG_LEVEL=DEBUG
```

### Frontend (nginx)

```bash
# Check nginx config
kubectl exec -n website <frontend-pod> -- cat /etc/nginx/nginx.conf

# Check nginx error log
kubectl exec -n website <frontend-pod> -- cat /var/log/nginx/error.log
```

### Keycloak / OAuth2 Proxy Logout

If `https://<domain>/api/v1/auth/logout` returns `502`, examine the logs of the website backend and of Keycloak immediately:

```bash
kubectl --kubeconfig <cloud-kubeconfig> \
  logs -n website -l app=website-backend --all-containers=true --prefix --since=10m

kubectl --kubeconfig <cloud-kubeconfig> \
  logs -n keycloak statefulset/keycloak --since=10m | tail -100
```

Interpretation:
- `Failed to obtain Keycloak admin token: 401` means that the `website-backend-logout` client secret in the live Keycloak is different from the SOPS-managed secret that `website-backend` uses.
- `Failed to query Keycloak users by email: 403` means that the service-account token does not have the `realm-management` roles.
- Keycloak did not work reliably with `hostname-admin` on a Tailscale MagicDNS host. The admin console therefore uses `https://auth-admin.<domain>/admin/` through private split DNS. Only tailnet devices can reach the admin console.
- If the admin console does not load, make sure that Keycloak runs with `--hostname=https://auth.<domain> --hostname-admin=https://auth-admin.<domain>`. Then make sure that the `private-admin-gateway` and `private-admin-dns` pods are healthy.

Required live shape for `website-backend-logout` in `platform/components/keycloak/values-cloud.yaml`:
- `fullScopeAllowed: true`
- explicit service-account mapping with `serviceAccountClientId: website-backend-logout`
- explicit `username: service-account-website-backend-logout`
- `realm-management` roles: `query-users`, `view-users`, `manage-users`

Required live shape for the `website-backend` deployment:
- `MANYFOLD_KEYCLOAK_BASE_URL` points at the in-cluster Keycloak service (`http://keycloak.keycloak.svc.cluster.local`), because the backend uses the `/admin/...` APIs for logout.
- Do not point backend admin automation at the hardened public issuer host when that host blocks `/admin/`.

Required live shape for the homepage oauth2-proxy deployment:
- `oidc_issuer_url` and `login_url` use the public host `https://auth.<domain>/...`, because the browser must reach them.
- `redeem_url`, `oidc_jwks_url` and `profile_url` use the in-cluster Keycloak service (`http://keycloak.keycloak.svc.cluster.local/...`). Callback handling then does not depend on external DNS or IPv6 egress.
- `skip_oidc_discovery` is enabled, because the public and in-cluster endpoints are split.
- `whitelist_domain` allows `.<domain>`. Otherwise oauth2-proxy drops the `/oauth2/sign_out?rd=...auth.<domain>...` redirects.

Required live shape for the `website-backend` logout redirect:
- `MANYFOLD_AUTH_PROXY_LOGOUT_URL` targets `https://<domain>/oauth2/sign_out?rd=...`.
- The encoded `rd` URL is the public Keycloak end-session endpoint on `https://auth.<domain>/...`.
- The redirect includes `{id_token}` and a valid `post_logout_redirect_uri`. Browser sign-out then ends the Keycloak SSO session, not only the local proxy cookie.

Useful checks:

```bash
# Secret hash in Kubernetes
kubectl --kubeconfig <cloud-kubeconfig> \
  get secret website-backend-keycloak-logout -n website \
  -o jsonpath='{.data.client-secret}' | base64 -d | shasum -a 256

# Verify the client can mint a token
SECRET=$(kubectl --kubeconfig <cloud-kubeconfig> \
  get secret website-backend-keycloak-logout -n website \
  -o jsonpath='{.data.client-secret}' | base64 -d)

curl -s -X POST \
  'https://auth.<domain>/realms/<realm>/protocol/openid-connect/token' \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  --data-urlencode 'grant_type=client_credentials' \
  --data-urlencode 'client_id=website-backend-logout' \
  --data-urlencode "client_secret=$SECRET"
```

Admin console URL: `https://auth-admin.<domain>/admin/` (private split DNS, tailnet only)

Hardening note: the admin console uses a private hostname (`auth-admin.<domain>`). Only tailnet devices resolve that hostname, through split DNS. The public hostname (`auth.<domain>`) serves only the OIDC endpoints. The public hostname does not expose the admin paths.

Operational note: a new run of `keycloak-config-cli` does not always repair a client secret that has drifted. If the live client is wrong, repair the client once through the Keycloak admin API. Then keep Git aligned with the live client.

### Private Admin Domains / Split DNS

If `auth-admin.<domain>` or the private `argocd.<domain>/argocd` path does not work from a tailnet device, check the foundation first:

```bash
kubectl --kubeconfig <cloud-kubeconfig> get application -n argocd private-admin-gateway private-admin-dns private-admin-routes
kubectl --kubeconfig <cloud-kubeconfig> get gatewayclass private-admin
kubectl --kubeconfig <cloud-kubeconfig> get gateway -n private-admin-gateway private-admin
kubectl --kubeconfig <cloud-kubeconfig> get svc -n private-admin-gateway private-admin-gateway
kubectl --kubeconfig <cloud-kubeconfig> get connector private-admin-dns
kubectl --kubeconfig <cloud-kubeconfig> get pods -n private-admin-dns -o wide
```

Interpretation:
- If `private-admin-gateway` has no address, examine the Tailscale operator logs and the events of the generated Service in the namespace `private-admin-gateway`.
- If the `private-admin-dns` pods run but the connector route is not reachable, make sure that the `/32` route is approved in the Tailscale admin console.
- If a tailnet client still gets public DNS answers, the split-DNS nameserver entry for `<domain>` is missing or points at the wrong IP address.

Useful checks:

```bash
kubectl --kubeconfig <cloud-kubeconfig> logs -n tailscale -l app.kubernetes.io/name=tailscale-operator --since=10m
kubectl --kubeconfig <cloud-kubeconfig> describe svc private-admin-gateway -n private-admin-gateway
kubectl --kubeconfig <cloud-kubeconfig> describe connector private-admin-dns
kubectl --kubeconfig <cloud-kubeconfig> exec -n private-admin-dns deploy/private-admin-dns -- sh -c 'nslookup auth-admin.<domain> 127.0.0.1 && nslookup <domain> 127.0.0.1'
```

Expected state:
- The Keycloak admin console is at `https://auth-admin.<domain>/admin/` through private split DNS.
- The Keycloak option `--hostname-admin=https://auth-admin.<domain>` is set in `values-cloud.yaml`.
- The public ArgoCD UI at `https://argocd.<domain>` stays active.

## Observability Tools

### Metrics (Prometheus/Grafana)

1. Open Grafana: `https://grafana.<domain>`
2. Check application dashboards
3. Look for:
   - Request rate
   - Error rate
   - Latency percentiles
   - Resource usage

### Traces (Tempo)

1. In Grafana, go to Explore → Tempo
2. Search by:
   - Service name
   - Trace ID (from logs)
   - Duration

### Logs (Loki)

```logql
# Errors in namespace
{namespace="website"} |= "error"

# Specific pod with JSON parsing
{namespace="website", pod=~"backend.*"} | json | level="ERROR"

# Filter by time
{namespace="website"} | json | __error__="" | level="ERROR"
```

## Collecting Debug Information

For escalation or offline analysis:

```bash
# Pod description
kubectl describe pod <pod-name> -n <namespace> > pod-describe.txt

# All logs
kubectl logs <pod-name> -n <namespace> --all-containers > pod-logs.txt

# Events
kubectl get events -n <namespace> --sort-by='.lastTimestamp' > events.txt

# Resource status
kubectl get all -n <namespace> -o yaml > namespace-resources.yaml
```

## Related

- [deploy.md](deploy.md) - Deployment procedures
- [rollback.md](rollback.md) - Rollback procedures
- [../cluster/debug-networking.md](../cluster/debug-networking.md) - Network debugging
- [../cluster/debug-storage.md](../cluster/debug-storage.md) - Storage debugging
