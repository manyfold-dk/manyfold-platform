// ops-approve.ts -- Layer-2 (ops-external) approval action handler.
//
// Runs alongside the existing remediation-approve.ts (Layer 1, platform-internal).
// Action ID is intentionally separate (`ops_approve`) so the two layers stay
// distinguishable in audit trails.

import type { App } from '@slack/bolt';
import { logger } from '../logger.js';
import { config } from '../config.js';
import { claimOpsDecision, releaseOpsDecision } from './ops-decisions.js';

/** Connection errors raised before a request is sent: the approval cannot have arrived. */
const NOT_SENT = new Set(['ECONNREFUSED', 'ENOTFOUND', 'EAI_AGAIN', 'EHOSTUNREACH', 'ENETUNREACH']);

/** Said when a refused approval could not be cleared, so the operator is not surprised later. */
const NOT_RELEASED =
  ' A retry may be refused as already approved: the decision could not be cleared.';

export function registerOpsApproveAction(app: App): void {
  const approverSet = new Set(config.opsFleet.approverUserIds);

  app.action('ops_approve', async ({ action, ack, body, client, respond }) => {
    await ack();

    const userId = body.user?.id ?? '';
    const actionId = action.type === 'button' ? (action.value ?? '') : '';

    if (!approverSet.has(userId)) {
      logger.warn({ userId, actionId }, 'ops-approve: rejected -- user not in allowlist');
      await respond({
        response_type: 'ephemeral',
        text: ':no_entry: You are not authorised to approve ops fleet actions.',
      });
      return;
    }

    if (!actionId) {
      logger.error({ userId }, 'ops-approve: missing action_id');
      return;
    }

    const claim = await claimOpsDecision(actionId, 'approved');

    /**
     * Releases this approval after it provably did not reach the host. If a Reject was clicked
     * meanwhile, that rejection now stands, and the card says so for everyone.
     */
    const releaseAndMark = async (id: string, c: typeof claim): Promise<boolean> => {
      const outcome = await releaseOpsDecision(id, c);
      if (outcome.rejectedInstead && 'message' in body && body.message && body.channel) {
        const channel = typeof body.channel === 'string' ? body.channel : body.channel.id;
        await client.chat
          .update({
            channel,
            ts: body.message.ts,
            text: `Rejected: ${id}`,
            blocks: [
              {
                type: 'section',
                text: {
                  type: 'mrkdwn',
                  text: `*Rejected (ops)* -- action: \`${id}\`. The approval did not reach the host; the rejection clicked meanwhile stands.`,
                },
              },
            ],
          })
          .catch(() => {});
      }
      return outcome.cleared;
    };
    const { standing, isNew } = claim;
    if (!isNew) {
      await respond({
        response_type: 'ephemeral',
        text:
          standing === 'unavailable'
            ? ':warning: The approval was not sent: the bot could not record it (Redis unavailable), so it cannot rule out an earlier rejection. Try again shortly.'
            : `This action was already ${standing}; nothing was sent.`,
      });
      return;
    }

    if (!config.opsFleet.webhookUrl || !config.opsFleet.webhookToken) {
      const released = await releaseAndMark(actionId, claim);
      await respond({
        response_type: 'ephemeral',
        text: `:warning: ops-fleet webhook is not configured on this bot.${released ? '' : NOT_RELEASED}`,
      });
      return;
    }

    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), config.opsFleet.timeoutMs);
    let response: Response;
    try {
      response = await fetch(
        `${config.opsFleet.webhookUrl}/approval/${encodeURIComponent(actionId)}`,
        {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            Authorization: `Bearer ${config.opsFleet.webhookToken}`,
          },
          body: JSON.stringify({ approved_by_id: userId }),
          signal: controller.signal,
        },
      );
    } catch (err) {
      // Only an error before any byte left proves non-delivery (the host refused or could not
      // be found). A timeout or a connection lost mid-request may follow a delivered approval,
      // so the outcome is unknown: saying otherwise invites a second approval.
      const code = (err as { cause?: { code?: string } }).cause?.code;
      const notSent = !controller.signal.aborted && code !== undefined && NOT_SENT.has(code);
      const released = notSent ? await releaseAndMark(actionId, claim) : true;
      logger.error(
        { err: err instanceof Error ? err.message : String(err), actionId, code },
        'ops-approve: webhook failed',
      );
      await respond({
        response_type: 'ephemeral',
        text: notSent
          ? `:warning: Approval webhook unreachable. The VPS did not receive the approval.${released ? '' : NOT_RELEASED}`
          : ':warning: No answer from the VPS. The approval may have arrived; check the VPS before approving again.',
      });
      return;
    } finally {
      clearTimeout(timer);
    }

    if (!response.ok) {
      logger.error({ status: response.status, actionId }, 'ops-approve: webhook non-2xx');
      const released = response.status < 500 ? await releaseAndMark(actionId, claim) : true;
      // A 4xx is the VPS refusing the request. A 5xx may come after it committed the approval,
      // so it is an unknown outcome, like a lost connection.
      await respond({
        response_type: 'ephemeral',
        text:
          response.status < 500
            ? `:warning: The VPS refused the approval (HTTP ${response.status}). The action was not approved.${released ? '' : NOT_RELEASED}`
            : `:warning: The VPS answered HTTP ${response.status}. The approval may have arrived; check the VPS before approving again.`,
      });
      return;
    }

    // The approval is delivered. Marking the message is best effort, and a failure here must not
    // read as a failed approval: that would invite a second one.
    if ('message' in body && body.message && body.channel) {
      const channel = typeof body.channel === 'string' ? body.channel : body.channel.id;
      try {
        await client.chat.update({
          channel,
          ts: body.message.ts,
          text: `Approved by <@${userId}>: ${actionId}`,
          blocks: [
            {
              type: 'section',
              text: {
                type: 'mrkdwn',
                text: `*Approved (ops)* by <@${userId}> -- action: \`${actionId}\``,
              },
            },
          ],
        });
      } catch (err) {
        logger.warn(
          { err: err instanceof Error ? err.message : String(err), actionId },
          'ops-approve: approved, but the message could not be updated',
        );
        await respond({
          response_type: 'ephemeral',
          text: `Approved: \`${actionId}\` reached the VPS. The message could not be updated.`,
        });
      }
    }
  });
}
