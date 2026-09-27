# Restore from Backup

Procedures for restoring data from backups.

## Table of Contents

- [Placeholders](#placeholders)
- [Backup Strategy](#backup-strategy)
  - [Recovery Tiers](#recovery-tiers)
- [Velero Restore](#velero-restore)
- [etcd Snapshot Restore](#etcd-snapshot-restore)
- [Application Data Restore](#application-data-restore)
- [GitOps Restore](#gitops-restore)
- [Namespace Restore](#namespace-restore)
- [Secrets Restore](#secrets-restore)
- [Verification Checklist](#verification-checklist)
- [Related](#related)

## Placeholders

Replace these placeholders with the values of your installation:

| Placeholder | Value |
|-------------|-------|
| `<app>` | The name of an application that keeps its own PostgreSQL database |
| `<app-namespace>` | The namespace of `<app>` |
| `<app-postgresql-pod>` | The PostgreSQL pod of `<app>` |
| `<oauth2-proxy-secret>` | The secret that the OAuth2 proxy values of an application reference |
| `<app-host>` | The public hostname of `<app>` |
| `<protected-app-host>` | The public hostname of an application behind an OAuth2 proxy |
| `<regeneration-endpoint>` | The path, without a leading slash, of the admin endpoint of `<app>` that regenerates derived data. The runbooks of `<app>` name the endpoint |
| `<backup-name>` | The name of a Velero backup. `velero backup get` lists the backups |
| `<control-plane-ip>` | The IP address of the control plane node that you use for `talosctl` commands |
| `<control-plane-ips>` | The IP addresses of all control plane nodes, separated by commas |
| `<namespace>` | The namespace that the step names: the namespace of the PVC or of the application that you restore |
| `<data-uid>` | The numeric user ID, not 0, that owns the data of the application. The `securityContext` of the application pod shows it |
| `<website-host>` | The public hostname of the website (the homepage) |
| `<argocd-host>` | The public hostname of the ArgoCD UI |
| `<auth-host>` | The public hostname of Keycloak |
| `<auth-admin-host>` | The private hostname of the Keycloak admin console |
| `<realm>` | The name of the Keycloak realm |
| `<admin-user>` | The Keycloak admin user that the Helm values define |

## Backup Strategy

The cluster uses Velero with Cloudflare R2 for automated backups:

- **Daily backups** -- Critical namespaces (observability, argocd, website, keycloak, platform-ops, and the application namespaces that `platform/resources/cloud/velero-schedules/schedule-daily.yaml` names), 7-day retention
- **Weekly backups** -- All namespaces (except kube-system), 30-day retention
- **Tenant backups** -- A tenant can have its own schedule in `platform/resources/cloud/velero-schedules/`
- **etcd snapshots** -- Cluster state backup (manual)
- **Git + SOPS** -- All manifests, configuration, and encrypted secrets

**Backup methods:**
- **Resource backups** -- Kubernetes objects (Deployments, Services, ConfigMaps, etc.) stored in Cloudflare R2
- **File-level PVC backups (fs-backup)** -- Opt-in per pod via `backup.velero.io/backup-volumes` annotation. Enabled for database pods (Keycloak PostgreSQL, application PostgreSQL, OpenBao Raft). Disabled globally (`defaultVolumesToFsBackup: false`) to avoid backing up regeneratable observability PVCs.
- **CSI volume snapshots** -- Not supported (Hetzner CSI lacks `ControllerCreateSnapshot`)

### Recovery Tiers

| Tier | What | Source | Recovery Method |
|------|------|--------|----------------|
| **Git-recoverable** | SOPS secrets, Helm values, manifests, realm config | Git + age key | ArgoCD sync after `setup-sops-age.sh create` |
| **Backup-recoverable (resources)** | K8s resource definitions (Services, Deployments) | Velero daily/weekly | `velero restore create` |
| **Backup-recoverable (data)** | Keycloak PostgreSQL, application PostgreSQL, OpenBao Raft | Velero fs-backup (RPO: the interval of the schedule that includes the namespace, 24h for a daily schedule) | `velero restore create` (includes PVC data) |
| **Regeneratable** | Prometheus metrics, Loki logs, Tempo traces | -- | Rebuilt by re-scraping/re-collecting after restore |

See [ADR-0020: Backup Strategy](../../adr/0020-backup-strategy.md) for details.

## etcd Snapshot Restore

### When to Use

- Cluster state corruption
- Need to rollback cluster configuration
- Recovery after accidental deletion of resources

### Creating a Snapshot

```bash
# From any control plane node
talosctl etcd snapshot db.snapshot --nodes <control-plane-ip>

# Store securely (e.g., copy off-cluster)
scp db.snapshot backup-server:/backups/etcd/
```

### Restoring a Snapshot

> **Warning:** This replaces ALL cluster state. All changes after the snapshot are lost.

This procedure removes the etcd data on all control plane nodes. Then it bootstraps etcd again on one node from the snapshot (`talosctl bootstrap --recover-from`). Talos has no `talosctl etcd recover` command.

1. **Keep the snapshot on your workstation.** Use `db.snapshot` from `talosctl etcd snapshot`, or the file `etcd-copy/db` from a copy of the etcd data directory (see [Scenario 2 in etcd-recovery.md](../talos/etcd-recovery.md#scenario-2-loss-of-quorum)). Get the snapshot before you do step 2, because step 2 removes the etcd data.

2. **Wipe the EPHEMERAL partition of all control plane nodes in one command.** The wipe removes the etcd data and keeps the machine configuration.

   > **Warning:** Do not wipe the nodes one at a time. While two nodes still have etcd data, they keep quorum, and a wiped node joins them again after its reboot. Then the old etcd cluster continues and the restore cannot start.

   ```bash
   talosctl reset --nodes <control-plane-ips> \
     --system-labels-to-wipe EPHEMERAL \
     --graceful=false --reboot=true
   ```

   If a control plane node has a hardware failure, replace the node (see [../cluster/scaling.md](../cluster/scaling.md)).

3. **Make sure that etcd is in the `Preparing` state on all control plane nodes**:
   ```bash
   talosctl service etcd --nodes <control-plane-ips>
   ```
   Do not continue until the state of etcd is `Preparing` on each node. If etcd runs on a node, that node joined a node that still had etcd data. Wipe that node again with the command in step 2 (with `--nodes <control-plane-ip>`), and do this step again.

4. **Bootstrap etcd from the snapshot on one node**:
   ```bash
   talosctl bootstrap --nodes <control-plane-ip> --recover-from=./db.snapshot
   ```
   If the file is a copy of the data directory (`etcd-copy/db`), add `--recover-skip-hash-check`:
   ```bash
   talosctl bootstrap --nodes <control-plane-ip> --recover-from=./etcd-copy/db --recover-skip-hash-check
   ```

5. **Wait for the control plane.** etcd on the bootstrap node becomes healthy and the control plane starts. The other control plane nodes then join etcd automatically.

6. **Verify cluster**:
   ```bash
   talosctl etcd members --nodes <control-plane-ip>
   kubectl get nodes
   ```

## Application Data Restore

### Database Restore from Velero Backup

Velero daily backups include both the raw PostgreSQL data directory and a `pg_dump` file (created by pre-backup hooks). The dump is the preferred restore source -- it's consistent and portable.

An application can exclude derived data from its logical dump, for example a table of embeddings that the application can regenerate. The raw PVC backup still captures the complete database files.

The daily and tenant Velero schedules include only the namespaces that they name. The weekly schedule includes every namespace except `kube-system`. Find all schedules that include the namespace. Select the newest `<backup-name>` from those schedules with the status `Completed`. Run `velero backup describe <backup-name> --details` and confirm that the volume backup of the namespace succeeded.

**Restore the full namespace** (recreates PVC + data + resources):
```bash
velero restore create --from-backup <backup-name> --include-namespaces keycloak
velero restore create --from-backup <backup-name> --include-namespaces <app-namespace>
```

**Restore from the pg_dump file** (if PVC exists but data is corrupt):
```bash
# Keycloak PostgreSQL
kubectl exec -n keycloak keycloak-postgresql-0 -- \
  sh -c 'PGPASSWORD=$(cat /opt/bitnami/postgresql/secrets/postgres-password) pg_restore -U postgres -d bitnami_keycloak --clean --if-exists /bitnami/postgresql/backup.dump'

# Application PostgreSQL
kubectl exec -n <app-namespace> <app-postgresql-pod> -- \
  sh -c 'pg_restore -U "$POSTGRES_USER" -d "$POSTGRES_DB" --clean --if-exists /var/lib/postgresql/data/backup.dump'
```

If the logical dump of the application excludes derived data, regenerate the derived data after the restore:

```bash
curl -X POST \
  -H "Authorization: Bearer <admin-token>" \
  "https://<app-host>/<regeneration-endpoint>"
```

> **Note:** The dump file (`backup.dump`) only exists inside the Velero backup -- it's removed by the post-backup hook. To access it, restore the PVC from Velero first.

### Manual Database Restore (from external dump)

If you have database dumps from another source:

```bash
# Copy backup into pod
kubectl cp backup.sql <namespace>/<pod>:/tmp/backup.sql

# Restore
kubectl exec -n <namespace> <pod> -- psql -U <user> -d <database> -f /tmp/backup.sql
```

### Volume Restore (Manual)

If you have volume backups:

1. **Create new PVC**:
   ```bash
   kubectl apply -f - <<EOF
   apiVersion: v1
   kind: PersistentVolumeClaim
   metadata:
     name: restored-data
     namespace: <namespace>
   spec:
     accessModes:
       - ReadWriteOnce
     resources:
       requests:
         storage: 10Gi
     storageClassName: hcloud-volumes
   EOF
   ```

2. **Mount and restore**. Create the helper pod in the namespace of the PVC. A pod can mount only a PVC in its own namespace. The security context of the pod agrees with the `restricted` Pod Security Standard, so the API server accepts the pod in all namespaces. The pod runs as `<data-uid>`, so the files that it writes have the owner that the application expects:
   ```bash
   kubectl run restore-helper -n <namespace> --image=alpine --restart=Never \
     --overrides='{"spec":{"securityContext":{"runAsNonRoot":true,"runAsUser":<data-uid>,"runAsGroup":<data-uid>,"fsGroup":<data-uid>,"seccompProfile":{"type":"RuntimeDefault"}},"containers":[{"name":"restore-helper","image":"alpine","command":["sleep","3600"],"securityContext":{"allowPrivilegeEscalation":false,"capabilities":{"drop":["ALL"]}},"volumeMounts":[{"name":"data","mountPath":"/data"}]}],"volumes":[{"name":"data","persistentVolumeClaim":{"claimName":"restored-data"}}]}}'
   kubectl wait -n <namespace> --for=condition=Ready pod/restore-helper --timeout=5m

   # Copy data into the volume
   kubectl cp backup.tar.gz <namespace>/restore-helper:/tmp/
   kubectl exec -n <namespace> restore-helper -- tar xzf /tmp/backup.tar.gz -C /data

   # Cleanup helper
   kubectl delete pod restore-helper -n <namespace>
   ```

3. **Update application to use restored PVC**

## GitOps Restore

All Kubernetes manifests are in Git. To restore:

1. **Ensure cluster is running** (see [full-rebuild.md](full-rebuild.md))

2. **ArgoCD syncs from Git**:
   ```bash
   # Force sync all applications
   argocd app sync --all
   ```

3. **For deleted resources**, ArgoCD will recreate them from Git manifests

### Restoring Deleted ArgoCD Application

```bash
# Reapply from Git
kubectl apply -f platform/argocd/cloud/applications/<app>.yaml

# Or resync parent app
argocd app sync cloud-platform
```

## Namespace Restore

To restore an entire namespace:

1. **If namespace was deleted**, recreate it:
   ```bash
   kubectl create namespace <namespace>
   ```

2. **ArgoCD will resync** if app still exists:
   ```bash
   argocd app sync <app-name>
   ```

3. **For PVC data**, use volume restore procedures above

## Secrets Restore

All platform secrets are SOPS-encrypted in Git. Restoring the age key is the single prerequisite for recovering every secret.

1. **Ensure SOPS age key is configured**:
   ```bash
   cd infrastructure/clusters/cloud/scripts
   ./setup-sops-age.sh status
   ```

2. **Resync cloud-secrets application**:
   ```bash
   argocd app sync cloud-secrets
   ```

3. **Verify auth stack secrets** (these enable the entire identity layer):
   ```bash
   # Keycloak
   kubectl get secret -n keycloak keycloak-admin-credentials
   kubectl get secret -n keycloak keycloak-postgresql-credentials
   kubectl get secret -n keycloak keycloak-config-cli-client-secrets
   kubectl get secret -n keycloak keycloak-logout-client-secret

   # OAuth2 proxies
   kubectl get secret -n auth-system oauth2-proxy-homepage-secrets
   # Repeat for each other application behind an OAuth2 proxy
   kubectl get secret -n auth-system <oauth2-proxy-secret>

   # SSO clients
   kubectl get secret -n argocd argocd-oidc-secret
   kubectl get secret -n observability grafana-oidc-secret
   ```

> **Note:** These secrets are the credentials and client secrets. The Keycloak realm configuration (clients, roles, `<admin-user>` user) is bootstrapped by `keycloak-config-cli` from Helm values -- it does not require secrets restore, only that Keycloak PostgreSQL is running. However, any users created manually in the Keycloak admin console exist only in PostgreSQL and are not recoverable from Git.

## Velero Restore

For detailed Velero procedures, see [velero-operations.md](velero-operations.md).

### Quick Restore Commands

```bash
# List available backups
velero backup get

# Restore specific namespace
velero restore create --from-backup <backup-name> --include-namespaces <namespace>

# Restore to different namespace (for testing)
velero restore create --from-backup <backup-name> \
  --include-namespaces observability \
  --namespace-mappings observability:restore-test

# Restore specific resources
velero restore create --from-backup <backup-name> --include-resources pvc,pv
```

### When to Use Velero

| Scenario | Use Velero | Alternative |
|----------|------------|-------------|
| Accidental namespace deletion | Yes | - |
| PVC data corruption | Yes | - |
| Full cluster restore | Yes | After rebuilding cluster |
| Single resource restore | Yes | kubectl apply from Git |
| Secret restore | Yes | ArgoCD sync (SOPS encrypted) |

## Verification Checklist

After any restore:

### Cluster Health

- [ ] All nodes are Ready: `kubectl get nodes`
- [ ] All pods are Running: `kubectl get pods -A | grep -v Running | grep -v Completed`
- [ ] ArgoCD apps are Synced: `kubectl get applications -n argocd`
- [ ] DNS resolving correctly: `dig <website-host>`
- [ ] TLS certificates valid: check browser or `curl https://<website-host>`

### Identity and Auth

- [ ] Keycloak healthy: `kubectl get pods -n keycloak`
- [ ] OIDC issuer responding: `curl -s https://<auth-host>/realms/<realm>/.well-known/openid-configuration | head -1`
- [ ] Keycloak admin console: `https://<auth-admin-host>/admin/` (private split DNS, via Tailscale)
- [ ] Homepage OAuth2 login: open `https://<website-host>` → Keycloak login → redirect back
- [ ] Application OAuth2 login, for each application behind an OAuth2 proxy: open `https://<protected-app-host>` → Keycloak login → redirect back
- [ ] ArgoCD SSO: open `https://<argocd-host>` → Keycloak OIDC login
- [ ] Grafana SSO: open Grafana via Tailscale → Keycloak OIDC login
- [ ] OpenBao unsealed: `kubectl exec -n platform-ops openbao-0 -c openbao -- bao status | grep Sealed`

### Applications

- [ ] Website accessible: `https://<website-host>`
- [ ] Application accessible: `https://<app-host>` (log in first if the application requires a login)

## Related

- [velero-operations.md](velero-operations.md) - Detailed Velero procedures
- [full-rebuild.md](full-rebuild.md) - Complete cluster rebuild
- [../talos/etcd-recovery.md](../talos/etcd-recovery.md) - etcd recovery
- [../cluster/debug-storage.md](../cluster/debug-storage.md) - Storage troubleshooting
- [ADR-0020: Backup Strategy](../../adr/0020-backup-strategy.md) - Architecture decision
