# Development Container

Fully containerized development environment for the Manyfold Platform. All tools run inside a container, keeping your Mac clean.

## Table of Contents

- [What's Included](#whats-included)
- [Prerequisites](#prerequisites)
- [Quick Start](#quick-start)
- [Git Credentials](#git-credentials)
- [Verify Installation](#verify-installation)
- [Running Services](#running-services)
- [Ports](#ports)
- [Building and Deploying](#building-and-deploying)
- [Persistent Volumes](#persistent-volumes)
- [Customization](#customization)
- [Troubleshooting](#troubleshooting)
- [Architecture](#architecture)
- [File Structure](#file-structure)
- [References](#references)

## What's Included

- **Java 25 LTS** (Eclipse Temurin) + **Maven 3.9.12**
- **Node.js 24 LTS** + **pnpm 10**
- **Quarkus CLI** + **Claude CLI**
- **kubectl** + **Helm** + **kustomize** (Kubernetes tools)
- **Python 3** with PyYAML (an installation's render harness)
- **sops** + **age** (secrets encryption)
- **zsh** with oh-my-zsh and Powerlevel10k

## Prerequisites

1. **Docker Desktop**: `brew install --cask docker` then start from Applications
2. **VS Code** with **Dev Containers** extension

## Quick Start

### VS Code (Recommended)

1. Open project in VS Code
2. `Cmd+Shift+P` → **Dev Containers: Reopen in Container**
3. Wait for build (5-10 min first time, 10-30 sec after)

### Direct Docker

```bash
./scripts/dev-shell.sh              # Interactive shell
./scripts/dev-shell.sh --daemon     # Background mode
./scripts/dev-shell.sh --exec zsh   # Attach to running container
./scripts/dev-shell.sh --help       # All options
```

## Git Credentials

The devcontainer automatically configures git credentials from your `.env` file.

**Setup:**
1. Ensure `GITHUB_PERSONAL_ACCESS_TOKEN` is set in `/workspace/.env`
2. The devcontainer will auto-configure git on startup
3. Git push/pull to private repositories will work without prompting for credentials

**What happens:**
- On container startup, `.env` is sourced
- Git credential helper is configured to use the token
- All git operations to GitHub authenticate automatically

**Manual configuration** (if needed):
```bash
# Check current git credentials
git config --global credential.helper

# Reconfigure manually
/workspace/.devcontainer/setup-git-credentials.sh
```

## Verify Installation

```bash
java -version           # openjdk 25.0.x
mvn --version           # Apache Maven 3.9.12
node --version          # v24.x.x
pnpm --version          # 10.x.x
quarkus version         # Quarkus CLI
claude --version        # Claude Code CLI
kubectl version --client # Kubernetes CLI
helm version            # Helm package manager
kustomize version       # kustomize (render harness)
sops --version          # SOPS secrets encryption
age --version           # age encryption tool
```

## Running Services

**Backend (Quarkus)**:
```bash
cd /workspace/apps/website/backend
mvn quarkus:dev
# http://localhost:8080
```

**Frontend (Vue + Vite)**:
```bash
cd /workspace/apps/website/frontend
pnpm install && pnpm dev
# http://localhost:5173
```

## Ports

| Port | Service |
|------|---------|
| 8080 | Quarkus backend API |
| 5173 | Vite frontend dev server |
| 9229 | Node.js debugger |

## Building and Deploying

The devcontainer builds and tests code; it does not deploy. There is one cluster, the cloud
cluster: the local kind cluster, its deploy scripts and its pipeline scripts were retired on
2026-09-27.

- Prove a change inside the devcontainer with the application's own tests and build
  (`mvn verify`, `pnpm build`) and, for manifests, a render: `kustomize build` of the changed
  directory here, and in an installation its render harness, which renders every Argo CD
  Application before and after a change and compares the two.
- In an installation, a reviewed merge has CI (GitHub Actions) build and push the application
  images and commit the new tags, and Argo CD deploys its `main` to the cloud cluster. The
  public repository's CI verifies a change (the publication gate, a secret scan, a render of
  every Kustomize directory, the application builds, a link check) and deploys nothing.
- Read the cloud cluster from inside the devcontainer through the mounted `~/.kube`:

```bash
kubectl --kubeconfig ~/.kube/config-manyfold-cloud get applications -n argocd
```

## Persistent Volumes

These volumes persist across container rebuilds:

| Volume | Path | Purpose |
|--------|------|---------|
| `claude-config` | `~/.claude` | Claude CLI authentication |
| `codex-config` | `~/.codex` | Codex CLI authentication |
| `maven-repo` | `~/.m2/repository` | Maven dependencies |
| `pnpm-store` | `~/.local/share/pnpm/store` | pnpm packages |

**Bind Mounts** (from macOS host):

| macOS Host Path | Container Path | Purpose |
|---------|---------------|---------|
| `~/.kube` | `~/.kube` | kubectl config (read-only) |
| `~/.manyfold-worktrees` | `~/.manyfold-worktrees` | Git worktrees |

## Customization

### Add VS Code Extensions

Edit `devcontainer.json`:
```json
"extensions": ["your.extension.id"]
```

### Add System Packages

Edit `Dockerfile`:
```dockerfile
RUN apt-get update && apt-get install -y your-package
```

### Rebuild After Changes

- VS Code: `Cmd+Shift+P` → "Dev Containers: Rebuild Container"
- CLI: `./scripts/dev-shell.sh --build`

## Troubleshooting

**Container won't start**:
```bash
docker info       # Check if Docker is running
# If not running, start Docker Desktop from Applications
```

**kubectl can't connect to the cloud cluster**:

The devcontainer reads the host's `~/.kube` through a bind mount. Make sure:
1. `~/.kube/config-manyfold-cloud` exists on the host
2. The `~/.kube` directory is mounted into the devcontainer (should be automatic)

```bash
# Check that the cloud kubeconfig reaches the cluster
kubectl --kubeconfig ~/.kube/config-manyfold-cloud get nodes
```

**Permission issues with git**:
```bash
git config --global --add safe.directory /workspace
```

**Exit container**: `Cmd+Shift+P` → "Dev Containers: Reopen Folder Locally"

## Architecture

- **Base**: Ubuntu 24.04 (ARM64/Apple Silicon or x86-64, following the build host)
- **User**: vscode (non-root with sudo)
- **Workspace**: `/workspace`
- **Java Home**: `/usr/lib/jvm/temurin-25-jdk` (a link to the architecture-specific directory)
- **Maven Home**: `/opt/maven`

## File Structure

```
.devcontainer/
├── Dockerfile           # Container image definition
├── devcontainer.json    # VS Code Dev Container config
├── dotfiles/            # Shell configuration (zshrc, p10k)
├── README.md            # This file
└── QUICK-START.md       # Quick reference
```

## References

- [VS Code Dev Containers](https://code.visualstudio.com/docs/devcontainers/containers)
- [Docker Desktop](https://www.docker.com/products/docker-desktop/)
