# View Talos Node Logs

How to access and analyze logs from Talos Linux nodes.

## Table of Contents

- [Placeholders](#placeholders)
- [Prerequisites](#prerequisites)
- [System Logs (dmesg)](#system-logs-dmesg)
- [Service Logs](#service-logs)
- [Kubernetes Logs](#kubernetes-logs)
- [Centralized Logging (Loki)](#centralized-logging-loki)
- [Common Log Locations](#common-log-locations)
- [Useful Log Queries](#useful-log-queries)
- [Exporting Logs](#exporting-logs)
- [Related](#related)

## Placeholders

Replace these placeholders with the values of your installation:

| Placeholder | Value |
|---|---|
| `<cp-1-ip>` | The private IP address of the first control plane node. |
| `<node-ip>`, `<ip>` | The private IP address of the node to inspect. |
| `<grafana-url>` | The URL of the Grafana instance of the cluster. |

## Prerequisites

```bash
# Ensure talosctl can reach nodes
talosctl version --nodes <cp-1-ip>
```

## System Logs (dmesg)

View kernel and system messages:

```bash
# All system messages
talosctl dmesg --nodes <node-ip>

# Follow logs in real-time
talosctl dmesg --nodes <node-ip> --follow

# Last 100 lines
talosctl dmesg --nodes <node-ip> | tail -100

# Filter for specific terms
talosctl dmesg --nodes <node-ip> | grep -i error
talosctl dmesg --nodes <node-ip> | grep -i network
talosctl dmesg --nodes <node-ip> | grep -i disk
```

## Service Logs

View logs from specific Talos services:

```bash
# List available services
talosctl services --nodes <node-ip>

# Common services to check:
talosctl logs kubelet --nodes <node-ip>
talosctl logs containerd --nodes <node-ip>
talosctl logs etcd --nodes <node-ip>          # Control plane only
talosctl logs apid --nodes <node-ip>

# Follow logs
talosctl logs kubelet --nodes <node-ip> --follow

# With timestamps
talosctl logs kubelet --nodes <node-ip> | head -50
```

## Kubernetes Logs

For application and cluster component logs:

```bash
# Pod logs
kubectl logs <pod-name> -n <namespace>
kubectl logs <pod-name> -n <namespace> --previous  # Previous container
kubectl logs -l app=<label> -n <namespace>         # All pods with label

# System component logs
kubectl logs -n kube-system -l k8s-app=cilium
kubectl logs -n kube-system -l k8s-app=coredns
kubectl logs -n argocd -l app.kubernetes.io/name=argocd-server
```

## Centralized Logging (Loki)

Access logs via Grafana:

1. Open Grafana: `<grafana-url>`
2. Go to Explore
3. Select "Loki" data source
4. Use LogQL queries:

```logql
# All logs from a namespace
{namespace="website"}

# Filter by pod
{namespace="website", pod=~"backend.*"}

# Search for errors
{namespace="website"} |= "error"

# JSON parsing
{namespace="website"} | json | level="error"
```

## Common Log Locations

| Component | How to Access |
|-----------|---------------|
| Kernel/dmesg | `talosctl dmesg --nodes <ip>` |
| Kubelet | `talosctl logs kubelet --nodes <ip>` |
| Containerd | `talosctl logs containerd --nodes <ip>` |
| etcd | `talosctl logs etcd --nodes <ip>` |
| API Server | `kubectl logs -n kube-system kube-apiserver-<node>` |
| Pod logs | `kubectl logs <pod> -n <namespace>` |
| All logs | Grafana → Loki |

## Useful Log Queries

### Finding Errors

```bash
# System errors
talosctl dmesg --nodes <node-ip> | grep -i "error\|fail\|panic"

# Kubelet errors
talosctl logs kubelet --nodes <node-ip> 2>&1 | grep -i error

# Pod crash loops
kubectl get pods -A | grep -E "CrashLoop|Error"
kubectl describe pod <pod-name> -n <namespace> | grep -A5 "Events:"
```

### Network Issues

```bash
# Network-related kernel messages
talosctl dmesg --nodes <node-ip> | grep -i "network\|eth0\|cilium"

# Cilium agent logs
kubectl logs -n kube-system -l k8s-app=cilium --tail=100

# DNS issues
kubectl logs -n kube-system -l k8s-app=coredns
```

### Storage Issues

```bash
# Disk-related messages
talosctl dmesg --nodes <node-ip> | grep -i "disk\|sda\|mount"

# CSI driver logs
kubectl logs -n kube-system -l app=hcloud-csi
```

## Exporting Logs

```bash
# Export dmesg to file
talosctl dmesg --nodes <node-ip> > dmesg-$(date +%Y%m%d).log

# Export all service logs
for svc in kubelet containerd apid; do
  talosctl logs $svc --nodes <node-ip> > ${svc}-$(date +%Y%m%d).log
done

# Export pod logs
kubectl logs <pod-name> -n <namespace> > pod-logs.txt
```

## Related

- [../cluster/debug-networking.md](../cluster/debug-networking.md) - Network debugging
- [etcd-recovery.md](etcd-recovery.md) - etcd troubleshooting
- [reset-node.md](reset-node.md) - Resetting problematic nodes
