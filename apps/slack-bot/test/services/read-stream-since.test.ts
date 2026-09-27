import { describe, expect, it, vi } from 'vitest';
import type { Redis } from 'ioredis';

vi.mock('../../src/logger.js', () => ({
  logger: { info: vi.fn(), warn: vi.fn(), debug: vi.fn(), error: vi.fn(), fatal: vi.fn() },
}));

const { readStreamSince } = await import('../../src/services/redis-client.js');

describe('readStreamSince', () => {
  it('takes the newest entries of the window, oldest first, and counts the window', async () => {
    const xrevrange = vi.fn(async () => [
      ['3000-0', ['pipeline', 'b']],
      ['2000-0', ['pipeline', 'a']],
    ]);
    const xrange = vi.fn(async () => [
      ['1000-0', []],
      ['2000-0', []],
      ['3000-0', []],
    ]);
    const redis = { xrevrange, xrange } as unknown as Redis;

    const out = await readStreamSince(redis, 'platform:deployments', 1000, 2);

    expect(xrevrange).toHaveBeenCalledWith('platform:deployments', '+', '1000-0', 'COUNT', 2);
    expect(out.entries.map((e) => e.id)).toEqual(['2000-0', '3000-0']);
    expect(out.total).toBe(3);
  });

  it('counts the window only up to the cap', async () => {
    const xrevrange = vi.fn(async () => []);
    const xrange = vi.fn(async () => [
      ['1-0', []],
      ['2-0', []],
    ]);
    const redis = { xrevrange, xrange } as unknown as Redis;

    const out = await readStreamSince(redis, 'platform:deployments', 0, 20, 2);

    expect(xrange).toHaveBeenCalledWith('platform:deployments', '0-0', '+', 'COUNT', 2);
    expect(out).toMatchObject({ total: 2, capped: true });
  });
});
