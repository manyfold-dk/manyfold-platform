# dev-workflow -- environment specifics

Repository-specific worktree, workflow-type globs and verification commands. The generic
workflow shape is in the vendored `SKILL.md`; this overlay supplies the commands.

This repository has no cluster and no deploy step. It is the generic half of a platform; an
instance repository pins it by commit and deploys from there. A change here is finished when
its checks pass and the pull request merges.

## Worktree

Only when the user asks for a branch (BRANCH-01).

- **Branch**: `feature/<task-name>`
- **Helper**: `./scripts/worktree-session.sh start|list|switch|cleanup <task-name>`. It
  creates `$MANYFOLD_WORKTREE_BASE/<task-name>` (default
  `~/.manyfold-worktrees/manyfold-platform/<task-name>`) from the current `HEAD` and links
  the main checkout's ignored operator files (`.env` files, `infrastructure/.working/`)
  into it when they exist.
- **Without the helper**, the plain shape:

```bash
git fetch origin
git worktree add <worktree-root>/<task-name> -b feature/<task-name> origin/main
```

> **Preflight (SKILL.md):** if the main checkout is dirty (`git status --short` non-empty),
> stop and ask before creating the worktree: a new worktree does not carry uncommitted
> changes. Do not stash, commit or copy them.

## Workflow-type globs

| Workflow | Trigger globs |
|----------|---------------|
| A: Code-only | `apps/*/backend/src/`, `apps/*/frontend/src/`, `apps/*/src/` |
| B: App + manifests | `apps/*/base/`, `apps/*/Dockerfile`, other manifests under `apps/` |
| C: Platform | `platform/`, `infrastructure/`, `build/` |

## Environment

There is nothing to set up and nothing to deploy to. Prove B and C changes offline:

- Kustomize: every changed directory, and every directory that includes it, builds with
  `kubectl kustomize <dir>`.
- OpenTofu: `tofu fmt -check`, `init -backend=false`, `validate` and `test` in the changed
  module or root.
- Helm values under `platform/components/`: an installation's Argo CD Application pins the
  chart, so no render is possible here; keep the file valid YAML and state the chart keys
  the change touches in the pull request.

## Team-mode domain -> agent mapping

| Domain | Agent type | Files |
|--------|-----------|-------|
| Application code | `implementer` | `apps/*/` sources |
| Manifests and platform | `general-purpose` | `apps/*/base/`, `platform/`, `infrastructure/` |
| Review | `spec-reviewer` | read-only |

## Verification before the pull request

Run the `verification-loop` overlay's checks for the changed paths; the publication gate
always. Put the results in the pull request body.

## After merge

Nothing deploys. When the user asked for the branch and authorizes cleanup:

```bash
git push origin --delete feature/<task-name>
./scripts/worktree-session.sh cleanup <task-name>   # or: git worktree remove <path>
```
