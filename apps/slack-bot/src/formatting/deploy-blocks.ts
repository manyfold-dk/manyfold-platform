import type { KnownBlock } from '@slack/types';
import { config } from '../config.js';
import type { DeploymentEvent } from '../types/index.js';

export function formatDeploySuccess(event: DeploymentEvent): KnownBlock[] {
  const shortSha = event.gitRevision.slice(0, 7);

  const details = [
    `*Pipeline:* ${event.pipeline}`,
    `*Environment:* ${event.environment.toUpperCase()}`,
    event.duration ? `*Duration:* ${event.duration}` : null,
    `*Commit:* ${shortSha}`,
    `*Image:* ${event.imageTag}`,
  ]
    .filter(Boolean)
    .join('\n');

  const blocks: KnownBlock[] = [
    {
      type: 'header',
      text: { type: 'plain_text', text: ':white_check_mark: Pipeline succeeded' },
    },
    { type: 'divider' },
    {
      type: 'section',
      text: { type: 'mrkdwn', text: details },
    },
  ];

  const actions = buildDeployActions(event);
  if (actions) blocks.push(actions);

  return blocks;
}

/** A run that did not succeed: failed, or cancelled (which the header tells apart). */
export function formatDeployFailure(event: DeploymentEvent): KnownBlock[] {
  const shortSha = event.gitRevision.slice(0, 7);

  const details = [
    `*Pipeline:* ${event.pipeline}`,
    `*Environment:* ${event.environment.toUpperCase()}`,
    event.duration ? `*Duration:* ${event.duration}` : null,
    `*Commit:* ${shortSha}`,
  ]
    .filter(Boolean)
    .join('\n');

  const blocks: KnownBlock[] = [
    {
      type: 'header',
      text: {
        type: 'plain_text',
        text:
          event.status === 'cancelled'
            ? ':heavy_minus_sign: Pipeline cancelled'
            : ':x: Pipeline failed',
      },
    },
    { type: 'divider' },
    {
      type: 'section',
      text: { type: 'mrkdwn', text: details },
    },
  ];

  const actions = buildDeployActions(event);
  if (actions) blocks.push(actions);

  return blocks;
}

function buildDeployActions(event: DeploymentEvent): KnownBlock | null {
  const elements: unknown[] = [];

  if (event.gitUrl) {
    const commitUrl = `${event.gitUrl.replace('.git', '')}/commit/${event.gitRevision}`;
    elements.push({
      type: 'button',
      text: { type: 'plain_text', text: 'View Commit' },
      url: commitUrl,
      action_id: 'view_commit',
    });
  }

  if (event.status === 'succeeded' && config.urls.argocd) {
    const argoUrl = `${config.urls.argocd}/applications/${event.pipeline}`;
    elements.push({
      type: 'button',
      text: { type: 'plain_text', text: 'View in ArgoCD' },
      url: argoUrl,
      action_id: 'view_argocd',
    });
  }

  if (elements.length === 0) return null;

  return {
    type: 'actions',
    elements,
  } as KnownBlock;
}
