import { describe, it, expect } from 'vitest';
import type { AlertEvent, StoredAlert } from '../../src/types/index.js';

// Set env vars before importing modules that read config
process.env.GRAFANA_BASE_URL = 'http://grafana.test';

const {
  alertFiringText,
  alertResolvedText,
  formatAlertFiring,
  formatAlertResolved,
  formatAlertList,
  MAX_LISTED_ALERTS,
} = await import('../../src/formatting/alert-blocks.js');

/** A cluster-scoped alert: the stream carries its namespace as an empty value. */
const clusterScoped: AlertEvent = {
  fingerprint: 'drift',
  status: 'firing',
  alertName: 'TenantDedupDriftStale',
  severity: 'warning',
  namespace: '',
  summary: 'Drift report is stale',
  timestamp: new Date().toISOString(),
};

const sectionTexts = (blocks: unknown[]) =>
  (blocks as { type: string; text?: { text: string } }[])
    .filter((b) => b.type === 'section')
    .map((b) => b.text!.text);

describe('alerts without a namespace', () => {
  it('omits the namespace line from a firing alert', () => {
    const [details] = sectionTexts(formatAlertFiring(clusterScoped));
    expect(details).not.toContain('Namespace');
    expect(details).toContain('Drift report is stale');
  });

  it('omits the namespace from the text fallbacks', () => {
    expect(alertFiringText(clusterScoped)).toBe('WARNING: TenantDedupDriftStale');
    expect(alertResolvedText(clusterScoped)).toBe('RESOLVED: TenantDedupDriftStale');
  });

  it('still names a namespace that is present', () => {
    const alert = { ...clusterScoped, namespace: 'tenant-a' };
    expect(alertFiringText(alert)).toBe('WARNING: TenantDedupDriftStale in tenant-a');
    expect(alertResolvedText(alert)).toBe('RESOLVED: TenantDedupDriftStale in tenant-a');
    expect(sectionTexts(formatAlertFiring(alert))[0]).toContain('*Namespace:* tenant-a');
  });

  it('omits the namespace line from a resolution', () => {
    const [details] = sectionTexts(formatAlertResolved(clusterScoped, '5m'));
    expect(details).toBe('*Duration:* 5m');
  });

  it('sends no empty section when a resolution has no details, which Slack would refuse', () => {
    const blocks = formatAlertResolved(clusterScoped);
    expect(blocks).toHaveLength(1);
    expect(blocks[0]).toMatchObject({ type: 'header' });
  });

  it('leaves the namespace out of the alert list instead of an empty fragment', () => {
    const [line] = sectionTexts(
      formatAlertList([
        {
          fingerprint: 'drift',
          alertName: 'TenantDedupDriftStale',
          severity: 'warning',
          namespace: '',
          summary: 's',
          status: 'firing',
          startsAt: new Date().toISOString(),
        },
      ]),
    );
    expect(line).toMatch(/^:warning: \*TenantDedupDriftStale\* — 0m ago\n/);
  });
});

