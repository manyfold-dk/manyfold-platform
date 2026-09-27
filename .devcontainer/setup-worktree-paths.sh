#!/usr/bin/env bash
#
# Set up symlinks to make git worktrees work inside the devcontainer
#
# Git worktrees use absolute paths. On macOS, paths like:
#   /Users/<user>/Developer/Private/<checkout>   (LOCAL_WORKSPACE_FOLDER, set by
#                                                  devcontainer.json and dev-shell.sh)
#   /Users/<user>/.manyfold-worktrees
# don't exist inside the container. This script creates symlinks so they resolve.
#

set -e

# Detect the macOS username from the worktree mount or git config
detect_macos_user() {
    # Try to find the username from existing worktree .git files
    shopt -s nullglob
    for gitfile in /home/vscode/.manyfold-worktrees/*/*/.git; do
        if [ -f "$gitfile" ]; then
            # Extract username from path like /Users/USERNAME/Developer/...
            local user=$(grep -o '/Users/[^/]*/' "$gitfile" 2>/dev/null | head -1 | sed 's|/Users/\([^/]*\)/|\1|')
            if [ -n "$user" ]; then
                echo "$user"
                return 0
            fi
        fi
    done
    shopt -u nullglob

    # Fallback: try git config
    local email=$(git config user.email 2>/dev/null || echo "")
    if [ -n "$email" ]; then
        # Use email prefix as a guess
        echo "${email%%@*}"
        return 0
    fi

    return 1
}

# Get macOS username
MACOS_USER=$(detect_macos_user)
if [ -z "$MACOS_USER" ] && [[ "${LOCAL_WORKSPACE_FOLDER:-}" == /Users/*/* ]]; then
    MACOS_USER=$(echo "$LOCAL_WORKSPACE_FOLDER" | sed 's|^/Users/\([^/]*\)/.*|\1|')
fi
if [ -z "$MACOS_USER" ]; then
    echo "⚠️  Could not detect macOS username for worktree paths"
    echo "   Worktrees may not work correctly inside the container"
    exit 0
fi

echo "ℹ️  Setting up worktree path symlinks for macOS user: $MACOS_USER"

# Create parent directories
sudo mkdir -p "/Users/$MACOS_USER/Developer/Private"
sudo mkdir -p "/Users/$MACOS_USER"

# Create symlink for the checkout's host path (its name is not fixed: the instance and the
# public reference installation are two checkouts of this tree). The container is opened from
# the main checkout; a container opened from a worktree has no main checkout mounted, so the
# worktree's .git file cannot resolve there, as before this change.
HOST_REPO="${LOCAL_WORKSPACE_FOLDER:-/Users/$MACOS_USER/Developer/Private/manyfold-platform}"
sudo mkdir -p "$(dirname "$HOST_REPO")"
if [ ! -e "$HOST_REPO" ]; then
    sudo ln -sf /workspace "$HOST_REPO"
    echo "   ✓ Linked main repo path ($HOST_REPO)"
fi

# Create symlink for canonical worktrees path
if [ -d "/home/vscode/.manyfold-worktrees" ] && [ ! -e "/Users/$MACOS_USER/.manyfold-worktrees" ]; then
    sudo ln -sf /home/vscode/.manyfold-worktrees "/Users/$MACOS_USER/.manyfold-worktrees"
    echo "   ✓ Linked canonical worktrees path"
fi

echo "✅ Worktree paths configured"
echo "   You can now work in worktrees from /home/vscode/.manyfold-worktrees/"
