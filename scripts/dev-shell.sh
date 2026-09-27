#!/usr/bin/env bash
#
# dev-shell.sh - Launch development container with Docker
#
# This script provides an easy way to run the devcontainer directly with Docker,
# without requiring VS Code or the Dev Containers extension.
#
# Usage:
#   ./scripts/dev-shell.sh                  # Start interactive shell
#   ./scripts/dev-shell.sh --build          # Rebuild image only
#   ./scripts/dev-shell.sh --rebuild        # Clean, rebuild, and start shell
#   ./scripts/dev-shell.sh --rebuild-daemon # Clean, rebuild, start daemon, exec shell
#   ./scripts/dev-shell.sh --daemon         # Start container in background
#   ./scripts/dev-shell.sh --stop           # Stop background container
#   ./scripts/dev-shell.sh --help           # Show full help

set -e

# Configuration
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
CONTAINER_NAME="manyfold-dev"
IMAGE_NAME="manyfold-devcontainer:latest"
DOCKERFILE="$PROJECT_DIR/.devcontainer/Dockerfile"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Helper functions
print_info() {
    echo -e "${BLUE}ℹ${NC} $1"
}

print_success() {
    echo -e "${GREEN}✔${NC} $1"
}

print_warning() {
    echo -e "${YELLOW}⚠${NC} $1"
}

print_error() {
    echo -e "${RED}✖${NC} $1"
}

show_help() {
    cat << EOF
Manyfold Platform - Development Container Shell

USAGE:
    ./scripts/dev-shell.sh [OPTIONS]

OPTIONS:
    (no options)    Start an interactive shell in a new container
    --build         Rebuild the container image before starting
    --rebuild       Clean container, rebuild image, and start shell
    --rebuild-daemon Clean, rebuild, start daemon, and exec into shell
    --daemon        Start container as daemon (background process)
    --exec          Execute command in running daemon container
    --stop          Stop the daemon container
    --restart       Restart the daemon container
    --logs          Show logs from daemon container
    --status        Show status of daemon container
    --clean         Remove daemon container and clean up
    --help          Show this help message

EXAMPLES:
    # Start interactive development shell
    ./scripts/dev-shell.sh

    # Rebuild image only
    ./scripts/dev-shell.sh --build

    # Full rebuild: clean, build, and start interactive shell
    ./scripts/dev-shell.sh --rebuild

    # Full rebuild in daemon mode: clean, build, start daemon, exec shell
    ./scripts/dev-shell.sh --rebuild-daemon

    # Start container in background
    ./scripts/dev-shell.sh --daemon

    # Execute command in running container
    ./scripts/dev-shell.sh --exec "mvn --version"

    # Stop background container
    ./scripts/dev-shell.sh --stop

PORTS:
    The following ports are automatically forwarded:
    - 8080  Quarkus backend API
    - 5173  Vite frontend dev server
    - 9229  Node.js debugger

VOLUMES:
    Your project directory is mounted at /workspace inside the container.

    Persistent named volumes (survive container rebuilds):
    - claude-config  → ~/.claude (Claude CLI auth)
    - codex-config   → ~/.codex (Codex CLI auth)
    - maven-repo     → ~/.m2/repository (Maven dependencies)
    - pnpm-store     → ~/.local/share/pnpm/store (pnpm packages)

    Bind mounts (shared with host):
    - ~/.manyfold-worktrees → ~/.manyfold-worktrees (Git worktrees)
    - ~/.kube → ~/.kube (kubectl config, read-only if present)

EOF
}

check_docker() {
    if ! command -v docker &> /dev/null; then
        print_error "Docker is not installed or not in PATH"
        echo "Please install Docker Desktop: https://www.docker.com/products/docker-desktop/"
        exit 1
    fi

    # Check if Docker is running
    if ! docker info &> /dev/null; then
        print_error "Docker is not running"
        print_info "Start Docker Desktop from your Applications folder"
        exit 1
    fi
}