describe('formatAlertFiring', () => {
  it('renders critical alert with red circle', () => {
    const alert: AlertEvent = {
      fingerprint: 'abc123',
      status: 'firing',
      alertName: 'PodCrashLooping',
      severity: 'critical',
      namespace: 'website',
      pod: 'website-backend-abc-xyz',
      summary: 'Pod is crash looping',
      timestamp: new Date().toISOString(),
    };
    const blocks = formatAlertFiring(alert);

    expect(blocks[0]).toMatchObject({ type: 'header' });
    const headerText = (blocks[0] as { type: 'header'; text: { text: string } }).text.text;
    expect(headerText).toContain('CRITICAL');
    expect(headerText).toContain('PodCrashLooping');
  });

  it('renders warning alert with warning emoji', () => {
    const alert: AlertEvent = {
      fingerprint: 'def456',
      status: 'firing',
      alertName: 'HighMemory',
      severity: 'warning',
      namespace: 'website',
      summary: 'Memory usage above 80%',
      timestamp: new Date().toISOString(),
    };
    const blocks = formatAlertFiring(alert);
    const headerText = (blocks[0] as { type: 'header'; text: { text: string } }).text.text;
    expect(headerText).toContain('WARNING');
  });

  it('includes namespace and pod details', () => {
    const alert: AlertEvent = {
      fingerprint: 'abc123',
      status: 'firing',
      alertName: 'PodCrashLooping',
      severity: 'critical',
      namespace: 'website',
      pod: 'backend-pod-1',
      summary: 'Pod restarting',
      timestamp: new Date().toISOString(),
    };
    const blocks = formatAlertFiring(alert);
    const detailBlock = blocks[2] as { type: 'section'; text: { text: string } };
    expect(detailBlock.text.text).toContain('website');
    expect(detailBlock.text.text).toContain('backend-pod-1');
    expect(detailBlock.text.text).toContain('Pod restarting');
  });

  it('includes Restart Pod button when pod is present', () => {
    const alert: AlertEvent = {
      fingerprint: 'abc123',
      status: 'firing',
      alertName: 'PodCrashLooping',
      severity: 'critical',
      namespace: 'website',
      pod: 'backend-pod-1',
      summary: 'Pod restarting',
      timestamp: new Date().toISOString(),
    };
    const blocks = formatAlertFiring(alert);
    const actionsBlock = blocks.find((b) => b.type === 'actions');
    expect(actionsBlock).toBeDefined();

    const elements = (
      actionsBlock as { type: 'actions'; elements: Array<{ text: { text: string } }> }
    ).elements;
    const restartButton = elements.find((e) => e.text.text === 'Restart Pod');
    expect(restartButton).toBeDefined();
  });

  it('includes Grafana button when GRAFANA_BASE_URL is set', () => {
    const alert: AlertEvent = {
      fingerprint: 'abc123',
      status: 'firing',
      alertName: 'PodCrashLooping',
      severity: 'critical',
      namespace: 'website',
      pod: 'backend-pod-1',
      summary: 'Pod restarting',
      timestamp: new Date().toISOString(),
    };
    const blocks = formatAlertFiring(alert);
    const actionsBlock = blocks.find((b) => b.type === 'actions');
    expect(actionsBlock).toBeDefined();

    const elements = (
      actionsBlock as { type: 'actions'; elements: Array<{ text: { text: string }; url?: string }> }
    ).elements;
    const grafanaButton = elements.find((e) => e.text.text === 'View in Grafana');
    expect(grafanaButton).toBeDefined();
    expect(grafanaButton!.url).toContain('grafana.test');
  });

  it('does not include Restart Pod button without pod', () => {
    const alert: AlertEvent = {
      fingerprint: 'abc123',
      status: 'firing',
      alertName: 'HighMemory',
      severity: 'warning',
      namespace: 'website',
      summary: 'Memory high',
      timestamp: new Date().toISOString(),
    };
    const blocks = formatAlertFiring(alert);
    const actionsBlock = blocks.find((b) => b.type === 'actions');
    if (actionsBlock) {
      const elements = (
        actionsBlock as { type: 'actions'; elements: Array<{ text: { text: string } }> }
      ).elements;
      const restartButton = elements.find((e) => e.text.text === 'Restart Pod');
      expect(restartButton).toBeUndefined();
    }
  });
});

describe('formatAlertResolved', () => {
  it('renders resolved header with checkmark', () => {
    const alert: AlertEvent = {
      fingerprint: 'abc123',
      status: 'resolved',
      alertName: 'PodCrashLooping',
      severity: 'critical',
      namespace: 'website',
      pod: 'backend-pod-1',
      summary: 'Pod restarting',
      timestamp: new Date().toISOString(),
    };
    const blocks = formatAlertResolved(alert);
    const headerText = (blocks[0] as { type: 'header'; text: { text: string } }).text.text;
    expect(headerText).toContain('RESOLVED');
    expect(headerText).toContain('PodCrashLooping');
  });

  it('includes duration when provided', () => {
    const alert: AlertEvent = {
      fingerprint: 'abc123',
      status: 'resolved',
      alertName: 'PodCrashLooping',
      severity: 'critical',
      namespace: 'website',
      summary: 'Pod restarting',
      timestamp: new Date().toISOString(),
    };
    const blocks = formatAlertResolved(alert, '15m');
    const detailBlock = blocks[2] as { type: 'section'; text: { text: string } };
    expect(detailBlock.text.text).toContain('15m');
  });
});

