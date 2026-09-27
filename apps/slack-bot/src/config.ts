/**
 * An integer of at least `min` from the environment, or the fallback when unset or empty (an
 * empty value in a manifest means "not set"). Anything else stops the start: `parseInt("30s")`
 * would quietly give 30, and a malformed value NaN, which turns a timeout or a threshold off.
 */
function intSetting(name: string, fallback: number, min = 1): number {
  const raw = process.env[name];
  if (raw === undefined || raw.trim() === '') return fallback;
  const n = Number(raw);
  if (!Number.isInteger(n) || n < min) {
    throw new Error(`${name} must be an integer of at least ${min}, got "${raw}"`);
  }
  return n;
}

function idList(raw: string | undefined): string[] {
  return (raw ?? '')
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean);
}

export const config = {
  slack: {
    botToken: process.env.SLACK_BOT_TOKEN ?? '',
    appToken: process.env.SLACK_APP_TOKEN ?? '',
    signingSecret: process.env.SLACK_SIGNING_SECRET ?? '',
  },
  channels: {
    alerts: process.env.CHANNEL_ALERTS ?? '#alerts',
    deployments: process.env.CHANNEL_DEPLOYMENTS ?? '#deployments',
    digest: process.env.CHANNEL_DIGEST ?? '#platform-status',
  },
  redis: {
    url: process.env.REDIS_URL ?? '',
  },
  backend: {
    url: process.env.BACKEND_URL ?? '',
    timeoutMs: 5000,
  },
  urls: {
    grafana: process.env.GRAFANA_BASE_URL ?? '',
    tekton: process.env.TEKTON_DASHBOARD_URL ?? '',
    argocd: process.env.ARGOCD_URL ?? '',
  },
  digest: {
    cronMorning: process.env.DIGEST_CRON_MORNING ?? '0 8 * * *',
    cronEvening: process.env.DIGEST_CRON_EVENING ?? '0 17 * * *',
    timezone: process.env.DIGEST_TIMEZONE ?? 'Europe/Copenhagen',
    enabled: process.env.DIGEST_ENABLED !== 'false',
  },
  consumers: {
    enabled: process.env.CONSUMERS_ENABLED !== 'false',
    // Reads block for 5s, and the loop backs off 5s between failures, so a
    // healthy consumer refreshes its liveness every ~5s. 120s means roughly a
    // dozen consecutive failures before readiness drops -- long enough to ride
    // out a Redis blip, short enough that a real stall surfaces in minutes
    // rather than never.
    stallThresholdMs: intSetting('CONSUMER_STALL_THRESHOLD_MS', 120_000),
  },
  approval: {
    timeoutSeconds: intSetting('APPROVAL_TIMEOUT_SECONDS', 300),
    // Slack user IDs that may approve a platform remediation (restart). Falls back to the
    // ops-fleet approvers, the same people in practice. Empty means nobody can approve.
    approverUserIds: idList(
      process.env.REMEDIATION_APPROVER_USER_IDS ?? process.env.OPS_FLEET_APPROVER_USER_IDS,
    ),
  },
  opsFleet: {
    webhookUrl: process.env.OPS_FLEET_WEBHOOK_URL ?? '',
    webhookToken: process.env.OPS_FLEET_WEBHOOK_TOKEN ?? '',
    timeoutMs: intSetting('OPS_FLEET_TIMEOUT_MS', 5000),
    approverUserIds: idList(process.env.OPS_FLEET_APPROVER_USER_IDS),
  },
  logLevel: process.env.LOG_LEVEL ?? 'info',
  port: intSetting('PORT', 3000),
} as const;

/**
 * The settings without which the bot cannot do its job. They have no defaults on purpose: a
 * default would be one deployment's host names. Called at start, so a missing value stops the
 * pod with a clear message instead of a connection error later.
 */
export function missingSettings(): string[] {
  const missing: string[] = [];
  if (!config.slack.botToken) missing.push('SLACK_BOT_TOKEN');
  if (!config.slack.appToken) missing.push('SLACK_APP_TOKEN');
  if (!config.slack.signingSecret) missing.push('SLACK_SIGNING_SECRET');
  if (!config.backend.url) missing.push('BACKEND_URL');
  // Redis also records ops approvals; without it the bot sends none (ops-decisions.ts).
  if ((config.consumers.enabled || config.opsFleet.webhookUrl) && !config.redis.url) {
    missing.push('REDIS_URL');
  }
  return missing;
}