build_image() {
    print_info "Building devcontainer image..."
    cd "$PROJECT_DIR/.devcontainer"

    # Pass host UID/GID to match file permissions between host and container
    USER_UID=$(id -u)
    USER_GID=$(id -g)
    print_info "Building with USER_UID=$USER_UID and USER_GID=$USER_GID"

    # Generate checksum of dotfiles to bust cache when they change
    DOTFILES_HASH=""
    if [ -d "dotfiles" ]; then
        DOTFILES_HASH=$(find dotfiles -type f -exec cat {} \; 2>/dev/null | shasum -a 256 | cut -c1-12)
        print_info "Dotfiles checksum: $DOTFILES_HASH"
    fi

    if docker build -t "$IMAGE_NAME" -f "$DOCKERFILE" \
        --build-arg USER_UID="$USER_UID" \
        --build-arg USER_GID="$USER_GID" \
        --build-arg DOTFILES_HASH="$DOTFILES_HASH" .; then
        print_success "Image built successfully: $IMAGE_NAME"
    else
        print_error "Image build failed"
        exit 1
    fi
}

check_image_exists() {
    if ! docker image inspect "$IMAGE_NAME" &> /dev/null; then
        print_warning "Image $IMAGE_NAME does not exist"
        print_info "Building image for the first time..."
        build_image
    fi
}

container_exists() {
    docker container inspect "$CONTAINER_NAME" &>/dev/null
}

container_running() {
    [ "$(docker inspect -f '{{.State.Running}}' "$CONTAINER_NAME" 2>/dev/null)" == "true" ]
}

