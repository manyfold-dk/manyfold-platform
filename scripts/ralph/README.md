# Ralph - Autonomous AI Coding Loop

Ralph runs Claude Code in an autonomous loop, completing tasks while you sleep.

## The Core Principle

AI gets worse as context grows. Think of the context window like a whiteboard:
- **Start**: Clean whiteboard, AI reads instructions clearly, executes precisely
- **After many turns**: Filled with old code, failed attempts, tangents
- **Result**: AI wades through noise, forgets things, contradicts itself

**Ralph solves this by wiping the whiteboard after every single task.** Fresh start, full brainpower, every time.

## Quick Start

```bash
# 1. Create your task file
cp prd.template.md ~/my-project/prd.md
# Edit prd.md with your actual tasks

# 2. Create your prompt
cp prompt.template.md ~/my-project/prompt.md
# Customize if needed

# 3. Run Ralph
cd ~/my-project
/path/to/ralph.sh
```

## How It Works

```
┌─────────────────────────────────────────┐
│  ralph.sh (outer loop)                  │
│                                         │
│  while tasks incomplete:                │
│    ┌─────────────────────────────────┐  │
│    │  Claude Code (fresh context)    │  │
│    │  1. Read prd.md                 │  │
│    │  2. Pick incomplete task        │  │
│    │  3. Implement                   │  │
│    │  4. Run validations             │  │
│    │  5. Mark Passes: true if ok     │  │
│    │  6. Commit                      │  │
│    └─────────────────────────────────┘  │
│    # Context wiped here                 │
│  done                                   │
└─────────────────────────────────────────┘
```

## Files

| File | Purpose |
|------|---------|
| `ralph.sh` | The outer loop script |
| `prompt.md` | Instructions for Claude (static, never changes) |
| `prd.md` | Task list with validation steps and pass flags |
| `activity.md` | Append-only log of actions (created automatically) |

## Task Format

```markdown
## Task: Implement user authentication
- **Category**: Backend
- **Description**: Add JWT-based auth to API endpoints
- **Validation**:
  - [ ] `npm test` - All tests pass
  - [ ] `curl -s localhost:3000/login -d '...' | jq -e '.token'` - Login returns token
  - [ ] `curl -s localhost:3000/protected -H 'Auth: ...' | jq -e '.data'` - Protected route works
- **Passes**: false
```

**Key fields:**
- `Validation`: Commands that Claude runs to verify the task
- `Passes`: Set to `true` only when ALL validations succeed

## Options

```bash
./ralph.sh [options]

--prd FILE           Task file (default: prd.md)
--prompt FILE        Prompt file (default: prompt.md)
--max-iterations N   Stop after N iterations (default: 50)
--dry-run            Show what would run without executing
```

## Why Complete Context Wipe?

Some implementations compact context instead of wiping it. This breaks the pattern:
- The AI guesses what's important to carry forward
- When it guesses wrong, critical information disappears
- Bugs compound, features break in unexpected ways

**The canonical Ralph keeps complete context wipe between iterations.**

## Why Static Prompt?

Models are verbose by default. If the AI modifies its own instructions each loop:
- Each loop adds tokens
- Ten iterations in, context is stuffed before the actual task starts

**The prompt.md should never change during execution.**

## When to Use Ralph

**Good for:**
- Proof of concepts (validate architecture overnight)
- Exploratory builds (multiple versions to compare)
- Well-defined task lists with clear validation

**Not for:**
- Production engineering (needs human review)
- Ambiguous requirements
- Tasks requiring cross-cutting architectural decisions

## Cost Estimates

- Typical run: 15-25 iterations at ~$2-3 each
- Correct setup: $30-50 for a working proof of concept
- Wrong setup (spinning in circles): $300+ and broken mess

**The difference is fresh context every loop.**

## Example PRD for This Project

```markdown
## Task: Add Prometheus metrics endpoint
- **Category**: Backend
- **Description**: Add /metrics endpoint with request count and latency histograms
- **Validation**:
  - [ ] `curl -s localhost:8080/metrics | grep -q http_requests_total` - Metrics exposed
  - [ ] `mvn test` - Tests pass
- **Passes**: false

## Task: Add Grafana dashboard
- **Category**: Observability
- **Description**: Create dashboard JSON for backend metrics in platform/observability/
- **Validation**:
  - [ ] `test -f platform/observability/dashboards/backend.json` - Dashboard file exists
  - [ ] `jq -e '.panels | length > 0' platform/observability/dashboards/backend.json` - Has panels
- **Passes**: false
```

## Troubleshooting

### Ralph keeps failing the same task

- Check if validation commands work manually
- Make task description more specific
- Break task into smaller sub-tasks

### Ralph marks task complete but validation actually fails

- Validation command might have bugs (returns 0 on failure)
- Test validation commands manually first
- Use `set -e` style commands that fail on error

### Context seems to carry over

- Ensure you're running `ralph.sh`, not calling Claude directly
- Check that the loop is actually restarting Claude each iteration

## Credits

Based on [Ralph](https://github.com/snarktank/ralph) by Ryan Carson, which builds on [Geoffrey Huntley's Ralph pattern](https://ghuntley.com/ralph/).
