# Slack Bot

The platform's operator channel in Slack. It posts alerts, deployment results and a twice-daily
health digest, answers slash commands about platform health, and runs an approval flow for
restarts.

A TypeScript service on [Bolt](https://tools.slack.dev/bolt-js/) in Socket Mode: the bot opens an
outbound WebSocket to Slack, so it needs no ingress and exposes nothing to the internet. Design
record: [ADR-0028](../../docs/adr/0028-slack-bot-microservice.md).

## Architecture

```
Slack  <── Socket Mode (outbound WebSocket) ──  slack-bot
                                                  │    │
                              XREADGROUP streams  │    │  HTTP: health, alerts, remediation
                                                  ▼    ▼
                                              Redis    website backend
                                                ▲
                                                │ XADD
                                   website backend (Alertmanager and pipeline webhooks)
```

- **Events arrive through Redis Streams.** The website backend receives the Alertmanager and
  pipeline webhooks and appends them to `platform:alerts` and `platform:deployments`. The bot
  reads them in a consumer group, so an event that arrives while the bot restarts is delivered
  after it, and one that fails is retried, not lost.
- **Readiness means consuming, not connected.** `/readyz` fails when no stream read has
  succeeded for `CONSUMER_STALL_THRESHOLD_MS`, so a consumer stuck behind a live TCP connection
  surfaces instead of looking healthy.
- **Queries go to the backend.** `/health`, `/alerts` and the digest read the backend's health
  API; the bot holds no platform state of its own.
- **Alerts can also go to an ops host.** With `OPS_FLEET_WEBHOOK_URL` and its token set, each
  alert is also posted to an operations agent's webhook; its proposed actions come back as
  Approve/Reject buttons.

## Slash commands

| Command                                                  | Answer                                                                      |
| -------------------------------------------------------- | --------------------------------------------------------------------------- |
| `/platformstatus`                                        | One line: overall health                                                    |
| `/health`                                                | Health by layer: infrastructure, cluster, platform, pipelines, applications |
| `/alerts`                                                | Active alerts by severity                                                   |
| `/remediate restart pod <namespace> <pod>`               | Asks for approval to restart a pod                                          |
| `/remediate restart deployment <namespace> <deployment>` | Asks for approval to restart a deployment                                   |

## Security model

| Concern                   | Control                                                                                                                                                                                                                                                                                                                                                                           |
| ------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Who can approve a restart | Only the Slack user IDs in `REMEDIATION_APPROVER_USER_IDS` (falls back to `OPS_FLEET_APPROVER_USER_IDS`). An empty list means nobody can approve                                                                                                                                                                                                                                  |
| Who can reject a restart  | An approver or the requester; anyone else is told so and the request stays open                                                                                                                                                                                                                                                                                                   |
| Replay of an approval     | An approval is taken from the store once; a second click finds nothing. Approvals expire after `APPROVAL_TIMEOUT_SECONDS`, and a sweep marks the expired message so its buttons disappear                                                                                                                                                                                         |
| Who performs the restart  | The website backend, not the bot. The backend requires a verified administrator token for remediation, so a request from the bot is refused (HTTP 401/403) and the bot says so in the thread instead of pretending it worked. A service identity for the bot is an open design item                                                                                               |
| Ops-host actions          | Approve/Reject for the operations agent accept only `OPS_FLEET_APPROVER_USER_IDS`; the webhook carries a bearer token. The first decision on an action stands (kept in Redis for a week), so an Approve cannot follow a Reject; an approval is not sent unless Redis confirms the claim. Limit: Redis keeps no volume, and the ops host refuses an approval older than 15 minutes |
| Inbound traffic           | None: Socket Mode is outbound only. The HTTP port serves `/healthz` and `/readyz`                                                                                                                                                                                                                                                                                                 |
| Credentials               | Slack tokens and the ops webhook token come from Kubernetes Secrets, never from the image or the manifests                                                                                                                                                                                                                                                                        |

## Configuration

Settings without a sensible default are required: the bot refuses to start and names each
missing one. Integer settings must be clean integers: `30s` stops the start instead of turning a timeout off.
An unset or empty variable takes the default.

| Variable                                                 | Default                        | Purpose                                   |
| -------------------------------------------------------- | ------------------------------ | ----------------------------------------- |
| `SLACK_BOT_TOKEN`                                        | required                       | Bot token (`xoxb-`)                       |
| `SLACK_APP_TOKEN`                                        | required                       | App-level token for Socket Mode (`xapp-`) |
| `SLACK_SIGNING_SECRET`                                   | required                       | Request signature verification            |
| `BACKEND_URL`                                            | required                       | Website backend base URL                  |
| `REDIS_URL`                                              | required when consumers are on | Redis holding the event streams           |
| `CONSUMERS_ENABLED`                                      | `true`                         | Read the event streams                    |
| `CONSUMER_STALL_THRESHOLD_MS`                            | `120000`                       | Stream-read age at which `/readyz` fails  |
| `CHANNEL_ALERTS`                                         | `#alerts`                      | Alert channel                             |
| `CHANNEL_DEPLOYMENTS`                                    | `#deployments`                 | Deployment channel                        |
| `CHANNEL_DIGEST`                                         | `#platform-status`             | Digest channel                            |
| `DIGEST_ENABLED`                                         | `true`                         | Post the scheduled digest                 |
| `DIGEST_CRON_MORNING` / `DIGEST_CRON_EVENING`            | `0 8 * * *` / `0 17 * * *`     | Digest schedule                           |
| `DIGEST_TIMEZONE`                                        | `Europe/Copenhagen`            | Time zone of the schedule                 |
| `GRAFANA_BASE_URL`, `TEKTON_DASHBOARD_URL`, `ARGOCD_URL` | empty                          | Link targets in messages                  |
| `REMEDIATION_APPROVER_USER_IDS`                          | ops-fleet approvers            | Comma-separated Slack user IDs            |
| `APPROVAL_TIMEOUT_SECONDS`                               | `300`                          | Approval expiry                           |
| `OPS_FLEET_WEBHOOK_URL`, `OPS_FLEET_WEBHOOK_TOKEN`       | empty (off)                    | Ops-host alert forwarding                 |
| `OPS_FLEET_APPROVER_USER_IDS`                            | empty                          | Who may approve ops-host actions          |
| `OPS_FLEET_TIMEOUT_MS`                                   | `5000`                         | Ops webhook timeout                       |
| `PORT`                                                   | `3000`                         | Health endpoint port                      |
| `LOG_LEVEL`                                              | `info`                         | Pino log level                            |

## Development

Node.js 22 or later and pnpm.

```bash
pnpm install
pnpm dev      # watch mode
pnpm test     # vitest
pnpm lint     # eslint and prettier --check; CI runs the same
pnpm format   # prettier --write
pnpm build    # tsc to dist/
```

| Path                 | Contents                                              |
| -------------------- | ----------------------------------------------------- |
| `src/app.ts`         | Bolt app, health server, start-up                     |
| `src/config.ts`      | Settings and their validation                         |
| `src/commands/`      | Slash commands                                        |
| `src/actions/`       | Button handlers and the approval store                |
| `src/consumers/`     | Redis Stream consumers                                |
| `src/scheduler/`     | Digest schedule                                       |
| `src/formatting/`    | Block Kit message builders                            |
| `src/services/`      | Backend, Redis and ops-webhook clients                |
| `base/`, `overlays/` | Kustomize: the workload, and per-environment settings |

## Deployment

`base/` holds the Deployment, Service and ServiceAccount; an overlay supplies the environment's
URLs, the image tag and the Secret references. The Secret `slack-bot-credentials` holds
`bot-token`, `app-token` and `signing-secret`.
