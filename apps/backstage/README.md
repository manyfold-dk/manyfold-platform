# Backstage

The platform's developer portal: a [Backstage](https://backstage.io) app whose software catalog
reads the platform's components, systems and APIs from Git. It was generated with
`@backstage/create-app` and uses the new backend system and the new frontend system.

Users sign in through OpenID Connect against the installation's Keycloak realm. The catalog's
source of truth is Git. The PostgreSQL database next to the app is not backed up: the backend
re-ingests the catalog from Git, and the rest of its state is an accepted loss (see
[Kubernetes base](#kubernetes-base)).

## Layout

| Path | What it is |
|---|---|
| `packages/app/` | The frontend. `src/modules/auth/` adds the OIDC sign-in page and its API |
| `packages/backend/` | The backend, which also serves the built frontend |
| `app-config.yaml` | Local development defaults: in-memory SQLite, guest sign-in, example entities in `examples/` |
| `app-config.production.yaml` | Production: PostgreSQL, OIDC, the catalog locations; every installation value is an environment variable |
| `Dockerfile` | The image, built from this directory |
| `base/` | The Kustomize base: the Deployment, its Service and the PostgreSQL database |

## Prerequisites

- Node.js 22 or 24. The Backstage CLI refuses other major versions.
- Yarn 4 through Corepack: `corepack enable`. `package.json` pins the version in
  `packageManager`.
- Docker, to build the image.

## Quick start

```sh
yarn install
yarn start        # frontend on http://localhost:3000, backend on http://localhost:7007
```

`yarn start` uses `app-config.yaml` only. Sign in as guest; the guest provider loads only when
`NODE_ENV` is `development`. Set `GITHUB_TOKEN` to read catalog files from GitHub without the
anonymous rate limit.

Checks, as CI runs them:

```sh
yarn install --immutable
yarn tsc
yarn lint
yarn test
yarn build:backend
```

## Configuration

The image starts the backend with `app-config.yaml` and `app-config.production.yaml`. The
production file reads these environment variables:

| Variable | Used for | Base value |
|---|---|---|
| `APP_BASE_URL` | `app.baseUrl`, `backend.baseUrl`, `backend.cors.origin` | Placeholder `http://localhost:7007` |
| `POSTGRES_HOST` | Database host | `backstage-postgres` |
| `POSTGRES_PORT` | Database port | `5432` |
| `POSTGRES_USER` | Database user | `backstage` |
| `POSTGRES_PASSWORD` | Database password | Secret `backstage-postgres`, key `POSTGRES_PASSWORD` |
| `GITHUB_TOKEN` | GitHub integration: reading catalog files | Secret `backstage-github-token`, key `GITHUB_TOKEN` |
| `AUTH_OIDC_METADATA_URL` | The realm's `.well-known/openid-configuration` | Placeholder `http://localhost/.well-known/openid-configuration` |
| `AUTH_OIDC_CLIENT_ID` | OIDC client | `backstage` |
| `AUTH_OIDC_CLIENT_SECRET` | OIDC client secret | Secret `backstage-oidc`, key `AUTH_OIDC_CLIENT_SECRET` |
| `AUTH_SESSION_SECRET` | `auth.session.secret`: signs the session cookie the OIDC login needs | Secret `backstage-oidc`, key `AUTH_SESSION_SECRET` |
| `NODE_OPTIONS` | Heap limit below the container's memory limit | `--max-old-space-size=1536` |

A third file, `app-config.instance.yaml`, is not in this repository. The installation mounts it
at `/app/instance/` and appends `--config /app/instance/app-config.instance.yaml` to the
container's arguments, so it overrides both files above. It carries what belongs to one
installation and is not a secret:

| Key | Purpose |
|---|---|
| `app.baseUrl`, `backend.baseUrl`, `backend.cors.origin` | The portal's public URL |
| `auth.providers.oidc.production.metadataUrl` | The Keycloak realm's metadata URL |
| `catalog.locations` | Further catalog files, if any. A list in a later file replaces the whole list, so repeat the two public locations of `app-config.production.yaml` |
| `organization.name`, `app.title` | Optional display names |

The production configuration:

- **Sign-in** uses the `oidc` provider with `prompt: auto`. The resolver
  `emailMatchingUserEntityProfileEmail` matches the user's email to a `User` entity and lets a
  user without one sign in.
- **The catalog** reads `catalog/all.yaml` of this repository and `catalog-info.yaml` of
  `estate-baseline`, both public. It accepts the kinds Component, System, API, Resource,
  Location, Domain, Group, User and Template.
- **TechDocs** is set to an external builder with the local publisher in `/tmp/techdocs`. A CI
  build of the docs and an object-storage publisher come later.
- **The guest provider** stays configured in `app-config.yaml` for development. The backend
  does not load its module outside `NODE_ENV=development`, and the sign-in page shows only
  Keycloak when `auth.environment` is `production`.

## Image

`Dockerfile` builds in three stages on `node:24-bookworm-slim`: the first installs every
workspace, type-checks and builds the backend bundle; the second installs the backend's
production dependencies from the bundle's skeleton; the third holds the bundle, those
dependencies and the two config files, owned by root and run as uid 1000. The process writes
only to `/tmp`, so it runs with a read-only root filesystem.

```sh
docker build -t backstage .
```

This repository builds no image and deploys nothing. The installation's CI builds the image
from this source and publishes it; the installation's overlay pins its tag
([ADR-0058](../../docs/adr/0058-untagged-images-in-application-bases.md)).

## Kubernetes base

`base/` declares the namespace `backstage` (PodSecurity `warn` and `audit` at restricted),
the Deployment and Service `backstage` (port 80 to 7007), and `backstage-postgres`. The
database runs on an `emptyDir`, deliberately without a volume claim. A container restart keeps
the `emptyDir`; replacing the Postgres Pod empties it. The backend then re-ingests the catalog
from Git, but scaffolder task history, user settings and notifications are lost, because Git does
not hold them. ADR 0060 D7 accepts that loss. Health probes use the backend's
`/.backstage/health/v1/liveness` and `/.backstage/health/v1/readiness`.

The installation's overlay adds:

- the image tag, with a Kustomize `images:` entry;
- `app-config.instance.yaml`, mounted at `/app/instance/`, and the extra `--config` argument;
- the Secrets `backstage-postgres`, `backstage-github-token`, `backstage-oidc` and the pull
  secret `ghcr-credentials`;
- the route and `pod-security.kubernetes.io/enforce`.

## Known quirk

`package.json` resolves `got` to the latest 11.x release. `@yarnpkg/core`, which the Backstage CLI pulls in,
declares `got` through a patch file that exists only in Yarn's own repository; without the
resolution `yarn install` fails with `ENOENT` on that patch.
