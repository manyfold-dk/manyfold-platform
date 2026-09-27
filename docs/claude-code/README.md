# Claude Code Developer Guide

Comprehensive guide for using Claude Code effectively in this project.

## Table of Contents

- [Quick Start](#quick-start)
- [Guides](#guides)
- [Terminal Aliases](#terminal-aliases)
- [Essential Keyboard Shortcuts](#essential-keyboard-shortcuts)
- [Project Features](#project-features)

## Quick Start

```bash
# Reload shell after pulling changes
source ~/.zshrc

# Start Claude Code
c                    # Start new session
cc                   # Continue last session
cr                   # Resume with session picker

# Parallel sessions (isolated worktrees)
ws start my-feature  # Create worktree + branch
cd ~/.manyfold-worktrees/manyfold-platform/my-feature
claude               # Start Claude in isolation
```

## Guides

| Guide | Description |
|-------|-------------|
| [Getting Started](getting-started.md) | Installation, aliases, first steps |
| [Keyboard Shortcuts](keyboard-shortcuts.md) | All shortcuts, commands, and thinking modes |
| [Parallel Sessions](parallel-sessions.md) | Git worktrees for multi-task work |
| [Features & Plugins](features.md) | Skills, plugins, Ralph, Kubernetes commands |
| [Recommendations (archived, May 2026)](CLAUDE-CODE-RECOMMENDATIONS.md), [v2](CLAUDE-CODE-RECOMMENDATIONSv2.md) | Pre-baseline setup advice; superseded by the vendored agent assets ([ADR-0050](../adr/0050-agent-instruction-baseline-and-session-altitudes.md)) |

## Terminal Aliases

Add to `~/.zshrc` (may already be configured):

```bash
alias c='claude'                           # Start Claude
alias cc='claude --continue'               # Continue last session
alias cr='claude --resume'                 # Resume with picker
alias ws='./scripts/worktree-session.sh'   # Worktree session management
alias cy='claude --dangerously-skip-permissions'  # Skip all prompts
```

## Essential Keyboard Shortcuts

| Shortcut | Action |
|----------|--------|
| `Shift+Tab` | Toggle Plan mode (research without editing) |
| `Escape` | Interrupt current operation |
| `Escape Escape` | Go back to previous prompts |
| `Ctrl+G` | Open external editor for long prompts |

## Project Features

This project includes Claude Code enhancements:

| Feature | Description |
|---------|-------------|
| **Skills** | `.claude/skills/*.md` - Workflow guidance for cluster verification, pipeline debugging |
| **Commands** | `/k8s-status`, `/deploy` - Kubernetes operations |
| **dx Plugin** | Status line + `/handoff` for session handoffs |
| **Playwright MCP** | Browser automation for testing |
| **Ralph** | Autonomous coding loop for overnight tasks |

## Best Practices

### The Golden Rule: Verification Loops

> "Give Claude a way to verify its work. If Claude has that feedback loop, it will 2-3x the quality of the final result."

For Kubernetes work:
- Run the render harness (`scripts/ci/render-apps.py`) and the application tests before a pull request; after the merge, check that ArgoCD reports the applications `Synced` and `Healthy` on the cloud cluster
- Use `kubectl get pods` to verify deployments
- Run `gh run list` to check the CI runs that build the images

### Explore → Plan → Code → Commit

1. **Explore**: Read files/URLs without writing code
2. **Plan**: Use `Shift+Tab` or thinking modes
3. **Code**: Implement and verify
4. **Commit**: Finalize and document

**Skipping steps 1-2 often leads to suboptimal solutions.**

## Related Documentation

- [CLAUDE.md](../../CLAUDE.md) - Project-specific instructions for Claude
- [.claude/rules/](../../.claude/rules/) - Detailed rules for different domains
