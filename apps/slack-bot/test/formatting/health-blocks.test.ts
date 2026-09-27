import { describe, it, expect } from 'vitest';
import {
  formatStatusLine,
  formatHealthBlocks,
  formatDigestBlocks,
} from '../../src/formatting/health-blocks.js';
import type { HealthResponse, HealthSummary } from '../../src/types/index.js';

describe('formatStatusLine', () => {
  it('returns healthy status with green circle', () => {
    const summary: HealthSummary = {
      overall: 'healthy',
      infrastructure: 'OK',
      cluster: 'OK',
      platform: 'OK',
      pipelines: 'OK',
      applications: 'OK',
      activeAlerts: 0,
    };
    const result = formatStatusLine(summary);
    expect(result).toContain(':large_green_circle:');
    expect(result).toContain('Platform Healthy');
    expect(result).toContain('0 active alerts');
  });

  it('returns degraded status with yellow circle', () => {
    const summary: HealthSummary = {
      overall: 'degraded',
      infrastructure: 'OK',
      cluster: 'degraded',
      platform: 'OK',
      pipelines: 'OK',
      applications: 'OK',
      activeAlerts: 2,
    };
    const result = formatStatusLine(summary);
    expect(result).toContain(':large_yellow_circle:');
    expect(result).toContain('Degraded');
    expect(result).toContain('2 active alerts');
  });

  it('returns unhealthy status with red circle', () => {
    const summary: HealthSummary = {
      overall: 'unhealthy',
      infrastructure: 'OK',
      cluster: 'down',
      platform: 'down',
      pipelines: 'N/A',
      applications: 'down',
      activeAlerts: 5,
    };
    const result = formatStatusLine(summary);
    expect(result).toContain(':red_circle:');
    expect(result).toContain('Unhealthy');
    expect(result).toContain('5 active alerts');
  });

  it('includes all layer statuses separated by pipes', () => {
    const summary: HealthSummary = {
      overall: 'healthy',
      infrastructure: 'OK',
      cluster: 'OK',
      platform: 'OK',
      pipelines: 'OK',
      applications: 'OK',
      activeAlerts: 0,
    };
    const result = formatStatusLine(summary);
    expect(result).toContain('Infra: OK');
    expect(result).toContain('Cluster: OK');
    expect(result).toContain('Platform: OK');
    expect(result).toContain('Pipelines: OK');
    expect(result).toContain('Apps: OK');
  });
});

describe('formatHealthBlocks', () => {
  it('returns header and divider for healthy platform', () => {
    const health: HealthResponse = {
      overall: 'healthy',
      layers: [{ name: 'Infrastructure', status: 'healthy', details: 'All nodes ready' }],
      activeAlerts: 0,
    };
    const blocks = formatHealthBlocks(health);
    expect(blocks[0]).toMatchObject({ type: 'header' });
    expect(blocks[1]).toMatchObject({ type: 'divider' });
  });

  it('renders each layer with correct emoji', () => {
    const health: HealthResponse = {
      overall: 'degraded',
      layers: [
        { name: 'Infrastructure', status: 'healthy', details: 'All nodes ready' },
        { name: 'Cluster', status: 'degraded', details: '1 pod failing' },
        { name: 'Applications', status: 'unhealthy', details: 'CrashLoopBackOff' },
      ],
      activeAlerts: 1,
    };
    const blocks = formatHealthBlocks(health);

    // Header + divider + 3 layers + divider + alerts section = 7
    expect(blocks.length).toBe(7);

    const infraBlock = blocks[2];
    expect(infraBlock.type).toBe('section');
    expect((infraBlock as { type: 'section'; text: { text: string } }).text.text).toContain(
      ':large_green_circle:',
    );
    expect((infraBlock as { type: 'section'; text: { text: string } }).text.text).toContain(
      'Infrastructure',
    );

    const clusterBlock = blocks[3];
    expect((clusterBlock as { type: 'section'; text: { text: string } }).text.text).toContain(
      ':large_yellow_circle:',
    );

    const appBlock = blocks[4];
    expect((appBlock as { type: 'section'; text: { text: string } }).text.text).toContain(
      ':red_circle:',
    );
  });

  it('shows active alerts section when alerts > 0', () => {
    const health: HealthResponse = {
      overall: 'degraded',
      layers: [],
      activeAlerts: 3,
    };
    const blocks = formatHealthBlocks(health);
    const alertBlock = blocks.find(
      (b) =>
        b.type === 'section' &&
        (b as { type: 'section'; text: { text: string } }).text.text.includes('Active Alerts'),
    );
    expect(alertBlock).toBeDefined();
    expect((alertBlock as { type: 'section'; text: { text: string } }).text.text).toContain('3');
  });

  it('does not show alerts section when no alerts', () => {
    const health: HealthResponse = {
      overall: 'healthy',
      layers: [],
      activeAlerts: 0,
    };
    const blocks = formatHealthBlocks(health);
    const alertBlock = blocks.find(
      (b) =>
        b.type === 'section' &&
        (b as { type: 'section'; text: { text: string } }).text.text.includes('Active Alerts'),
    );
    expect(alertBlock).toBeUndefined();
  });
});

