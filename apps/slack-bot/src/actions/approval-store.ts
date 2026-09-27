import { config } from '../config.js';
import type { KnownBlock } from '@slack/types';
import { logger } from '../logger.js';
import { BoundedSet } from '../services/bounded.js';
import { formatRemediationExpired } from '../formatting/remediation-blocks.js';
import type { PendingApproval } from '../types/index.js';

interface ApprovalInput {
  action: string;
  target: string;
  namespace: string;
  resourceType: string;
  requestedBy: string;
  channelId: string;
  /** Set once the request's message is posted (see attachMessage). */
  messageTs?: string;
}

export interface StoredApproval extends PendingApproval {
  resourceType: string;
}

const pendingApprovals = new Map<string, StoredApproval>();
// Approvals a click has taken, so a late post can tell "handled" from "expired".
const handled = new BoundedSet<string>(1_000);
let counter = 0;

export function addPendingApproval(input: ApprovalInput): string {
  const id = `approval-${Date.now()}-${++counter}`;
  pendingApprovals.set(id, {
    id,
    action: input.action,
    target: input.target,
    namespace: input.namespace,
    resourceType: input.resourceType,
    requestedBy: input.requestedBy,
    channelId: input.channelId,
    messageTs: input.messageTs ?? '',
    createdAt: Date.now(),
  });
  logger.info({ approvalId: id, action: input.action, target: input.target }, 'Approval created');
  return id;
}

/**
 * Records the message that carries an approval's buttons, once it is posted. When the approval
 * is already gone, says whether a click handled it or it expired while the post was under way.
 */
export function attachMessage(id: string, messageTs: string): 'attached' | 'handled' | 'expired' {
  const approval = pendingApprovals.get(id);
  if (approval) {
    approval.messageTs = messageTs;
    return 'attached';
  }
  return handled.has(id) ? 'handled' : 'expired';
}

/** Drops an approval whose message could not be posted. */
export function removePendingApproval(id: string): void {
  pendingApprovals.delete(id);
}

export function getPendingApproval(id: string): StoredApproval | undefined {
  return pendingApprovals.get(id);
}

function expiryCutoff(): number {
  return Date.now() - config.approval.timeoutSeconds * 1000;
}

/**
 * Returns the approval and removes it in one step, so a second click on the same button
 * finds nothing and cannot run the action twice. An expired approval is refused here too (the
 * cleanup sweep runs once a minute and would otherwise leave a late click a window), but stays
 * in the store: the sweep still has to mark its message expired.
 */
export function takePendingApproval(id: string): StoredApproval | undefined {
  const approval = pendingApprovals.get(id);
  if (!approval || approval.createdAt < expiryCutoff()) return undefined;
  pendingApprovals.delete(id);
  handled.add(id);
  return approval;
}

export function getExpiredApprovals(): StoredApproval[] {
  const cutoff = expiryCutoff();
  const expired: StoredApproval[] = [];
  for (const [, approval] of pendingApprovals) {
    if (approval.createdAt < cutoff) {
      expired.push(approval);
    }
  }
  return expired;
}

/** The part of the Slack Web API client the sweep uses. */
interface MessageUpdater {
  chat: {
    update(args: {
      channel: string;
      ts: string;
      blocks: KnownBlock[];
      text: string;
    }): Promise<unknown>;
  };
}

/**
 * Once a minute, drops expired approvals and marks their messages expired, so the buttons of a
 * dead request disappear. The message update is best effort: the approval is gone either way.
 */
export function startApprovalCleanup(client: MessageUpdater): void {
  setInterval(() => {
    for (const approval of getExpiredApprovals()) {
      pendingApprovals.delete(approval.id);
      logger.info({ approvalId: approval.id }, 'Expired approval removed');
      if (!approval.messageTs) continue;
      client.chat
        .update({
          channel: approval.channelId,
          ts: approval.messageTs,
          blocks: formatRemediationExpired(
            approval.action,
            approval.target,
            approval.namespace,
            approval.requestedBy,
          ),
          text: 'Remediation request expired',
        })
        .catch((err: unknown) =>
          logger.warn({ err, approvalId: approval.id }, 'Could not mark approval expired'),
        );
    }
  }, 60_000);
}
