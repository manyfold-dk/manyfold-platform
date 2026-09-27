# Getting Started with Claude Code

Setup and first steps for using Claude Code in this project.

## Table of Contents

- [Prerequisites](#prerequisites)
- [Shell Aliases](#shell-aliases)
- [First Session](#first-session)
- [Tool Lazy Loading](#tool-lazy-loading)
- [dx Plugin](#dx-plugin)
- [Troubleshooting](#troubleshooting)

## Prerequisites

1. Claude Code CLI installed
2. Access to the project repository
3. Shell configured (zsh recommended)

## Shell Aliases

Add these to your `~/.zshrc`:

```bash
alias c='claude'                           # Start Claude
alias cc='claude --continue'               # Continue last session
alias cr='claude --resume'                 # Resume with picker
alias ws='./scripts/worktree-session.sh'   # Worktree session management
alias cy='claude --dangerously-skip-permissions'  # Skip all prompts
```

Reload after adding:

```bash
source ~/.zshrc
```

### Usage Examples

```bash
c                    # Start a new Claude session
cc                   # Pick up where you left off
cr                   # Choose from recent sessions
ws start my-feature  # Create isolated worktree session
cd ~/.manyfold-worktrees/<checkout>/my-feature
cy                   # Full auto-approve mode (use carefully!)
```

## First Session

1. Navigate to the project:
   ```bash
   cd /path/to/manyfold-platform
   ```

2. Start Claude:
   ```bash
   c
   ```

3. Try a simple query:
   ```
   What does this project do?
   ```

4. End session:
   ```
   /clear
   ```
   Or just close the terminal.

## Tool Lazy Loading

MCP tools are loaded on-demand instead of all at once, saving context window space.

**How it works**: Automatically enabled. When Claude needs a tool, it searches and loads it. Your 200k context won't shrink to 70k from tool definitions.

**No action needed** - this is automatic.

## dx Plugin

Community plugin adding a status line and `/handoff` command.

### Status Line

The status bar at the bottom shows:
- Current model
- Git branch and uncommitted files
- Token usage progress bar

### /handoff Command

When ending a session or switching context:

```
/handoff
```

This generates/updates `HANDOFF.md` with:
- Goal
- Progress
- What worked / didn't work
- Next steps

Use this before `/clear` or ending sessions to preserve context.

## Troubleshooting

### Aliases not working

```bash
source ~/.zshrc
```

### Plugins not showing

Restart Claude Code after plugin installation.

### /handoff not found

Ensure dx plugin is installed:

```bash
claude plugin list | grep dx
```

### Skills not triggering

Skills are guidance, not commands. They help Claude when you ask about related topics. Try being explicit: "Follow the cluster-verify skill to check the cluster."

## Next Steps

- [Keyboard Shortcuts](keyboard-shortcuts.md) - Learn all shortcuts and commands
- [Parallel Sessions](parallel-sessions.md) - Work on multiple tasks simultaneously
- [Features & Plugins](features.md) - Explore skills, Ralph, and more
