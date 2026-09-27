# -----------------------------------------------------------------------------
# Cloudflare R2 Buckets
# -----------------------------------------------------------------------------
#
# R2 buckets for backup storage and other object storage needs.
# Uses the same Cloudflare API token as DNS management.
#
# All backup destinations are EU-jurisdiction (ADR-0035). Bucket names are
# instance inputs (variables.tf). Per-bucket scoped R2 API tokens live in SOPS
# secrets, not in the OpenTofu environment.

# Velero replacement bucket. Velero owns pruning through backup TTL, so this
# bucket intentionally has no R2 lock or lifecycle rule.
module "velero_backups_eu" {
  source                   = "../../../modules/r2-backup-bucket"
  account_id               = var.cloudflare_account_id
  bucket_name              = var.velero_backups_bucket_name
  retention_prefix         = ""
  lock_retention_days      = 0
  lifecycle_retention_days = 0
}

# OpenBao replacement bucket. Snapshots are timestamped and externally retained,
# so whole-bucket lock and lifecycle provide 30-day WORM protection.
module "openbao_snapshots_eu" {
  source                   = "../../../modules/r2-backup-bucket"
  account_id               = var.cloudflare_account_id
  bucket_name              = var.openbao_snapshots_bucket_name
  retention_prefix         = ""
  lock_retention_days      = 30
  lifecycle_retention_days = 31
}

# Velero replica bucket (ADR-0020). WORM second copy of the Velero backup
# bucket: the primary stays prunable (Velero TTL), this replica's history/
# prefix is the delete-resistant copy. Destination shape: live current/ mirror
# + locked history/.
module "velero_backups_eu_replica" {
  source                   = "../../../modules/r2-backup-bucket"
  account_id               = var.cloudflare_account_id
  bucket_name              = var.velero_backups_replica_bucket_name
  retention_prefix         = "history/"
  lock_retention_days      = 30
  lifecycle_retention_days = 31
}

# Tenant carve-out exports bucket. Daily age/sops-encrypted
# Keycloak realm-user + OpenBao tenant-KV exports, timestamped and externally
# retained -- same whole-bucket 30-day WORM rationale as the OpenBao snapshots.
module "tenant_carveouts_eu" {
  source                   = "../../../modules/r2-backup-bucket"
  account_id               = var.cloudflare_account_id
  bucket_name              = var.tenant_carveouts_bucket_name
  retention_prefix         = ""
  lock_retention_days      = 30
  lifecycle_retention_days = 31
}

# -----------------------------------------------------------------------------
# Outputs
# -----------------------------------------------------------------------------

output "backup_bucket_eu_name" {
  description = "Name of the EU R2 bucket for Velero backups"
  value       = module.velero_backups_eu.bucket_name
}

output "backup_bucket_eu_endpoint" {
  description = "EU S3-compatible endpoint for the Velero backup bucket"
  value       = module.velero_backups_eu.endpoint
  sensitive   = true
}

output "openbao_snapshots_eu_bucket_name" {
  description = "Name of the EU R2 bucket for OpenBao Raft snapshots"
  value       = module.openbao_snapshots_eu.bucket_name
}

output "tenant_carveouts_eu_bucket_name" {
  description = "Name of the EU R2 bucket for tenant carve-out exports"
  value       = module.tenant_carveouts_eu.bucket_name
}

output "velero_backups_eu_replica_bucket_name" {
  description = "Name of the EU R2 replica bucket for Velero backups (WORM second copy)"
  value       = module.velero_backups_eu_replica.bucket_name
}
