# r2-backup-bucket

An OpenTofu module for a Cloudflare R2 bucket that holds backups: created in the EU
jurisdiction, optionally with a Bucket Lock (objects cannot be deleted or overwritten for a
number of days) and a lifecycle rule that expires them afterwards.

```hcl
module "snapshots" {
  source                   = "../modules/r2-backup-bucket"
  account_id               = var.cloudflare_account_id
  bucket_name              = "example-snapshots"
  retention_prefix         = "history/" # "" for the whole bucket
  lock_retention_days      = 30         # 0: no lock
  lifecycle_retention_days = 31         # 0: no expiry
}
```

| Input | Default | Meaning |
|---|---|---|
| `account_id` | | Cloudflare account ID |
| `bucket_name` | | Bucket name; R2 scopes names by jurisdiction |
| `retention_prefix` | `history/` | Key prefix the lock and lifecycle rules apply to |
| `lock_retention_days` | `30` | Days an object under the prefix is immutable |
| `lifecycle_retention_days` | `31` | Days before an object under the prefix expires |

With a lock, the expiry has to be longer than the lock: R2 refuses to delete a locked object.
The module checks this before it plans the lifecycle rule.

| Output | Meaning |
|---|---|
| `bucket_name` | The bucket's name |
| `endpoint` | The account's EU-jurisdiction S3 endpoint |

The module creates the bucket and its rules only. An S3 API token for the application that
writes to the bucket is made separately.

## Tests

```bash
tofu init && tofu test
```

The tests mock the Cloudflare provider, so they need no account or token.
