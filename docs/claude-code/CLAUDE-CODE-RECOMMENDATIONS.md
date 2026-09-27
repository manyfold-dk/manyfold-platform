# Claude Code Setup Recommendations

> **Archived (2026-08-23).** Pre-baseline advice from May 2026. Agent assets are now vendored from the shared agent baseline; see [ADR-0050](../adr/0050-agent-instruction-baseline-and-session-altitudes.md).

> **Note (2026-09-27):** the local kind cluster and its scripts that this record names
> (`infrastructure/clusters/local/scripts`, `cluster.sh`, `verify-cluster.sh`, `run-pipeline.sh`)
> are retired; see [ADR-0054's amendment of 2026-09-27](../adr/0054-public-platform-and-private-instance-repositories.md#amendment-2026-09-27-no-demo-tree-throwaway-cloud-instances-instead).
>
> **Note (2026-09-27):** Tekton, the `tekton-status` skill and the `tkn` commands that this
> record names are retired as well; GitHub Actions build every image. See
> [ADR-0006's amendment of 2026-09-27](../adr/0006-cicd-tooling-selection.md#operational-amendment-2026-09-27-tekton-retired-from-the-repository).

Based on best practices from:
- [Anthropic's Official Best Practices](https://www.anthropic.com/engineering/claude-code-best-practices)
- [Boris Cherny](https://twitter-thread.com/t/2007179832300581177) (Claude Code creator)
- [Affaan Mustafa's everything-claude-code](https://github.com/affaan-m/everything-claude-code) (Anthropic hackathon winner)
- [ykdojo's claude-code-tips](https://github.com/ykdojo/claude-code-tips)
- [Ralph](https://github.com/snarktank/ralph) - Autonomous AI coding loops by Ryan Carson, building on Geoffrey Huntley's Ralph pattern

## Current Setup Assessment

Your setup is already well-structured with:
- ✅ Comprehensive CLAUDE.md with project context and build commands
- ✅ Modular rules in `.claude/rules/` (kubernetes.md, worktrees.md)
- ✅ Extensive bash permissions pre-configured
- ✅ Git worktree support for parallel sessions
- ✅ Plugins enabled (github, commit-commands, frontend-design)

## High-Priority Recommendations

### 1. Enable MCP Tool Lazy Loading

Your context window can shrink significantly with many tools. Enable lazy loading:

```bash
# Add to ~/.claude/settings.json (user-level)
claude config set env.ENABLE_TOOL_SEARCH true
```

Or manually edit `~/.claude/settings.json`:
```json
{
  "env": {
    "ENABLE_TOOL_SEARCH": "true"
  }
}
```

### 2. Add Custom Status Line

Track model, tokens, git status at a glance. Install the dx plugin:

```bash
claude plugin marketplace add ykdojo/claude-code-tips
claude plugin install dx@ykdojo
```

### 3. Add Playwright MCP for Browser Automation

You already have playwright permissions configured. Add the MCP server for richer browser automation:

```bash
claude mcp add -s user playwright npx @playwright/mcp@latest
```

This complements your existing cluster verification with Playwright UI tests.

### 4. Create Project-Specific Skills

Add `.claude/skills/` for your common workflows. Example for your Kubernetes work:

**`.claude/skills/cluster-verify.md`**
```markdown
# Cluster Verification Skill

When asked to verify or check the cluster:
1. Run `cd infrastructure/clusters/local/scripts && ./cluster.sh status`
2. Run `cd infrastructure/clusters/local/scripts && ./verify-cluster.sh --quick`
3. If pods are failing, check logs with `kubectl logs -n <namespace> <pod>`
4. For deeper issues, run full verification: `./verify-cluster.sh --verbose`
```

**`.claude/skills/tekton-status/SKILL.md`**
```markdown
# Tekton Status & Debugging

Health check (run in parallel):
1. Tekton pods: `kubectl get pods -n tekton-pipelines`
2. Pipeline runs: `tkn pipelinerun list -n tekton-builds --limit 10`
3. CronJobs: `kubectl get cronjobs,jobs -n tekton-builds`

When debugging failures:
1. Get status: `tkn pipelinerun describe <run-name> -n tekton-builds`
2. Stream logs: `tkn pipelinerun logs -f <run-name> -n tekton-builds`
3. Never use --watch flag - use tkn CLI for monitoring
```

### 5. Add Handoff Documents for Context Persistence

Create `/handoff` command for session transitions. Add `.claude/commands/handoff.md`:

```markdown
# Handoff Command

Generate a HANDOFF.md summarizing:
1. **Goal**: What we were trying to accomplish
2. **Progress**: What was completed
3. **Current State**: Where things stand now
4. **Blockers**: Any issues encountered
5. **Next Steps**: What should be done next

Save to `docs/HANDOFF.md` for the next session.
```

### 6. Add Kubernetes-Specific Commands

**`.claude/commands/k8s-status.md`**
```markdown
# Kubernetes Status Command

Quick cluster health check:
1. Show ArgoCD application sync status
2. List any unhealthy pods
3. Check ingress endpoints
4. Report any recent events/warnings
```

**`.claude/commands/deploy.md`**
```markdown
# Deploy Command

Deploy changes to local cluster:
1. Ensure changes are committed and pushed
2. Run `./run-pipeline.sh website`
3. Monitor with `tkn pipelinerun logs -f`
4. Verify deployment with `./cluster.sh status`
```

## Workflow Best Practices (from Boris Cherny & Anthropic)

### The Golden Rule: Verification Loops

> "The most important thing to get great results out of Claude Code: **give Claude a way to verify its work**. If Claude has that feedback loop, it will 2-3x the quality of the final result." — Boris Cherny

For your Kubernetes work, this means:
- Have Claude run `./verify-cluster.sh` after changes
- Use `kubectl get pods` to verify deployments
- Run `tkn pipelinerun describe` to check pipeline status
- Use Playwright tests for UI verification

### Explore → Plan → Code → Commit

The official Anthropic workflow:

1. **Explore**: Ask Claude to read files/URLs without writing code
2. **Plan**: Request a plan using thinking mode (`ultrathink` for complex tasks)
3. **Code**: Have Claude implement and verify
4. **Commit**: Finalize and update documentation

**Skipping steps 1-2 often leads to suboptimal solutions.**

Use `Shift+Tab` to toggle Plan mode, or start prompts with:
- "think" - light analysis
- "think hard" - deeper analysis
- "ultrathink" - thorough analysis for complex problems

### Parallel Claude Sessions (Boris's Setup)

Boris runs **5+ Claude sessions in parallel**:
- 5 terminal tabs numbered 1-5
- 5-10 additional sessions on claude.ai/code
- Uses system notifications to know when Claude needs input

For your setup, leverage your existing worktree infrastructure:
```bash
# Terminal 1: Main feature work
./scripts/worktree-session.sh start feature-a

# Terminal 2: Bug fix
./scripts/worktree-session.sh start bugfix-b

# Terminal 3: Documentation
./scripts/worktree-session.sh start docs-update
```

### Subagents for Automation

Use subagents for common verification tasks. Create `.claude/commands/verify.md`:
```markdown
# Verify Command

Run verification as a background subagent:
1. Build the project
2. Run tests
3. Check cluster status
4. Report results

Use Haiku model for speed.
```

### Course Correction Tools

Don't restart sessions—use these instead:
- **Escape**: Interrupt any phase while preserving context
- **Double-tap Escape**: Jump to previous prompts and explore alternatives
- **Request undo**: Ask Claude to revert changes and try different approaches
- **`/clear`**: Reset conversation without losing file context

### Team Collaboration Tips

From Boris's team at Anthropic:
- **Share CLAUDE.md**: Check it into git so the whole team contributes
- **Tag @.claude in PRs**: Update documentation as part of code reviews
- **Document mistakes**: When Claude does something wrong, add it to CLAUDE.md

## Medium-Priority Recommendations

### 7. Add Hooks for Safety Checks

Create `.claude/hooks/pre-commit-check.json`:
```json
{
  "name": "pre-commit-check",
  "trigger": "before:Bash",
  "matcher": {
    "command": "git commit"
  },
  "action": {
    "type": "confirm",
    "message": "About to commit. Have you verified the changes?"
  }
}
```

### 8. Configure Terminal Aliases

Add to your shell profile for faster access:
```bash
alias c='claude'
alias cc='claude --continue'
alias cr='claude --resume'
alias ws='./scripts/worktree-session.sh'
alias cs='./scripts/worktree-session.sh'
```

### 9. Essential Keyboard Shortcuts

**Navigation:**
- `Shift+Tab` - Toggle Plan mode (research without editing)
- `Escape` - Interrupt current operation
- `Escape Escape` - Jump to previous prompts
- `Ctrl+G` - Open external editor for long prompts
- `Ctrl+B` - Background long-running commands

**Quick Actions:**
- `!command` - Execute bash instantly without asking Claude
- `#note` - Save to memory (Claude asks where to store it)
- `/clear` - Reset conversation context
- `/compact` - Summarize conversation to free context

### 10. Add Voice Input

Consider voice transcription for faster communication:
- **macOS**: [superwhisper](https://superwhisper.com/) or MacWhisper
- Works even in shared spaces with EarPods

### 11. Use Exponential Backoff for Long Operations

For pipeline runs and cluster operations, use this pattern in your prompts:
> "Check status every 1 minute, then 2 minutes, then 4 minutes until complete"

More token-efficient than continuous monitoring.

## Low-Priority / Future Enhancements

### 12. Disable Auto-Updates (if patching system prompt)

If you apply system prompt optimizations:
```json
{
  "env": {
    "DISABLE_AUTOUPDATER": "1"
  }
}
```

### 13. Add GitHub Actions Debug Command

Install the `/gha` command for CI debugging:
```bash
claude plugin install dx@ykdojo
```
Usage: `/gha <github-actions-url>` to auto-investigate failures.

### 14. Consider Container Safety Mode

For experimental/research tasks, run Claude Code in containers:
```bash
docker run -it --rm \
  -v $(pwd):/workspace \
  claude-code --dangerously-skip-permissions
```

## Ralph: Autonomous AI Coding Loops

Ralph is an open-source pattern for running Claude Code in autonomous loops that build software while you sleep. The key insight: **AI gets worse as context grows**, so Ralph wipes context after every task.

### The Core Principle

Think of the context window like a whiteboard:
- **Start**: Clean whiteboard, AI reads instructions clearly, executes precisely
- **After many turns**: Filled with old code, failed attempts, tangents
- **Result**: AI wades through noise, forgets things, contradicts itself

Ralph solves this by **wiping the whiteboard after every single task**. Fresh start, full brainpower, every time.

### Why Compaction Breaks Ralph

Some implementations compact context instead of wiping it. This breaks the pattern because:
- The AI guesses what's important to carry forward
- When it guesses wrong, critical information disappears
- Bugs compound, features break in unexpected ways

**The correct approach**: Complete context wipe between iterations.

### The Growing File Problem

Another common mistake: letting the AI modify its own instructions each loop.

Models are verbose by default. Each loop adds tokens. Ten iterations in, you've stuffed the context window before the actual task starts.

**The canonical Ralph keeps the prompt static.** Only a simple flag marking tasks complete changes.

### The Canonical Implementation

The original is brutally simple - one bash while loop:

```bash
while true; do
  claude -p "$(cat prompt.md)" --allowedTools "Edit,Write,Bash,Read"
  # Check if all tasks marked complete, then break
done
```

The prompt tells Claude: read the plan, pick the most important incomplete task, implement it, test it, commit it, mark it done.

**Key**: The loop lives outside the model's control. The AI can't decide when to stop or modify the loop.

### Task Structure That Works

Each task in your plan file needs:

```markdown
## Task: Implement user authentication
- **Category**: Backend
- **Description**: Add JWT-based auth to API endpoints
- **Validation**:
  - [ ] `npm test` passes
  - [ ] Can login with test credentials
  - [ ] Protected routes return 401 without token
- **Passes**: false
```

The AI finds tasks where `Passes: false`, implements, runs validation, and only marks `Passes: true` if everything checks out.

**Exit condition**: Loop stops when every task shows `Passes: true`.

### Files You Need

| File | Purpose |
|------|---------|
| `prompt.md` | Static instructions (never changes) |
| `prd.md` | Task list with passes flags |
| `activity.md` | Log file (append-only, AI reads fresh each time) |
| `settings.json` | Sandbox configuration for permissions |

### Variations That Work

These add capabilities without breaking fresh-context-every-loop:

1. **Parallel Ralphs**: Multiple instances, different tasks, same codebase
2. **Browser validation**: Playwright automation instead of just unit tests
3. **GitHub Issues integration**: Ralph picks open issues, closes when done

### When to Use Ralph

**Good for**:
- Proof of concepts (validate architecture overnight)
- Exploratory builds (multiple versions to compare)
- Well-defined task lists with clear validation

**Not for**:
- Production engineering (needs human review)
- Ambiguous requirements
- Tasks requiring cross-cutting architectural decisions

### Cost Math

- Typical run: 15-25 iterations at ~$2-3 each
- Correct setup: $30-50 for a working proof of concept
- Wrong setup (spinning in circles): $300+ and a broken mess

**The difference is fresh context every loop.**

### Resources

- [Ralph GitHub](https://github.com/snarktank/ralph) - Original implementation
- [Damian Player's breakdown](https://x.com/damianplayer) - Detailed explanation

## Multi-Claude Workflows (Advanced)

### Parallel Code Review Pattern

From Anthropic's best practices:
1. **Claude A**: Writes code
2. **Claude B**: Reviews and tests the code
3. **Claude C**: Reads both outputs and refines

Separation often yields better results than single-agent handling.

### Multiple Checkouts

Create 3-4 git checkouts in separate folders:
```bash
# Using your existing worktree setup
./scripts/worktree-session.sh start task-a
./scripts/worktree-session.sh start task-b
./scripts/worktree-session.sh start task-c
```

Cycle through to manage permission requests while work continues in parallel.

### Headless Mode for Automation

Use for CI/CD integration:
```bash
claude -p "Run tests and report failures" --output-format stream-json
```

Useful for:
- Pre-commit hooks
- Build scripts
- Issue triage automation

## Context Window Management Tips

From Affaan's experience:
- **Limit MCPs**: Don't enable all at once. Your 200k context can shrink to 70k.
- **Keep under 10 MCPs enabled per project**
- **Fresh conversations perform better** - start new sessions for distinct tasks
- **Use `/compact`** when context gets heavy
- **Create HANDOFF.md** documents between sessions

## Your Specific Stack Recommendations

Given your Java/Quarkus + Vue + Kubernetes stack:

1. **Add Quarkus skill** for dev mode patterns
2. **Add Vue testing skill** with pnpm commands
3. **Add Terraform/OpenTofu skill** for cloud operations
4. **Consider Supabase MCP** if using database
5. **Add ArgoCD MCP** (if one exists) for GitOps operations

## Quick Wins (Do Today)

1. Enable tool lazy loading (30 seconds)
2. Install dx plugin for status line (1 minute)
3. Add Playwright MCP (1 minute)
4. Create one skill file for cluster verification (5 minutes)

## Summary

Your setup is already solid. The main gaps are:
- **Skills**: Add reusable workflow documentation
- **Commands**: Create project-specific slash commands
- **Context management**: Implement handoff patterns
- **MCP optimization**: Enable lazy loading

The biggest impact will come from skills that codify your Kubernetes debugging and deployment workflows.

---

## Sources

**Official & Creator Resources:**
- [Claude Code: Best Practices for Agentic Coding](https://www.anthropic.com/engineering/claude-code-best-practices) - Anthropic Engineering
- [Boris Cherny's Setup Thread](https://twitter-thread.com/t/2007179832300581177) - Claude Code creator

**Community Best Practices:**
- [everything-claude-code](https://github.com/affaan-m/everything-claude-code) - Affaan Mustafa (Anthropic hackathon winner)
- [claude-code-tips](https://github.com/ykdojo/claude-code-tips) - ykdojo (40+ tips)
- [Ultimate Claude Code Tips Collection](https://dev.to/damogallagher/the-ultimate-claude-code-tips-collection-advent-of-claude-2025-5b73) - Advent of Claude 2025
- [How I Use Every Claude Code Feature](https://blog.sshh.io/p/how-i-use-every-claude-code-feature) - Shrivu Shankar
- [ClaudeLog](https://claudelog.com/) - Community docs and tutorials

**Autonomous Coding Loops:**
- [Ralph](https://github.com/snarktank/ralph) - Autonomous AI coding loop by Ryan Carson, building on Geoffrey Huntley's Ralph pattern
- [Damian Player's Ralph Breakdown](https://x.com/damianplayer) - Detailed implementation guide
