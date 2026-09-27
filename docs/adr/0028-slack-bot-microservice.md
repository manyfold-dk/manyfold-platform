# ADR 0028: Slack Bot Microservice

## Table of Contents

- [Status](#status)
- [Context](#context)
- [Decision](#decision)
- [Rationale](#rationale)
- [Consequences](#consequences)
- [References](#references)

## Status

Accepted

## Context

The platform had outbound-only Slack integration via webhooks — `#deployments` for Tekton pipeline notifications and `#alerts` for Alertmanager alerts. There was no way to query platform health, inspect alerts, or trigger remediations from Slack. The backend already exposes rich APIs (layered health, alert store, remediation service) that were only accessible via the web dashboard.

Key requirements:
- Interactive slash commands for health queries and remediation
- Bidirectional communication (not just outbound notifications)
- Alert notifications with action buttons (acknowledge, restart pod)
- Deployment notifications with links to commits, pipelines, and ArgoCD
- Scheduled health digests
- Remediation approval workflows
- No public ingress required (operational security)

Options considered:
1. **Extend website backend** — Add Slack integration directly to the Quarkus backend
2. **Standalone Slack bot service** — Separate microservice using Slack Bolt SDK
3. **Serverless functions** — Cloud functions triggered by Slack events

## Decision

We will deploy a standalone TypeScript (Node.js) Slack bot microservice using the Slack Bolt framework in Socket Mode, with Redis Streams as the event bus between the website backend and the bot.

### Key Choices

| Decision | Choice | Alternatives Considered |
|----------|--------|------------------------|
| Language | TypeScript (Node.js 22) | Java/Quarkus, Go |
| Framework | Slack Bolt SDK (`@slack/bolt`) | Raw Slack API, Hubot |
| Communication | Socket Mode (outbound WebSocket) | HTTP endpoint with public ingress |
| Event bus | Redis Streams with consumer groups | Direct HTTP calls, Kafka, NATS |
| Namespace | `platform-ops` (new) | `website`, `tekton-builds` |
| Approval model | Button-based with 5-minute timeout | Thread-based, emoji reactions |
| Migration | Phased (4 phases) | Big-bang cutover |

## Rationale

### Standalone service over backend extension

- **Separation of concerns**: The Quarkus backend serves the web application; mixing in Slack-specific logic (Socket Mode, consumer groups, slash command routing) would couple two distinct domains
- **Independent lifecycle**: The bot can be deployed, scaled, and restarted without affecting the website
- **Technology fit**: The Slack Bolt SDK for Node.js is the most mature and best-documented Slack integration library

### TypeScript over Java

- The Slack Bolt SDK is most mature in TypeScript/JavaScript — official SDK with active maintenance
- Aligns with the frontend stack (TypeScript, pnpm, Vitest)
- Lower memory footprint for a lightweight event consumer (64-128Mi vs 256Mi+ for JVM)

### Socket Mode over HTTP endpoints

- **No public ingress needed** — the bot makes outbound WebSocket connections only
- No TLS certificate management for the bot
- Simpler network security — no need to expose additional endpoints
- Slightly higher latency than HTTP (negligible for this use case)

### Redis Streams over direct HTTP

- **Persistent delivery** — messages survive bot restarts via consumer groups
- **Replay capability** — can re-process missed events using `XAUTOCLAIM`
- **Decoupled** — backend publishes without knowing about consumers
- **Reusable** — other future services can consume the same streams
- Redis is lightweight (64-128Mi) and simple to operate

### platform-ops namespace

- Separates operational tooling from application workloads (`website` namespace)
- Clear ownership boundary — the bot is platform infrastructure, not a user-facing app
- Consistent with the platform's namespace-per-concern pattern

### Phased migration

- Phase 1: Deploy bot with slash commands only (no notification cutover)
- Phase 2: Cut over alert notifications (bot replaces Alertmanager webhook)
- Phase 3: Cut over deployment notifications (bot replaces Tekton notify-slack task)
- Phase 4: Remove old webhook configuration
- Each phase is independently verifiable with clear rollback

## Consequences

### Positive

- Interactive platform management from Slack (health queries, remediation, alert inspection)
- Richer notification formatting with Block Kit and action buttons
- Approval workflows for remediations (safer than direct execution)
- Persistent event delivery via Redis Streams (crash recovery)
- Foundation for future Slack integrations (e.g., incident management)
- No public ingress required (Socket Mode)

### Negative

- Additional service to operate and monitor (slack-bot + Redis)
- New technology in the stack (Node.js alongside Java backend)
- Slack App configuration required (Bot Token, App Token, Signing Secret)
- Redis is a new infrastructure dependency (though lightweight)

### Neutral

- Existing webhook-based notifications continue to work during phased migration
- The bot follows the same Kustomize overlay pattern as the website (base + local/cloud)
- Own Tekton pipeline for independent build/deploy lifecycle
- ArgoCD manages deployment in both local and cloud clusters

## References

- Implementation plan of 2026-02-01 (private)
- [ADR-0023: Self-Healing Platform](0023-self-healing-platform.md)
- [Slack Bolt SDK Documentation](https://slack.dev/bolt-js/)
- [Redis Streams Documentation](https://redis.io/docs/data-types/streams/)
