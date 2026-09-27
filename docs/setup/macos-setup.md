# macOS Development Setup

One-time setup steps for developing on the Manyfold Platform.

## Table of Contents

- [Prerequisites](#prerequisites)
- [Tool Versions](#tool-versions)
- [Verification](#verification)
- [Cluster Access](#cluster-access)
- [Troubleshooting](#troubleshooting)

## Prerequisites

### 1. Install Homebrew

```bash
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
```

### 2. Install Required Tools

```bash
# Container runtime
brew install --cask docker

# Kubernetes tools
brew install kubectl helm

# ArgoCD CLI (for GitOps management)
brew install argocd

# Secrets management (SOPS + age encryption)
brew install sops age

# Node.js (required for Claude Code and MCP plugins)
brew install node

# AI coding assistants (optional)
brew install --cask codex
# Claude Code: npm install -g @anthropic-ai/claude-code
```

### 3. Configure and Start Docker Desktop

1. **Start Docker Desktop** from your Applications folder
2. Complete the initial setup wizard
3. Wait for Docker to fully start (whale icon in menu bar is stable)

**Configure resources (recommended):**
- Open Docker Desktop → Settings → Resources
- CPUs: 6 (or more)
- Memory: 8 GB (or more)
- Disk: 50 GB (or more)
- Click "Apply & Restart"

## Tool Versions

| Tool | Minimum Version | Tested Version | Install Command |
|------|-----------------|----------------|-----------------|
| Docker Desktop | 4.0+ | 4.30+ | `brew install --cask docker` |
| kubectl | 1.31+ | 1.35.0 | `brew install kubectl` |
| Helm | 3.0+ | 3.17.1 | `brew install helm` |
| argocd | 2.10+ | 3.2.5 | `brew install argocd` |
| sops | 3.9+ | 3.10.2 | `brew install sops` |
| age | 1.2+ | 1.3.1 | `brew install age` |
| Node.js | 20+ | 25.3.0 | `brew install node` |
| Codex | - | latest | `brew install --cask codex` |
| Claude Code | - | latest | `npm install -g @anthropic-ai/claude-code` |

## Verification

After completing setup, verify all tools are working:

```bash
# Check Docker
docker --version
docker info  # Should show Docker Desktop running

# Check Kubernetes tools
kubectl version --client
helm version
argocd version --client  # ArgoCD CLI

# Check secrets management tools
sops --version
age --version

# Check Node.js (required for Claude Code MCP plugins)
node --version
npx --version

# Check AI tools (optional)
codex --version
claude --version
```

## Cluster Access

There is no local cluster. The local kind cluster was retired on 2026-09-27 (see
[ADR-0054's amendment of 2026-09-27](../adr/0054-public-platform-and-private-instance-repositories.md#amendment-2026-09-27-no-demo-tree-throwaway-cloud-instances-instead)).
Development and verification run against the cloud cluster: the installation's render harness,
the per-application tests, and pull requests that Argo CD deploys after review and merge.

Get the kubeconfig for the cloud cluster from the OpenTofu output, as the
[cloud bootstrap guide](../../infrastructure/clusters/cloud/bootstrap/README.md#1-get-kubeconfig)
describes, and pass it explicitly (`<cloud-kubeconfig>` is the path of that file):

```bash
kubectl --kubeconfig <cloud-kubeconfig> get nodes
```

A demo or a major-upgrade rehearsal is a throwaway Hetzner Cloud instance built from the public
repository. No runbook for that path exists yet; the ADR-0054 amendment records the decision.

## Troubleshooting

### Docker Desktop won't start

1. Restart your Mac
2. Open Docker Desktop from Applications
3. If still failing, reset to factory defaults: Docker Desktop → Troubleshoot → Reset to factory defaults

### kubectl: "connection refused" or "Unauthorized" error

Check that the command uses the cloud kubeconfig and that the file is current:
```bash
kubectl --kubeconfig <cloud-kubeconfig> config current-context
kubectl --kubeconfig <cloud-kubeconfig> get nodes
```

### Permission denied errors

Ensure your user has admin access for Docker Desktop installation.
