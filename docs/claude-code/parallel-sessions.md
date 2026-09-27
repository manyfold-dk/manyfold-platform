# Parallel Sessions with Git Worktrees

Run multiple agent sessions simultaneously using Git worktrees for complete isolation.

## Table of Contents

- [Overview](#overview)
- [Quick Start](#quick-start)
- [Session Commands](#session-commands)
- [Typical Workflow](#typical-workflow)
- [Common Scenarios](#common-scenarios)
- [Git Worktree Commands](#git-worktree-commands)
- [Cleanup](#cleanup)
- [Troubleshooting](#troubleshooting)
- [Best Practices](#best-practices)
- [Locations Reference](#locations-reference)

## Overview

Git worktrees allow you to have multiple working directories from the same repository. Each worktree:
- Has its own files (no conflicts)
- Has its own branch
- Can commit independently
- Can be paused and resumed

This enables running multiple agent sessions on different tasks simultaneously.

The canonical shared root is `~/.manyfold-worktrees/<checkout>/<task-name>`, where `<checkout>` is the directory name of the main checkout (the helper derives it, so two checkouts of this tree keep their worktrees apart). Use `./scripts/worktree-session.sh` as the shared helper.

`ws start` creates the worktree, feature branch, and repo-specific environment symlinks when their source files exist. For feature work, start from the main repo with the `dev-workflow` skill first, then continue from the worktree it creates.

## Quick Start

```bash
# Start new session
./scripts/worktree-session.sh start auth-refactor

# In the new directory, start your agent
cd ~/.manyfold-worktrees/<checkout>/auth-refactor
<start-your-agent>
```

Or using the alias:

```bash
ws start auth-refactor
cd ~/.manyfold-worktrees/<checkout>/auth-refactor
<start-your-agent>
```

## Session Commands

| Command | Description |
|---------|-------------|
| `ws start <name>` | Create new worktree and branch for isolated session |
| `ws list` | List all active sessions |
| `ws switch <name>` | Switch to existing session (opens new shell) |
| `ws cleanup <name>` | Remove session worktree and branch (refuses uncommitted or unmerged work; `--force` overrides) |
| `ws cleanup-all` | Remove all session worktrees under the worktree root (keeps sessions with uncommitted or unmerged work; `--force` removes them too) |
| `ws status` | Show current worktree and git status |

**Note**: `ws` is an alias for `./scripts/worktree-session.sh`.

## Typical Workflow

### 1. Start Multiple Parallel Sessions

```bash
# Terminal 1 - Authentication refactor
ws start auth-refactor
cd ~/.manyfold-worktrees/<checkout>/auth-refactor
claude

# Terminal 2 - Add metrics feature
ws start add-metrics
cd ~/.manyfold-worktrees/<checkout>/add-metrics
claude

# Terminal 3 - Bug fixes
ws start fix-login-bug
cd ~/.manyfold-worktrees/<checkout>/fix-login-bug
claude
```

### 2. Work in Isolation

Each session:
- Has its own files (no conflicts)
- Has its own branch (`feature/auth-refactor`, etc.)
- Can commit independently
- Can be paused and resumed

### 3. Finish and Merge

```bash
# When task is complete
cd ~/.manyfold-worktrees/<checkout>/auth-refactor

# Ensure everything is committed
git status

# Push to remote and create PR
git push -u origin feature/auth-refactor
gh pr create --title "Refactor authentication system" --body "..."

# After PR is merged, cleanup
cd /path/to/manyfold-platform
ws cleanup auth-refactor
```

## Common Scenarios

### Two Agent Sessions Running

```bash
# Session 1 commits to feature/auth-refactor
# Session 2 commits to feature/add-metrics
# No conflicts! Each has separate files and branches
```

### Review Changes from One Session While Another Works

```bash
# Terminal 1: Session working on add-metrics
cd ~/.manyfold-worktrees/<checkout>/add-metrics
<agent-still-running>

# Terminal 2: Review auth-refactor changes
cd ~/.manyfold-worktrees/<checkout>/auth-refactor
git log
git diff main
```

### Merge Conflicts When Both Sessions Modify Same File

```bash
# Won't happen during work - isolated files
# CAN happen when merging both PRs to main

# Solution: Merge first PR, then rebase second PR
cd ~/.manyfold-worktrees/<checkout>/add-metrics
git fetch origin
git rebase origin/main
# Resolve conflicts if any
git push --force-with-lease
```

### Forgot Which Sessions Are Active

```bash
ws list

# Output shows:
#   ● main (primary worktree)
#   ● auth-refactor
#     Branch: feature/auth-refactor
#     Path: ~/.manyfold-worktrees/<checkout>/auth-refactor
#   ● add-metrics
#     Branch: feature/add-metrics
#     Path: ~/.manyfold-worktrees/<checkout>/add-metrics
```

## Git Worktree Commands

| Command | Description |
|---------|-------------|
| `git worktree list` | List all worktrees and their branches |
| `git worktree add <path> -b <branch>` | Manually create worktree with new branch |
| `git worktree remove <path>` | Remove worktree |
| `git worktree prune` | Clean up stale worktree administrative files |

### Check If You're in a Worktree

```bash
# Quick check
git rev-parse --git-dir
# If output contains "worktrees" → you're in a worktree
# If output is just ".git" → you're in main repo

# Get worktree name
basename "$(git rev-parse --show-toplevel)"
```

## Cleanup

```bash
# Remove specific session
ws cleanup auth-refactor

# Remove all sessions under the worktree root (except main)
ws cleanup-all

# A session with uncommitted changes or an unmerged branch is kept;
# --force removes it and discards that work
ws cleanup --force auth-refactor

# Manual cleanup if script fails
git worktree remove --force ~/.manyfold-worktrees/<checkout>/auth-refactor
git branch -D feature/auth-refactor

# Prune stale worktree references
git worktree prune
```

## Troubleshooting

### "worktree already exists"

```bash
# List all worktrees
git worktree list

# Remove the existing one
git worktree remove <path>

# Or use the script
ws cleanup <name>
```

### "branch already exists"

```bash
# Delete the branch
git branch -D feature/<name>

# Or checkout existing branch
git worktree add <path> feature/<name>
```

### "Cannot remove working tree"

```bash
# Force removal
git worktree remove --force <path>
```

### Lost track of worktrees

```bash
# See all worktrees
git worktree list

# Show status of each
ws status
```

## Best Practices

| Practice | Why |
|----------|-----|
| Use descriptive task names | `auth-refactor` not `test` or `temp` |
| One task per worktree | Don't mix unrelated changes |
| Clean up when done | Remove merged worktrees to avoid clutter |
| Commit frequently | Each worktree is isolated, commit freely |
| Use conventional commits | Helps with changelog generation |
| Create PRs from worktrees | Push and create PR before cleaning up |
| Don't nest worktrees | Keep them in `~/.manyfold-worktrees/` |

## Locations Reference

| What | Where |
|------|-------|
| Main repository | `/path/to/manyfold-platform` (your clone) |
| Worktree base | `~/.manyfold-worktrees/<checkout>/` |
| Session script | `./scripts/worktree-session.sh` |
| Worktree format | `~/.manyfold-worktrees/<checkout>/<task-name>` |
| Branch format | `feature/<task-name>` |

## Quick Reference Card

```bash
# START
ws start my-task
cd ~/.manyfold-worktrees/<checkout>/my-task
claude

# LIST
ws list

# SWITCH
ws switch my-task

# STATUS
ws status

# CLEANUP
ws cleanup my-task
```
