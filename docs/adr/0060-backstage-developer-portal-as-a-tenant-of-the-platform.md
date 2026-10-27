# ADR 0060: A Backstage Developer Portal as a Tenant of the Platform

## Table of Contents

- [Status](#status)
- [Reconciled at acceptance](#reconciled-at-acceptance)
- [Context](#context)
- [Decision](#decision)
- [Cut list](#cut-list)
- [Open points for the owner](#open-points-for-the-owner)
- [Rationale](#rationale)
- [Consequences](#consequences)
- [References](#references)

## Status

Accepted (2026-10-19)

Proposed 2026-10-18 as a proof of concept, time-boxed to one weekend and two evenings.
Accepted 2026-10-19 with the decisions as built on the first two days.
[Reconciled at acceptance](#reconciled-at-acceptance) lists every decision that changed from
the proposal. Acceptance records the decisions; it does not close the proof of concept. Two
of the six done-criteria are still open ([D1](#d1-a-portal-as-a-proof-of-concept-with-six-done-criteria)).
The record moves to Superseded, with the reasons, when the portal is torn down.

Amended 2026-10-27: the portal is named ManyStage. The cut-list row 'Custom theme or
branding' is lifted; the portal carries the Manyfold design system (theme module, logo,
bundled fonts) since the amendment.

## Reconciled at acceptance

The decision text below states each decision as built. Where the build differs from the
proposal, a note under the decision keeps the original, and this table gives the reason.

| Decision | As proposed (2026-10-18) | As built (2026-10-19) | Why |
|---|---|---|---|
| D2 Placement | Helm values under a platform component directory; the Postgres the chart bundles | A Kustomize base beside the source in `apps/backstage/base/`, with its own Postgres; the instance overlays it | The other applications under `apps/` ship the same way: a base beside the source, an overlay in the instance ([ADR-0058](0058-untagged-images-in-application-bases.md)) |
| D3 Catalog model | One `Domain` per hosted tenant; component types `service`, `website`, `library`, `documentation` | One `tenants` domain for every tenant; the component type `gitops` for the reference installation, the instance and tenant repositories | A domain per tenant would name each tenant in this repository. A repository of manifests that Argo CD reads is none of the four proposed types |
| D3 Groups | One `Group` per Keycloak group, all declared here | One `Group` per Keycloak realm group; a group whose name the publication gate denies is declared in the instance catalog | The publication gate keeps such names out of this repository |
| D4 TechDocs | Each repository's CI builds with the TechDocs CLI and publishes to the bucket; the portal only reads | The backend builds with mkdocs inside its image on first view and publishes to a bucket claimed through the platform's object-storage API | Publishing from CI would put the bucket's write keys into public repositories |
| D5 Template | Two parameters; a run that opens the landing-zone pull request | A form with four fields and a per-run `Secret` token field; the pull request opens as a draft with a checklist; fetches use absolute URLs to this repository's `main` | The scaffolder's engine does not render the landing-zone placeholders; the editor's dry run refuses relative paths; the portal holds no standing write credential |
| D6 Conformance card | `conformance.json` in the TechDocs bucket, read by the browser; no backend plugin | A baseline workflow publishes the file to a data branch of the private baseline repository; a backend route reads it with the integration token and caches it | The browser cannot read a private repository. The cut list allows a backend plugin that serves only a JSON file |
| D7 Database | The chart's Postgres on an unbacked volume; a Velero annotation if the proof of concept outlives the weekend | Postgres on an `emptyDir`, no volume at all | Nothing the proof of concept keeps must survive a Pod replacement; a volume would need the backup annotation or a deviation record of its own |
| Cut list | Kubernetes plugin not built | The Kubernetes plugin that `create-app` ships was removed; the other defaults of `create-app` stay installed | See [Cut list](#cut-list) |

## Context

The estate is about ten repositories: this reference installation, its private instance, the
two halves of the baseline, the solution umbrella, the operations agent, the applications under
`apps/` and the tenants hosted on the platform. What each repository is, who owns it and
whether it still conforms to the baseline is written down, but in each repository's own files
and in the conformance job's exit codes. There is no one place that shows the whole estate.

The operator wants to learn what a developer portal does to an estate this size: what a catalog
entry must carry before anyone trusts it, where the cost of running Backstage actually sits,
what a documentation portal exposes about documentation that already exists, and whether a
conformance signal that people can see changes behaviour where a failing cron job did not. The
honest scale is one operator, about ten repositories and a weekend. The decision below is
sized to that, and every claim made from it has to say so.

Constraints that shaped the decision:

- The platform's own rules apply to the portal as to any workload. The public and private split
  of [ADR-0054](0054-public-platform-and-private-instance-repositories.md) puts generic
  configuration here and hostnames, client ids, image tags and secrets in the instance.
  [ADR-0055](0055-upstream-versions-in-the-public-platform-repository.md) and
  [ADR-0058](0058-untagged-images-in-application-bases.md) govern how images are named and
  pinned. Every workload goes through Argo CD; storage goes through the platform's own
  Crossplane API ([ADR-0005](0005-argocd-crossplane-responsibility-split.md)).
- No spend beyond the existing nodes. The Backstage backend asks for about 2 GiB of memory;
  the installation checks that a worker has the headroom before the Application is created,
  and if none has, the proof of concept stops rather than adding a node.
- Credentials follow the estate's credential rules: issued for one purpose, stored in the
  installation's secret store, revoked at the end. Nothing long-lived in an image or in Git.
- Time: about 16 to 20 hours. Everything that does not fit is cut, not stretched.

## Decision

### D1: A portal, as a proof of concept with six done-criteria

The platform gets a Backstage developer portal. It is done when all six hold:

| # | Criterion | State at acceptance (2026-10-19) |
|---|---|---|
| 1 | The catalog shows the estate: at least eight components across the repositories above, grouped into systems and domains, each with an owner group that exists in Keycloak | Met |
| 2 | TechDocs renders for at least two repositories from their existing `docs/`, including the ADR index, with no document rewritten for the purpose | Met: this repository and the public baseline repository |
| 3 | One scaffolder template, "new tenant", runs end to end from `platform/tenants/_tenant-repo-scaffold/` and `platform/tenants/_template/`: a repository plus a pull request against the landing-zone directory | Open: the dry run passes; the real run waits for a write token |
| 4 | One custom frontend card, "Baseline conformance", shows per component whether the `estate-baseline` checks report clean (exit 0) or drift (exit 3), read from a JSON file that a scheduled job writes | Met |
| 5 | This record, accepted, with the cut list below | Met by this acceptance |
| 6 | A five-minute demo and a one-page lessons note | Open |

If time runs out, criteria 3 and 4 are kept and the rest is reported as not done.

### D2: Self-hosted Backstage, run as a tenant of the platform

The portal is an application built from `@backstage/create-app`, not a hosted Backstage
offering. It runs the way every other application on the platform runs:

| Concern | Where it lives | Form |
|---|---|---|
| Application source | This repository, `apps/backstage` | The generated app, the conformance card and its backend route, `app-config.production.yaml` with every installation value as an environment variable |
| Build and test | This repository's CI | Type-check, lint, tests and the backend build, like the other applications under `apps/` |
| Image | The installation's CI | Built from the pinned commit of this repository and pushed to the installation's registry, the way the installation builds its other application images; the installation's overlay pins the reference, and the base here names no tag (the ADR-0058 pattern) |
| Kubernetes base and catalog data | This repository, `apps/backstage/base/` and `catalog/` | The Deployment, its Service and the Postgres; the Domain, System, Group and Location entities and the template |
| Argo CD Application, hostname, OIDC client id, image reference, bucket claim, the installation's config file | The instance repository | Overlay values, as [ADR-0054](0054-public-platform-and-private-instance-repositories.md) requires |
| Login | Keycloak, a new OIDC client in the operator realm | The Backstage OIDC provider; the client secret and the session secret in the installation's secret store |
| Ingress | The Envoy Gateway the installation already exposes on its tailnet | One `HTTPRoute` on an internal hostname; no public DNS ([open point (b)](#open-points-for-the-owner), decided) |
| GitHub access for the catalog, TechDocs and the conformance route | A read-only fine-grained token, contents read on all repositories of the organisation | In the installation's secret store, injected as an environment variable; see [open point (a)](#open-points-for-the-owner), decided |
| TechDocs storage | An object bucket claimed through the platform's `ObjectBucket` API in the instance | The bucket's name, endpoint and keys reach the pod as a Secret |

> As proposed: generic Helm values under a platform component directory and the Postgres the
> chart bundles. See [Reconciled at acceptance](#reconciled-at-acceptance).

### D3: The catalog model

Backstage's system model has three layers: organisational entities (`Group`, `User`), the
software (`Component`, `API`, `Resource`) and the ecosystem (`System`, `Domain`). The portal
uses all three, with these rules:

| Kind | Used for | Rule |
|---|---|---|
| `Domain` | Two business areas: `platform`, the hosting platform and what it ships; `tenants`, the workloads hosted on the platform | Owned by the group that runs the area |
| `System` | A set of components that ships together: the reference installation with its instance, the baseline pair, the solution umbrella, the operations agent, the applications, each tenant's workloads | Owned by one group; `spec.domain` set. The instance declares the systems under `tenants` |
| `Component` | One repository or one deployable application | `spec.type` is `gitops`, `service`, `website`, `library` or `documentation`; `gitops` marks a repository of manifests that Argo CD reads, as the reference installation, the instance and the tenant repositories are. `spec.lifecycle` is `production` or `experimental`; `spec.owner` is required and names a `Group`; `spec.system` set |
| `Resource` | Only what a component depends on and the platform provides: an object bucket, a database | Added only where a component's `spec.dependsOn` needs a target. None declared at acceptance |
| `API` | The platform's Crossplane APIs and the connector's MCP surface, where a machine-readable definition exists | Not invented where no definition exists. None declared at acceptance |
| `Group` | One per Keycloak realm group that owns something | Named as in Keycloak; no organisation chart is invented; `spec.type: team`. A group whose name the publication gate denies is declared in the instance catalog, not here |
| `User` | Not imported for the proof of concept | Sign-in through Keycloak resolves to the group memberships; a user entity is added only if a plugin needs one |

The platform's Domains, Systems and Groups live in `catalog/` in this repository, with the
naming contract in its [README](../../catalog/README.md). Each repository describes itself in
its own `catalog-info.yaml`, written from what the repository already states in its
`README.md`, agent instructions and ADR index, and merged through one pull request per
repository. A `Location` in `catalog/` points at those files, and the instance registers the
locations of the private repositories. The portal reads the organisation's repositories and
nothing else.

The realm had no group for the platform's operators. The proof of concept added the realm
group `platform-operators`, because `spec.owner` has to name something that can log in.

> As proposed: one `Domain` per hosted tenant, and the four component types without `gitops`.
> See [Reconciled at acceptance](#reconciled-at-acceptance).

### D4: TechDocs built by the backend

The portal's backend builds an entity's documentation the first time someone opens it, and
again when the source has changed (`techdocs.builder: local`). It runs mkdocs with the
`techdocs-core` plugin inside its own container and publishes the site to an object bucket
that the instance claims through the platform's own `ObjectBucket` API. The bucket keeps the
built sites across Pod replacements. Only the portal holds the bucket's keys. Each
participating repository contributes a `mkdocs.yml` next to its existing `docs/` and nothing
else; no document is written or rewritten for the portal. What TechDocs exposes about the
existing documentation is one of the questions the proof of concept asks. The
[application README](../../apps/backstage/README.md#techdocs) carries the details.

> As proposed: each repository's CI runs the TechDocs CLI and publishes to the bucket, and the
> backend only reads. Publishing from CI would put the bucket's write keys into public
> repositories. See [Reconciled at acceptance](#reconciled-at-acceptance).

### D5: One scaffolder template

The portal carries one template, "new tenant". It automates part of the hand procedure that
the platform's documentation already describes, which is why it is the one worth automating
and the one whose drift from the hand procedure is visible. The
[template README](../../catalog/templates/README.md#new-tenant) maps each step to the hand
procedure.

- **The form** asks for the tenant name, the owning group, a one-line description and the
  installation repository, and a token for the run.
- **The steps** fetch the tenant repository scaffold, publish a repository in the
  organisation, and open a pull request that adds the landing zone under `platform/tenants/`
  from the `_template/` files.
- **The pull request opens as a draft with a checklist.** The landing-zone files use
  `{{TENANT}}` placeholders. The scaffolder's nunjucks engine opens a variable only at `${{`,
  so it does not render them, and no installed action replaces text. The checklist's first
  item is the template README's `sed` command, and the steps that stay with the operator
  follow.
- **The fetches use absolute URLs to this repository's `main`.** The template editor's dry run
  refuses a relative path outside the uploaded directory. The template therefore reads `main`,
  whatever commit the installation pins.
- **The write token is a per-run `Secret` field**, not a standing integration credential. The
  scaffolder holds it only until a worker claims the task, redacts it from the task log and
  never returns it through the task API. Only the two publish steps use it; the fetch steps
  keep the portal's read-only token.

The template is dry-run first and then run once with a throwaway name.

> As proposed: two parameters, tenant name and owner group, and a run that opens the
> landing-zone pull request. See [Reconciled at acceptance](#reconciled-at-acceptance).

### D6: A conformance card with one backend route

A baseline workflow runs the `estate-baseline` checks over the catalogued repositories and
publishes `conformance.json` (repository, check, exit code, timestamp, deviation ADRs) to a
data branch of the private baseline repository. A backend plugin reads that file through the
backend's URL reader with the GitHub integration's token. It caches the file for five minutes
and, after a failed read, waits 60 seconds before it tries GitHub again. It serves the file at
one route. A frontend card calls that route and shows clean, drift or check failed per
component, with a link to the deviation ADR when one exists.

The route is the one backend plugin written for the portal. It has no database table and no
scheduler, and it serves only a JSON file, which the cut list allows. The card is the
platform's standards made visible where a developer looks, instead of in a job log.

> As proposed: the job writes `conformance.json` to the TechDocs bucket and the browser reads it,
> with no backend plugin. The browser cannot read a private repository. See
> [Reconciled at acceptance](#reconciled-at-acceptance).

### D7: The database

Backstage keeps its catalog, scaffolder task history, user settings and notifications in
Postgres. The proof of concept runs one Postgres beside the backend, in the application's own
base, which is the platform's existing pattern of one Postgres per application. It has **no
volume at all**: the data directory is an `emptyDir`.

- A container restart keeps the `emptyDir`.
- A Pod replacement empties it. Scaffolder task history, user settings and notifications are
  then lost, because Git does not hold them.
- The catalog re-ingests from Git and TechDocs reads its sites from the bucket, so both rebuild
  without intervention.

That loss is acceptable for a proof of concept. It is the first thing to change for anything
longer-lived: a volume with the Velero annotation the backup rule expects, or a deviation
record of its own. No production database is built for this.

> As proposed: the Postgres the chart bundles, on an unbacked volume. See
> [Reconciled at acceptance](#reconciled-at-acceptance).

### D8: Secrets stay out of this repository

The GitHub token, the OIDC client secret, the session secret, the bucket keys and the Postgres
password exist only in the installation's secret store and reach the pod as environment
variables. `app-config.production.yaml` here names them as `${VARIABLE}` placeholders. The
scaffolder's write token is pasted per run and never stored by the installation (D5). No
hostname, client id, bucket name or image tag appears in this repository; the publication gate
runs before every push, as for every other change here.

## Cut list

Deliberately not done, each a sentence of the form "not done, because", not a weekend:

| Not done | Because | State at acceptance |
|---|---|---|
| Kubernetes plugin with cluster credentials | It would give the portal read access to every namespace for a view Argo CD already provides | `create-app` ships the plugin; it was removed |
| Search backend | Ten repositories do not need an index; the catalog page lists them | Nothing built. `create-app`'s default search backend and page stay installed, unconfigured |
| Permissions framework | One operator and read-only data; authentication through Keycloak is the whole access model | No policy written. `create-app`'s default allow-all policy stays installed |
| Custom theme or branding | It teaches nothing about running a portal | Done 2026-10-27, after the proof of concept closed |
| High-availability Postgres | The database holds nothing that Git and the bucket do not hold, beyond what D7 accepts losing | Not done |
| Multi-cluster | There is one cluster | Not done |
| A hosted Backstage offering | The question is what self-hosting costs; a hosted offering answers a different question | Not done |
| Any backend plugin beyond serving a JSON file | The card reads a file; a backend plugin is where Backstage's upgrade cost sits | Held: the conformance route is the one backend plugin (D6) |
| Catalog import of anything outside the organisation | The scope is this estate | Not done |
| Notifications | Nobody to notify but the operator | Nothing built. `create-app`'s default notifications and signals plugins stay installed |
| New documentation written for TechDocs | What TechDocs does to existing documentation is the finding; new documents would hide it | Held: only `mkdocs.yml` files were added |

Removing the `create-app` defaults that the cut list names is the same kind of change as the
Kubernetes plugin's removal. It is deferred ([open points](#open-points-for-the-owner)).

## Open points for the owner

| | Point | Recommendation | State at acceptance (2026-10-19) |
|---|---|---|---|
| (a) | A read-only fine-grained GitHub token for the catalog and TechDocs, scoped to all repositories of the organisation, current and future, with contents read, stored in the installation's secret store like every other credential, revoked when the portal is torn down | Issue it for the proof of concept's duration only; the scaffolder's publish step gets a separate, shorter-lived token | Decided: issued 2026-10-18 by the owner, read-only, scoped to the organisation, for 30 days. The scaffolder's write token is a per-run form field (D5) |
| (b) | A public hostname, or the portal stays behind the tailnet | Tailnet plus Keycloak, no public DNS | Decided: an internal hostname on the tailnet, behind the Envoy Gateway the installation already exposes there |
| (c) | Make the application's CI job a required check on this repository's `main` | Required, like the other checks | Deferred. Today auto-merge can merge an application change before the job has run |
| (d) | Lock the Python TechDocs dependencies with hashes | Lock them | Deferred. `requirements.txt` pins the top-level package only |
| (e) | Make the ADR index's amendment links resolve | Link to the ADR file, not to a fragment of the index | Deferred. Some status cells link to fragments that exist only in the ADR, not in the index |
| (f) | Narrow the trigger paths of the instance's other application image workflows | Trigger on each application's own paths | Deferred. A pin bump rebuilds the sibling applications (see [Observed](#observed-during-the-build)) |
| (g) | Remove the `create-app` defaults that the cut list names | Remove them in one change | Deferred |

## Rationale

**Why a portal at all.** The estate's self-description is complete but scattered. A catalog
forces each repository to answer three questions in one file: what it is, who owns it and what
it belongs to. The value of the exercise is in the answers that turn out to be hard, not in the
portal.

**Why Backstage, self-hosted.** Backstage is the portal the operator has used as a consumer
and never run. Running it is the only way to learn where its cost sits, which the proof of
concept expects to be in building and upgrading the application rather than in operating it.
A hosted offering would hide exactly that. It would also move the estate's metadata and a
GitHub token outside the estate for a weekend experiment.

**Why a tenant of the platform and not a special case.** Argo CD, the gateway, Keycloak and
the Crossplane bucket API exist. A portal that bypassed them would prove nothing about the
platform's self-service and would add a snowflake to operate. The portal consuming the
platform's own API for its bucket is the point, not a detail.

**Why this catalog model.** Domain and System are optional in Backstage. They are used here
because the question "what does this belong to" is the one the estate's repositories answer
least consistently, and because a model with only Components reproduces the repository list
the organisation page already shows. Groups are taken from Keycloak as they are, because an
invented organisation chart for one operator would be fiction and the owner field has to name
something that can log in. One `tenants` domain replaces one domain per tenant, because this
repository names no tenant.

**Why the backend builds TechDocs.** The proposal chose Backstage's recommended architecture:
the build runs in each repository's CI, where the documentation changes, and the portal stays
read-only. That puts the bucket's write keys into every documented repository, and several
are public. The backend build keeps the keys in one place, at the cost of Python and mkdocs in
the image and write access to the bucket from the portal.

**Why one template.** A template is a frozen procedure. The tenant landing zone is the one
procedure the platform documents step by step and performs rarely, so it is the one where
automation pays and where drift between the template and the hand procedure would be seen.
The draft pull request and its checklist are that drift made visible.

**Why a backend route for the card.** The proposal wanted a frontend-only card, because a
backend plugin is the first thing to break on a Backstage upgrade. The conformance data lives
in a private repository, so the browser cannot read it with the user's session. The smallest
backend plugin that solves this reads one file with the integration token and serves it; it
holds no state beyond a five-minute cache.

**Why Postgres without a volume.** The data is a cache of Git plus state the proof of concept
can afford to lose. A volume would need the backup annotation or a deviation record and adds
nothing the proof of concept needs. A production database would be a weekend by itself.

## Consequences

### Positive

- The estate gets one place that shows every repository, its owner and its conformance state,
  and the `catalog-info.yaml` files remain useful even if the portal is torn down.
- The exercise produces answers the ADRs do not yet hold: which repositories have no clear
  owner, which documentation renders badly as a site, and how long agents take to write
  metadata that a human then corrects. The plan records hours and corrections.
- The portal consumes the platform's own APIs, which tests them from a new kind of tenant.

### Negative

- About 2 GiB of memory on a worker for the duration, and a Keycloak client, a realm group, a
  read token and a bucket to issue, inventory and revoke.
- The scaffolder's real run creates a repository in the organisation that has to be deleted
  afterwards, by the owner. Each real run needs a write token minted for it and revoked after.
- Each participating repository gains two files, `catalog-info.yaml` and `mkdocs.yml`, which
  it must keep or remove when the proof of concept ends. No repository gains a CI step.
- The portal's database has no volume (D7); a Pod replacement loses scaffolder history, user
  settings and notifications.
- The image carries a Python environment for mkdocs next to the Node backend (D4).

### Observed during the build

- **The gateway's default request timeout cuts large bundles.** On a slow client path, the
  Envoy Gateway's default 15-second route timeout ended the transfer of the frontend's large
  bundles, and the browser reported a protocol error. The instance raised the route timeouts.
- **Egress must allow the repository tarball host.** TechDocs fetches a repository as a
  tarball from a GitHub host other than the API host. The egress policy listed the API hosts
  only, so the first documentation build timed out until the policy allowed that host.
- **A pin bump rebuilds sibling applications.** The instance's other application image
  workflows trigger on paths that include their overlays. Moving the pin touches those
  overlays, so each bump rebuilt and redeployed the other applications from identical source.

### Neutral

- Nothing in this record claims a scale beyond one operator and about ten repositories. The
  lessons note that closes the proof of concept says so in its first line.
- Teardown is one Argo CD Application, one Keycloak client and its realm group, one token, one
  bucket and this record's status.

## References

- [ADR-0005: Argo CD Deploys, Crossplane Provisions](0005-argocd-crossplane-responsibility-split.md)
- [ADR-0033: Multi-Tenancy Model and Tenant Isolation](0033-multi-tenancy-model-and-tenant-isolation.md)
- [ADR-0054: A Public Platform Repository and a Private Instance Repository](0054-public-platform-and-private-instance-repositories.md)
- [ADR-0055: Upstream Versions in the Public Platform Repository](0055-upstream-versions-in-the-public-platform-repository.md)
- [ADR-0058: Untagged Images in Application Bases](0058-untagged-images-in-application-bases.md)
- [The application README](../../apps/backstage/README.md), [the catalog README](../../catalog/README.md) and [the template README](../../catalog/templates/README.md)
- Backstage documentation: [the system model](https://backstage.io/docs/features/software-catalog/system-model), [the descriptor format](https://backstage.io/docs/features/software-catalog/descriptor-format), [TechDocs architecture](https://backstage.io/docs/features/techdocs/architecture), [writing templates](https://backstage.io/docs/features/software-templates/writing-templates), [plugin development](https://backstage.io/docs/plugins/plugin-development)
- [Conformance README in estate-baseline](https://github.com/manyfold-dk/estate-baseline/blob/main/scripts/conformance/README.md)