describe('formatDigestBlocks', () => {
  it('includes period and overall status', () => {
    const health: HealthResponse = {
      overall: 'healthy',
      layers: [],
      activeAlerts: 0,
    };
    const blocks = formatDigestBlocks(
      health,
      { active: 0, critical: 0, warning: 0, info: 0 },
      { recentActions: 0, status: 'normal' },
      { shown: [], total: 0 },
      'Morning',
    );
    const header = blocks[0] as { type: 'header'; text: { text: string } };
    expect(header.text.text).toContain('Morning');
    expect(header.text.text).toContain(':sunny:');
  });

  it('shows evening emoji for evening digest', () => {
    const health: HealthResponse = {
      overall: 'healthy',
      layers: [],
      activeAlerts: 0,
    };
    const blocks = formatDigestBlocks(
      health,
      { active: 0, critical: 0, warning: 0, info: 0 },
      { recentActions: 0, status: 'normal' },
      { shown: [], total: 0 },
      'Evening',
    );
    const header = blocks[0] as { type: 'header'; text: { text: string } };
    expect(header.text.text).toContain('Evening');
    expect(header.text.text).toContain(':city_sunset:');
  });

  it('includes alert and remediation summaries', () => {
    const health: HealthResponse = {
      overall: 'degraded',
      layers: [],
      activeAlerts: 2,
    };
    const blocks = formatDigestBlocks(
      health,
      { active: 2, critical: 1, warning: 1, info: 0 },
      { recentActions: 3, status: 'normal' },
      { shown: [], total: 0 },
      'Morning',
    );
    const summaryBlock = blocks.find(
      (b) =>
        b.type === 'section' &&
        (b as { type: 'section'; text: { text: string } }).text.text.includes('Alerts firing now'),
    );
    expect(summaryBlock).toBeDefined();
    const text = (summaryBlock as { type: 'section'; text: { text: string } }).text.text;
    expect(text).toContain('2 active');
    expect(text).toContain('critical 1');
    expect(text).not.toContain('12h');
    expect(text).toContain('Recent actions: 3');
    expect(text).toContain('Status: normal');
  });

  it('includes deployment list when deployments exist', () => {
    const health: HealthResponse = {
      overall: 'healthy',
      layers: [],
      activeAlerts: 0,
    };
    const deployments = [
      {
        pipeline: 'website',
        imageTag: 'main-abc1234',
        status: 'succeeded',
        timestamp: '2026-02-01T10:00:00Z',
      },
      {
        pipeline: 'shop',
        imageTag: 'main-def5678',
        status: 'failed',
        timestamp: '2026-02-01T11:00:00Z',
      },
    ];
    const blocks = formatDigestBlocks(
      health,
      { active: 0, critical: 0, warning: 0, info: 0 },
      { recentActions: 0, status: 'normal' },
      { shown: deployments, total: 5 },
      'Evening',
    );
    const deployBlock = blocks.find(
      (b) =>
        b.type === 'section' &&
        (b as { type: 'section'; text: { text: string } }).text.text.includes('Deployments'),
    );
    expect(deployBlock).toBeDefined();
    expect((deployBlock as { type: 'section'; text: { text: string } }).text.text).toContain(
      'website',
    );
    expect((deployBlock as { type: 'section'; text: { text: string } }).text.text).toContain(
      'main-abc1234',
    );
    expect((deployBlock as { type: 'section'; text: { text: string } }).text.text).toContain(
      ':x: shop',
    );
    expect((deployBlock as { type: 'section'; text: { text: string } }).text.text).toContain(
      '3 earlier',
    );
  });

  it('shows deployment times in the digest time zone', () => {
    const blocks = formatDigestBlocks(
      { overall: 'healthy', layers: [], activeAlerts: 0 },
      { active: 0, critical: 0, warning: 0, info: 0 },
      { recentActions: 0, status: 'normal' },
      {
        shown: [
          {
            pipeline: 'website',
            imageTag: 't',
            status: 'succeeded',
            timestamp: '2026-07-01T10:00:00Z',
          },
        ],
        total: 1,
      },
      'Morning',
      'Europe/Copenhagen',
    );
    const deploy = blocks.find(
      (b) =>
        b.type === 'section' &&
        (b as { type: 'section'; text: { text: string } }).text.text.includes('Deployments'),
    ) as { type: 'section'; text: { text: string } };
    expect(deploy.text.text).toContain('(12:00)');
  });
});
