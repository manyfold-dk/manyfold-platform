import type { App } from '@slack/bolt';
import { logger } from '../logger.js';
import { config } from '../config.js';
import { getPendingApproval, takePendingApproval } from './approval-store.js';
import { formatRemediationRejected } from '../formatting/remediation-blocks.js';

/**
 * Handles "Reject" on a remediation approval. An approver or the requester may reject; anyone
 * else in the channel could otherwise cancel a restart they have no say over.
 */
export function registerRejectAction(app: App): void {
  const approvers = new Set(config.approval.approverUserIds);

  app.action('remediation_reject', async ({ action, ack, body, client, respond }) => {
    await ack();

    if (action.type !== 'button' || !action.value) return;
    const approvalId = action.value;
    const rejectedBy = body.user.id;

    const pending = getPendingApproval(approvalId);
    if (pending && !approvers.has(rejectedBy) && pending.requestedBy !== rejectedBy) {
      logger.warn({ approvalId, rejectedBy }, 'Remediation rejection refused: not an approver');
      await respond({
        response_type: 'ephemeral',
        text: 'Only an approver or the requester can reject this request.',
      });
      return;
    }

    const approval = takePendingApproval(approvalId);
    // A click can arrive before the post that created the card has returned; the click itself
    // carries the card's timestamp.
    if (approval && !approval.messageTs && 'message' in body && body.message?.ts) {
      approval.messageTs = body.message.ts;
    }
    if (!approval) {
      logger.warn({ approvalId }, 'Approval not found (expired or already handled)');
      await respond({
        response_type: 'ephemeral',
        text: 'This request has expired or was already handled.',
      });
      return;
    }

    logger.info({ approvalId, rejectedBy }, 'Remediation rejected');

    await client.chat.update({
      channel: approval.channelId,
      ts: approval.messageTs,
      blocks: formatRemediationRejected(
        approval.action,
        approval.target,
        approval.namespace,
        approval.requestedBy,
        rejectedBy,
      ),
      text: `Remediation rejected by <@${rejectedBy}>`,
    });
  });
}
