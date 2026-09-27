import { describe, it, expect } from 'vitest';
import type { DeploymentEvent } from '../../src/types/index.js';

// Set env vars before importing modules that read config
process.env.ARGOCD_URL = 'https://argocd.test';

const { formatDeploySuccess, formatDeployFailure } =
  await import('../../src/formatting/deploy-blocks.js');

describe('formatDeploySuccess', () => {
  const event: DeploymentEvent = {
    pipeline: 'website',
    runName: 'website-run-abc123',
    status: 'succeeded',
    gitRevision: 'abc1234567890',
    gitUrl: 'https://github.com/manyfold-dk/manyfold-platform.git',
    imageTag: 'main-abc1234',
    environment: 'cloud',
    duration: '3m 42s',
    timestamp: new Date().toISOString(),
  };

  it('renders success header with checkmark', () => {
    const blocks = formatDeploySuccess(event);
    const header = blocks[0] as { type: 'header'; text: { text: string } };
    expect(header.text.text).toContain(':white_check_mark:');
    expect(header.text.text).toContain('succeeded');
  });

  it('includes pipeline details', () => {
    const blocks = formatDeploySuccess(event);
    const detailBlock = blocks[2] as { type: 'section'; text: { text: string } };
    expect(detailBlock.text.text).toContain('website');
    expect(detailBlock.text.text).toContain('CLOUD');
    expect(detailBlock.text.text).toContain('3m 42s');
    expect(detailBlock.text.text).toContain('abc1234'); // short SHA
    expect(detailBlock.text.text).toContain('main-abc1234');
  });

  it('includes action buttons', () => {
    const blocks = formatDeploySuccess(event);
    const actionsBlock = blocks.find((b) => b.type === 'actions');
    expect(actionsBlock).toBeDefined();

    const elements = (
      actionsBlock as { type: 'actions'; elements: Array<{ text: { text: string }; url?: string }> }
    ).elements;

    const commitButton = elements.find((e) => e.text.text === 'View Commit');
    expect(commitButton).toBeDefined();
    expect(commitButton!.url).toContain('github.com');
    expect(commitButton!.url).toContain('abc1234567890');

    expect(elements.find((e) => e.action_id === 'view_pipeline')).toBeUndefined();

    const argoButton = elements.find((e) => e.text.text === 'View in ArgoCD');
    expect(argoButton).toBeDefined();
    expect(argoButton!.url).toContain('argocd.test');
  });
});

describe('formatDeployFailure', () => {
  const event: DeploymentEvent = {
    pipeline: 'website',
    runName: 'website-run-def456',
    status: 'failed',
    gitRevision: 'def4567890abc',
    gitUrl: 'https://github.com/manyfold-dk/manyfold-platform.git',
    imageTag: '',
    environment: 'local',
    duration: '1m 15s',
    timestamp: new Date().toISOString(),
  };

  it('renders failure header with X', () => {
    const blocks = formatDeployFailure(event);
    const header = blocks[0] as { type: 'header'; text: { text: string } };
    expect(header.text.text).toContain(':x:');
    expect(header.text.text).toContain('failed');
  });

  it('includes pipeline details', () => {
    const blocks = formatDeployFailure(event);
    const detailBlock = blocks[2] as { type: 'section'; text: { text: string } };
    expect(detailBlock.text.text).toContain('website');
    expect(detailBlock.text.text).toContain('LOCAL');
    expect(detailBlock.text.text).toContain('1m 15s');
    expect(detailBlock.text.text).toContain('def4567'); // short SHA
  });

  it('does not include ArgoCD button for failed deployments', () => {
    const blocks = formatDeployFailure(event);
    const actionsBlock = blocks.find((b) => b.type === 'actions');
    if (actionsBlock) {
      const elements = (
        actionsBlock as { type: 'actions'; elements: Array<{ text: { text: string } }> }
      ).elements;
      const argoButton = elements.find((e) => e.text.text === 'View in ArgoCD');
      expect(argoButton).toBeUndefined();
    }
  });

  it('says cancelled, not failed, for a cancelled run', () => {
    const blocks = formatDeployFailure({
      pipeline: 'website',
      runName: 'r1',
      status: 'cancelled',
      gitRevision: 'abcdef1234',
      gitUrl: 'https://example.com/repo.git',
      imageTag: 'main-abcdef1',
      environment: 'cloud',
      timestamp: '2026-02-01T10:00:00Z',
    });
    const header = blocks[0] as { type: 'header'; text: { text: string } };
    expect(header.text.text).toContain('cancelled');
    expect(header.text.text).not.toContain('failed');
  });
});
