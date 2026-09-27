# Operational Runbooks

Runbooks for operating a Manyfold platform cluster on Hetzner Cloud with Talos Linux.

Placeholders: `<cloud-kubeconfig>` is the kubeconfig of the cloud cluster, `<cloud-env-file>` the
file that exports the infrastructure credentials, `<domain>` the installation's domain.

## Table of Contents

- [Prerequisites](#prerequisites)
- [Runbook Categories](#runbook-categories)
- [Quick Reference](#quick-reference)
  - [Emergency Contacts](#emergency-contacts)
  - [Common Tasks](#common-tasks)
  - [Cluster Information](#cluster-information)
- [See Also](#see-also)

## Prerequisites

All runbooks assume you have:

1. **Kubeconfig configured**:
   ```bash
   export KUBECONFIG=<cloud-kubeconfig>
   kubectl get nodes
   ```

2. **Talos CLI installed** (for Talos-specific operations):
   ```bash
   brew install siderolabs/tap/talosctl
   talosctl version --client
   ```

3. **Access to infrastructure credentials** (for OpenTofu operations):
   ```bash
   source <cloud-env-file>
   ```

## Runbook Categories

| Category | Description | When to Use |
|----------|-------------|-------------|
| [Talos Operations](talos/) | Talos Linux and node management | OS upgrades, node resets, etcd issues |
| [Cluster Operations](cluster/) | Kubernetes cluster management | Scaling, maintenance, networking |
| [Disaster Recovery](disaster-recovery/) | Backup and restore procedures | Data loss, cluster rebuild |
| [Application Operations](applications/) | Application deployment and debugging | Deploy, rollback, debug apps |

## Quick Reference

### Emergency Contacts

- **Hetzner Cloud Console**: https://console.hetzner.cloud/
- **Cloudflare Dashboard**: https://dash.cloudflare.com/
- **ArgoCD UI**: https://argocd.<domain>
- **Grafana Dashboards**: https://grafana.<domain>

### Common Tasks

| Task | Runbook |
|------|---------|
| Upgrade Talos OS | [talos/upgrade-os.md](talos/upgrade-os.md) |
| Upgrade Kubernetes | [talos/upgrade-kubernetes.md](talos/upgrade-kubernetes.md) |
| Reset a node | [talos/reset-node.md](talos/reset-node.md) |
| View node logs | [talos/view-logs.md](talos/view-logs.md) |
| Recover etcd | [talos/etcd-recovery.md](talos/etcd-recovery.md) |
| Scale cluster | [cluster/scaling.md](cluster/scaling.md) |
| Drain node for maintenance | [cluster/drain-node.md](cluster/drain-node.md) |
| Debug networking | [cluster/debug-networking.md](cluster/debug-networking.md) |
| Debug storage | [cluster/debug-storage.md](cluster/debug-storage.md) |
| GitHub webhooks | [cluster/github-webhooks.md](cluster/github-webhooks.md) |
| ArgoCD app not converging | [cluster/argocd-app-not-converging.md](cluster/argocd-app-not-converging.md) |
| Full cluster rebuild | [disaster-recovery/full-rebuild.md](disaster-recovery/full-rebuild.md) |
| Restore from backup | [disaster-recovery/restore-backup.md](disaster-recovery/restore-backup.md) |
| Velero backup/restore | [disaster-recovery/velero-operations.md](disaster-recovery/velero-operations.md) |
| OpenBao Raft snapshot restore | [disaster-recovery/openbao-raft-restore.md](disaster-recovery/openbao-raft-restore.md) |
| Deploy application | [applications/deploy.md](applications/deploy.md) |
| Rollback deployment | [applications/rollback.md](applications/rollback.md) |
| Debug application | [applications/debug.md](applications/debug.md) |
| Registry mirrors | [applications/registry.md](applications/registry.md) |
| OpenBao root-token regen, rekey, sealed-state recovery | [applications/openbao.md](applications/openbao.md) |

### Cluster Information

The installation records its live cluster facts in its own cloud information file (private).

| Resource | Value |
|----------|-------|
| Provider | Hetzner Cloud (`<location>`) |
| OS | Talos Linux |
| Control Plane | `<n>` x `<server-type>` |
| Workers | `<n>` x `<server-type>` |
| Load Balancer | `<load-balancer-type>` (`<public-address>`) |
| API Endpoint | `https://<api-subdomain>.<domain>:6443` |

## See Also

- Cloud infrastructure information and the cloud bootstrap guide (private, in the instance repository)
- [ADR-0015: Kubernetes Distribution](../adr/0015-kubernetes-distribution.md)
