# Debug Networking Issues

Procedures for diagnosing and resolving network problems in the cluster.

## Table of Contents

- [Placeholders](#placeholders)
- [Diagnostic Tools](#diagnostic-tools)
  - [Cilium Status](#cilium-status)
  - [Hubble Observability](#hubble-observability)
  - [DNS Resolution](#dns-resolution)
- [Common Issues](#common-issues)
  - [Issue: Pods Can't Reach External Services](#issue-pods-cant-reach-external-services)
  - [Issue: Service-to-Service Communication Fails](#issue-service-to-service-communication-fails)
  - [Issue: Gateway / HTTPRoute Not Working](#issue-gateway--httproute-not-working)
  - [Issue: DNS Not Resolving](#issue-dns-not-resolving)
  - [Issue: Intermittent Connectivity](#issue-intermittent-connectivity)
- [Network Policies](#network-policies)
- [External DNS Issues](#external-dns-issues)
- [Load Balancer Issues](#load-balancer-issues)
- [Collecting Network Debug Info](#collecting-network-debug-info)
- [Related](#related)

## Placeholders

Replace these placeholders with the values of your installation:

| Placeholder | Value |
|-------------|-------|
| `<hubble-host>` | The hostname of the Hubble UI |
| `<argocd-host>` | The public hostname of the ArgoCD UI |
| `<domain>` | The apex domain of the installation |
| `<public-ip>` | A public IP address that answers ping, for example a public DNS resolver |
| `<api-load-balancer>` | The name of the Hetzner load balancer in front of the Kubernetes API |

## Diagnostic Tools

### Cilium Status

```bash
# Check Cilium pods
kubectl get pods -n kube-system -l k8s-app=cilium

# Cilium status
kubectl exec -n kube-system -l k8s-app=cilium -- cilium status

# Cilium connectivity test
kubectl exec -n kube-system -l k8s-app=cilium -- cilium connectivity test
```

### Hubble Observability

Access Hubble UI at `https://<hubble-host>` for:
- Real-time flow visualization
- Service map
- DNS query monitoring
- Dropped packet analysis

```bash
# Check Hubble relay
kubectl get pods -n kube-system -l k8s-app=hubble-relay

# Hubble CLI (if installed)
hubble observe --namespace website
```

### DNS Resolution

```bash
# Test DNS from a pod
kubectl run dnstest --image=busybox:1.28 --rm -it --restart=Never -- nslookup kubernetes.default

# Check CoreDNS
kubectl get pods -n kube-system -l k8s-app=coredns
kubectl logs -n kube-system -l k8s-app=coredns
```

## Common Issues

### Issue: Pods Can't Reach External Services

**Symptoms:**
- Pods timeout when calling external APIs
- DNS resolution for external domains fails

**Diagnosis:**
```bash
# Test from a pod
kubectl run nettest --image=busybox:1.28 --rm -it --restart=Never -- sh
# Inside pod:
ping <public-ip>
nslookup google.com
wget -O- https://httpbin.org/ip
```

**Resolution:**
1. Check Cilium egress policies:
   ```bash
   kubectl get cnp -A  # CiliumNetworkPolicy
   ```

2. Verify node can reach internet:
   ```bash
   talosctl dmesg --nodes <node-ip> | grep -i network
   ```

3. Check Hetzner firewall rules

### Issue: Service-to-Service Communication Fails

**Symptoms:**
- Backend can't reach other services
- Connection refused or timeout between pods

**Diagnosis:**
```bash
# Check service endpoints
kubectl get endpoints <service-name> -n <namespace>

# Check if pods are ready
kubectl get pods -n <namespace> -o wide

# Test connectivity from another pod
kubectl exec -n <namespace> <source-pod> -- curl -v http://<service-name>:<port>
```

**Resolution:**
1. Verify service selectors match pod labels:
   ```bash
   kubectl get svc <service> -n <namespace> -o yaml
   kubectl get pods -n <namespace> --show-labels
   ```

2. Check NetworkPolicy isn't blocking:
   ```bash
   kubectl get networkpolicy -n <namespace>
   kubectl get cnp -n <namespace>
   ```

### Issue: Gateway / HTTPRoute Not Working

**Symptoms:**
- External requests timeout
- 502/503 errors from gateway

**Diagnosis:**
```bash
# Check Cilium Gateway
kubectl get gateway -n cilium-gateway
kubectl describe gateway main -n cilium-gateway

# Check HTTPRoute resources
kubectl get httproute -A
kubectl describe httproute <route-name> -n <namespace>

# Check gateway proxy pods
kubectl get pods -n cilium-gateway

# Check service backend
kubectl get endpoints -n <namespace>
```

**Resolution:**
1. Verify Gateway is accepted and programmed:
   ```bash
   kubectl get gateway -n cilium-gateway -o jsonpath='{.items[*].status.conditions}'
   ```

2. Check HTTPRoute is attached to Gateway:
   ```bash
   kubectl get httproute -A -o jsonpath='{range .items[*]}{.metadata.name}{"\t"}{.status.parents[*].conditions[*].type}{"\n"}{end}'
   ```

3. Check backend service and pods are healthy

4. Check TLS certificate (if HTTPS, cloud only):
   ```bash
   kubectl get certificate -n <namespace>
   kubectl describe certificate <cert-name> -n <namespace>
   ```

### Issue: DNS Not Resolving

**Symptoms:**
- `nslookup` fails inside pods
- Services can't be reached by name

**Diagnosis:**
```bash
# Check CoreDNS pods
kubectl get pods -n kube-system -l k8s-app=coredns
kubectl logs -n kube-system -l k8s-app=coredns

# Check CoreDNS configmap
kubectl get cm coredns -n kube-system -o yaml

# Test DNS directly
kubectl run dnstest --image=busybox:1.28 --rm -it --restart=Never -- \
  nslookup kubernetes.default.svc.cluster.local
```

**Resolution:**
1. Restart CoreDNS if stuck:
   ```bash
   kubectl rollout restart deployment coredns -n kube-system
   ```

2. Check Cilium DNS proxy:
   ```bash
   kubectl exec -n kube-system -l k8s-app=cilium -- cilium status | grep DNS
   ```

### Issue: Intermittent Connectivity

**Symptoms:**
- Random timeouts
- Connections work sometimes but not always

**Diagnosis:**
```bash
# Check for packet drops in Hubble
# Look at Hubble UI for dropped flows

# Check node network interfaces
talosctl dmesg --nodes <node-ip> | grep -i "dropped\|error\|link"

# Monitor connections
kubectl exec -n kube-system -l k8s-app=cilium -- cilium monitor
```

**Resolution:**
1. Check for resource pressure:
   ```bash
   kubectl top nodes
   kubectl top pods -n kube-system
   ```

2. Review Cilium agent logs:
   ```bash
   kubectl logs -n kube-system -l k8s-app=cilium --tail=100
   ```

## Network Policies

### List All Policies

```bash
# Kubernetes NetworkPolicy
kubectl get networkpolicy -A

# Cilium NetworkPolicy
kubectl get cnp -A
kubectl get ccnp -A  # ClusterCiliumNetworkPolicy
```

### Debug Policy Enforcement

```bash
# Check what policies apply to a pod
kubectl exec -n kube-system -l k8s-app=cilium -- \
  cilium policy get --endpoints <endpoint-id>

# Get endpoint ID for a pod
kubectl exec -n kube-system -l k8s-app=cilium -- \
  cilium endpoint list
```

## External DNS Issues

### Check External-DNS

```bash
# Check external-dns pod
kubectl get pods -n external-dns
kubectl logs -n external-dns -l app.kubernetes.io/name=external-dns

# Verify DNS records in Cloudflare
# Check Cloudflare dashboard or use dig:
dig <argocd-host>
dig +short <domain>
```

## Load Balancer Issues

### Hetzner Load Balancer

```bash
# Check LB status
hcloud load-balancer describe <api-load-balancer>

# Verify targets
hcloud load-balancer describe <api-load-balancer> | grep -A20 Targets
```

## Collecting Network Debug Info

For escalation or deeper analysis:

```bash
# Full Cilium diagnostics
kubectl exec -n kube-system -l k8s-app=cilium -- cilium debuginfo

# Export Cilium bugtool
kubectl exec -n kube-system -l k8s-app=cilium -- cilium-bugtool

# Hubble flows export
hubble observe --namespace website -o json > hubble-flows.json
```

## Related

- [debug-storage.md](debug-storage.md) - Storage troubleshooting
- [../talos/view-logs.md](../talos/view-logs.md) - Viewing node logs
- [../applications/debug.md](../applications/debug.md) - Application debugging
