// ops-reject.ts -- Layer-2 rejection action handler.
//
// Rejections don't need to reach the VPS -- the approval file simply never
// gets written and the executor times out naturally. The decision is recorded
// (ops-decisions.ts), so a later Approve on the same card cannot send it.

import type { App } from '@slack/bolt';
import { logger } from '../logger.js';
import { config } from '../config.js';
import { claimOpsDecision } from './ops-decisions.js';

export function registerOpsRejectAction(app: App): void {
  const approverSet = new Set(config.opsFleet.approverUserIds);

  app.action('ops_reject', async ({ action, ack, body, client, respond }) => {
    await ack();

    const userId = body.user?.id ?? '';
    const actionId = action.type === 'button' ? (action.value ?? '') : '';

    if (!approverSet.has(userId)) {
      logger.warn({ userId, actionId }, 'ops-reject: rejected -- user not in allowlist');
      await respond({
        response_type: 'ephemeral',
        text: ':no_entry: You are not authorised to reject ops fleet actions.',
      });
      return;
    }

    const { standing, isNew, unconfirmed } = await claimOpsDecision(actionId, 'rejected');
    if (!isNew) {
      // Already decided: the card keeps the name of whoever decided.
      await respond({
        response_type: 'ephemeral',
        text:
          standing === 'rejected'
            ? 'This action was already rejected.'
            : standing === 'approved'
              ? 'This action was already approved. If that approval turns out not to have reached the host, your rejection takes its place.'
              : `This action was already ${standing}; it cannot be rejected any more.`,
      });
      return;
    }

    if ('message' in body && body.message && body.channel) {
      const channel = typeof body.channel === 'string' ? body.channel : body.channel.id;
      try {
        await client.chat.update({
          channel,
          ts: body.message.ts,
          text: `Rejected by <@${userId}>: ${actionId}${unconfirmed ? ' (recorded in this bot only)' : ''}`,
          blocks: [
            {
              type: 'section',
              text: {
                type: 'mrkdwn',
                text: `*Rejected (ops)* by <@${userId}> -- action: \`${actionId}\`${unconfirmed ? '\n_Recorded in this bot only: the shared store did not answer, so another bot process may not see it._' : ''}`,
              },
            },
          ],
        });
      } catch (err) {
        // The rejection is recorded; only the card is stale. Say so, since it still shows Approve.
        logger.warn(
          { err: err instanceof Error ? err.message : String(err), actionId },
          'ops-reject: rejected, but the message could not be updated',
        );
        await respond({
          response_type: 'ephemeral',
          text: `Rejected: \`${actionId}\` stands as rejected. The message could not be updated; its Approve button will be refused.`,
        });
      }
    }
  });
}
