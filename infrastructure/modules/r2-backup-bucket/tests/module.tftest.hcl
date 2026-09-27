# Run with: tofu init && tofu test (no Cloudflare credentials needed; the provider is mocked)

mock_provider "cloudflare" {}

variables {
  account_id  = "example-account"
  bucket_name = "example-backups"
}

run "defaults_lock_and_expire_the_history_prefix" {
  command = plan

  assert {
    condition     = length(cloudflare_r2_bucket_lock.this) == 1 && length(cloudflare_r2_bucket_lifecycle.this) == 1
    error_message = "The defaults should create both the lock and the lifecycle rule."
  }

  assert {
    condition     = cloudflare_r2_bucket_lock.this[0].rules[0].prefix == "history/"
    error_message = "The lock should apply to the history/ prefix by default."
  }

  assert {
    condition     = cloudflare_r2_bucket.this.jurisdiction == "eu"
    error_message = "Buckets are created in the EU jurisdiction."
  }

  assert {
    condition     = output.endpoint == "https://example-account.eu.r2.cloudflarestorage.com"
    error_message = "The endpoint should be the account's EU-jurisdiction endpoint."
  }
}

run "zero_days_disable_both_rules" {
  command = plan

  variables {
    lock_retention_days      = 0
    lifecycle_retention_days = 0
  }

  assert {
    condition     = length(cloudflare_r2_bucket_lock.this) == 0 && length(cloudflare_r2_bucket_lifecycle.this) == 0
    error_message = "Zero days should create neither rule."
  }
}

run "expiry_must_outlast_the_lock" {
  command = plan

  variables {
    lock_retention_days      = 30
    lifecycle_retention_days = 30
  }

  expect_failures = [cloudflare_r2_bucket_lifecycle.this]
}

run "negative_days_are_rejected" {
  command = plan

  variables {
    lock_retention_days = -1
  }

  expect_failures = [var.lock_retention_days]
}
