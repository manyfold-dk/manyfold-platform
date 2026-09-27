import type { App } from '@slack/bolt';
import { logger } from '../logger.js';
import { postApprovalRequest } from './request-approval.js';

export function registerAlertAcknowledgeAction(app: App): void {
  app.action('remediate_pod_restart', async ({ action, ack, body, client }) => {
    await ack();

    if (action.type !== 'button' || !action.value) return;

    const { namespace, pod, alertFingerprint } = JSON.parse(action.value) as {
      namespace: string;
      pod: string;
      alertFingerprint: string;
    };

    logger.info(
      { user: body.user.id, namespace, pod, alertFingerprint },
      'Alert remediation button clicked',
    );

    const channelId = body.channel?.id;
    if (!channelId) return;

    // The same approval flow as /remediate.
    try {
      await postApprovalRequest(client, {
        channelId,
        requestedBy: body.user.id,
        action: 'Restart pod',
        target: pod,
        namespace,
        resourceType: 'pod',
      });
    } catch (err) {
      logger.error({ err, namespace, pod }, 'Could not post the restart request');
    }
  });

  // No-op handlers for link buttons (Slack requires action handlers for all actions)
  app.action('view_grafana', async ({ ack }) => {
    await ack();
  });
  app.action('view_commit', async ({ ack }) => {
    await ack();
  });
  app.action('view_pipeline', async ({ ack }) => {
    await ack();
  });
  app.action('view_argocd', async ({ ack }) => {
    await ack();
  });
}
