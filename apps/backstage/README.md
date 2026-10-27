# ManyStage

ManyStage is the platform's developer portal: a [Backstage](https://backstage.io) app whose
software catalog reads the platform's components, systems and APIs from Git. It was generated
with `@backstage/create-app` and uses the new backend system and the new frontend system.

Users sign in through OpenID Connect against the installation's Keycloak realm. The catalog's
source of truth is Git. The PostgreSQL database next to the app is not backed up: the backend
re-ingests the catalog from Git, and the rest of its state is an accepted loss (see
[Kubernetes base](#kubernetes-base)).

## Layout

| Path | What it is |
|---|---|
| `packages/app/` | The frontend. `src/modules/auth/` adds the OIDC sign-in page and its API; `src/modules/theme/` the theme, see [Theme](#theme) |
| `packages/backend/` | The backend, which also serves the built frontend |
| `plugins/conformance/`, `plugins/conformance-backend/` | The Baseline conformance card and the route that serves its data |
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

## Theme

The module `packages/app/src/modules/theme/` styles the portal in the Manyfold design system.
It replaces Backstage's built-in light and dark themes with two built by `createUnifiedTheme`,
under the same theme ids, so a user's stored choice still applies. `tokens.ts` holds the
design system's colours, light and dark, its fonts and the corner radius. `themes.ts` maps
them onto the Material UI palette and typography and adds a few component overrides.
`styles.css` sets the same colours and fonts as the variables of the Backstage UI components,
which the catalog pages use. The dark theme's solid buttons carry ink text instead of white,
because white on the dark theme's brand blue is below a contrast of 4.5:1.

The fonts, Inter and JetBrains Mono, come from the `@fontsource` packages. The frontend bundle
carries them and the backend serves them from the portal's own origin, so the browser loads no
font from a third party and the default Content Security Policy needs no change.

To change a colour, change its value in `tokens.ts` and the same value in `styles.css`, then
check both themes with `yarn start` (Settings, Appearance). The sidebar is dark in both themes;
its colours are `nav` in `tokens.ts`. The logo, the mark and the wordmark ManyStage, is inline
SVG in `src/modules/nav/`; the design system fixes the colours of the mark's bars.

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
| `TECHDOCS_BUCKET` | TechDocs publisher: the bucket name | Secret `backstage-techdocs-bucket`, key `TECHDOCS_BUCKET` |
| `TECHDOCS_ENDPOINT` | TechDocs publisher: the S3-compatible endpoint URL | Secret `backstage-techdocs-bucket`, key `TECHDOCS_ENDPOINT` |
| `TECHDOCS_ACCESS_KEY_ID` | TechDocs publisher: access key | Secret `backstage-techdocs-bucket`, key `TECHDOCS_ACCESS_KEY_ID` |
| `TECHDOCS_SECRET_ACCESS_KEY` | TechDocs publisher: secret key | Secret `backstage-techdocs-bucket`, key `TECHDOCS_SECRET_ACCESS_KEY` |
| `TECHDOCS_REGION` | TechDocs publisher: the region the S3 client signs for | `us-east-1` |
| `NODE_OPTIONS` | Heap limit below the container's memory limit | `--max-old-space-size=1536` |

A third file, `app-config.instance.yaml`, is not in this repository. The installation mounts it
at `/app/instance/` and appends `--config /app/instance/app-config.instance.yaml` to the
container's arguments, so it overrides both files above. It carries what belongs to one
installation and is not a secret:

| Key | Purpose |
|---|---|
| `app.baseUrl`, `backend.baseUrl`, `backend.cors.origin` | The portal's public URL |
| `auth.providers.oidc.production.metadataUrl` | The Keycloak realm's metadata URL |
| `conformance.sourceUrl` | The URL of `conformance.json`; see [Baseline conformance card](#baseline-conformance-card) |
| `catalog.locations` | Further catalog files, if any. A list in a later file replaces the whole list, so repeat the two public locations of `app-config.production.yaml` |
| `organization.name`, `app.title` | Optional display names |

The production configuration:

- **Sign-in** uses the `oidc` provider with `prompt: auto`. The resolver
  `emailMatchingUserEntityProfileEmail` matches the user's email to a `User` entity and lets a
  user without one sign in.
- **The catalog** reads `catalog/all.yaml` of this repository and `catalog-info.yaml` of
  `estate-baseline`, both public. It accepts the kinds Component, System, API, Resource,
  Location, Domain, Group, User and Template.
- **TechDocs** is built by the backend and published to an S3-compatible bucket; see
  [TechDocs](#techdocs).
- **The guest provider** stays configured in `app-config.yaml` for development. The backend
  does not load its module outside `NODE_ENV=development`, and the sign-in page shows only
  Keycloak when `auth.environment` is `production`.

## TechDocs

The backend builds an entity's documentation the first time someone opens it, and again
when the source has changed (`techdocs.builder: local`). It reads the source at the
entity's `backstage.io/techdocs-ref` annotation, runs mkdocs with the `techdocs-core`
plugin inside the container (`techdocs.generator.runIn: local`), and uploads the site to
the bucket (`techdocs.publisher.type: awsS3`, path-style addressing for an S3-compatible
store). The bucket keeps the built sites across Pod replacements.

The backend builds instead of CI because publishing from CI would put the bucket's
credentials into every documented repository, and several of them are public. ADR 0060 D4
is adjusted accordingly.

- **Python in the image.** The runtime stage installs `python3` and a virtual environment
  at `/opt/techdocs` from `requirements.txt`: `mkdocs-techdocs-core` at an exact version,
  which pins its own plugins exactly. Renovate updates the file.
- **Writable paths.** The generator works in `os.tmpdir()`, which is `/tmp`, the
  `emptyDir` of the read-only Pod.
- **A repository's docs.** A repository opts in with a `mkdocs.yml` next to its
  `catalog-info.yaml` and the annotation `backstage.io/techdocs-ref: dir:.`. This
  repository's own `mkdocs.yml` at the root is an example. The generator removes mkdocs keys
  and plugins outside its allow-list before it builds.
- **Bucket and credentials.** The installation creates the Secret
  `backstage-techdocs-bucket` with the four keys `TECHDOCS_BUCKET`, `TECHDOCS_ENDPOINT`,
  `TECHDOCS_ACCESS_KEY_ID` and `TECHDOCS_SECRET_ACCESS_KEY`. The base sets
  `TECHDOCS_REGION` to `us-east-1`; an overlay changes it where the store needs a real
  region.

Build a repository's site locally the same way, with the image:

```sh
docker run --rm -v "$PWD:/src:ro" --entrypoint sh backstage \
  -c 'cp -r /src /tmp/in && cd /tmp/in && mkdocs build -d /tmp/out'
```

## Baseline conformance card

Every Component's overview page carries the card "Baseline conformance". It shows, for the
repository the entity names in its `github.com/project-slug` annotation, one row per
baseline check: the check's name, its status, when it ran, and a link to each deviation ADR
the repository records. A component without results shows "No conformance data for this
component" and the time the file was generated.

The data is one file, `conformance.json`, which the estate's scheduled conformance job
writes to a branch of a repository:

```json
{
  "generated_at": "<ISO-8601>",
  "estate_baseline_commit": "<40 hex>",
  "results": [
    {
      "repository": "<owner>/<repo>",
      "check": "conformance|agent",
      "exit_code": 0,
      "timestamp": "<ISO-8601>",
      "deviation_adrs": ["<url>"]
    }
  ]
}
```

| `exit_code` | Status |
|---|---|
| `0` | clean |
| `3` | drift |
| anything else | check failed: the check itself did not complete |

The plugin `conformance-backend` reads the file from `conformance.sourceUrl` through the
backend's URL reader, which authenticates with the GitHub integration's token, keeps it for
five minutes and serves it at `/api/conformance/conformance.json` with `fetched_at` added.
The card calls that route with the signed-in user's token. The repository ships no URL:
the installation sets `conformance.sourceUrl` in `app-config.instance.yaml`. For a file in a
private repository it is the `github.com` blob URL, because the GitHub reader matches only
the integration's host:

```yaml
conformance:
  sourceUrl: https://github.com/<owner>/<repo>/blob/<branch>/conformance.json
```

Without it the route answers 503 and the card shows the error.

## Image

`Dockerfile` builds in three stages on `node:24-bookworm-slim`: the first installs every
workspace, type-checks and builds the backend bundle; the second installs the backend's
production dependencies from the bundle's skeleton; the third holds the bundle, those
dependencies, the two config files and the TechDocs generator's Python environment, owned by
root and run as uid 1000. The process writes
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
- the Secrets `backstage-postgres`, `backstage-github-token`, `backstage-oidc`,
  `backstage-techdocs-bucket` and the pull secret `ghcr-credentials`;
- the route and `pod-security.kubernetes.io/enforce`.

## Known quirk

`package.json` resolves `got` to the latest 11.x release. `@yarnpkg/core`, which the Backstage CLI pulls in,
declares `got` through a patch file that exists only in Yarn's own repository; without the
resolution `yarn install` fails with `ENOENT` on that patch.
