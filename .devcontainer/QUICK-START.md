# Devcontainer Quick Start

Quick reference for running the development container.

## Table of Contents

- [VS Code (Recommended)](#vs-code-recommended)
- [Direct Docker (No VS Code)](#direct-docker-no-vs-code)
- [Common Commands](#common-commands)
- [Ports](#ports)
- [Authentication](#authentication)
- [Troubleshooting](#troubleshooting)
- [File Locations](#file-locations)
- [What's Inside](#whats-inside)
- [Architecture](#architecture)

## VS Code (Recommended)

1. Install **Dev Containers** extension in VS Code
2. Open project folder
3. Press `Cmd+Shift+P` → "Dev Containers: Reopen in Container"
4. Wait for build (first time only)

## Direct Docker (No VS Code)

### Interactive Shell

```bash
# Start development shell
./scripts/dev-shell.sh

# Inside container you have access to:
java --version    # Java 25
mvn --version     # Maven 3.9.12
node --version    # Node 24
pnpm --version    # pnpm 10
quarkus version   # Quarkus CLI
claude --version  # Claude Code CLI
```

### Background Container (Multiple Terminals)

```bash
# Start container in background
./scripts/dev-shell.sh --daemon

# Open terminal 1 - backend
./scripts/dev-shell.sh --exec zsh
cd /workspace/apps/website/backend
mvn quarkus:dev

# Open terminal 2 - frontend (new terminal window)
./scripts/dev-shell.sh --exec zsh
cd /workspace/apps/website/frontend
pnpm dev

# When done
./scripts/dev-shell.sh --stop
```

## Common Commands

```bash
# Build/rebuild container image
./scripts/dev-shell.sh --build

# Check if container is running
./scripts/dev-shell.sh --status

# View container logs
./scripts/dev-shell.sh --logs

# Stop and remove container
./scripts/dev-shell.sh --clean

# Show all options
./scripts/dev-shell.sh --help
```

## Ports

These ports are automatically forwarded to your Mac:

- **8080** - Quarkus backend API
- **5173** - Vite frontend dev server
- **9229** - Node.js debugger

Access at: `http://localhost:<port>`

## Authentication

### Claude CLI

Your Claude CLI authentication is automatically shared from your Mac to the container. You **won't need to re-authenticate** each time.

**First time setup** (if you haven't authenticated on your Mac yet):
```bash
# On your Mac (outside container)
claude config

# Now it's available inside the container automatically!
```

### Git Configuration

Git is automatically configured with:
```bash
git config --global --add safe.directory /workspace
```

Set your identity inside the container:
```bash
git config --global user.name "Your Name"
git config --global user.email "your.email@example.com"
```

## Troubleshooting

**"Container not found"**
```bash
./scripts/dev-shell.sh --daemon  # Start it first
```

**"Docker not running"**
```bash
# Start Docker Desktop from Applications folder
docker info  # Verify it's running
```

**"Image doesn't exist"**
```bash
./scripts/dev-shell.sh --build
```

**Need to rebuild after Dockerfile changes**
```bash
./scripts/dev-shell.sh --clean
./scripts/dev-shell.sh --build
```

## File Locations

- **Dockerfile**: `.devcontainer/Dockerfile`
- **VS Code Config**: `.devcontainer/devcontainer.json`
- **Helper Script**: `scripts/dev-shell.sh`
- **Full Guide**: `.devcontainer/README.md`

## What's Inside

✅ Java 25 LTS (Eclipse Temurin)
✅ Maven 3.9.12
✅ Node.js 24 LTS
✅ pnpm 10
✅ Quarkus CLI
✅ Claude Code CLI
✅ Git, curl, wget, vim
✅ zsh with Oh My Zsh
✅ Powerlevel10k theme
✅ zsh-autosuggestions

## Architecture

The container builds for the architecture of the build host: **ARM64 (Apple Silicon)** or **x86-64**.

Java home: `/usr/lib/jvm/temurin-25-jdk` (a link to the architecture-specific directory)
Maven home: `/opt/maven`
Workspace: `/workspace` (your project directory)

---

For detailed documentation, see: [.devcontainer/README.md](README.md)
