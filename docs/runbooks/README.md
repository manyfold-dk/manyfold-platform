# Operational Runbooks

Runbooks for operating a Manyfold platform cluster on Hetzner Cloud with Talos Linux.

**Placeholders.** Replace each placeholder with the value of your installation before you run a command:

| Placeholder | Value |
|---|---|
| `<cloud-kubeconfig>` | The kubeconfig of the cloud cluster |
| `<cloud-env-file>` | The local, untracked file with the infrastructure credentials (one `NAME=value` per line) |
| `<credential-helper>` | The credential helper of the installation, if the installation has one (see [Prerequisites](#prerequisites)) |
| `<hcloud-token-name>` | The name of the Hetzner Cloud token in `<cloud-env-file>` or for `<credential-helper>` (`HCLOUD_TOKEN`, unless the installation names it otherwise) |
| `<domain>` | The domain of the installation |

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

3. **Access to infrastructure credentials** (for OpenTofu operations). Give each command only
   the credentials that the command needs. An installation gives the credentials in one of two
   ways: with a credential helper, or with `<cloud-env-file>`. Each runbook that reads a
   credential shows the helper form first and the form without a helper second. Use the form
   that your installation has.

   **With a credential helper.** `<credential-helper>` runs one command, or a shell, with the
   named credentials in the environment of that command only. No credential value enters the
   caller's shell or an argument list.

   ```bash
   <credential-helper> NAME [NAME...] -- COMMAND [ARG...]
   ```

   - Each `NAME` is the name of a stored credential. The command gets the value in a variable
     with the same name.
   - The command runs in the caller's working directory. The command gets the caller's stdin
     and terminal.
   - The helper returns the exit status of the command.
   - If a named credential is missing or empty, the helper exits non-zero. The helper then does
     not start the command.
   - To run more than one command, or to use a variable, give `sh -c '<script>'` as the
     command. The single quotes keep every expansion inside the child shell.
   - Inside the script, do not put a credential value in an argument. Give the value to the
     program on stdin or in the environment.
   - To work in a shell, give `"$SHELL"` as the command. The values stay until you type `exit`.
     For one block, use `sh -c` instead.

   The Hetzner Cloud tools read the token from `HCLOUD_TOKEN`. The helper gives the token in
   the variable `<hcloud-token-name>`. The script therefore sets `HCLOUD_TOKEN` before it runs
   the tool:

   ```bash
   <credential-helper> <hcloud-token-name> CLOUDFLARE_API_TOKEN -- \
     sh -c 'export HCLOUD_TOKEN="${<hcloud-token-name>}"; exec tofu plan'
   ```

   **Without a credential helper.** Read only the variables that a command needs from
   `<cloud-env-file>`. Do not source `<cloud-env-file>`: the shell then holds every credential
   of the file. Each line that a command reads is an unquoted `NAME=value` line. The line has
   no inline comment. The read keeps quotes and comments as part of the value.

   ```bash
   export HCLOUD_TOKEN="$(sed -n 's/^<hcloud-token-name>=//p' <cloud-env-file> | head -1)"
   export CLOUDFLARE_API_TOKEN="$(sed -n 's/^CLOUDFLARE_API_TOKEN=//p' <cloud-env-file> | head -1)"
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
