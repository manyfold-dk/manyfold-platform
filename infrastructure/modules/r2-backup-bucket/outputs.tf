output "bucket_name" {
  description = "Name of the R2 bucket."
  value       = cloudflare_r2_bucket.this.name
}

output "endpoint" {
  description = "EU-jurisdiction R2 S3 endpoint for the account."
  value       = "https://${var.account_id}.eu.r2.cloudflarestorage.com"
}
