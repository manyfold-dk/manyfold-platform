import type { App } from '@slack/bolt';
import type { Redis } from 'ioredis';
import { logger } from '../logger.js';
import { config } from '../config.js';
import { acknowledgeMessage } from '../services/redis-client.js';
import { consumeStream } from './consume.js';
import type { createOpsFleetForwarder } from '../services/ops-fleet-forwarder.js';
import { formatAlertFiring, formatAlertResolved } from '../formatting/alert-blocks.js';
import type { AlertEvent } from '../types/index.js';
import { BoundedMap, BoundedSet } from '../services/bounded.js';

type OpsFleetForwarder = ReturnType<typeof createOpsFleetForwarder>;

// Prometheus' always-firing dead-man's-switch alert. It is routed down this
// path deliberately (Alertmanager -> backend webhook -> this stream ->
// ops-fleet) so that its *absence* on the VPS proves the chain is severed --
// the failure mode that went unnoticed for a week in July 2026. It is a
// liveness marker, not an incident: forward it and stop. Posting it to #alerts
// every few minutes would be pure noise, and letting it reach the VPS as a
// normal alert would wake the ops agent on a 5-minute cadence.
const WATCHDOG_ALERT_NAME = 'Watchdog';

// Alert fingerprint -> Slack message timestamp, to thread the resolution under the firing
// message. Bounded: an alert that never resolves must not hold memory forever; past the limit
// its resolution is posted unthreaded.
const alertMessageMap = new BoundedMap<string, string>(5_000);
// Stream message IDs already posted, so a redelivered message is acknowledged, not re-posted.
const processedIds = new BoundedSet<string>(10_000);

export function startAlertConsumer(
  app: App,
  redis: Redis,
  opsFleetForwarder?: OpsFleetForwarder,
): void {
  const stream = 'platform:alerts' as const;

  async function processMessage(id: string, fields: Record<string, string>): Promise<void> {
    if (processedIds.has(id)) {
      await acknowledgeMessage(redis, stream, id);
      return;
    }

    const alert: AlertEvent = {
      fingerprint: fields.fingerprint,
      status: fields.status as AlertEvent['status'],
      alertName: fields.alertName,
      severity: fields.severity as AlertEvent['severity'],
      namespace: fields.namespace,
      pod: fields.pod,
      deployment: fields.deployment,
      summary: fields.summary,
      timestamp: fields.timestamp,
    };

    if (alert.alertName === WATCHDOG_ALERT_NAME) {
      // Awaited, unlike the fan-out below: for the heartbeat the forward IS the
      // payload. An undelivered heartbeat stays pending and is claimed again, so a
      // failure is retried instead of silently acknowledged.
      const delivered = opsFleetForwarder ? await opsFleetForwarder(alert) : true;
      if (!delivered) {
        logger.error(
          { messageId: id },
          'Watchdog heartbeat not delivered to ops-fleet; left pending',
        );
        return;
      }
      // Not added to processedIds -- a heartbeat arrives every few minutes and
      // would grow that Set without bound; re-forwarding one is harmless.
      await acknowledgeMessage(redis, stream, id);
      return;
    }

    try {
      if (alert.status === 'firing') {
        const result = await app.client.chat.postMessage({
          channel: config.channels.alerts,
          blocks: formatAlertFiring(alert),
          text: `${alert.severity.toUpperCase()}: ${alert.alertName} in ${alert.namespace}`,
        });

        if (result.ts) {
          alertMessageMap.set(alert.fingerprint, result.ts);
        }

        logger.info(
          {
            alert: alert.alertName,
            fingerprint: alert.fingerprint,
            channel: config.channels.alerts,
          },
          'Alert posted to Slack',
        );
      } else if (alert.status === 'resolved') {
        const originalTs = alertMessageMap.get(alert.fingerprint);
        const duration = calculateDuration(alert.timestamp);

        await app.client.chat.postMessage({
          channel: config.channels.alerts,
          thread_ts: originalTs,
          blocks: formatAlertResolved(alert, duration),
          text: `RESOLVED: ${alert.alertName} in ${alert.namespace}`,
        });

        alertMessageMap.delete(alert.fingerprint);
        logger.info(
          { alert: alert.alertName, fingerprint: alert.fingerprint },
          'Alert resolution posted to Slack',
        );
      }

      // Fan out to the ops fleet VPS webhook (fire-and-forget: a slow or
      // unreachable VPS must not stall the Slack path).
      if (opsFleetForwarder) {
        void opsFleetForwarder(alert);
      }

      processedIds.add(id);
      await acknowledgeMessage(redis, stream, id);
    } catch (err) {
      logger.error({ err, alert: alert.alertName, messageId: id }, 'Failed to post alert to Slack');
      // Don't acknowledge — will be retried
    }
  }

  consumeStream(redis, stream, processMessage).catch((err) => {
    logger.fatal({ err }, 'Alert consumer loop crashed');
  });
}

function calculateDuration(_resolvedTimestamp: string): string | undefined {
  // We don't have the firing time easily here, so return undefined
  // The resolved event could include it in the future
  return undefined;
}
