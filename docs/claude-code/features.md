# Features & Plugins

Project-specific Claude Code enhancements including skills, plugins, and automation tools.

## Table of Contents

- [Project Skills](#project-skills)
- [Kubernetes Commands](#kubernetes-commands)
- [Playwright MCP Server](#playwright-mcp-server)
- [Ralph - Autonomous Coding Loop](#ralph---autonomous-coding-loop)
- [File Locations](#file-locations)

## Project Skills

Skills are markdown files that teach Claude your workflows. Located in `.claude/skills/`.

### Cluster Verification Skill

**File**: `.claude/skills/cluster-verify.md`

**Triggers on prompts like**:
- "Check the cluster"
- "Verify the deployment"
- "Why are pods failing?"

Claude will automatically follow the documented troubleshooting steps.

### Creating New Skills

1. Create a markdown file in `.claude/skills/`
2. Document the workflow with clear steps
3. Include example commands and expected outputs
4. Skills activate when Claude recognizes relevant context

**Tip**: Skills are guidance, not commands. Be explicit if needed: "Follow the cluster-verify skill to check the cluster."

## Kubernetes Commands

Slash commands for common K8s operations.

### /k8s-status

Quick cluster health check:

```
/k8s-status
```

Returns:
- ArgoCD sync status
- Unhealthy pods
- Recent warnings
- Ingress endpoints

### /deploy

Deploy a change to the cloud cluster:

```
/deploy
```

The cloud cluster deploys through a pull request, its review and merge, and the ArgoCD sync
that follows. The local kind cluster this command once targeted was retired on 2026-09-27.

## Playwright MCP Server

Enhanced browser automation capabilities beyond the built-in plugin.

**How to use**: Available automatically when you need browser interaction. Claude can:
- Navigate to URLs
- Take screenshots
- Fill forms
- Click elements
- Run browser automation scripts

**Example prompts**:

```
Open the ArgoCD dashboard at https://argocd.<domain> and take a screenshot

Fill out the login form with username "admin" and click submit

Navigate to the Grafana dashboard and check if metrics are loading
```

## Ralph - Autonomous Coding Loop

Run Claude in a loop to complete tasks while you sleep.

**Location**: `scripts/ralph/`

### How It Works

1. Claude reads a task list (prd.md)
2. Attempts each task
3. Runs validation commands
4. Marks tasks as passed/failed
5. Loops until all tasks pass or max iterations reached
6. Fresh context every iteration

### Setup

1. **Create task file** in your project:
   ```bash
   cp scripts/ralph/prd.template.md ~/my-project/prd.md
   ```

2. **Edit prd.md** with your tasks:
   ```markdown
   ## Task: Add health endpoint
   - **Category**: Backend
   - **Description**: Add /health returning {"status": "ok"}
   - **Validation**:
     - [ ] `curl -s localhost:8080/health | jq -e '.status'` - Returns status
     - [ ] `mvn test` - Tests pass
   - **Passes**: false
   ```

3. **Create prompt** (or use template):
   ```bash
   cp scripts/ralph/prompt.template.md ~/my-project/prompt.md
   ```

4. **Run Ralph**:
   ```bash
   cd ~/my-project
   /path/to/manyfold-platform/scripts/ralph/ralph.sh
   ```

### Key Concepts

| Concept | Description |
|---------|-------------|
| Fresh context | Claude starts clean each loop iteration |
| Static prompt | Never modify prompt.md during execution |
| Validation-driven | Tasks only pass when ALL validations succeed |
| Activity log | Check `activity.md` for what happened |

### When to Use

| Good For | Not Good For |
|----------|--------------|
| Proof of concepts overnight | Production code (needs human review) |
| Well-defined task lists | Ambiguous requirements |
| Exploratory builds | Security-sensitive code |

See `scripts/ralph/README.md` for full documentation.

## File Locations

| What | Where |
|------|-------|
| Skills | `.claude/skills/*.md` |
| Commands | `.claude/commands/*.md` |
| Ralph scripts | `scripts/ralph/` |
| Claude settings | `.claude/settings.json` |
| Local settings | `.claude/settings.local.json` (gitignored) |

## Adding New Features

### New Skill

```bash
# Create skill file
cat > .claude/skills/my-workflow.md << 'EOF'
# My Workflow

When asked about [topic], follow these steps:

1. First, check...
2. Then, run...
3. Finally, verify...
EOF
```

### New Command

```bash
# Create command file
cat > .claude/commands/my-command.md << 'EOF'
# /my-command

Description of what this command does.

## Steps

1. ...
2. ...
EOF
```

Commands are invoked with `/my-command` in Claude.
