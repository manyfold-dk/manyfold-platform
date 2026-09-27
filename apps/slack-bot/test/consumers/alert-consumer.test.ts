import { describe, it, expect, vi } from 'vitest';
import type { Redis } from 'ioredis';
import type { App } from '@slack/bolt';

vi.mock('../../src/logger.js', () => ({
  logger: { info: vi.fn(), warn: vi.fn(), debug: vi.fn(), error: vi.fn(), fatal: vi.fn() },
}));

const { startAlertConsumer } = await import('../../src/consumers/alert-consumer.js');

function streamEntry(fields: Record<string, string>) {
  return Object.entries(fields).flat();
}

/**
 * Drives the consumer through exactly one message, then parks it: the second
 * read never resolves, so the infinite consume loop stops without us having to
 * tear it down.
 */
function harness(fields: Record<string, string>, delivered = true, messageId = '1-0') {
  const postMessage = vi.fn(async () => ({ ts: '111.222' }));
  const forwarded: unknown[] = [];
  const acked: string[] = [];
  let reads = 0;

  const app = { client: { chat: { postMessage } } } as unknown as App;

  const redis = {
    xautoclaim: vi.fn(async () => ['0-0', []]),
    xreadgroup: vi.fn(() => {
      reads += 1;
      if (reads === 1) {
        return Promise.resolve([['platform:alerts', [[messageId, streamEntry(fields)]]]]);
      }
      return new Promise(() => {}); // park
    }),
    xack: vi.fn(async (_s: string, _g: string, id: string) => {
      acked.push(id);
      return 1;
    }),
    xgroup: vi.fn(async () => 'OK'),
  } as unknown as Redis;

  const forwarder = vi.fn(async (a: unknown) => {
    forwarded.push(a);
    return delivered;
  });

  startAlertConsumer(app, redis, forwarder);

  return { postMessage, forwarded, acked, flush: () => new Promise((r) => setTimeout(r, 10)) };
}

describe('alert consumer: Watchdog heartbeat', () => {
  it('forwards the watchdog to ops-fleet without posting it to Slack', async () => {
    const h = harness({
      fingerprint: 'wd1',
      status: 'firing',
      alertName: 'Watchdog',
      severity: 'none',
      namespace: 'observability',
      summary: 'always firing',
      timestamp: new Date().toISOString(),
    });

    await h.flush();

    expect(h.forwarded).toHaveLength(1);
    expect(h.postMessage).not.toHaveBeenCalled();
    expect(h.acked).toContain('1-0');
  });

  it('still posts an ordinary alert to Slack', async () => {
    const h = harness({
      fingerprint: 'abc',
      status: 'firing',
      alertName: 'KubeJobFailed',
      severity: 'warning',
      namespace: 'platform-renovate',
      summary: 'job failed',
      timestamp: new Date().toISOString(),
    });

    await h.flush();

    expect(h.postMessage).toHaveBeenCalledTimes(1);
    expect(h.forwarded).toHaveLength(1);
    expect(h.acked).toContain('1-0');
  });

  it('leaves an undelivered watchdog pending, so it is claimed again', async () => {
    const h = harness(
      {
        fingerprint: 'wd2',
        status: 'firing',
        alertName: 'Watchdog',
        severity: 'none',
        namespace: 'observability',
        summary: 'always firing',
        timestamp: new Date().toISOString(),
      },
      false,
      '9-0',
    );

    await h.flush();

    expect(h.forwarded).toHaveLength(1);
    expect(h.acked).not.toContain('9-0');
  });
});
