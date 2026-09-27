# verification-loop -- environment specifics

Repository-specific verification domains and commands. The generic discipline is in the
vendored `SKILL.md`; this overlay supplies the commands. They are the checks CI runs
(`.github/workflows/ci.yml`); run the ones the change touches, the publication gate always.

This repository has no cluster and deploys nothing, so there is no live or post-merge check
here. An instance that pins this repository verifies its own deployment.

## Domains

| Argument | Verify |
|----------|--------|
| `publish` | The publication gate (always) |
| `java` | `build/`, `apps/accounting-mcp/`, `apps/website/backend/` |
| `node` | `apps/slack-bot/`, `apps/synthetic/`, `apps/website/frontend/` |
| `k8s` | Every Kustomize directory |
| `tofu` | `infrastructure/modules/`, `infrastructure/clusters/*/bootstrap/` |
| `docs` | Markdown links and anchors |
| `all` or none | Every domain the change touches |

## Publication gate

The gate refuses instance values, private names and credentials. It lives in
`estate-baseline`; use a checkout at the commit `ci.yml` pins. Without the private name list,
the shapes and both secret scanners still run:

```bash
<estate-baseline>/scripts/publish-check/publish-check.sh . --names none --allow .publish-allow.tsv
```

Expected: `publish-check: OK`. A hit is a stop. Reword the file; add a row to
`.publish-allow.tsv` (format in its header) only for a shape the repository may carry,
with the reason as a comment above it. Never add a name row.

## Java

```bash
mvn -B -ntp -f build/parent/pom.xml validate      # the parent resolves from Maven Central
(cd apps/accounting-mcp && mvn -B -ntp verify)     # tests, Checkstyle, SpotBugs, PMD, format check
(cd apps/website/backend && mvn -B -ntp verify)
```

`mvn verify` rewrites nothing; `mvn spotless:apply` formats. Dev Services start the databases
the tests need, so a container runtime must be available.

## Node

In each of `apps/slack-bot`, `apps/synthetic` and `apps/website/frontend`:

```bash
pnpm install --frozen-lockfile
pnpm lint            # ESLint and the Prettier check; nothing is rewritten
pnpm test            # apps/website/frontend: pnpm test:unit
pnpm build
```

## Kubernetes

```bash
(                    # a subshell, so the exit status is the check's and the shell survives
  rc=0               # not `status`: zsh reserves it
  while IFS= read -r k; do
    kubectl kustomize "$(dirname "$k")" > /dev/null || { echo "FAIL: $k"; rc=1; }
  done < <(find . -name kustomization.yaml -not -path './.git/*' | sort)
  exit $rc
)
```

Helm values under `platform/components/` have no render here: the chart version is pinned by
the installation's Argo CD Application.

## OpenTofu

```bash
(                    # set -e stops at the first failing check, as CI does
  set -e
  for m in infrastructure/modules/*/ infrastructure/clusters/*/bootstrap/; do
    tofu -chdir="$m" fmt -check -recursive
    tofu -chdir="$m" init -backend=false -input=false
    tofu -chdir="$m" validate
    if [ -d "${m}tests" ]; then tofu -chdir="$m" test; fi
  done
)
```

Module tests mock their providers. Never run `plan` or `apply` from this repository: it holds
no backend, no variable file and no credential.

## Docs

CI runs lychee offline over every Markdown file with fragments checked and `.lycheeignore`
applied:

```bash
lychee --offline --include-fragments --no-progress --root-dir "$PWD" '**/*.md'
```

A new exception in `.lycheeignore` carries a comment saying why the link cannot resolve here.

## Git

Before committing, `git status` and `git diff --cached --stat`: stage explicit paths only,
and confirm no `.env` file, `terraform.tfvars`, `backend.hcl` or state file is staged
(`.gitignore` lists them).
