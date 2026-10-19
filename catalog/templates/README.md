# Scaffolder templates

Software templates for the developer portal's scaffolder, in Backstage's
[template format](https://backstage.io/docs/features/software-templates/writing-templates).
[`../all.yaml`](../all.yaml) registers each one, so an installation that registers the
catalog gets them too.

| Template | What it does |
|---|---|
| [`new-tenant`](new-tenant/template.yaml) | Creates a tenant's repository and opens the pull request for its landing zone |

## new-tenant

The template automates part of the hand procedure that two READMEs describe:
[the landing-zone template](../../platform/tenants/_template/README.md#onboarding-tenant-n)
and [the tenant repository scaffold](../../platform/tenants/_tenant-repo-scaffold/README.md).
It adds nothing to that procedure. Both directories stay the source. The template reads them,
and its own `skeleton/`, from this repository's `main` by absolute URL. A relative URL would
break the template editor's dry run, which refuses a path outside the uploaded directory.

### What it does

| Step | Action | Hand procedure |
|---|---|---|
| Fetch the tenant repository scaffold | `fetch:plain` | Step 2.1: the repository starts from the scaffold. The files are copied verbatim |
| Add `gitops/prod/` | `fetch:plain` of [`new-tenant/skeleton/`](new-tenant/skeleton/) | Step 2.3 |
| Create the tenant repository | `publish:github` | Step 2.1: `manyfold-dk/<tenant>`, private, default branch `main`, topic `manyfold-tenant`, no branch protection (the estate applies its rulesets separately) |
| Fetch the landing-zone template | `fetch:template` with `templateFileExtension: .tmpl` | Step 1: copy, and rename `*.yaml.tmpl` to `*.yaml` |
| Remove the template README | `fs:delete` | Step 1: `rm README.md` |
| Open the landing-zone pull request | `publish:github:pull-request`, as a draft | The rest of Step 1, Steps 2.2 and 3, and the validation, as a checklist in the description |

The form asks for the tenant name, the owning group, a one-line description and the
installation repository. The installation repository is the one that keeps the tenants'
rendered landing zones under `platform/tenants/`. The template names no installation, so
the form asks for it.

### What stays manual

- **The `{{TENANT}}` placeholders.** The scaffolder's template engine opens a variable only
  at `${{`, so the landing-zone files pass through it unchanged. The scaffolder has no action
  that replaces text. The pull request therefore opens as a draft, and its first checklist
  item is the template README's `sed` command. The installation's tenant-manifest
  validation fails on the branch until that item is done.
- **The scaffold's `ci.yml`.** The template copies it to the tenant repository's root, where
  it does not run. The scaffold README's
  [How To Use](../../platform/tenants/_tenant-repo-scaffold/README.md#how-to-use) moves it to
  `.github/workflows/ci.yml` and edits its `env:` block for one application. A new tenant
  has no application yet.
- **Steps 2.2 to 5.** Argo CD's read access to the tenant repository, the landing-zone
  Application, and the shared platform services stay with the operator. The pull request
  lists them.
- **Catalog registration.** The scaffold holds no `catalog-info.yaml`, so the template
  registers nothing in the catalog.

### GitHub permissions

The portal stores no GitHub write credential. The form's last field, "GitHub token for this
run", takes a token for one run. It is a `Secret` field: the value reaches the two publish
steps as a task secret, and the task's recorded parameters hold only asterisks. The fetch
steps keep the portal's read-only integration token.

A dry run in the template editor needs no token. Leave the field empty: the publish steps
do not run in a dry run, and the fetch steps read this repository, which is public. A real
run with the field empty falls back to the integration token and fails at the repository
creation.

For a real run, mint a short-lived fine-grained personal access token:

| Setting | Value |
|---|---|
| Resource owner | The organisation |
| Repository access | All repositories. The tenant repository does not exist when the token is minted, and the run pushes to it and to the installation repository |
| Organisation permissions | None |

| Repository permission | Level | Used by |
|---|---|---|
| Administration | Read and write | `publish:github`: create the repository in the organisation, set its topics |
| Repository creation, where GitHub offers it | Read and write | `publish:github`: GitHub's permission table lists the repository-creation endpoint under both this permission and Administration |
| Contents | Read and write | `publish:github`: push the first commit. `publish:github:pull-request`: create the branch, its blobs, tree and commit in the installation repository |
| Pull requests | Read and write | `publish:github:pull-request`: open the pull request |
| Metadata | Read | GitHub requires it for every fine-grained token |

The run needs no Workflows permission, because no file lands under `.github/workflows/`.
