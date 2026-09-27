import type { App } from '@slack/bolt';
import { addPendingApproval, attachMessage, removePendingApproval } from './approval-store.js';
import {
  formatRemediationExpired,
  formatRemediationRequest,
} from '../formatting/remediation-blocks.js';

type SlackClient = App['client'];

interface RequestInput {
  channelId: string;
  requestedBy: string;
  action: string;
  target: string;
  namespace: string;
  resourceType: string;
}

/**
 * Posts a restart request with Approve/Reject buttons. The approval exists before the message,
 * so the buttons carry its real id from the start: there is no placeholder to replace, and no
 * card left with dead buttons when an update fails. If the post fails, the approval goes too.
 */
export async function postApprovalRequest(
  client: SlackClient,
  input: RequestInput,
): Promise<string> {
  const id = addPendingApproval({
    action: input.action,
    target: input.target,
    namespace: input.namespace,
    resourceType: input.resourceType,
    requestedBy: input.requestedBy,
    channelId: input.channelId,
  });
  try {
    const result = await client.chat.postMessage({
      channel: input.channelId,
      blocks: formatRemediationRequest(
        input.action,
        input.target,
        input.namespace,
        input.requestedBy,
        id,
      ),
      text: `Remediation request: ${input.action} ${input.namespace}/${input.target}`,
    });
    if (!result.ts) throw new Error('Slack returned no message timestamp');
    if (attachMessage(id, result.ts) === 'expired') {
      // Expired while Slack was posting: the card must not keep live-looking buttons. (One a
      // click already handled keeps the outcome that click wrote.)
      await client.chat
        .update({
          channel: input.channelId,
          ts: result.ts,
          blocks: formatRemediationExpired(
            input.action,
            input.target,
            input.namespace,
            input.requestedBy,
          ),
          text: 'Remediation request expired',
        })
        .catch(() => {});
    }
    return id;
  } catch (err) {
    removePendingApproval(id);
    throw err;
  }
}
