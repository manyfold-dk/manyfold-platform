import { describe, it, expect, vi } from 'vitest';
import type { Redis } from 'ioredis';

vi.mock('../../src/logger.js', () => ({
  logger: { info: vi.fn(), warn: vi.fn(), debug: vi.fn(), error: vi.fn(), fatal: vi.fn() },
}));

const { consumeStream } = await import('../../src/consumers/consume.js');

function entry(id: string): [string, string[]] {
  return [id, ['k', 'v']];
}

describe('consumeStream', () => {
  it('claims pending messages again while running, not only at start', async () => {
    let claims = 0;
    const redis = {
      xautoclaim: vi.fn(async () => {
        claims += 1;
        // The second claim finds the message that failed in the first round.
        return ['0-0', claims === 2 ? [entry('1-0')] : []];
      }),
      xreadgroup: vi.fn(async () => null),
    } as unknown as Redis;
    const handled: string[] = [];

    await consumeStream(redis, 'platform:alerts', async (id) => void handled.push(id), {
      reclaimEveryMs: 0,
      rounds: 2,
    });

    expect(claims).toBe(2);
    expect(handled).toEqual(['1-0']);
  });

  it('reads new messages every round and reclaims only when the interval has passed', async () => {
    const redis = {
      xautoclaim: vi.fn(async () => ['0-0', []]),
      xreadgroup: vi.fn(async () => [['platform:alerts', [entry('2-0')]]]),
    } as unknown as Redis;
    const handled: string[] = [];

    await consumeStream(redis, 'platform:alerts', async (id) => void handled.push(id), {
      reclaimEveryMs: 60_000,
      rounds: 3,
    });

    expect(redis.xautoclaim).toHaveBeenCalledTimes(1);
    expect(handled).toEqual(['2-0', '2-0', '2-0']);
  });

  it('reads new messages after a reclaim pass that outlasts the interval', async () => {
    vi.useFakeTimers();
    try {
      const redis = {
        xautoclaim: vi.fn(async () => ['0-0', [entry('1-0')]]),
        xreadgroup: vi.fn(async () => [['platform:alerts', [entry('5-0')]]]),
      } as unknown as Redis;
      const handled: string[] = [];
      const slowHandle = async (id: string) => {
        handled.push(id);
        // A retry that takes longer than the whole reclaim interval.
        if (id === '1-0') vi.setSystemTime(Date.now() + 120_000);
      };

      await consumeStream(redis, 'platform:alerts', slowHandle, {
        reclaimEveryMs: 60_000,
        rounds: 2,
      });

      expect(redis.xautoclaim).toHaveBeenCalledTimes(1);
      expect(handled).toEqual(['1-0', '5-0', '5-0']);
    } finally {
      vi.useRealTimers();
    }
  });

  it('continues the pending walk from the returned cursor on the next pass', async () => {
    const starts: string[] = [];
    const redis = {
      xautoclaim: vi.fn(async (...args: unknown[]) => {
        starts.push(args[4] as string);
        return starts.length === 1 ? ['7-0', [entry('1-0')]] : ['0-0', [entry('8-0')]];
      }),
      xreadgroup: vi.fn(async () => null),
    } as unknown as Redis;
    const handled: string[] = [];

    await consumeStream(redis, 'platform:alerts', async (id) => void handled.push(id), {
      reclaimEveryMs: 0,
      rounds: 2,
    });

    expect(starts).toEqual(['0-0', '7-0']);
    expect(handled).toEqual(['1-0', '8-0']);
  });

  it('retries a bounded batch per pass', async () => {
    const counts: number[] = [];
    const redis = {
      xautoclaim: vi.fn(async (...args: unknown[]) => {
        counts.push(args[6] as number);
        return ['0-0', []];
      }),
      xreadgroup: vi.fn(async () => null),
    } as unknown as Redis;

    await consumeStream(redis, 'platform:alerts', async () => {}, { rounds: 1 });

    expect(counts).toEqual([5]);
  });
});
