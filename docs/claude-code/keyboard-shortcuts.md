# Keyboard Shortcuts & Commands

Complete reference for Claude Code keyboard shortcuts, slash commands, and thinking modes.

## Table of Contents

- [Navigation](#navigation)
- [Input](#input)
- [Quick Actions](#quick-actions)
- [Slash Commands](#slash-commands)
- [Thinking Modes](#thinking-modes)
- [Course Correction](#course-correction)
- [Voice Input](#voice-input)
- [Container Safety Mode](#container-safety-mode)
- [Context Window Management](#context-window-management)

## Navigation

| Shortcut | Action |
|----------|--------|
| `Shift+Tab` | Toggle Plan mode (research without editing) |
| `Escape` | Interrupt current operation |
| `Escape Escape` | Jump to previous prompts, explore alternatives |
| `Ctrl+C` | Cancel current input |

## Input

| Shortcut | Action |
|----------|--------|
| `Ctrl+G` | Open external editor for long prompts |
| `Ctrl+B` | Background long-running commands |
| `Up/Down` | Navigate prompt history |

## Quick Actions

Type these prefixes in your prompt:

| Prefix | Action |
|--------|--------|
| `!command` | Execute bash instantly without Claude |
| `#note` | Save to memory (Claude asks where to store) |

## Slash Commands

### Built-in Commands

| Command | Action |
|---------|--------|
| `/clear` | Reset conversation context |
| `/compact` | Summarize conversation to free context |
| `/help` | Show available commands |
| `/resume` | Resume a previous session |

### Project Commands

| Command | Action |
|---------|--------|
| `/handoff` | Generate session handoff document (dx plugin) |
| `/k8s-status` | Quick cluster health check |
| `/deploy` | Deploy a change to the cloud cluster (pull request, merge, ArgoCD sync) |

## Thinking Modes

Prefix your prompts to control analysis depth:

| Prefix | Depth | Use Case |
|--------|-------|----------|
| `think` | Light analysis | Simple questions |
| `think hard` | Deeper analysis | Complex problems |
| `ultrathink` | Thorough analysis | Architecture decisions, debugging |

### Examples

```
think about how to add a new endpoint

think hard about the authentication flow

ultrathink about how to restructure the deployment pipeline
```

## Course Correction

Don't restart sessions - use these instead:

1. **Escape** - Interrupt while preserving context
2. **Double-tap Escape** - Go back to previous prompts
3. **"undo that"** - Ask Claude to revert changes
4. **`/clear`** - Reset conversation without losing file context

### When to Use Each

| Situation | Action |
|-----------|--------|
| Claude is going wrong direction | `Escape`, then redirect |
| Want to try different approach | `Escape Escape` to go back |
| Made unwanted file changes | "undo that" or "revert the last change" |
| Context is cluttered | `/clear` to start fresh |
| Need to preserve progress | `/handoff` before clearing |

## Voice Input

For faster communication, consider voice transcription.

### macOS Options

| Tool | Description |
|------|-------------|
| [superwhisper](https://superwhisper.com/) | AI-powered dictation, works system-wide |
| [MacWhisper](https://goodsnooze.gumroad.com/l/macwhisper) | Local Whisper transcription |

**Tip**: Works even in shared spaces with EarPods/AirPods.

### Setup

1. Install your preferred tool
2. Configure global hotkey (e.g., `Cmd+Shift+Space`)
3. Dictate, then paste into Claude Code

## Container Safety Mode

For experimental or risky operations, run Claude Code in a container:

```bash
# Basic container run
docker run -it --rm \
  -v $(pwd):/workspace \
  -w /workspace \
  node:20 \
  npx @anthropic-ai/claude-code --dangerously-skip-permissions

# With mounted credentials
docker run -it --rm \
  -v $(pwd):/workspace \
  -v ~/.claude:/root/.claude:ro \
  -w /workspace \
  node:20 \
  npx @anthropic-ai/claude-code
```

**Use cases**:
- Testing destructive operations
- Running untrusted code
- Isolated experiments

## Context Window Management

Tips from heavy users:

| Tip | Description |
|-----|-------------|
| **Limit MCPs** | Don't enable all at once (200k can shrink to 70k) |
| **Keep under 10 MCPs** | Per project maximum |
| **Fresh conversations** | Start new sessions for distinct tasks |
| **Use `/compact`** | When context gets heavy |
| **Create HANDOFF.md** | Document between sessions with `/handoff` |

### Signs You Need to Manage Context

- Claude starts forgetting earlier instructions
- Responses become slower
- Claude asks about things you already discussed

### Recovery Steps

1. Run `/handoff` to save progress
2. Run `/compact` to summarize
3. If still heavy, `/clear` and start fresh
4. Reference HANDOFF.md in new session
