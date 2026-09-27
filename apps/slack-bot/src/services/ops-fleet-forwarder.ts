// ops-fleet-forwarder.ts -- POST alerts to the ops VPS webhook.
//
// Called from consumers/alert-consumer.ts alongside the Slack post. The
// webhook URL comes from OPS_FLEET_WEBHOOK_URL; a deployment points it at an
// egress Service in front of the ops host.

import type { Logger } from 'pino';
import type { AlertEvent } from '../types/index.js';

export interface OpsFleetForwarderConfig {
  webhookUrl: string; // OPS_FLEET_WEBHOOK_URL
  webhookToken: string; // bearer token
  timeoutMs: number; // default 5000
  logger: Logger;
}

export function createOpsFleetForwarder(cfg: OpsFleetForwarderConfig) {
  const { webhookUrl, webhookToken, timeoutMs, logger } = cfg;

  /** Posts one alert; true when the webhook accepted it. Never throws. */
  return async function forwardAlert(alert: AlertEvent): Promise<boolean> {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeoutMs);
    try {
      const response = await fetch(`${webhookUrl}/alert`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Authorization: `Bearer ${webhookToken}`,
        },
        body: JSON.stringify({ source: 'alert-consumer', alert }),
        signal: controller.signal,
      });
      if (!response.ok) {
        logger.warn(
          { status: response.status, fingerprint: alert.fingerprint },
          'ops-fleet: alert forward non-2xx (Slack post still proceeded)',
        );
      }
      return response.ok;
    } catch (err) {
      // Non-fatal: Layer 1 (Slack) is still the primary surface for the alert.
      logger.error(
        { err: err instanceof Error ? err.message : String(err), fingerprint: alert.fingerprint },
        'ops-fleet: alert forward failed (Slack post still proceeded)',
      );
      return false;
    } finally {
      clearTimeout(timer);
    }
  };
}
