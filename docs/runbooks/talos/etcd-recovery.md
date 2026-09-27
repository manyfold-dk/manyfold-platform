# etcd Cluster Recovery

Procedures for recovering from etcd issues in the Talos cluster.

## Table of Contents

- [Placeholders](#placeholders)
- [Prerequisites](#prerequisites)
- [Common Scenarios](#common-scenarios)
  - [Scenario 1: Single Member Failure](#scenario-1-single-member-failure)
  - [Scenario 2: Loss of Quorum](#scenario-2-loss-of-quorum)
  - [Scenario 3: etcd Data Corruption](#scenario-3-etcd-data-corruption)
  - [Scenario 4: Split Brain](#scenario-4-split-brain)
- [Monitoring etcd Health](#monitoring-etcd-health)
- [Backup and Restore](#backup-and-restore)
- [Prevention](#prevention)
- [Related](#related)

## Placeholders

Replace these placeholders with the values of your installation:

| Placeholder | Value |
|---|---|
| `<cp-1-ip>`, `<cp-2-ip>`, `<cp-3-ip>` | The private IP addresses of the three control plane nodes. |
| `<node-ip>`, `<healthy-node-ip>`, `<unhealthy-node-ip>`, `<corrupted-node-ip>` | The private IP address of the node that the step names. |
| `<unhealthy-member-id>`, `<corrupted-member-id>` | The etcd member ID of the node. `talosctl etcd members` shows the IDs. |

## Prerequisites

- Access to at least one healthy control plane node
- Understanding that etcd requires majority quorum (2 of 3 nodes)

```bash
# Check current etcd status
talosctl etcd members --nodes <cp-1-ip>
```

## Common Scenarios

### Scenario 1: Single Member Failure

One etcd member is unhealthy but quorum is maintained (2 of 3 healthy).

**Symptoms:**
- One control plane node showing issues
- `talosctl etcd members` shows one member unhealthy
- Cluster still functions normally

**Resolution:**

1. Check which member is unhealthy:
   ```bash
   talosctl etcd members --nodes <cp-1-ip>
   ```

2. Try restarting etcd on the unhealthy node:
   ```bash
   talosctl service etcd restart --nodes <unhealthy-node-ip>
   ```

3. If the restart does not help, reset the node. First drain the node (see the prerequisites in [reset-node.md](reset-node.md#prerequisites)).

   > **Warning:** Always set `--system-labels-to-wipe`. Without it, `talosctl reset` wipes the full system disk (see [reset-node.md](reset-node.md#option-1-soft-reset-preserves-machine-config)).

   Use `--graceful=false` because the member is already removed. A graceful reset tries to leave etcd first.
   ```bash
   # Remove the member from etcd. `talosctl etcd members` shows the member ID.
   talosctl etcd remove-member --nodes <healthy-node-ip> <unhealthy-member-id>

   # Wipe only the EPHEMERAL partition. The node keeps its machine configuration
   # and joins etcd again after the reboot.
   talosctl reset --nodes <unhealthy-node-ip> \
     --system-labels-to-wipe EPHEMERAL \
     --graceful=false --reboot=true
   ```

### Scenario 2: Loss of Quorum

Two or more etcd members failed. etcd has no quorum, and the Kubernetes API is down or read-only.

**Symptoms:**
- API server returns "etcd cluster is unavailable"
- Unable to create/modify resources
- Only 1 healthy etcd member (or none)

**Resolution:**

> **Warning:** This is a critical situation. Do the steps in sequence.

Member operations cannot recover a cluster that lost quorum. `talosctl etcd remove-member` needs quorum, and `talosctl etcd forfeit-leadership` only moves the leader role. The recovery is a new etcd bootstrap from a snapshot (`talosctl bootstrap --recover-from`).

1. **Get a snapshot.** If etcd still runs on one node, take a snapshot from that node:
   ```bash
   talosctl etcd snapshot db.snapshot --nodes <healthy-node-ip>
   ```
   If the snapshot command fails, copy the database file from the etcd data directory. `talosctl cp` needs an empty or new local directory. The command writes the file `etcd-copy/db`:
   ```bash
   talosctl cp /var/lib/etcd/member/snap/db ./etcd-copy --nodes <healthy-node-ip>
   ```
   The copied file can be inconsistent. Use it only when you cannot get a snapshot. If no node has etcd data, use the newest snapshot that you keep off-cluster.

2. **Make sure that etcd cannot recover.** Examine the member list and the etcd service on each control plane node:
   ```bash
   talosctl etcd members --nodes <cp-1-ip>
   talosctl service etcd --nodes <cp-1-ip>,<cp-2-ip>,<cp-3-ip>
   ```
   If quorum comes back (for example, a failed node starts again), stop here.

3. **Restore etcd from the snapshot.** Do the procedure in [Restoring a Snapshot](../disaster-recovery/restore-backup.md#restoring-a-snapshot).

4. **If you have no snapshot and no etcd data**, rebuild the cluster: see [../disaster-recovery/full-rebuild.md](../disaster-recovery/full-rebuild.md).

### Scenario 3: etcd Data Corruption

etcd data is corrupted on one or more nodes.

**Symptoms:**
- etcd logs show corruption errors
- Node fails to start etcd service
- "panic" or "corruption" in etcd logs

**Resolution:**

1. Check etcd logs:
   ```bash
   talosctl logs etcd --nodes <node-ip>
   ```

2. If single node corruption (quorum maintained):
   ```bash
   # Remove corrupted member
   talosctl etcd remove-member --nodes <healthy-node-ip> <corrupted-member-id>

   # Wipe and rejoin
   talosctl reset --nodes <corrupted-node-ip> \
     --system-labels-to-wipe EPHEMERAL \
     --graceful=false --reboot=true
   ```

   Use `--graceful=false` because the member is already removed and its etcd does not run.

3. If more than one node has corrupted data, etcd has no quorum. Do the procedure in [Scenario 2: Loss of Quorum](#scenario-2-loss-of-quorum).

### Scenario 4: Split Brain

Network partition caused etcd members to disagree.

**Symptoms:**
- Different API servers returning different data
- etcd shows members in different states
- "request timed out" or "leader changed" messages

**Resolution:**

1. Identify the partition:
   ```bash
   talosctl etcd members --nodes <cp-1-ip>
   talosctl etcd members --nodes <cp-2-ip>
   talosctl etcd members --nodes <cp-3-ip>
   ```

2. Check network connectivity between nodes:
   ```bash
   # From each node, check connectivity to others
   talosctl dmesg --nodes <node-ip> | grep -i network
   ```

3. Fix network issues (check Hetzner firewall, private network)

4. Once network is restored, etcd should self-heal. If not:
   ```bash
   # Restart etcd on all nodes
   talosctl service etcd restart --nodes <cp-1-ip>,<cp-2-ip>,<cp-3-ip>
   ```

## Monitoring etcd Health

### Check Member Status

```bash
# List all members
talosctl etcd members --nodes <cp-1-ip>

# Check cluster health
talosctl etcd status --nodes <cp-1-ip>
```

### Via Prometheus/Grafana

etcd metrics are exposed and scraped by Prometheus:

- **Dashboard**: Grafana → etcd dashboard
- **Key metrics**:
  - `etcd_server_has_leader` - Should be 1
  - `etcd_server_leader_changes_seen_total` - Should be stable
  - `etcd_disk_wal_fsync_duration_seconds` - Should be low

### Alerting

Custom alerts for etcd are defined in the observability stack:

```yaml
# Example alert (already configured)
- alert: EtcdMembersMismatch
  expr: etcd_server_has_leader == 0
  for: 5m
  labels:
    severity: critical
```

## Backup and Restore

### Creating a Backup

```bash
# Snapshot etcd from any control plane node
talosctl etcd snapshot db.snapshot --nodes <cp-1-ip>

# This creates a local file with etcd data
```

### Restoring from Backup

> **Warning:** This replaces all cluster data.

See [../disaster-recovery/restore-backup.md](../disaster-recovery/restore-backup.md) for full procedure.

## Prevention

1. **Monitor etcd metrics** in Grafana
2. **Set up alerts** for etcd issues
3. **Regular backups** (consider automated snapshots)
4. **Test recovery procedures** periodically
5. **Use 3 control plane nodes** for fault tolerance

## Related

- [reset-node.md](reset-node.md) - Resetting nodes
- [upgrade-os.md](upgrade-os.md) - Safe upgrade procedures
- [../disaster-recovery/full-rebuild.md](../disaster-recovery/full-rebuild.md) - Full cluster rebuild
- [../disaster-recovery/restore-backup.md](../disaster-recovery/restore-backup.md) - Restore from backup
