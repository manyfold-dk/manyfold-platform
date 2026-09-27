# ADR 0035: EU Data Residency for Storage

## Table of Contents

- [Status](#status)
- [Context](#context)
- [Decision](#decision)
- [Rationale](#rationale)
- [Consequences](#consequences)
- [Rollback](#rollback)
- [References](#references)

## Status

Accepted - 2026-05-24. Migration completed 2026-05-30 (all backup buckets now jurisdiction=eu; old WEUR buckets decommissioned).

## Context

Manyfold is operated from Denmark and treats persistent production data as EU-resident by default. Object storage now holds application data, backup data, and operational recovery artifacts, so best-effort placement hints are not enough for the platform's compliance posture.

Cloudflare R2 supports both a `location` hint and a hard jurisdiction boundary. The `location` hint, such as `weur`, is best effort and is only honored when a bucket name is first created. The `jurisdiction = "eu"` setting is the control that guarantees object data and metadata stay in the EU and changes the S3 endpoint to `https://<account-id>.eu.r2.cloudflarestorage.com`.

## Decision

Every persistent object-storage bucket must stay in the EU:

- Cloudflare R2 buckets use `jurisdiction = "eu"` and the EU endpoint.
- Hetzner Object Storage buckets use EU regions such as `hel1`, `fsn1`, or `nbg1`.
- Bucket-scoped credentials are required for backup destinations.
- Tenant onboarding must verify the source bucket and backup bucket are both EU-resident before production enablement.

Existing backup buckets that were created with only the R2 `weur` location hint were migrated by creating EU-jurisdiction replacement buckets, copying data, rotating credentials, repointing consumers, and verifying historical restore before the old buckets were removed.

## Rationale

EU jurisdiction is a clearer invariant than provider-specific placement hints. It also avoids coupling backup and restore safety to account-wide R2 credentials by requiring least-privilege, bucket-scoped backup tokens.

This choice matches the platform's existing provider split: Hetzner remains the EU source object-storage provider for workloads, and Cloudflare R2 remains the geographically independent backup provider.

## Consequences

Positive consequences:

- Object data, backup data, and R2 metadata remain inside the EU.
- Backup credentials have narrower blast radius.
- R2 remains egress-free for restores and has no EU jurisdiction price premium.

Tradeoffs:

- Existing non-jurisdiction buckets cannot be converted in place; migration requires replacement buckets and restore verification.
- More bucket-scoped R2 tokens must be created and rotated.
- R2 Bucket Lock must be planned per bucket because locks can intentionally block deletes until retention expires.

## Rollback

Reverting persistent storage to non-EU buckets is not permitted without a superseding ADR. If a migration fails, keep consumers pointed at the old bucket, fix the EU replacement, recopy data, and repeat restore verification.

## References

- [ADR 0020: Backup Strategy with Cloudflare R2](0020-backup-strategy.md)
- [ADR 0033: Multi-Tenancy Model and Tenant Isolation](0033-multi-tenancy-model-and-tenant-isolation.md)
- The object storage backup and EU residency implementation plan of 2026-05-24 (private)
