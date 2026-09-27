import type { App } from '@slack/bolt';
import { logger } from '../logger.js';
import { getAlerts } from '../services/backend-client.js';
import { formatAlertList } from '../formatting/alert-blocks.js';

export function registerAlertsCommand(app: App): void {
  app.command('/alerts', async ({ command, ack, respond }) => {
    await ack();
    logger.info({ user: command.user_id, channel: command.channel_id }, '/alerts command received');

    try {
      const alerts = await getAlerts();
      await respond({
        response_type: 'ephemeral',
        blocks: formatAlertList(alerts),
      });
    } catch (err) {
      logger.error({ err }, '/alerts command failed');
      await respond({
        response_type: 'ephemeral',
        text: ':x: Backend unreachable, try again shortly.',
      });
    }
  });
}
