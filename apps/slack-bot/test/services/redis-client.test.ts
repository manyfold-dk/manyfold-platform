import { describe, it, expect, vi } from 'vitest';
import type { Redis } from 'ioredis';

// redis-client imports the logger from app.ts, which self-starts the Slack app
// on import. Stub it so these tests exercise the stream helpers in isolation.
vi.mock('../../src/logger.js', () => ({
  logger: { info: vi.fn(), warn: vi.fn(), debug: vi.fn(), error: vi.fn() },
}));

const { readNewMessages, claimPendingMessages, msSinceLastStreamRead } =
  await import('../../src/services/redis-client.js');

const NOGROUP_ERR =
  "NOGROUP No such key 'platform:alerts' or consumer group 'slack-bot' in XREADGROUP with GROUP option";

/**
 * Builds a fake Redis whose stream read fails with `failure` on the first call
 * and succeeds on every call after, so we can assert the recovery path.
 */
function fakeRedis(method: 'xreadgroup' | 'xautoclaim', failure: Error, success: unknown) {
  const xgroupCalls: unknown[][] = [];
  let calls = 0;

  const redis = {
    xgroup: vi.fn(async (...args: unknown[]) => {
      xgroupCalls.push(args);
      return 'OK';
    }),
    [method]: vi.fn(async () => {
      calls += 1;
      if (calls === 1) throw failure;
      return success;
    }),
  };

  return { redis: redis as unknown as Redis, xgroupCalls, readCalls: () => calls };
}

describe('readNewMessages', () => {
  it('recreates a missing consumer group and retries instead of failing', async () => {
    const payload = [['platform:alerts', [['1-0', ['alertName', 'KubeJobFailed']]]]];
    const { redis, xgroupCalls, readCalls } = fakeRedis(
      'xreadgroup',
      new Error(NOGROUP_ERR),
      payload,
    );

    const messages = await readNewMessages(redis, 'platform:alerts');

    expect(xgroupCalls).toHaveLength(1);
    expect(xgroupCalls[0]).toEqual(
      expect.arrayContaining(['CREATE', 'platform:alerts', 'slack-bot', 'MKSTREAM']),
    );
    expect(readCalls()).toBe(2);
    expect(messages).toEqual([{ id: '1-0', fields: { alertName: 'KubeJobFailed' } }]);
  });

  it('propagates errors that are not NOGROUP', async () => {
    const { redis, xgroupCalls } = fakeRedis('xreadgroup', new Error('READONLY'), null);

    await expect(readNewMessages(redis, 'platform:alerts')).rejects.toThrow('READONLY');
    expect(xgroupCalls).toHaveLength(0);
  });
});

describe('msSinceLastStreamRead', () => {
  it('counts an empty-but-successful read as liveness', async () => {
    const redis = {
      xreadgroup: vi.fn(async () => null), // idle stream: blocks, returns nothing
      xgroup: vi.fn(async () => 'OK'),
    } as unknown as Redis;

    await readNewMessages(redis, 'platform:alerts');

    // An idle consumer is a healthy consumer -- this must not read as stalled.
    expect(msSinceLastStreamRead()).toBeLessThan(1000);
  });
});

describe('claimPendingMessages', () => {
  it('recreates a missing consumer group and retries', async () => {
    const { redis, xgroupCalls, readCalls } = fakeRedis('xautoclaim', new Error(NOGROUP_ERR), [
      '0-0',
      [],
    ]);

    const messages = await claimPendingMessages(redis, 'platform:deployments');

    expect(xgroupCalls).toHaveLength(1);
    expect(readCalls()).toBe(2);
    expect(messages).toEqual([]);
  });
});
