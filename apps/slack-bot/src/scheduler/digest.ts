import type { App } from '@slack/bolt';
import cron from 'node-cron';
import { logger } from '../logger.js';
import { config } from '../config.js';
import type { Redis } from 'ioredis';
import { getHealth, getAlertSummary, getRemediationSummary } from '../services/backend-client.js';
import { formatDigestBlocks, type DigestDeployment } from '../formatting/health-blocks.js';
import { createRedisClient, readStreamSince } from '../services/redis-client.js';
import { withTimeout } from '../services/timeout.js';

/**
 * The first digest after a start covers the longest gap of the default schedule: 17:00 to
 * 08:00 is 15 hours, 16 on the autumn DST night; 17 leaves room.
 */
const FIRST_WINDOW_MS = 17 * 3600_000;
/** A digest never waits longer than this on Redis; it posts without the section instead. */
const REDIS_TIMEOUT_MS = 5_000;

let redis: Redis | undefined;
/**
 * Where the last successful deployment read ended: the next digest covers everything since. It
 * moves only after a successful read, so a digest posted while Redis was down loses nothing.
 */
let deploymentsCutoff: number | undefined;

/**
 * Deployments since the previous digest, from the stream the deploy consumer reads. Best effort
 * and bounded: without Redis the digest still posts, without the section.
 */
async function recentDeployments(sinceMs: number): Promise<{
  deployments: DigestDeployment[];
  total: number;
  capped: boolean;
  ok: boolean;
}> {
  if (!config.consumers.enabled) return { deployments: [], total: 0, capped: false, ok: true };
  try {
    redis ??= createRedisClient();
    const { entries, total, capped } = await withTimeout(
      readStreamSince(redis, 'platform:deployments', sinceMs),
      REDIS_TIMEOUT_MS,
    );
    const deployments = entries.map((e) => ({
      pipeline: e.fields.pipeline ?? 'unknown',
      imageTag: e.fields.imageTag ?? '',
      status: e.fields.status ?? 'unknown',
      timestamp: e.fields.timestamp ?? new Date(Number(e.id.split('-')[0])).toISOString(),
    }));
    return { deployments, total, capped, ok: true };
  } catch (err) {
    logger.warn({ err }, 'Digest: deployments unavailable');
    return { deployments: [], total: 0, capped: false, ok: false };
  }
}

export function startDigestScheduler(app: App): void {
  cron.schedule(
    config.digest.cronMorning,
    () => {
      postDigest(app, 'Morning').catch((err) => {
        logger.error({ err }, 'Morning digest failed');
      });
    },
    { timezone: config.digest.timezone },
  );

  cron.schedule(
    config.digest.cronEvening,
    () => {
      postDigest(app, 'Evening').catch((err) => {
        logger.error({ err }, 'Evening digest failed');
      });
    },
    { timezone: config.digest.timezone },
  );

  logger.info(
    {
      morning: config.digest.cronMorning,
      evening: config.digest.cronEvening,
      timezone: config.digest.timezone,
    },
    'Digest scheduler configured',
  );
}

async function postDigest(app: App, period: 'Morning' | 'Evening'): Promise<void> {
  logger.info({ period }, 'Generating platform digest');

  const [health, alertSummary, remediationSummary] = await Promise.all([
    getHealth(),
    getAlertSummary(),
    getRemediationSummary(),
  ]);

  const now = Date.now();
  const { deployments, total, capped, ok } = await recentDeployments(
    deploymentsCutoff ?? now - FIRST_WINDOW_MS,
  );

  // The backend's summary counts the alerts firing now, by severity; it has no time window, so
  // the digest reports exactly that.
  const blocks = formatDigestBlocks(
    health,
    {
      active: alertSummary.total,
      critical: alertSummary.critical,
      warning: alertSummary.warning,
      info: alertSummary.info,
    },
    {
      recentActions: remediationSummary.recentActions,
      status: remediationSummary.status,
    },
    { shown: deployments, total, capped },
    period,
    config.digest.timezone,
  );

  await app.client.chat.postMessage({
    channel: config.channels.digest,
    blocks,
    text: `${period} Platform Digest`,
  });

  if (ok) deploymentsCutoff = now;
  logger.info({ period, channel: config.channels.digest }, 'Digest posted');
}
