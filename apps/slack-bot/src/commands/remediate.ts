import type { App } from '@slack/bolt';
import { postApprovalRequest } from '../actions/request-approval.js';
import { logger } from '../logger.js';

const USAGE =
  'Usage: `/remediate restart pod <namespace> <pod>` or `/remediate restart deployment <namespace> <name>`';

export function registerRemediateCommand(app: App): void {
  app.command('/remediate', async ({ command, ack, respond }) => {
    await ack();
    logger.info(
      { user: command.user_id, channel: command.channel_id, text: command.text },
      '/remediate command received',
    );

    const parts = command.text.trim().split(/\s+/);

    if (parts.length < 4 || parts[0] !== 'restart') {
      await respond({ response_type: 'ephemeral', text: USAGE });
      return;
    }

    const [, resourceType, namespace, target] = parts;

    if (resourceType !== 'pod' && resourceType !== 'deployment') {
      await respond({
        response_type: 'ephemeral',
        text: `Unknown resource type \`${resourceType}\`. ${USAGE}`,
      });
      return;
    }

    const action = `Restart ${resourceType}`;

    try {
      await postApprovalRequest(app.client, {
        channelId: command.channel_id,
        requestedBy: command.user_id,
        action,
        target,
        namespace,
        resourceType,
      });
    } catch (err) {
      logger.error({ err }, '/remediate command failed');
      await respond({
        response_type: 'ephemeral',
        text: ':x: Failed to post remediation request. Make sure the bot is invited to this channel.',
      });
    }
  });
}
