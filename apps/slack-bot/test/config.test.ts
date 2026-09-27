import { afterEach, describe, expect, it, vi } from 'vitest';

const saved = { ...process.env };

async function loadConfig(env: Record<string, string | undefined>) {
  vi.resetModules();
  for (const [k, v] of Object.entries(env)) {
    if (v === undefined) delete process.env[k];
    else process.env[k] = v;
  }
  return import('../src/config.js');
}

afterEach(() => {
  process.env = { ...saved };
});

const complete = {
  SLACK_BOT_TOKEN: 'bot',
  SLACK_APP_TOKEN: 'app',
  SLACK_SIGNING_SECRET: 'signing',
  BACKEND_URL: 'http://backend.example',
  REDIS_URL: 'redis://redis.example:6379',
};

describe('integer settings', () => {
  it('uses the default when unset or empty and takes a clean integer', async () => {
    let m = await loadConfig({ APPROVAL_TIMEOUT_SECONDS: undefined });
    expect(m.config.approval.timeoutSeconds).toBe(300);
    m = await loadConfig({ APPROVAL_TIMEOUT_SECONDS: '' });
    expect(m.config.approval.timeoutSeconds).toBe(300);
    m = await loadConfig({ APPROVAL_TIMEOUT_SECONDS: '120' });
    expect(m.config.approval.timeoutSeconds).toBe(120);
  });

  it.each(['30s', '0', '-5', '1.5', 'abc'])(
    'refuses %s instead of bending it into a number',
    async (bad) => {
      await expect(loadConfig({ APPROVAL_TIMEOUT_SECONDS: bad })).rejects.toThrow(
        /APPROVAL_TIMEOUT_SECONDS must be an integer/,
      );
    },
  );
});

describe('missingSettings', () => {
  it('is empty when every required setting is present', async () => {
    const m = await loadConfig(complete);
    expect(m.missingSettings()).toEqual([]);
  });

  it('names what is missing: there are no deployment-specific defaults', async () => {
    const m = await loadConfig({ ...complete, BACKEND_URL: undefined, REDIS_URL: undefined });
    expect(m.missingSettings()).toEqual(['BACKEND_URL', 'REDIS_URL']);
  });

  it('does not need Redis when the stream consumers are off', async () => {
    const m = await loadConfig({ ...complete, REDIS_URL: undefined, CONSUMERS_ENABLED: 'false' });
    expect(m.missingSettings()).toEqual([]);
  });
});

describe('approver lists', () => {
  it('falls back to the ops-fleet approvers and trims the list', async () => {
    const m = await loadConfig({
      REMEDIATION_APPROVER_USER_IDS: undefined,
      OPS_FLEET_APPROVER_USER_IDS: ' U1 , ,U2',
    });
    expect(m.config.approval.approverUserIds).toEqual(['U1', 'U2']);
  });
});
