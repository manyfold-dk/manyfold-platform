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
 * Drives the consumer through the given messages, one per read, then parks it: the next read
 * never resolves, so the infinite consume loop stops without us having to tear it down.
 */
function run(messages: Array<{ id: string; fields: Record<string, string> }>, delivered = true) {
  let ts = 0;
  const postMessage = vi.fn(async (_args: Record<string, unknown>) => ({ ts: `111.${++ts}` }));
  const forwarded: unknown[] = [];
  const acked: string[] = [];
  let reads = 0;

  const app = { client: { chat: { postMessage } } } as unknown as App;

  const redis = {
    xautoclaim: vi.fn(async () => ['0-0', []]),
    xreadgroup: vi.fn(() => {
      const msg = messages[reads++];
      if (msg) {
        return Promise.resolve([['platform:alerts', [[msg.id, streamEntry(msg.fields)]]]]);
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

// The consumer remembers processed message IDs for the life of the module, so every message in
// this file gets its own.
let seq = 0;
const nextId = () => `${++seq}-0`;

function harness(fields: Record<string, string>, delivered = true) {
  const id = nextId();
  return { ...run([{ id, fields }], delivered), id };
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
    expect(h.acked).toContain(h.id);
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
    );

    await h.flush();

    expect(h.forwarded).toHaveLength(1);
    expect(h.acked).not.toContain(h.id);
  });
});

const alertFields = (over: Record<string, string>) => ({
  fingerprint: 'fp',
  status: 'firing',
  alertName: 'KubeJobFailed',
  severity: 'warning',
  namespace: 'platform-renovate',
  summary: 'job failed',
  timestamp: new Date().toISOString(),
  ...over,
});

describe('alert consumer: #alerts belongs to the Alertmanager Slack receiver', () => {
  it('does not repeat an alert without a pod, but still forwards and acknowledges it', async () => {
    const h = harness(alertFields({ fingerprint: 'nopod' }));

    await h.flush();

    expect(h.postMessage).not.toHaveBeenCalled();
    expect(h.forwarded).toHaveLength(1);
    expect(h.acked).toContain(h.id);
  });

  it('does not repeat the resolution of an alert without a pod either', async () => {
    const h = harness(alertFields({ fingerprint: 'nopod-r', status: 'resolved' }));

    await h.flush();

    expect(h.postMessage).not.toHaveBeenCalled();
    expect(h.forwarded).toHaveLength(1);
    expect(h.acked).toContain(h.id);
  });

  it('posts an alert that names a pod, with the Restart Pod button', async () => {
    const h = harness(
      alertFields({
        fingerprint: 'pod-f',
        alertName: 'KubePodCrashLooping',
        severity: 'critical',
        namespace: 'website',
        pod: 'website-backend-0',
      }),
    );

    await h.flush();

    expect(h.postMessage).toHaveBeenCalledTimes(1);
    const args = h.postMessage.mock.calls[0]![0];
    expect(args.text).toBe('CRITICAL: KubePodCrashLooping in website');
    expect(JSON.stringify(args.blocks)).toContain('remediate_pod_restart');
    expect(h.forwarded).toHaveLength(1);
    expect(h.acked).toContain(h.id);
  });

  it("threads a pod alert's resolution under the bot's firing message", async () => {
    const pod = { fingerprint: 'pod-t', namespace: 'website', pod: 'website-backend-0' };
    const ids = [nextId(), nextId()];
    const h = run([
      { id: ids[0]!, fields: alertFields(pod) },
      { id: ids[1]!, fields: alertFields({ ...pod, status: 'resolved' }) },
    ]);

    await h.flush();

    expect(h.postMessage).toHaveBeenCalledTimes(2);
    const firingTs = (await h.postMessage.mock.results[0]!.value).ts;
    const resolved = h.postMessage.mock.calls[1]![0];
    expect(resolved.thread_ts).toBe(firingTs);
    expect(resolved.text).toBe('RESOLVED: KubeJobFailed in website');
    expect(h.forwarded).toHaveLength(2);
    expect(h.acked).toEqual(ids);
  });

  it("posts a pod alert's resolution unthreaded when its firing message is unknown", async () => {
    const h = harness(
      alertFields({ fingerprint: 'pod-u', status: 'resolved', pod: 'website-backend-0' }),
    );

    await h.flush();

    expect(h.postMessage).toHaveBeenCalledTimes(1);
    expect(h.postMessage.mock.calls[0]![0].thread_ts).toBeUndefined();
  });
});
