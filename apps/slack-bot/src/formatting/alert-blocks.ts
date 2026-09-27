import type { KnownBlock } from '@slack/types';
import { clip } from './text.js';
import { config } from '../config.js';
import type { AlertEvent, StoredAlert } from '../types/index.js';

/** Most severe first; unknown severities last. */
const SEVERITY_ORDER = ['critical', 'warning', 'info'];

function severityRank(severity: string): number {
  const i = SEVERITY_ORDER.indexOf(severity);
  return i === -1 ? SEVERITY_ORDER.length : i;
}

const SEVERITY_EMOJI: Record<string, string> = {
  critical: ':red_circle:',
  warning: ':warning:',
  info: ':information_source:',
};

// A cluster-scoped alert carries no namespace label; the stream then holds an empty value. Every
// namespace line and fragment below is left out for it rather than printed empty.
const inNamespace = (alert: AlertEvent) => (alert.namespace ? ` in ${alert.namespace}` : '');

/** The message's `text`: the notification and the fallback where blocks are not shown. */
export function alertFiringText(alert: AlertEvent): string {
  return `${alert.severity.toUpperCase()}: ${alert.alertName}${inNamespace(alert)}`;
}

export function alertResolvedText(alert: AlertEvent): string {
  return `RESOLVED: ${alert.alertName}${inNamespace(alert)}`;
}

export function formatAlertFiring(alert: AlertEvent): KnownBlock[] {
  const emoji = SEVERITY_EMOJI[alert.severity] ?? ':white_circle:';
  const severityLabel = alert.severity.toUpperCase();

  const details = [
    alert.namespace ? `*Namespace:* ${alert.namespace}` : null,
    alert.pod ? `*Pod:* ${alert.pod}` : null,
    alert.deployment ? `*Deployment:* ${alert.deployment}` : null,
    `*Summary:* ${clip(alert.summary ?? '', 1_000)}`,
  ]
    .filter(Boolean)
    .join('\n');

  const blocks: KnownBlock[] = [
    {
      type: 'header',
      text: {
        type: 'plain_text',
        text: clip(`${emoji} ${severityLabel}  ${alert.alertName}`, 150),
      },
    },
    { type: 'divider' },
    {
      type: 'section',
      text: { type: 'mrkdwn', text: details },
    },
  ];

  const actions: KnownBlock = {
    type: 'actions',
    elements: [],
  };

  if (alert.pod) {
    (actions as { type: 'actions'; elements: unknown[] }).elements.push({
      type: 'button',
      text: { type: 'plain_text', text: 'Restart Pod' },
      action_id: 'remediate_pod_restart',
      value: JSON.stringify({
        namespace: alert.namespace,
        pod: alert.pod,
        alertFingerprint: alert.fingerprint,
      }),
      style: 'danger',
    });
  }

  if (config.urls.grafana) {
    const target = alert.pod ?? alert.deployment ?? alert.namespace;
    const grafanaUrl = `${config.urls.grafana}/d/k8s-pods?var-namespace=${alert.namespace}&var-pod=${target}`;
    (actions as { type: 'actions'; elements: unknown[] }).elements.push({
      type: 'button',
      text: { type: 'plain_text', text: 'View in Grafana' },
      url: grafanaUrl,
      action_id: 'view_grafana',
    });
  }

  if ((actions as { type: 'actions'; elements: unknown[] }).elements.length > 0) {
    blocks.push(actions);
  }

  return blocks;
}

export function formatAlertResolved(alert: AlertEvent, duration?: string): KnownBlock[] {
  const details = [
    alert.namespace ? `*Namespace:* ${alert.namespace}` : null,
    alert.pod ? `*Pod:* ${alert.pod}` : null,
    alert.deployment ? `*Deployment:* ${alert.deployment}` : null,
    duration ? `*Duration:* ${duration}` : null,
  ]
    .filter(Boolean)
    .join('\n');

  const blocks: KnownBlock[] = [
    {
      type: 'header',
      text: {
        type: 'plain_text',
        text: clip(`:white_check_mark: RESOLVED  ${alert.alertName}`, 150),
      },
    },
  ];
  // Slack refuses a section with empty text, and a refused post is retried without end.
  if (details) {
    blocks.push({ type: 'divider' }, { type: 'section', text: { type: 'mrkdwn', text: details } });
  }
  return blocks;
}

/** At most this many alerts are listed in one message. */
export const MAX_LISTED_ALERTS = 45;

export function formatAlertList(alerts: StoredAlert[]): KnownBlock[] {
  if (alerts.length === 0) {
    return [
      {
        type: 'section',
        text: { type: 'mrkdwn', text: ':white_check_mark: No active alerts' },
      },
    ];
  }

  const blocks: KnownBlock[] = [
    {
      type: 'header',
      text: { type: 'plain_text', text: `Active Alerts (${alerts.length})` },
    },
    { type: 'divider' },
  ];

  // Slack refuses a message over 50 blocks; the header and divider take two, and one is kept
  // for the overflow note.
  const listed = [...alerts]
    .sort((x, y) => severityRank(x.severity) - severityRank(y.severity))
    .slice(0, MAX_LISTED_ALERTS);
  for (const alert of listed) {
    const emoji = SEVERITY_EMOJI[alert.severity] ?? ':white_circle:';
    const age = formatAge(alert.startsAt);
    const heading = [`${emoji} *${clip(alert.alertName, 100)}*`, alert.namespace, age]
      .filter(Boolean)
      .join(' — ');

    blocks.push({
      type: 'section',
      text: {
        type: 'mrkdwn',
        text: `${heading}\n${clip(alert.summary ?? '', 300)}`,
      },
    });
  }

  if (alerts.length > listed.length) {
    blocks.push({
      type: 'context',
      elements: [
        {
          type: 'mrkdwn',
          text: `…and ${alerts.length - listed.length} more. The full list is on the platform page.`,
        },
      ],
    });
  }

  return blocks;
}

function formatAge(isoTimestamp: string): string {
  const diff = Date.now() - new Date(isoTimestamp).getTime();
  const minutes = Math.floor(diff / 60_000);
  if (minutes < 60) return `${minutes}m ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}h ago`;
  return `${Math.floor(hours / 24)}d ago`;
}
