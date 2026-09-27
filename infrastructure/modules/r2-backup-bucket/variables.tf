variable "account_id" {
  type        = string
  description = "Cloudflare account ID."
}

variable "bucket_name" {
  type        = string
  description = "R2 bucket name. Names are scoped by jurisdiction."
}

variable "retention_prefix" {
  type        = string
  default     = "history/"
  description = "Object key prefix the lock and lifecycle rules apply to. Use an empty string for the whole bucket."
}

variable "lifecycle_retention_days" {
  type        = number
  default     = 31
  description = "Days before lifecycle expires objects under retention_prefix. Use 0 to disable lifecycle expiry. For locked buckets, keep this greater than lock_retention_days."

  validation {
    condition     = var.lifecycle_retention_days >= 0 && floor(var.lifecycle_retention_days) == var.lifecycle_retention_days
    error_message = "lifecycle_retention_days must be a whole number of days, 0 or more."
  }
}

variable "lock_retention_days" {
  type        = number
  default     = 30
  description = "Days objects under retention_prefix are immutable. Use 0 to disable bucket lock."

  validation {
    condition     = var.lock_retention_days >= 0 && floor(var.lock_retention_days) == var.lock_retention_days
    error_message = "lock_retention_days must be a whole number of days, 0 or more."
  }
}