start_interactive() {
    print_info "Starting interactive development shell..."
    print_info "Project mounted at: /workspace"
    print_info "Press Ctrl+D or type 'exit' to leave the container"
    echo ""

    mkdir -p "$HOME/.manyfold-worktrees"

    # Persistent volumes (match devcontainer.json)
    VOLUME_MOUNTS=(
        "-v" "claude-config:/home/vscode/.claude"
        "-v" "codex-config:/home/vscode/.codex"
        "-v" "maven-repo:/home/vscode/.m2/repository"
        "-v" "pnpm-store:/home/vscode/.local/share/pnpm/store"
        "-v" "$HOME/.manyfold-worktrees:/home/vscode/.manyfold-worktrees"
    )

    # Bind mount ~/.kube if it exists (for kubectl access to host clusters)
    if [ -d "$HOME/.kube" ]; then
        VOLUME_MOUNTS+=("-v" "$HOME/.kube:/home/vscode/.kube:ro")
    fi

    print_info "Persistent volumes: claude-config, codex-config, maven-repo, pnpm-store"

    # Load environment variables from .env if it exists
    ENV_VARS=()
    if [ -f "$PROJECT_DIR/.env" ]; then
        print_info "Loading environment variables from .env"
        while IFS='=' read -r key value; do
            # Skip comments and empty lines
            [[ "$key" =~ ^#.*$ ]] && continue
            [[ -z "$key" ]] && continue
            # Remove quotes from value if present
            value="${value%\"}"
            value="${value#\"}"
            ENV_VARS+=("-e" "$key=$value")
        done < "$PROJECT_DIR/.env"
    fi

    # Build the run command environment variables
    RUN_ENV_ARGS=()
    # Pass TERM_PROGRAM for iTerm2 profile switching
    if [ -n "$TERM_PROGRAM" ]; then
        RUN_ENV_ARGS+=("-e" "TERM_PROGRAM=$TERM_PROGRAM")
    fi

    # Startup script that initializes the environment
    STARTUP_SCRIPT='
        sudo chown -R vscode:vscode /home/vscode/.claude /home/vscode/.codex 2>/dev/null || true
        # Symlink .claude.json from volume to home (OAuth credentials are stored here)
        if [ -f /home/vscode/.claude/.claude.json ]; then
            ln -sf /home/vscode/.claude/.claude.json /home/vscode/.claude.json
        elif [ -f /home/vscode/.claude.json ] && [ ! -L /home/vscode/.claude.json ]; then
            # Move existing file into volume and create symlink
            mv /home/vscode/.claude.json /home/vscode/.claude/.claude.json
            ln -sf /home/vscode/.claude/.claude.json /home/vscode/.claude.json
        else
            # Create symlink for new installations
            ln -sf /home/vscode/.claude/.claude.json /home/vscode/.claude.json 2>/dev/null || true
        fi
        git config --global --add safe.directory /workspace 2>/dev/null
        [ -f /workspace/.env ] && set -a && source /workspace/.env && set +a
        /workspace/.devcontainer/setup-git-credentials.sh 2>/dev/null || true
        /workspace/.devcontainer/setup-worktree-paths.sh 2>/dev/null || true
        exec zsh
    '

    docker run --rm -it \
        --name "${CONTAINER_NAME}-temp" \
        -v "$PROJECT_DIR:/workspace" \
        "${VOLUME_MOUNTS[@]}" \
        -w /workspace \
        -p 8080:8080 \
        -p 5173:5173 \
        -p 9229:9229 \
        "${RUN_ENV_ARGS[@]}" \
        "${ENV_VARS[@]}" \
        "$IMAGE_NAME" \
        zsh -c "$STARTUP_SCRIPT"
}

start_daemon() {
    if container_running; then
        print_warning "Container '$CONTAINER_NAME' is already running"
        print_info "Use './scripts/dev-shell.sh --exec zsh' to attach"
        exit 0
    fi

    if container_exists; then
        print_info "Starting existing container..."
        docker start "$CONTAINER_NAME"
    else
        mkdir -p "$HOME/.manyfold-worktrees"

        # Persistent volumes (match devcontainer.json)
        VOLUME_MOUNTS=(
            "-v" "claude-config:/home/vscode/.claude"
            "-v" "codex-config:/home/vscode/.codex"
            "-v" "maven-repo:/home/vscode/.m2/repository"
            "-v" "pnpm-store:/home/vscode/.local/share/pnpm/store"
            "-v" "$HOME/.manyfold-worktrees:/home/vscode/.manyfold-worktrees"
        )

        # Bind mount ~/.kube if it exists (for kubectl access to host clusters)
        if [ -d "$HOME/.kube" ]; then
            VOLUME_MOUNTS+=("-v" "$HOME/.kube:/home/vscode/.kube:ro")
        fi

        print_info "Persistent volumes: claude-config, codex-config, maven-repo, pnpm-store"

        # Load environment variables from .env if it exists
        ENV_VARS=()
        if [ -f "$PROJECT_DIR/.env" ]; then
            print_info "Loading environment variables from .env"
            while IFS='=' read -r key value; do
                # Skip comments and empty lines
                [[ "$key" =~ ^#.*$ ]] && continue
                [[ -z "$key" ]] && continue
                # Remove quotes from value if present
                value="${value%\"}"
                value="${value#\"}"
                ENV_VARS+=("-e" "$key=$value")
            done < "$PROJECT_DIR/.env"
        fi

        # Build the run command environment variables
        RUN_ENV_ARGS=()
        # Pass TERM_PROGRAM for iTerm2 profile switching
        if [ -n "$TERM_PROGRAM" ]; then
            RUN_ENV_ARGS+=("-e" "TERM_PROGRAM=$TERM_PROGRAM")
        fi

        # Startup script that initializes the environment
        STARTUP_SCRIPT='
            sudo chown -R vscode:vscode /home/vscode/.claude /home/vscode/.codex 2>/dev/null || true
            # Symlink .claude.json from volume to home (OAuth credentials are stored here)
            if [ -f /home/vscode/.claude/.claude.json ]; then
                ln -sf /home/vscode/.claude/.claude.json /home/vscode/.claude.json
            elif [ -f /home/vscode/.claude.json ] && [ ! -L /home/vscode/.claude.json ]; then
                # Move existing file into volume and create symlink
                mv /home/vscode/.claude.json /home/vscode/.claude/.claude.json
                ln -sf /home/vscode/.claude/.claude.json /home/vscode/.claude.json
            else
                # Create symlink for new installations
                ln -sf /home/vscode/.claude/.claude.json /home/vscode/.claude.json 2>/dev/null || true
            fi
            git config --global --add safe.directory /workspace 2>/dev/null
            [ -f /workspace/.env ] && set -a && source /workspace/.env && set +a
            /workspace/.devcontainer/setup-git-credentials.sh 2>/dev/null || true
            /workspace/.devcontainer/setup-worktree-paths.sh 2>/dev/null || true
            exec zsh
        '

        print_info "Creating and starting daemon container..."
        docker run -d -it \
            --name "$CONTAINER_NAME" \
            -v "$PROJECT_DIR:/workspace" \
            "${VOLUME_MOUNTS[@]}" \
            -w /workspace \
            -p 8080:8080 \
            -p 5173:5173 \
            -p 9229:9229 \
            "${RUN_ENV_ARGS[@]}" \
            "${ENV_VARS[@]}" \
            "$IMAGE_NAME" \
            zsh -c "$STARTUP_SCRIPT"
    fi

    print_success "Container '$CONTAINER_NAME' is running in background"
    print_info "Attach with: ./scripts/dev-shell.sh --exec zsh"
    print_info "Stop with: ./scripts/dev-shell.sh --stop"
}

exec_in_container() {
    if ! container_running; then
        print_error "Container '$CONTAINER_NAME' is not running"
        print_info "Start it with: ./scripts/dev-shell.sh --daemon"
        exit 1
    fi

    if [ $# -eq 0 ]; then
        # No command provided, start interactive shell
        docker exec -it "$CONTAINER_NAME" zsh
    else
        # Execute provided command
        docker exec -it "$CONTAINER_NAME" zsh -c "$*"
    fi
}

stop_container() {
    if container_running; then
        print_info "Stopping container '$CONTAINER_NAME'..."
        docker stop "$CONTAINER_NAME"
        print_success "Container stopped"
    else
        print_warning "Container '$CONTAINER_NAME' is not running"
    fi
}

restart_container() {
    if container_exists; then
        print_info "Restarting container '$CONTAINER_NAME'..."
        docker restart "$CONTAINER_NAME"
        print_success "Container restarted"
    else
        print_warning "Container '$CONTAINER_NAME' does not exist"
        print_info "Creating new container..."
        start_daemon
    fi
}

show_logs() {
    if container_exists; then
        docker logs -f "$CONTAINER_NAME"
    else
        print_error "Container '$CONTAINER_NAME' does not exist"
        exit 1
    fi
}

show_status() {
    if container_exists; then
        if container_running; then
            print_success "Container '$CONTAINER_NAME' is RUNNING"
            echo ""
            docker inspect "$CONTAINER_NAME" --format '{{.State.Status}}: {{.State.StartedAt}}'
            echo ""
            print_info "Ports:"
            docker port "$CONTAINER_NAME"
        else
            print_warning "Container '$CONTAINER_NAME' exists but is NOT running"
        fi
    else
        print_info "Container '$CONTAINER_NAME' does not exist"
    fi
}

clean_container() {
    if container_running; then
        print_info "Stopping container..."
        docker stop "$CONTAINER_NAME"
    fi

    if container_exists; then
        print_info "Removing container..."
        docker rm "$CONTAINER_NAME"
        print_success "Container removed"
    else
        print_info "No container to clean up"
    fi
}

# Main script
main() {
    check_docker

    case "${1:-}" in
        --help|-h)
            show_help
            exit 0
            ;;
        --build|-b)
            build_image
            ;;
        --rebuild)
            clean_container
            build_image
            start_interactive
            ;;
        --rebuild-daemon)
            clean_container
            build_image
            start_daemon
            exec_in_container
            ;;
        --daemon|-d)
            check_image_exists
            start_daemon
            ;;
        --exec|-e)
            shift
            exec_in_container "$@"
            ;;
        --stop|-s)
            stop_container
            ;;
        --restart|-r)
            restart_container
            ;;
        --logs|-l)
            show_logs
            ;;
        --status)
            show_status
            ;;
        --clean|-c)
            clean_container
            ;;
        "")
            check_image_exists
            start_interactive
            ;;
        *)
            print_error "Unknown option: $1"
            echo ""
            show_help
            exit 1
            ;;
    esac
}

main "$@"