describe('formatAlertList', () => {
  it('returns no-alerts message for empty list', () => {
    const blocks = formatAlertList([]);
    expect(blocks.length).toBe(1);
    expect((blocks[0] as { type: 'section'; text: { text: string } }).text.text).toContain(
      'No active alerts',
    );
  });

  it('renders alert list with header and count', () => {
    const alerts: StoredAlert[] = [
      {
        fingerprint: 'a1',
        alertName: 'PodCrashLooping',
        severity: 'critical',
        namespace: 'website',
        pod: 'pod-1',
        summary: 'Pod crashing',
        status: 'firing',
        startsAt: new Date(Date.now() - 30 * 60_000).toISOString(),
      },
      {
        fingerprint: 'a2',
        alertName: 'HighMemory',
        severity: 'warning',
        namespace: 'observability',
        summary: 'Memory at 90%',
        status: 'firing',
        startsAt: new Date(Date.now() - 2 * 3600_000).toISOString(),
      },
    ];
    const blocks = formatAlertList(alerts);

    // Header + divider + 2 alert sections = 4
    expect(blocks.length).toBe(4);

    const header = blocks[0] as { type: 'header'; text: { text: string } };
    expect(header.text.text).toContain('2');

    const firstAlert = blocks[2] as { type: 'section'; text: { text: string } };
    expect(firstAlert.text.text).toContain('PodCrashLooping');
    expect(firstAlert.text.text).toContain(':red_circle:');
    expect(firstAlert.text.text).toContain('30m ago');

    const secondAlert = blocks[3] as { type: 'section'; text: { text: string } };
    expect(secondAlert.text.text).toContain('HighMemory');
    expect(secondAlert.text.text).toContain(':warning:');
    expect(secondAlert.text.text).toContain('2h ago');
  });

  it("stays within Slack's 50-block limit and says how many are not listed", () => {
    const alerts: StoredAlert[] = Array.from({ length: 60 }, (_, i) => ({
      fingerprint: `f${i}`,
      alertName: `Alert${i}`,
      severity: 'warning',
      namespace: 'ns',
      summary: 's',
      status: 'firing',
      startsAt: new Date().toISOString(),
    }));

    const blocks = formatAlertList(alerts);

    expect(blocks.length).toBeLessThanOrEqual(50);
    expect(blocks.length).toBe(2 + MAX_LISTED_ALERTS + 1);
    const note = blocks[blocks.length - 1] as { type: 'context'; elements: { text: string }[] };
    expect(note.type).toBe('context');
    expect(note.elements[0]!.text).toContain(`${60 - MAX_LISTED_ALERTS} more`);
  });

  it('lists critical alerts first, so the cap never hides them', () => {
    const alerts: StoredAlert[] = Array.from({ length: 50 }, (_, i) => ({
      fingerprint: `w${i}`,
      alertName: `Warn${i}`,
      severity: 'warning',
      namespace: 'ns',
      summary: 's',
      status: 'firing',
      startsAt: new Date().toISOString(),
    }));
    alerts.push({
      fingerprint: 'c1',
      alertName: 'NodeDown',
      severity: 'critical',
      namespace: 'ns',
      summary: 's',
      status: 'firing',
      startsAt: new Date().toISOString(),
    });

    const blocks = formatAlertList(alerts);

    const first = blocks[2] as { type: 'section'; text: { text: string } };
    expect(first.text.text).toContain('NodeDown');
  });

  it('clips an overlong summary so Slack accepts the message', () => {
    const blocks = formatAlertFiring({
      fingerprint: 'long',
      status: 'firing',
      alertName: 'A'.repeat(300),
      severity: 'warning',
      namespace: 'ns',
      summary: 'x'.repeat(10_000),
      timestamp: new Date().toISOString(),
    });
    for (const block of blocks) {
      const b = block as { type: string; text?: { text: string } };
      if (b.type === 'header') expect(b.text!.text.length).toBeLessThanOrEqual(150);
      if (b.type === 'section') expect(b.text!.text.length).toBeLessThanOrEqual(3_000);
    }
  });

  it('clips the resolved header too', () => {
    const blocks = formatAlertResolved(
      {
        fingerprint: 'long',
        status: 'resolved',
        alertName: 'B'.repeat(300),
        severity: 'warning',
        namespace: 'ns',
        summary: 's',
        timestamp: new Date().toISOString(),
      },
      '5m',
    );
    const header = blocks[0] as { type: 'header'; text: { text: string } };
    expect(header.text.text.length).toBeLessThanOrEqual(150);
  });
});
