---
status: accepted
deviation-from: baseline v1.1
field: manifest.pvc-backup-annotation
tenant-value: "no backup.velero.io/backup-volumes annotation on the local registry and the pull-through cache mirrors"
baseline-value: "backup.velero.io/backup-volumes present"
reason: "the registry volumes hold regenerable data: a cache refills from its upstream registry, and an image in the local registry can be rebuilt from its source"
reconverge: "when a volume in the registry base holds data that exists nowhere else"
paths:
  - platform/resources/cloud/registry/base/*
---

# ADR 0059: Registry Volumes Outside Velero File-System Backup

## Table of Contents

- [Status](#status)
- [Context](#context)
- [Decision](#decision)
- [Rationale](#rationale)
- [Consequences](#consequences)
- [References](#references)

## Status

Accepted (2026-10-03), on the owner's decision. A recorded deviation from the baseline
conformance rule `manifest.pvc-backup-annotation`, in the form the
[conformance README](https://github.com/manyfold-dk/estate-baseline/blob/main/scripts/conformance/README.md#deviation-adr-contract)
prescribes. The frontmatter above is what the conformance checker parses. Its `paths:` limits
the deviation to the registry base.

## Context

[ADR-0020](0020-backup-strategy.md) makes Velero's file-system backup opt-in: Velero backs up a
pod's volume only when the pod template names it in `backup.velero.io/backup-volumes`. The rule
`manifest.pvc-backup-annotation` reports every manifest that mounts a PVC without that
annotation, so that an unbacked volume is a decision and not an accident.

The registry base in `platform/resources/cloud/registry/base/` mounts five such volumes:

| File | Volume | What it holds |
|---|---|---|
| `mirror-docker-io.yaml`, `mirror-ghcr-io.yaml`, `mirror-quay-io.yaml`, `mirror-registry-k8s-io.yaml` | `mirror-*-cache` | Pull-through cache blobs of the upstream registries |
| `local-registry.yaml` | `local-registry-data` | Images that builds in the cluster pushed |

## Decision

1. **Pull-through caches:** not backed up. ADR-0020 already classifies the mirror caches as
   excluded.
2. **Local registry:** not backed up. It holds build output, not source.
3. An installation that keeps data in the local registry that exists nowhere else adds the
   annotation in its overlay, with a patch on the `local-registry` pod template.
4. The deviation covers the registry base only. Every other volume in this repository carries
   the annotation or a deviation of its own.

## Rationale

The rule protects data that exists nowhere else. A pull-through cache refills from its upstream
registry on the next pull. An image in the local registry can be rebuilt from the commit it was
built from.

A backup of these volumes would copy cache blobs and build output into the backup repository
every run. It would add size and restore time, and it would protect nothing that a pull or a
rebuild does not restore.

## Consequences

**Positive:**

- The backup repository does not grow by the registry caches.
- The deviation covers one directory. The checker still reports a volume without the annotation
  in every other file of this repository, including a new database volume.

**Negative:**

- After the loss of a cache volume, the first pulls of each image go to the upstream registry
  again, and they are slower until the cache refills.
- A new volume added to the registry base is not reported. The reviewer of that change checks
  whether the volume holds data that exists nowhere else.

## References

- [ADR-0020: Backup Strategy](0020-backup-strategy.md)
- [ADR-0008: Container Registry Strategy](0008-container-registry-strategy.md)
- [ADR-0054: A Public Platform Repository and a Private Instance Repository](0054-public-platform-and-private-instance-repositories.md)
- [Conformance README](https://github.com/manyfold-dk/estate-baseline/blob/main/scripts/conformance/README.md)
