import type { App } from '@slack/bolt';
import { logger } from '../logger.js';
import { getHealth } from '../services/backend-client.js';
import { formatHealthBlocks } from '../formatting/health-blocks.js';

export function registerHealthCommand(app: App): void {
  app.command('/health', async ({ command, ack, respond }) => {
    await ack();
    logger.info({ user: command.user_id, channel: command.channel_id }, '/health command received');

    try {
      const health = await getHealth();
      await respond({
        response_type: 'ephemeral',
        blocks: formatHealthBlocks(health),
      });
    } catch (err) {
      logger.error({ err }, '/health command failed');
      await respond({
        response_type: 'ephemeral',
        text: ':x: Backend unreachable, try again shortly.',
      });
    }
  });
}
