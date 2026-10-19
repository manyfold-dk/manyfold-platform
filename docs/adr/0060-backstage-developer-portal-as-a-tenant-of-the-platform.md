# ADR 0060: A Backstage Developer Portal as a Tenant of the Platform

## Table of Contents

- [Status](#status)
- [Context](#context)
- [Decision](#decision)
- [Cut list](#cut-list)
- [Open points for the owner](#open-points-for-the-owner)
- [Rationale](#rationale)
- [Consequences](#consequences)
- [References](#references)

## Status

Proposed (2026-10-18). A proof of concept, time-boxed to one weekend and two evenings. The
record moves to Accepted when the six done-criteria below hold, or to Superseded with the
reasons when the portal is torn down.

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

| # | Criterion |
|---|---|
| 1 | The catalog shows the estate: at least eight components across the repositories above, grouped into systems and domains, each with an owner group that exists in Keycloak |
| 2 | TechDocs renders for at least two repositories from their existing `docs/`, including the ADR index, with no document rewritten for the purpose |
| 3 | One scaffolder template, "new tenant", runs end to end from `platform/tenants/_tenant-repo-scaffold/` and `platform/tenants/_template/`: a repository plus a pull request against the landing-zone directory |
| 4 | One custom frontend card, "Baseline conformance", shows per component whether the `estate-baseline` checks report clean (exit 0) or drift (exit 3), read from a JSON file that a scheduled job writes |
| 5 | This record, accepted, with the cut list below |
| 6 | A five-minute demo and a one-page lessons note |

If time runs out, criteria 3 and 4 are kept and the rest is reported as not done.

### D2: Self-hosted Backstage, run as a tenant of the platform

The portal is an application built from `@backstage/create-app`, not a hosted Backstage
offering. It runs the way every other application on the platform runs:

| Concern | Where it lives | Form |
|---|---|---|
| Application source | This repository, `apps/backstage` (a new directory beside the other applications) | The generated app, the two plugins, `app-config.yaml` with every installation value as an environment placeholder |
| Build and test | This repository's CI | Lint, type-check and build, like the other applications under `apps/` |
| Image | The installation's CI | Built from the pinned commit of this repository and pushed to the installation's registry, the way the installation builds its other application images; the installation's overlay pins the reference, and the generic values here name no tag (the ADR-0058 pattern) |
| Generic Helm values and catalog data | This repository, `platform/components/backstage/` and `catalog/` | Chart reference, resource requests, generic `app-config` fragments, the Domain, System, Group and Location entities |
| Argo CD Application, hostname, OIDC client id, image reference, bucket name | The instance repository | Overlay values, as [ADR-0054](0054-public-platform-and-private-instance-repositories.md) requires |
| Login | Keycloak, a new OIDC client in the operator realm | The Backstage OIDC provider; the client secret in the installation's secret store |
| Ingress | The Envoy Gateway the installation already exposes on its tailnet | One `HTTPRoute` on an internal hostname; no public DNS ([open point (b)](#open-points-for-the-owner), decided) |
| GitHub access for the catalog and TechDocs | A read-only fine-grained token, contents read on all repositories of the organisation | In the installation's secret store, injected as an environment variable; see [open point (a)](#open-points-for-the-owner), decided |

### D3: The catalog model

Backstage's system model has three layers: organisational entities (`Group`, `User`), the
software (`Component`, `API`, `Resource`) and the ecosystem (`System`, `Domain`). The portal
uses all three, with these rules:

| Kind | Used for | Rule |
|---|---|---|
| `Domain` | A business area: the hosting platform, and one domain per hosted tenant | Owned by the group that runs the area |
| `System` | A set of components that ships together: the reference installation with its instance, the baseline pair, the solution umbrella, the operations agent, each application, each tenant's workloads | Owned by one group; `spec.domain` set |
| `Component` | One repository or one deployable application | `spec.type` is `service`, `website`, `library` or `documentation`; `spec.lifecycle` is `production` or `experimental`; `spec.owner` is required and names a `Group`; `spec.system` set |
| `Resource` | Only what a component depends on and the platform provides: an object bucket, a database | Added only where a component's `spec.dependsOn` needs a target |
| `API` | The platform's Crossplane APIs and the connector's MCP surface, where a machine-readable definition exists | Not invented where no definition exists |
| `Group` | One per Keycloak group that exists today | Named as in Keycloak; no organisation chart is invented; `spec.type: team` |
| `User` | Not imported for the proof of concept | Sign-in through Keycloak resolves to the group memberships; a user entity is added only if a plugin needs one |

The Domains, Systems and Groups live in `catalog/` in this repository. Each repository
describes itself in its own `catalog-info.yaml`, written from what the repository already
states in its `README.md`, agent instructions and ADR index, and merged through one pull
request per repository. A `Location` in `catalog/` points at those files. The portal reads
the organisation's repositories and nothing else.

### D4: TechDocs in the recommended architecture

Documentation is built outside the portal. Each participating repository gets a `mkdocs.yml`
that points at its existing `docs/`; its CI runs the TechDocs CLI and publishes the site to an
object bucket that the platform's own `ObjectBucket` API provides. The portal's backend reads
from that bucket and never builds. No document is written or rewritten for the portal: what
TechDocs exposes about the existing documentation is one of the questions the proof of concept
asks.

**Amendment 2026-10-19: the backend builds.** Publishing from each repository's CI would put
the bucket's write credentials into public repositories. The portal's backend therefore builds
an entity's site with mkdocs inside its own container and publishes it to the bucket
(`techdocs.builder: local`); the image carries Python and `mkdocs-techdocs-core`, and only the
portal holds the bucket's credentials. Each repository still contributes only a `mkdocs.yml`
next to its existing `docs/`, and no document is rewritten. The rationale below records the
original choice.

### D5: One scaffolder template

The portal carries one template, "new tenant", with two parameters: tenant name and owner
group. Its steps fetch the tenant repository scaffold, publish a repository in the organisation
and open a pull request that adds the landing zone under `platform/tenants/` from the
`_template/` files. It is dry-run first and then run once with a throwaway name. It is the
template that the platform's own documentation already describes by hand, which is why it is
the one worth automating and the one whose drift from the hand procedure is visible.

### D6: A frontend-only conformance card

A scheduled job runs the `estate-baseline` checks over the catalogued repositories and writes
`conformance.json` (repository, check, exit code, timestamp) to the same bucket as TechDocs.
A small frontend plugin reads that file and shows clean or drift per component, with a link to
the deviation ADR when one exists. There is no backend plugin and no database table: the card
reads a file, and the job that writes it already exists in the baseline's cron form. The card
is the platform's standards made visible where a developer looks, instead of in a job log.

**Amendment 2026-10-19: a minimal backend route.** The scheduled job writes
`conformance.json` to a branch of a private repository instead of the bucket, and the browser
cannot read a private repository. A backend plugin with one route therefore reads the file
through the backend's URL reader with the GitHub integration's token, keeps it for five
minutes and serves it to the card. It has no database table and no scheduler. The rationale
below records the original choice.

### D7: The database

Backstage keeps its catalog, scaffolder task history and user settings in Postgres. The proof
of concept uses the Postgres that the Backstage chart bundles, which is the platform's existing
pattern of one Postgres per application beside the workload. Its volume is **not backed up**:
the catalog is re-ingested from Git at every start, the documentation lives in the bucket, and
the scaffolder history and user settings are accepted as lost on a volume loss. No production
database is built for this. If the proof of concept outlives the weekend, the volume gets the
Velero annotation the backup rule expects or a deviation record of its own.

### D8: Secrets stay out of this repository

The GitHub token, the OIDC client secret and the Postgres password exist only in the
installation's secret store and reach the pod as environment variables. `app-config.yaml`
here names them as `${VARIABLE}` placeholders. No hostname, client id, bucket name or image
tag appears in this repository; the publication gate runs before every push, as for every
other change here.

## Cut list

Deliberately not done, each a sentence of the form "not done, because", not a weekend:

| Not done | Because |
|---|---|
| Kubernetes plugin with cluster credentials | It would give the portal read access to every namespace for a view Argo CD already provides |
| Search backend | Ten repositories do not need an index; the catalog page lists them |
| Permissions framework | One operator and read-only data; authentication through Keycloak is the whole access model |
| Custom theme or branding | It teaches nothing about running a portal |
| High-availability Postgres | The database holds nothing that Git and the bucket do not hold (D7) |
| Multi-cluster | There is one cluster |
| A hosted Backstage offering | The question is what self-hosting costs; a hosted offering answers a different question |
| Any backend plugin beyond serving a JSON file | The card reads a file; a backend plugin is where Backstage's upgrade cost sits |
| Catalog import of anything outside the organisation | The scope is this estate |
| Notifications | Nobody to notify but the operator |
| New documentation written for TechDocs | What TechDocs does to existing documentation is the finding; new documents would hide it |

## Open points for the owner

Two decisions precede the first change in the instance repository:

| | Decision | Recommendation | State (2026-10-18) |
|---|---|---|---|
| (a) | A read-only fine-grained GitHub token for the catalog and TechDocs, scoped to all repositories of the organisation, current and future, with contents read, stored in the installation's secret store like every other credential, revoked when the portal is torn down | Issue it for the proof of concept's duration only; the scaffolder's publish step needs write scopes and gets a second, shorter-lived token or the operator's own session, decided on day 2 | Decided and issued 2026-10-18 by the owner, for 30 days, on all repositories of the organisation rather than the catalogued ones only; registered in the estate's credential register and stored as an encrypted Secret in the instance, which the change that creates the Application references |
| (b) | A public hostname, or the portal stays behind the tailnet | Tailnet plus Keycloak, no public DNS: a proof of concept, and the estate's credential inventory already lists public exposure as a risk | Decided: the portal gets an internal hostname on the tailnet, behind the Envoy Gateway the installation already exposes there; no public DNS |

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
something that can log in.

**Why the recommended TechDocs architecture and not the basic one.** The basic architecture
builds documentation inside the portal's pod, which needs the build toolchain in the image and
write access to storage from the portal. The recommended one puts the build where the
documentation changes, in each repository's CI, and leaves the portal read-only. It is also
the architecture that uses the platform's bucket API.

**Why one template.** A template is a frozen procedure. The tenant landing zone is the one
procedure the platform documents step by step and performs rarely, so it is the one where
automation pays and where drift between the template and the hand procedure would be seen.

**Why a frontend-only card.** The conformance checks already run and already exit 0 or 3.
The missing piece is visibility, which a file and a card provide. A backend plugin would be
the first thing to break on a Backstage upgrade and would add nothing the file does not carry.

**Why the bundled Postgres, unbacked.** The data is a cache of Git plus two things the proof
of concept can afford to lose. A production database would be a weekend by itself.

## Consequences

### Positive

- The estate gets one place that shows every repository, its owner and its conformance state,
  and the `catalog-info.yaml` files remain useful even if the portal is torn down.
- The exercise produces answers the ADRs do not yet hold: which repositories have no clear
  owner, which documentation renders badly as a site, and how long agents take to write
  metadata that a human then corrects. The plan records hours and corrections.
- The portal consumes the platform's own APIs, which tests them from a new kind of tenant.

### Negative

- About 2 GiB of memory on a worker for the duration, and a Keycloak client and a GitHub token
  to issue, inventory and revoke.
- The scaffolder's real run creates a repository in the organisation that has to be deleted
  afterwards, by the owner.
- Each participating repository gains two files (`catalog-info.yaml`, `mkdocs.yml`) and a CI
  step that it must keep or remove when the proof of concept ends.
- The portal's database is not backed up (D7); scaffolder history is lost on a volume loss.

### Neutral

- Nothing in this record claims a scale beyond one operator and about ten repositories. The
  lessons note that closes the proof of concept says so in its first line.
- Teardown is one Argo CD Application, one Keycloak client, one token, one bucket and this
  record's status.

## References

- [ADR-0005: Argo CD Deploys, Crossplane Provisions](0005-argocd-crossplane-responsibility-split.md)
- [ADR-0033: Multi-Tenancy Model and Tenant Isolation](0033-multi-tenancy-model-and-tenant-isolation.md)
- [ADR-0054: A Public Platform Repository and a Private Instance Repository](0054-public-platform-and-private-instance-repositories.md)
- [ADR-0055: Upstream Versions in the Public Platform Repository](0055-upstream-versions-in-the-public-platform-repository.md)
- [ADR-0058: Untagged Images in Application Bases](0058-untagged-images-in-application-bases.md)
- Backstage documentation: [the system model](https://backstage.io/docs/features/software-catalog/system-model), [the descriptor format](https://backstage.io/docs/features/software-catalog/descriptor-format), [TechDocs architecture](https://backstage.io/docs/features/techdocs/architecture), [writing templates](https://backstage.io/docs/features/software-templates/writing-templates), [plugin development](https://backstage.io/docs/plugins/plugin-development)
- [Conformance README in estate-baseline](https://github.com/manyfold-dk/estate-baseline/blob/main/scripts/conformance/README.md)
