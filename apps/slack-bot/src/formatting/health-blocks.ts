import type { KnownBlock } from '@slack/types';
import { clip } from './text.js';
import type { HealthResponse, HealthSummary } from '../types/index.js';

const STATUS_EMOJI: Record<string, string> = {
  healthy: ':large_green_circle:',
  degraded: ':large_yellow_circle:',
  unhealthy: ':red_circle:',
};

export function formatStatusLine(summary: HealthSummary): string {
  const emoji = STATUS_EMOJI[summary.overall] ?? ':white_circle:';
  const overallLabel =
    summary.overall === 'healthy'
      ? 'Healthy'
      : summary.overall === 'degraded'
        ? 'Degraded'
        : 'Unhealthy';

  return [
    `${emoji} Platform ${overallLabel}`,
    `Infra: ${summary.infrastructure}`,
    `Cluster: ${summary.cluster}`,
    `Platform: ${summary.platform}`,
    `Pipelines: ${summary.pipelines}`,
    `Apps: ${summary.applications}`,
    `${summary.activeAlerts} active alerts`,
  ].join(' | ');
}

export function formatHealthBlocks(health: HealthResponse): KnownBlock[] {
  const blocks: KnownBlock[] = [
    {
      type: 'header',
      text: { type: 'plain_text', text: 'Platform Health Report' },
    },
    { type: 'divider' },
  ];

  for (const layer of health.layers) {
    const emoji = STATUS_EMOJI[layer.status] ?? ':white_circle:';
    blocks.push({
      type: 'section',
      text: {
        type: 'mrkdwn',
        text: `${emoji} *${layer.name}*\n${layer.details}`,
      },
    });
  }

  if (health.activeAlerts > 0) {
    blocks.push(
      { type: 'divider' },
      {
        type: 'section',
        text: {
          type: 'mrkdwn',
          text: `:warning: *Active Alerts (${health.activeAlerts})*`,
        },
      },
    );
  }

  return blocks;
}

/** One deployment in the digest window. */
export interface DigestDeployment {
  pipeline: string;
  imageTag: string;
  status: string;
  timestamp: string;
}

export function formatDigestBlocks(
  health: HealthResponse,
  alertSummary: { active: number; critical: number; warning: number; info: number },
  remediationSummary: { recentActions: number; status: string },
  deployments: { shown: DigestDeployment[]; total: number; capped?: boolean },
  period: 'Morning' | 'Evening',
  timeZone = 'UTC',
): KnownBlock[] {
  const date = new Date().toLocaleDateString('en-US', {
    month: 'short',
    day: 'numeric',
    year: 'numeric',
    timeZone,
  });
  const emoji = STATUS_EMOJI[health.overall] ?? ':white_circle:';
  const overallLabel = health.overall.charAt(0).toUpperCase() + health.overall.slice(1);

  const blocks: KnownBlock[] = [
    {
      type: 'header',
      text: {
        type: 'plain_text',
        text: `${period === 'Morning' ? ':sunny:' : ':city_sunset:'} ${period} Platform Digest — ${date}`,
      },
    },
    { type: 'divider' },
    {
      type: 'section',
      text: {
        type: 'mrkdwn',
        text: `*Overall:* ${emoji} ${overallLabel}`,
      },
    },
  ];

  for (const layer of health.layers) {
    const layerEmoji = STATUS_EMOJI[layer.status] ?? ':white_circle:';
    blocks.push({
      type: 'section',
      text: {
        type: 'mrkdwn',
        text: `${layerEmoji} *${layer.name}*    ${layer.details}`,
      },
    });
  }

  blocks.push(
    { type: 'divider' },
    {
      type: 'section',
      text: {
        type: 'mrkdwn',
        text: [
          '*Alerts firing now*',
          `  ${alertSummary.active} active  |  critical ${alertSummary.critical}  |  warning ${alertSummary.warning}  |  info ${alertSummary.info}`,
          '',
          '*Remediations*',
          `  Recent actions: ${remediationSummary.recentActions}  |  Status: ${remediationSummary.status}`,
        ].join('\n'),
      },
    },
  );

  if (deployments.shown.length > 0) {
    // The newest few, each line bounded, so the section stays far below Slack's 3,000-character
    // limit however busy the window was.
    const deployLines = deployments.shown.map((d) => {
      // In the digest's own time zone, the one its schedule is read in.
      const time = new Date(d.timestamp).toLocaleTimeString('en-GB', {
        hour: '2-digit',
        minute: '2-digit',
        hour12: false,
        timeZone,
      });
      const icon = d.status === 'succeeded' ? ':white_check_mark:' : ':x:';
      return `${icon} ${clip(d.pipeline, 40)} — ${clip(d.imageTag, 40)} (${time})`;
    });
    const more = deployments.total - deployments.shown.length;
    if (more > 0) deployLines.push(`…and ${more}${deployments.capped ? '+' : ''} earlier`);
    blocks.push({
      type: 'section',
      text: {
        type: 'mrkdwn',
        text: `*Deployments since the last digest*\n${deployLines.join('\n')}`,
      },
    });
  }

  return blocks;
}
