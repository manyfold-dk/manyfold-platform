import type { App } from '@slack/bolt';
import { logger } from '../logger.js';
import { config } from '../config.js';
import { takePendingApproval } from './approval-store.js';
import {
  restartPod,
  restartDeployment,
  RemediationNotAuthorizedError,
  RemediationHttpError,
} from '../services/backend-client.js';
import {
  formatRemediationApproved,
  formatRemediationResult,
} from '../formatting/remediation-blocks.js';

/**
 * Approves a pending restart.
 *
 * Only users on the approver allowlist may approve. A listed approver may approve their own
 * request: with one operator, a second pair of eyes does not exist, and the allowlist is the
 * control (review finding S1). The approval is taken out of the store before anything runs,
 * so a double click acts once.
 */
export function registerApproveAction(app: App): void {
  const approvers = new Set(config.approval.approverUserIds);

  app.action('remediation_approve', async ({ action, ack, body, client, respond }) => {
    await ack();

    if (action.type !== 'button' || !action.value) return;
    const approvalId = action.value;
    const approvedBy = body.user.id;

    if (!approvers.has(approvedBy)) {
      logger.warn({ approvalId, approvedBy }, 'Remediation approval rejected: not an approver');
      await respond({
        response_type: 'ephemeral',
        text: ':no_entry: You are not authorised to approve platform remediations.',
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

    logger.info({ approvalId, approvedBy }, 'Remediation approved');

    // Best effort: the approval is already taken, so a failed message update must not stop
    // the approved action from running.
    try {
      await client.chat.update({
        channel: approval.channelId,
        ts: approval.messageTs,
        blocks: formatRemediationApproved(
          approval.action,
          approval.target,
          approval.namespace,
          approval.requestedBy,
          approvedBy,
        ),
        text: `Remediation approved by <@${approvedBy}>`,
      });
    } catch (err) {
      logger.warn(
        { err, approvalId },
        'Could not mark the request approved in Slack; restarting anyway',
      );
    }

    try {
      const result =
        approval.resourceType === 'pod'
          ? await restartPod(approval.namespace, approval.target)
          : await restartDeployment(approval.namespace, approval.target);

      await client.chat.postMessage({
        channel: approval.channelId,
        thread_ts: approval.messageTs,
        blocks: formatRemediationResult(
          result.success,
          approval.action,
          approval.target,
          approval.namespace,
          result.message,
        ),
        text: result.success ? 'Remediation succeeded' : 'Remediation failed',
      });
    } catch (err) {
      const refused = err instanceof RemediationNotAuthorizedError;
      logger.error({ err, approvalId }, 'Remediation execution failed');
      await client.chat.postMessage({
        channel: approval.channelId,
        thread_ts: approval.messageTs,
        text: refused
          ? ':no_entry: The backend refused the restart: this bot has no identity it accepts for restarts. Use the platform health page.'
          : err instanceof RemediationHttpError && err.status < 500
            ? `:x: The backend refused the request (HTTP ${err.status}): the restart did not run.`
            : ':warning: No clear answer from the backend. The restart may have happened; check the workload before retrying.',
      });
    }
  });
}
