# Claude Code Recommendations v2

> **Archived (2026-08-23).** Pre-baseline advice from May 2026. Agent assets are now vendored from the shared agent baseline; see [ADR-0050](../adr/0050-agent-instruction-baseline-and-session-altitudes.md).

> **Note (2026-09-27):** the local kind cluster and its scripts that this record names
> (`infrastructure/clusters/local/scripts`, `cluster.sh`, `verify-cluster.sh`, `run-pipeline.sh`)
> are retired; see [ADR-0054's amendment of 2026-09-27](../adr/0054-public-platform-and-private-instance-repositories.md#amendment-2026-09-27-no-demo-tree-throwaway-cloud-instances-instead).
>
> **Note (2026-09-27):** Tekton, the `tekton-status` skill and the `tkn` commands that this
> record names are retired as well; GitHub Actions build every image. See
> [ADR-0006's amendment of 2026-09-27](../adr/0006-cicd-tooling-selection.md#operational-amendment-2026-09-27-tekton-retired-from-the-repository).

An updated analysis of your Claude Code setup, comparing against [affaan-m/everything-claude-code](https://github.com/affaan-m/everything-claude-code) and identifying high-value additions.

## Table of Contents

- [What You Already Have](#what-you-already-have)
- [Gap Analysis](#gap-analysis)
- [High-Priority Recommendations](#high-priority-recommendations)
- [Medium-Priority Recommendations](#medium-priority-recommendations)
- [Low-Priority / Future Ideas](#low-priority--future-ideas)
- [Not Recommended for This Project](#not-recommended-for-this-project)
- [Implementation Order](#implementation-order)

## What You Already Have

Your setup is comprehensive. Here's the current state:

### Implemented from v1 Recommendations

| Feature | Status | Notes |
|---------|--------|-------|
| Tool lazy loading (`ENABLE_TOOL_SEARCH`) | ✅ Enabled | In `~/.claude/settings.json` |
| dx plugin + status line | ✅ Installed | dx@ykdojo, the marketplace release |
| Playwright MCP | ✅ Installed | playwright@claude-plugins-official |
| Skills (k8s-status, tekton-status) | ✅ Created | In `.claude/skills/` |
| Commands (/deploy) | ✅ Created | In `.claude/commands/` |
| Worktree support | ✅ Complete | `worktree-session.sh` + docs |
| Ralph autonomous loops | ✅ Documented | In docs + scripts/ralph/ |
| Handoff documentation | ✅ Documented | dx plugin provides `/handoff` |

### Current Plugin Stack

```
User-level (all projects):
├── superpowers@claude-plugins-official     # Workflow orchestration
├── code-simplifier@claude-plugins-official # Code cleanup
├── feature-dev@claude-plugins-official     # Feature development
├── playwright@claude-plugins-official      # Browser automation
├── code-review@claude-plugins-official     # PR reviews
└── dx@ykdojo                               # Status line, /gha, /handoff

Project-level (this repo):
├── github@claude-plugins-official          # GitHub MCP
├── frontend-design@claude-plugins-official # UI development
└── commit-commands@claude-plugins-official # /commit, /push
```

### Current Skills and Rules

```
.claude/
├── skills/
│   ├── k8s-status/SKILL.md      # Kubernetes cluster troubleshooting
│   └── tekton-status/SKILL.md   # Pipeline debugging
├── commands/
│   └── deploy.md                # Local deployment workflow
└── rules/
    ├── kubernetes.md            # Local K8s operations
    ├── cloud-infrastructure.md  # Hetzner/Talos, SOPS
    ├── application-dev.md       # Java/Vue development
    ├── git-workflow.md          # Commits, PRs, reviews
    ├── worktrees.md             # Parallel session management
    └── documentation.md         # Doc standards
```

## Gap Analysis

Comparing against `everything-claude-code`:

### Missing Features Worth Adding

| Feature | everything-claude-code | Your Setup | Value |
|---------|------------------------|------------|-------|
| **Security review skill** | ✅ security-review | ❌ Missing | HIGH - K8s security matters |
| **TDD workflow skill** | ✅ tdd-workflow | ❌ Missing | MEDIUM - Good for Java/Vue |
| **Backend patterns skill** | ✅ backend-patterns | ❌ Missing | MEDIUM - Quarkus patterns |
| **Hooks (pre-commit check)** | ✅ Extensive | ❌ Missing | MEDIUM - Safety nets |
| **Verification loop skill** | ✅ verification-loop | ❌ Missing | HIGH - Your #1 workflow |
| **Memory persistence hooks** | ✅ Session lifecycle | ❌ Missing | LOW - `/handoff` suffices |
| **Pattern extraction** | ✅ continuous-learning | ❌ Missing | LOW - Nice to have |
| **Console.log warnings** | ✅ Hook | ❌ Missing | LOW - Not critical |

### Features You Have That everything-claude-code Lacks

| Feature | Your Setup | Value |
|---------|------------|-------|
| **Kubernetes-native skills** | k8s-status, tekton-status | Domain-specific |
| **GitOps workflow integration** | ArgoCD, pipelines | Production-ready |
| **Worktree parallel sessions** | worktree-session.sh | Boris-style workflow |
| **Comprehensive bash permissions** | settings.json | Low friction |
| **Ralph implementation** | scripts/ralph/ | Autonomous coding |

## High-Priority Recommendations

### 1. Add Security Review Skill

Your platform handles secrets (SOPS/age), cloud credentials, and Kubernetes RBAC. A security skill prevents accidental exposure.

**Create `.claude/skills/security-review/SKILL.md`**:

```markdown
---
name: security-review
description: Security review checklist for Kubernetes manifests, secrets, and infrastructure code. Use when reviewing PRs, adding secrets, or modifying RBAC.
allowed-tools:
  - Read
  - Grep
  - Bash(kubectl:*)
  - Bash(sops:*)
---

# Security Review Checklist

## Secrets Handling

Before committing any changes involving secrets:

1. **Never commit plaintext secrets**
   - Check for `.env` files: `grep -r "^[A-Z_]*=" . --include="*.env*"`
   - Check for inline secrets: `grep -rE "(password|secret|token|key)\s*[:=]" . --include="*.yaml" --include="*.yml"`

2. **Verify SOPS encryption**
   - All secrets in `platform/resources/*/secrets/` must be `.enc.yaml`
   - Run: `find . -name "*.yaml" -path "*/secrets/*" ! -name "*.enc.yaml"`

3. **Check .gitignore coverage**
   - `.env.cloud`, `*.tfvars` with secrets, kubeconfig files

## Kubernetes Security

1. **RBAC least privilege**
   - No `ClusterRoleBinding` to `cluster-admin` except system components
   - ServiceAccounts should have minimal permissions

2. **Network policies**
   - Ingress should be explicit, not open
   - Check for `allow-all` policies

3. **Container security**
   - No `privileged: true` unless required (Cilium, etc.)
   - No `hostNetwork: true` unless required

## Cloud Infrastructure

1. **Cloudflare DNS** - Never touch mail records (MX, DKIM, SPF)
2. **Hetzner tokens** - Only in `.env.cloud` (gitignored)
3. **Talos configs** - Machine configs contain sensitive data

## Red Flags (Block PR)

- [ ] Plaintext passwords/tokens in code
- [ ] Unencrypted secrets in git
- [ ] Overly permissive RBAC
- [ ] Cloud credentials in committed files
```

### 2. Add Verification Loop Skill

This is your #1 workflow improvement. Codify the verification pattern.

**Create `.claude/skills/verification-loop/SKILL.md`**:

````markdown
---
name: verification-loop
description: Systematic verification after making changes. Use after any code, config, or infrastructure change.
allowed-tools:
  - Bash(*)
  - Read
---

# Verification Loop

## The Principle

> "Give Claude a way to verify its work. If Claude has that feedback loop, it will 2-3x the quality."

**NEVER claim success without running verification commands.**

## Verification by Domain

### Application Changes (Java/Vue)

```bash
# Backend
cd apps/website/backend
mvn test                    # Tests pass
mvn spotless:check          # Formatting OK
mvn verify                  # Lint checks pass

# Frontend
cd apps/website/frontend
pnpm test:unit              # Tests pass
pnpm lint                   # Lint OK
pnpm build                  # Builds successfully
```

### Kubernetes Manifests

```bash
# Validate YAML syntax
kubectl --dry-run=client -o yaml -f <manifest.yaml>

# Check cluster state after apply
kubectl get pods -n <namespace>
kubectl get events -n <namespace> --sort-by='.lastTimestamp' | tail -10
```

### Local Cluster Changes

```bash
cd infrastructure/clusters/local/scripts
./verify-cluster.sh --quick   # Fast health check
./cluster.sh status           # ArgoCD app status
```

### Cloud Cluster Changes

`<cloud-kubeconfig>` is the path of the kubeconfig file for the cloud cluster.

```bash
cd infrastructure/clusters/cloud/scripts
./setup-argocd.sh status
kubectl --kubeconfig <cloud-kubeconfig> get pods -A | grep -v Running | grep -v Completed
```

### Pipeline Changes

```bash
# After running pipeline
tkn pipelinerun describe <run-name> -n tekton-builds
# Check ArgoCD synced
./cluster.sh status
```

### OpenTofu Changes

```bash
cd infrastructure/clusters/cloud/bootstrap
source ../../.env.cloud
tofu plan -var-file=../../../.working/cloud/bootstrap/terraform.tfvars
# Review plan output before apply
```

## Verification Pattern

1. Make change
2. Run appropriate verification commands
3. Interpret output
4. If failure: investigate and fix
5. Re-run verification
6. Only mark complete when verification passes
````

### 3. Add Pre-Commit Safety Hook

Prevent accidental secret commits and enforce best practices.

**Create `.claude/hooks/pre-commit-secrets.json`**:

```json
{
  "hooks": [
    {
      "matcher": {
        "tool": "Bash",
        "command": "git commit"
      },
      "hook": {
        "type": "confirm",
        "message": "Pre-commit check: Have you verified no secrets are staged? Run `git diff --cached` to review."
      }
    }
  ]
}
```

**Create `.claude/hooks/dangerous-commands.json`**:

```json
{
  "hooks": [
    {
      "matcher": {
        "tool": "Bash",
        "command": "git push --force"
      },
      "hook": {
        "type": "block",
        "message": "Force push blocked. Use `git push --force-with-lease` if you must overwrite history."
      }
    },
    {
      "matcher": {
        "tool": "Bash",
        "command": "kubectl delete namespace"
      },
      "hook": {
        "type": "confirm",
        "message": "About to delete a namespace. This is destructive. Are you sure?"
      }
    },
    {
      "matcher": {
        "tool": "Bash",
        "command": "tofu destroy"
      },
      "hook": {
        "type": "block",
        "message": "Infrastructure destruction blocked. Run manually if intentional."
      }
    }
  ]
}
```

### 4. Add Cloud Deploy Command

Parallel to your local `/deploy`, add guidance for cloud operations.

**Create `.claude/commands/cloud-status.md`**:

````markdown
# Cloud Status Command

Check cloud cluster health and deployment status.

## Quick Status

Run these in parallel:

```bash
# ArgoCD status
cd infrastructure/clusters/cloud/scripts && ./setup-argocd.sh status

# Unhealthy pods
kubectl --kubeconfig <cloud-kubeconfig> get pods -A | grep -v Running | grep -v Completed | head -20

# Recent pipeline runs
kubectl --kubeconfig <cloud-kubeconfig> get pipelineruns -n tekton-builds --sort-by=.metadata.creationTimestamp | tail -5

# Certificate status
kubectl --kubeconfig <cloud-kubeconfig> get certificates -A
```

## Report Format

- **ArgoCD**: X synced, Y out of sync, Z degraded
- **Pods**: All healthy / List unhealthy
- **Pipelines**: Recent run status
- **Certificates**: All valid / Expiring soon / Failed
````

## Medium-Priority Recommendations

### 5. Add TDD Workflow Skill

For feature development in Java and Vue.

**Create `.claude/skills/tdd-workflow/SKILL.md`**:

````markdown
---
name: tdd-workflow
description: Test-Driven Development workflow. Use when implementing new features.
allowed-tools:
  - Bash(mvn:*)
  - Bash(pnpm:*)
  - Edit
  - Write
  - Read
---

# TDD Workflow

## The Cycle

1. **Red**: Write a failing test
2. **Green**: Write minimal code to pass
3. **Refactor**: Improve without changing behavior

## Backend (Java/Quarkus)

### Write Test First

```java
@QuarkusTest
class FeatureTest {
    @Test
    void testNewFeature() {
        // Given
        // When
        // Then - expect failure
    }
}
```

### Run Test (should fail)

```bash
cd apps/website/backend && mvn test -Dtest=FeatureTest
```

### Implement

Write minimal code to make test pass.

### Verify Green

```bash
cd apps/website/backend && mvn test
```

### Refactor

Improve code, re-run tests after each change.

## Frontend (Vue/Vitest)

### Write Test First

```typescript
describe('NewFeature', () => {
  it('should do the thing', () => {
    // Arrange
    // Act
    // Assert - expect failure
  })
})
```

### Run Test

```bash
cd apps/website/frontend && pnpm test:unit --run
```

### Implement & Verify

Same cycle: implement, verify green, refactor.
````

### 6. Add Backend Patterns Skill

Codify your Quarkus patterns.

**Create `.claude/skills/backend-patterns/SKILL.md`**:

````markdown
---
name: backend-patterns
description: Quarkus backend development patterns and conventions.
---

# Backend Patterns

## Project Structure

```
apps/website/backend/
├── src/main/java/dk/manyfold/
│   ├── resource/      # REST endpoints
│   ├── service/       # Business logic
│   ├── model/         # Domain entities
│   └── repository/    # Data access
└── src/test/java/     # Tests mirror src/main
```

## REST Endpoint Pattern

```java
@Path("/api/v1/resource")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ResourceEndpoint {

    @Inject
    ResourceService service;

    @GET
    public List<Resource> list() {
        return service.findAll();
    }

    @GET
    @Path("/{id}")
    public Resource get(@PathParam("id") Long id) {
        return service.findById(id)
            .orElseThrow(() -> new NotFoundException());
    }
}
```

## Service Pattern

```java
@ApplicationScoped
public class ResourceService {

    @Inject
    ResourceRepository repository;

    public List<Resource> findAll() {
        return repository.listAll();
    }

    @Transactional
    public Resource create(Resource resource) {
        repository.persist(resource);
        return resource;
    }
}
```

## Dev Mode

```bash
cd apps/website/backend
mvn quarkus:dev    # Hot reload at http://localhost:8080
```

Access Dev UI: http://localhost:8080/q/dev/

## Build & Test

```bash
mvn test           # Run tests
mvn verify         # Full quality checks
mvn spotless:apply # Auto-format
mvn package        # Build JAR
```
````

### 7. Add Subagent Orchestration Rule

The superpowers plugin provides agents. Document when to use them.

**Update `.claude/rules/subagent-usage.md`**:

````markdown
# Subagent Usage Guidelines

The superpowers plugin provides specialized agents. Use them appropriately.

## Available Agents (via superpowers)

| Agent | Use When |
|-------|----------|
| code-reviewer | After implementing a major feature |
| code-explorer | Understanding unfamiliar code |
| code-architect | Planning multi-file changes |

## When to Delegate

**Delegate to subagent** when:
- Task is independent and well-defined
- Benefits from specialized focus
- Main context should stay clean

**Handle directly** when:
- Quick fix or small change
- Context is already loaded
- Interactive debugging needed

## Parallel Agent Pattern

For independent tasks, spawn multiple agents:

```
Task 1: Code reviewer on backend changes
Task 2: Test runner for frontend
Task 3: Verification of cluster status
```

All three can run concurrently.
````

### 8. Add Pattern Extraction Workflow

Capture lessons learned systematically.

**Create `.claude/skills/pattern-extraction/SKILL.md`**:

````markdown
---
name: pattern-extraction
description: Extract and document patterns from completed work. Use after finishing complex tasks.
---

# Pattern Extraction

After completing a complex task, extract learnings for BEST-PRACTICES.md.

## What to Capture

1. **Gotchas** - Things that were harder than expected
2. **Workarounds** - Non-obvious solutions that work
3. **Anti-patterns** - What NOT to do (and why)
4. **Tool behaviors** - CLI quirks, API behaviors

## Process

1. Review what was done in this session
2. Identify anything that would trip up someone else
3. Add to BEST-PRACTICES.md with date and context
4. Link to relevant ADRs if applicable

## Example Entry

```markdown
## Tekton PipelineRun Monitoring (2026-01-25)

**Gotcha**: The `--watch` flag in `tkn pipelinerun logs` can hang Claude Code.

**Workaround**: Use `tkn pipelinerun logs -f <name>` for streaming, or poll with
`tkn pipelinerun describe <name>` for status checks.

**Related**: `.claude/skills/tekton-status/SKILL.md`
```
````

## Low-Priority / Future Ideas

### 9. Session Lifecycle Hooks (Memory Persistence)

The `everything-claude-code` repo has sophisticated memory hooks. Given that you have `/handoff` and Ralph, this is lower priority.

**If you want it later**, create `.claude/hooks/session-end.json`:

```json
{
  "hooks": [
    {
      "event": "session_end",
      "hook": {
        "type": "script",
        "command": "echo 'Session ended. Consider running /handoff for complex in-progress work.'"
      }
    }
  ]
}
```

### 10. Console.log Detection Hook

Not critical for your Java/K8s stack, but useful for Vue development.

```json
{
  "hooks": [
    {
      "matcher": {
        "tool": "Write",
        "content": "console.log"
      },
      "hook": {
        "type": "warn",
        "message": "console.log detected. Consider using proper logging or removing before commit."
      }
    }
  ]
}
```

### 11. Custom MCP Servers

`everything-claude-code` includes MCPs for Supabase, Vercel, Railway. For your stack, consider:

- **ArgoCD MCP** (if one exists) - Direct API access
- **Hetzner MCP** - Cloud operations
- **Talos MCP** - Cluster management

These are advanced and may not exist yet. The current kubectl/talosctl permissions work fine.

## Not Recommended for This Project

| Feature | Reason |
|---------|--------|
| ClickHouse skill | Not using ClickHouse |
| Frontend-patterns skill | Already have frontend-design plugin |
| Package manager detection | Already standardized on pnpm + mvn |
| Eval harness | Not doing LLM fine-tuning |
| Strategic compact | `/compact` and context management already covered |

## Implementation Order

### This Week (High Impact, Low Effort)

1. **Security review skill** - 15 minutes, prevents costly mistakes
2. **Verification loop skill** - 15 minutes, codifies your #1 best practice
3. **Pre-commit hooks** - 10 minutes, safety net

### This Month (Medium Effort)

4. **Cloud status command** - 10 minutes
5. **TDD workflow skill** - 15 minutes
6. **Backend patterns skill** - 20 minutes
7. **Subagent usage rule** - 10 minutes

### When Needed

8. Pattern extraction skill - When you have time for reflection
9. Session hooks - If `/handoff` proves insufficient
10. Custom MCPs - When you find ones that work for your tools

## Summary

Your setup is already in the top tier. The main gaps from `everything-claude-code` are:

1. **Security-focused skills** - Critical for infrastructure work
2. **Explicit verification workflow** - Codify your best practice
3. **Safety hooks** - Prevent destructive accidents

The rest of `everything-claude-code` is either already covered by your setup, not applicable to your stack, or nice-to-have for later.

---

## Sources

- [everything-claude-code](https://github.com/affaan-m/everything-claude-code) - Affaan Mustafa
- [Claude Code Best Practices](https://www.anthropic.com/engineering/claude-code-best-practices) - Anthropic
- [CLAUDE-CODE-RECOMMENDATIONS.md](CLAUDE-CODE-RECOMMENDATIONS.md) - Previous recommendations
