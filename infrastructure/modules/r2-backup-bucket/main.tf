terraform {
  required_providers {
    cloudflare = {
      source  = "cloudflare/cloudflare"
      version = "~> 5.0"
    }
  }
}

resource "cloudflare_r2_bucket" "this" {
  account_id   = var.account_id
  name         = var.bucket_name
  jurisdiction = "eu"
}

resource "cloudflare_r2_bucket_lock" "this" {
  count        = var.lock_retention_days > 0 ? 1 : 0
  account_id   = var.account_id
  bucket_name  = cloudflare_r2_bucket.this.name
  jurisdiction = "eu"

  rules = [{
    id      = "lock-retention"
    enabled = true
    prefix  = var.retention_prefix
    condition = {
      type            = "Age"
      max_age_seconds = var.lock_retention_days * 24 * 3600
    }
  }]
}

resource "cloudflare_r2_bucket_lifecycle" "this" {
  count        = var.lifecycle_retention_days > 0 ? 1 : 0
  account_id   = var.account_id
  bucket_name  = cloudflare_r2_bucket.this.name
  jurisdiction = "eu"

  lifecycle {
    # Expiry must not reach an object while the lock still holds it: R2 refuses the
    # deletion and the rule fails on every run.
    precondition {
      condition     = var.lock_retention_days == 0 || var.lifecycle_retention_days > var.lock_retention_days
      error_message = "lifecycle_retention_days must be greater than lock_retention_days when the bucket is locked."
    }
  }

  rules = [{
    id      = "expire-retention"
    enabled = true
    conditions = {
      prefix = var.retention_prefix
    }
    delete_objects_transition = {
      condition = {
        type    = "Age"
        max_age = var.lifecycle_retention_days * 24 * 3600
      }
    }
  }]
}
