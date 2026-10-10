# Website

The public site and the operator console of the platform, in one image: a Quarkus backend that
serves a Vue 3 single-page application and the API behind it.

| Part | What it is |
|---|---|
| Public pages | The landing page, the platform write-up, published architecture decisions, the privacy notice, and a live status page |
| Operator console (`/platform`) | Layered platform health, active alerts with restart actions, deep health, web vitals, and an incident drill |
| API (`/api/v1`) | Health and status aggregation, the alert and pipeline webhooks, restarts, web-vitals collection |

## Contents

- [Architecture](#architecture)
- [Security model](#security-model)
- [API](#api)
- [Configuration](#configuration)
- [Development](#development)
- [Layout](#layout)

## Architecture

```
browser ──> oauth2-proxy ──> website backend (Quarkus) ──> Prometheus, Alertmanager, Kubernetes API
            (login for                │                      (health, alerts, restarts)
             non-public paths)        └──> Redis stream ──> Slack bot
Alertmanager, CI ──(bearer token)──> /api/v1/alerts/webhook, /api/v1/pipeline/webhook
```

- **One image.** The Vite build is copied into the backend's static resources, and a routing
  filter answers unknown non-API paths with `index.html`, so client-side routes survive a reload.
  There is no separate frontend server.
- **Health is aggregated and cached.** One pass reads Prometheus, the Kubernetes API and this
  pod's own deep-health endpoint, layer by layer (infrastructure, network, cluster, platform,
  pipelines, applications). The public endpoints serve a cached answer, so the load on those
  backends is a function of time, not of traffic.
- **Alerts fan out.** Alertmanager posts to the webhook; the backend appends each alert, firing or
  resolved, to a Redis stream that the Slack bot consumes. Each call reaches one replica, so the
  stream events are built from the call itself, not from what the replica holds.
- **Active alerts come from Alertmanager.** The alert list, the critical list, their summary and
  auto-remediation read `GET /api/v2/alerts` with the filters that reproduce what the webhook is
  sent: active, not silenced, not inhibited, routed to the `backend-webhook` receiver (the Watchdog
  heartbeat left out). Both replicas read the same Alertmanager, so they agree, and an alert is
  gone as soon as it resolves, whichever replica its resolution reached. Auto-remediation fails
  closed: without an answer from Alertmanager it restarts nothing in that cycle. Only one replica
  runs it, the holder of the Redis key `platform:auto-remediation:worker` (renewed every 60 s
  cycle, expires after 150 s), so the cooldown and attempt limit each replica keeps in memory hold
  as for one replica; without Redis no replica restarts anything. The three alert
  endpoints fall back to the alerts this replica was sent by webhook, which can include alerts
  whose resolution reached the other replica, and say so in `X-Alert-Source: replica`
  (`alertmanager` otherwise). `/health` counts Prometheus' firing alerts, silenced ones included.
- **Web vitals** from real visitors are recorded as bounded metrics: at most 50 routes and the
  known navigation types become tags, anything else counts under `other`, so free text cannot
  grow the series count.

## Security model

The table describes the cloud overlay. The local overlay is a development cluster on a laptop:
its route sends every path straight to the backend with no oauth2-proxy in front, so the console
and the login-only endpoints are open there. Restarts still answer 401: the token tenant is off,
so no caller holds the `admin` role.

| Surface | Control |
|---|---|
| Public pages and public API (`/api/v1/status`, `/status/history`, `/health`, `/health/summary`, `/metrics/vitals`) | Listed in oauth2-proxy's skip-auth pattern. They return states, counts, and the names and namespaces of the platform components and applications they report on; never alert names, exception messages or host names |
| Operator console and the rest of the API | oauth2-proxy login against the identity provider |
| Restarts (`/api/v1/remediation/*/restart`) | The backend verifies the access token oauth2-proxy forwards (signature, issuer, `azp`) and requires the `admin` role; a header alone is not trusted. Without a valid token: 401 |
| Webhooks | A bearer token compared in constant time; missing or wrong: 401. A network policy admits only the in-cluster callers |
| Logout | The backend's endpoint, which also ends the identity-provider session, is `POST` only, so a link or an image cannot trigger it. The proxy's own sign-out path still accepts `GET` and clears the proxy session |

## API

| Endpoint | Access | Purpose |
|---|---|---|
| `GET /api/v1/status`, `GET /api/v1/status/history` | public | The status page: services, their state, recent history |
| `GET /api/v1/health`, `GET /api/v1/health/summary` | public | Layered platform health |
| `GET /api/v1/health/deep` | login | This backend: JVM, resources, dependencies |
| `GET /api/v1/alerts`, `/alerts/summary`, `/alerts/critical` | login | Firing alerts, with labels, from Alertmanager; header `X-Alert-Source` says `alertmanager`, or `replica` when this replica's webhook store answered instead |
| `POST /api/v1/alerts/webhook`, `POST /api/v1/pipeline/webhook` | bearer token | Alertmanager and pipeline events |
| `POST /api/v1/remediation/pod/restart`, `/deployment/restart` | `admin` role | Restart a pod or a deployment |
| `GET /api/v1/remediation/summary` | login | Recent restarts |
| `POST /api/v1/metrics/vitals`, `GET /api/v1/metrics/vitals/recent` | public / login | Web vitals |
| `POST /api/v1/auth/logout` | login | End the session at the proxy and the identity provider |
| `/health/live`, `/health/ready` | public | Kubernetes probes; the route sends `/health` straight to the backend |
| `/metrics`, `/openapi`, `/swagger-ui` | login (in-cluster: direct) | Prometheus scrape, API description |

## Configuration

The defaults in `backend/src/main/resources/application.properties` are local development. Each
cluster's overlay sets its own values through the environment:

| Variable | Purpose |
|---|---|
| `MANYFOLD_PROMETHEUS_URL` | Prometheus for health, alert counts on `/health` and history |
| `MANYFOLD_ALERTMANAGER_URL` | Alertmanager for the active alerts; `MANYFOLD_ALERTMANAGER_RECEIVER` names the webhook's receiver (default `backend-webhook`) |
| `QUARKUS_OTEL_EXPORTER_OTLP_ENDPOINT` | Trace collector |
| `QUARKUS_REDIS_HOSTS` | Redis holding the alert stream |
| `MANYFOLD_STATUS_BACKEND_URL` | The address the status page probes this backend at |
| `MANYFOLD_STATUS_PUBLIC_TLS_TARGET` | `host:port` whose certificate the status page reports (optional) |
| `MANYFOLD_HEALTH_POD_APPS` | Applications checked by pod phase, `name\|namespace\|label=value`, comma-separated (optional) |
| `MANYFOLD_WEBHOOK_TOKEN` | The webhooks' bearer token |
| `QUARKUS_OIDC_*`, `MANYFOLD_OIDC_EXPECTED_CLIENT` | Token verification for restarts; the tenant is off unless the overlay enables it |
| `MANYFOLD_KEYCLOAK_*`, `MANYFOLD_AUTH_PROXY_LOGOUT_URL` | Logout at the identity provider and the proxy |

## Development

Java 25, Maven 3.9, Node.js 22 and pnpm; the devcontainer has them all.

Run the two in separate terminals, each from `apps/website`:

```bash
# Terminal 1 -- backend on :8080; it expects Redis on localhost:6379, Prometheus on
# localhost:9090 and Alertmanager on localhost:9093 (port-forwards to a cluster work)
cd backend && mvn quarkus:dev
```

```bash
# Terminal 2 -- frontend on :5173, proxying /api to the backend
cd frontend && pnpm install && pnpm dev
```

| Command | Does |
|---|---|
| `mvn verify` (backend) | Tests, then Checkstyle, SpotBugs, PMD and the Spotless format check; nothing is rewritten. The rules are shared with the repository's other Java service in [`build/lint/`](../../build/lint/) |
| `mvn spotless:apply` | Format the backend |
| `pnpm lint` (frontend) | ESLint and the Prettier check, as CI runs them |
| `pnpm format` | Format the frontend (published content under `src/content/` is left as written) |
| `pnpm test:unit`, `pnpm test:e2e` | Vitest; Playwright against a stubbed API |

The image builds from the repository root, which is its Docker context (the backend reads the
parent POM from `build/parent/`):

```bash
docker build -f apps/website/Dockerfile -t website:dev .
```

## Layout

| Path | Contents |
|---|---|
| `backend/src/main/java/.../api/v1` | REST resources |
| `backend/src/main/java/.../health` | Health, status and alert aggregation; Prometheus and Kubernetes clients |
| `backend/src/main/java/.../auth` | Webhook authentication, logout |
| `backend/src/main/java/.../metrics` | Web-vitals metrics |
| `backend/src/main/java/.../web` | SPA routing |
| `frontend/src/views`, `frontend/src/components` | Pages and components |
| `frontend/src/content/decisions` | The published architecture decisions (scrubbed copies) |
| `base/`, `overlays/` | Kustomize: shared resources, and each cluster's Deployment, route and settings |
