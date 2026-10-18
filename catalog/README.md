# Catalog

The developer portal's catalog data for this repository, in Backstage's
[descriptor format](https://backstage.io/docs/features/software-catalog/descriptor-format).
The model is D3 of [ADR-0060](../docs/adr/0060-backstage-developer-portal-as-a-tenant-of-the-platform.md).

## What is here

| File | Holds |
|---|---|
| [`all.yaml`](all.yaml) | The `Location` the portal registers. Its targets are the files below and the repository's own `catalog-info.yaml` files |
| [`groups.yaml`](groups.yaml) | The `Group` entities that own something in this repository, one per identity-provider group |
| [`domains.yaml`](domains.yaml) | The `Domain` entities |
| [`systems.yaml`](systems.yaml) | The `System` entities of the platform domain |
| [`../catalog-info.yaml`](../catalog-info.yaml) | This repository as a `Component` |
| `../apps/<app>/catalog-info.yaml` | Each application under `apps/` as a `Component` |

## How the Location is read

An installation registers `catalog/all.yaml` by its URL on the default branch. Backstage
resolves each relative target against the URL of the `Location` itself, so the targets stay
valid on any branch or fork. The other repositories of an estate describe themselves in
their own `catalog-info.yaml` and reference the systems here by name; a reference resolves
across locations once both are registered.

## The naming contract

The names below are fixed: other repositories' `catalog-info.yaml` files name them.
Rename one only together with every file that references it.

| Kind | Name | What it is |
|---|---|---|
| Group | `platform-operators` | The people who run the platform; owner of everything the platform and the baseline ship |
| Domain | `platform` | The hosting platform and what it ships |
| Domain | `tenants` | Workloads hosted on the platform |
| System | `hosting-platform` | This reference installation and its private instance |
| System | `estate-baseline` | The shared baseline, public half and private half |
| System | `solution` | The solution umbrella: strategy, decisions, roadmap, cross-repository plans |
| System | `operations` | The operations automation agent |
| System | `applications` | The applications under `apps/` |

Components use `spec.owner: platform-operators`, a `spec.system` from the table, and a
`spec.type` of `gitops`, `service`, `website`, `library` or `documentation`.

## What is not here

The instance repository declares the systems under the `tenants` domain, the components of
the private repositories, the groups that own nothing here and any value that belongs to one
installation. This repository names no tenant. `API` and `Resource` entities are not
declared yet.
