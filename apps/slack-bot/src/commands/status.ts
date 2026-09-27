import type { App } from '@slack/bolt';
import { logger } from '../logger.js';
import { getHealthSummary } from '../services/backend-client.js';
import { formatStatusLine } from '../formatting/health-blocks.js';

export function registerStatusCommand(app: App): void {
  app.command('/platformstatus', async ({ command, ack, respond }) => {
    await ack();
    logger.info(
      { user: command.user_id, channel: command.channel_id },
      '/platformstatus command received',
    );

    try {
      const summary = await getHealthSummary();
      await respond({
        response_type: 'ephemeral',
        text: formatStatusLine(summary),
      });
    } catch (err) {
      logger.error({ err }, '/platformstatus command failed');
      await respond({
        response_type: 'ephemeral',
        text: ':x: Backend unreachable, try again shortly.',
      });
    }
  });
}
