# Synthetic monitoring

Checks the platform's public fronts the way a visitor meets them, from two vantage points, and
pushes the results to Prometheus through a Pushgateway. Design record:
[ADR-0036](../website/frontend/src/content/decisions/0036-synthetic-monitoring-and-integrity-verification.md),
as the website publishes it.

## Contents

- [Two vantage points](#two-vantage-points)
- [Metrics](#metrics)
- [Configuration](#configuration)
- [Development](#development)
- [Layout](#layout)

## Two vantage points

| Part                          | Runs                                                                                | Checks                                                                                                                                                                                   |
| ----------------------------- | ----------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Browser runner (`src/runner`) | A Kubernetes CronJob per front, every five minutes, in a real Chromium (Playwright) | `smoke`: a public page loads with a heading and navigation. `login`: a sign-in through the identity provider reaches the application's landing view. Read-only: no journey changes state |
| Edge probe (`src/edge`)       | A Cloudflare Worker, every two minutes, outside the cluster                         | Each front answers its expected status code and sends HSTS                                                                                                                               |

The two disagree usefully: a front that is up from inside but down from the edge points at DNS,
TLS or the CDN; the reverse points at the cluster. Neither holds a credential it could leak
beyond its own: the runner's login is a dedicated monitoring user, and the edge probe needs none.

## Metrics

Both sides push the same gauges, labelled `front`, `journey`, `vantage` (`internal` or
`external`) and `tenant`: `synthetic_check_up`, `synthetic_check_duration_seconds`,
`synthetic_run_timestamp_seconds`; the runner adds `synthetic_login_success`, the edge
`synthetic_assertion_ok{assertion="hsts"}`. Every sample carries its `# TYPE` line from one
shared module (`src/shared/metrics.ts`): a metric first registered untyped makes the Pushgateway
refuse the other side's typed push. Label values are escaped.

A run whose journey fails still pushes `up=0` and reports the journey's own error; a failed push
never hides it. A missing setting is such a failure: journeys read their settings inside the
timed body. The edge probe counts a refused push (non-2xx) as failed, pushes the remaining
fronts, and then fails the cron run, naming the fronts it could not push.

## Configuration

Browser runner (CronJob environment):

| Variable                   | Journey | Meaning                                                         |
| -------------------------- | ------- | --------------------------------------------------------------- |
| `PW_JOURNEY`               | all     | `smoke` or `login`                                              |
| `FRONT`, `TENANT`          | all     | Metric labels; `TENANT` defaults to `platform`                  |
| `JOURNEY_URL`              | all     | The page to open                                                |
| `PUSHGATEWAY_URL`          | all     | Where results go                                                |
| `SMOKE_EXPECT_LINK`        | smoke   | Optional: a link that must be present, by exact accessible name |
| `IDENTITY_HOST`            | login   | The identity provider's host the login redirects to             |
| `LOGIN_USER`, `LOGIN_PASS` | login   | The monitoring user, from a Secret                              |

Edge probe (`wrangler.jsonc` vars, examples in this directory; `INGEST_TOKEN` is a Worker secret):

| Variable     | Meaning                                                                                                |
| ------------ | ------------------------------------------------------------------------------------------------------ |
| `INGEST_URL` | The token-gated Pushgateway ingest                                                                     |
| `TARGETS`    | JSON list of `{front, url, expect}`; `url` must be `https://`. A malformed entry is skipped and logged |

## Development

Node.js 24 and pnpm.

```bash
pnpm install
pnpm test            # unit tests (vitest): metrics, journey error handling, edge targets
pnpm lint            # both TypeScript projects, ESLint and the Prettier check, as CI runs them
pnpm build           # lists the Playwright journeys
PW_JOURNEY=smoke FRONT=www JOURNEY_URL=https://example.com/ PUSHGATEWAY_URL=http://localhost:9091 \
  pnpm journey       # one journey against a local Pushgateway
pnpm edge:dev        # the Worker under wrangler
```

The runner image (`Dockerfile`) holds only `src/runner` and `src/shared`; CI builds it on push.
The Worker deploys by hand from a deployment's own copy of `wrangler.jsonc`, with its Worker
name, ingest endpoint and fronts: `pnpm exec wrangler deploy -c <that file>`.

## Layout

| Path                                  | Contents                                                            |
| ------------------------------------- | ------------------------------------------------------------------- |
| `src/shared/metrics.ts`               | Exposition format, shared by both sides                             |
| `src/runner/journey.ts`               | Journey wrapper (timing, push, error handling) and the login helper |
| `src/runner/journeys/`                | The Playwright journeys: `smoke`, `login`                           |
| `src/edge/index.ts`                   | The Worker                                                          |
| `tsconfig.json`, `tsconfig.edge.json` | Node types for the runner, Workers types for the edge               |
