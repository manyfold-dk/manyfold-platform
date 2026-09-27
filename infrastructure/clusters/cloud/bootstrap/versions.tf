terraform {
  required_version = ">= 1.6.0"

  required_providers {
    hcloud = {
      source  = "hetznercloud/hcloud"
      version = "~> 1.59"
    }
    cloudflare = {
      source  = "cloudflare/cloudflare"
      version = "~> 5.0"
    }
    talos = {
      source  = "siderolabs/talos"
      version = "~> 0.11"
    }
    local = {
      source  = "hashicorp/local"
      version = "~> 2.5"
    }
  }

  # S3-compatible backend (Cloudflare R2), partial configuration.
  # backend.hcl supplies the installation's bucket, endpoint and credentials
  # (see backend.hcl.example). Initialize with:
  #   tofu init -backend-config=backend.hcl
  backend "s3" {
    key    = "cloud/bootstrap/terraform.tfstate"
    region = "auto"

    # R2 doesn't support these features
    skip_credentials_validation = true
    skip_requesting_account_id  = true
    skip_metadata_api_check     = true
    skip_region_validation      = true
    skip_s3_checksum            = true
    use_path_style              = true
  }
}

provider "hcloud" {
  # Token via HCLOUD_TOKEN environment variable
}

provider "cloudflare" {
  # Token via CLOUDFLARE_API_TOKEN environment variable
}

provider "talos" {}
