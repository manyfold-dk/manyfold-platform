import { describe, expect, it, vi } from 'vitest';
import { fakeRedis } from '../helpers/fake-redis.js';

vi.mock('../../src/logger.js', () => ({
  logger: { info: vi.fn(), warn: vi.fn(), debug: vi.fn(), error: vi.fn(), fatal: vi.fn() },
}));
vi.mock('../../src/config.js', () => ({
  config: {
    opsFleet: {
      webhookUrl: 'http://ops.example',
      webhookToken: 't',
      timeoutMs: 50,
      approverUserIds: ['U1'],
    },
  },
}));

const { setDecisionStore, claimOpsDecision, releaseOpsDecision } =
  await import('../../src/actions/ops-decisions.js');
setDecisionStore(fakeRedis() as never);
const { registerOpsApproveAction } = await import('../../src/actions/ops-approve.js');
const { registerOpsRejectAction } = await import('../../src/actions/ops-reject.js');

type Handler = (args: Record<string, unknown>) => Promise<void>;

function handlers(update: () => Promise<unknown> = async () => ({})) {
  const map: Record<string, Handler> = {};
  const app = { action: (id: string, h: Handler) => (map[id] = h) } as never;
  registerOpsApproveAction(app);
  registerOpsRejectAction(app);
  const click = async (id: 'ops_approve' | 'ops_reject', actionId: string) => {
    const respond = vi.fn();
    await map[id]!({
      action: { type: 'button', value: actionId },
      ack: vi.fn(),
      body: { user: { id: 'U1' }, message: { ts: '1.0' }, channel: { id: 'C1' } },
      client: { chat: { update: vi.fn(update) } },
      respond,
    });
    return respond;
  };
  return click;
}

describe('ops decisions: the first click wins', () => {
  it('a Reject after an Approve is refused', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => new Response(null, { status: 200 })),
    );
    const click = handlers();

    await click('ops_approve', 'act-a');
    const respond = await click('ops_reject', 'act-a');

    expect(respond.mock.calls.at(-1)![0].text).toContain('already approved');
    vi.unstubAllGlobals();
  });

  it('an Approve after a Reject is not sent', async () => {
    const fetchMock = vi.fn(async () => new Response(null, { status: 200 }));
    vi.stubGlobal('fetch', fetchMock);
    const click = handlers();

    await click('ops_reject', 'act-b');
    const respond = await click('ops_approve', 'act-b');

    expect(fetchMock).not.toHaveBeenCalled();
    expect(respond.mock.calls.at(-1)![0].text).toContain('already rejected');
    vi.unstubAllGlobals();
  });

  it('an approval that provably never arrived can be tried again', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(new Response(null, { status: 409 }))
      .mockResolvedValueOnce(new Response(null, { status: 200 }));
    vi.stubGlobal('fetch', fetchMock);
    const click = handlers();

    await click('ops_approve', 'act-c');
    await click('ops_approve', 'act-c');

    expect(fetchMock).toHaveBeenCalledTimes(2);
    vi.unstubAllGlobals();
  });

  it('a second Approve sends nothing', async () => {
    const fetchMock = vi.fn(async () => new Response(null, { status: 200 }));
    vi.stubGlobal('fetch', fetchMock);
    const click = handlers();

    await click('ops_approve', 'act-d');
    const respond = await click('ops_approve', 'act-d');

    expect(fetchMock).toHaveBeenCalledOnce();
    expect(respond.mock.calls.at(-1)![0].text).toContain('already approved');
    vi.unstubAllGlobals();
  });

  it('keeps decisions in Redis, so a restarted bot still knows them', async () => {
    const kv = new Map<string, string>();
    const fake = {
      set: vi.fn(async (k: string, v: string, ..._opts: unknown[]) => {
        if (kv.has(k)) return null;
        kv.set(k, v);
        return 'OK';
      }),
      get: vi.fn(async (k: string) => kv.get(k) ?? null),
      del: vi.fn(async (k: string) => Number(kv.delete(k))),
      eval: vi.fn(async (_s: string, _n: number, k: string, v: string) =>
        kv.get(k) === v ? Number(kv.delete(k)) : 0,
      ),
    };
    setDecisionStore(fake as never);
    try {
      expect(await claimOpsDecision('act-r', 'rejected')).toMatchObject({
        standing: 'rejected',
        isNew: true,
        token: expect.any(String),
      });
      // A new process: its memory is empty, Redis still holds the rejection.
      setDecisionStore(fake as never);
      expect(await claimOpsDecision('act-r', 'approved')).toEqual({
        standing: 'rejected',
        isNew: false,
      });
      expect(fake.set).toHaveBeenCalledWith(
        'ops-decision:act-r',
        expect.stringMatching(/^rejected:/),
        'EX',
        604800,
        'NX',
      );
      expect(fake.get).toHaveBeenCalledWith('ops-decision:act-r');
    } finally {
      setDecisionStore(fakeRedis() as never);
    }
  });

  it('a decision made while Redis was down still stands when Redis is back', async () => {
    const kv = new Map<string, string>();
    const fake = {
      set: vi.fn(async (k: string, v: string) => {
        if (kv.has(k)) return null;
        kv.set(k, v);
        return 'OK';
      }),
      get: vi.fn(async (k: string) => kv.get(k) ?? null),
      del: vi.fn(async () => 1),
    };
    setDecisionStore(null);
    await claimOpsDecision('act-o', 'rejected'); // Redis "down": decided in memory
    setDecisionStore(fake as never, { keepMemory: true }); // Redis back, empty; same process
    try {
      expect(await claimOpsDecision('act-o', 'approved')).toEqual({
        standing: 'rejected',
        isNew: false,
      });
    } finally {
      setDecisionStore(fakeRedis() as never);
    }
  });

  it('a taken key is not new even when its value cannot be read', async () => {
    const fake = {
      set: vi.fn(async () => null),
      get: vi.fn(async () => {
        throw new Error('timeout');
      }),
      del: vi.fn(async () => 1),
    };
    setDecisionStore(fake as never);
    try {
      expect(await claimOpsDecision('act-t', 'approved')).toEqual({
        standing: 'decided',
        isNew: false,
      });
    } finally {
      setDecisionStore(fakeRedis() as never);
    }
  });

  it('while Redis hangs, a concurrent Reject is still recorded after an unsent Approve', async () => {
    const fake = {
      set: vi.fn(() => new Promise(() => {})),
      get: vi.fn(async () => null),
      del: vi.fn(async () => 1),
    };
    setDecisionStore(fake as never);
    vi.useFakeTimers();
    try {
      const approve = claimOpsDecision('act-c2', 'approved');
      const reject = claimOpsDecision('act-c2', 'rejected');
      await vi.advanceTimersByTimeAsync(5_000);
      const results = await Promise.all([approve, reject]);
      // One at a time: the approval cannot be confirmed and is not sent; the rejection that
      // follows is recorded, so no later Approve can run the action.
      expect(results[0]).toEqual({ standing: 'unavailable', isNew: false });
      expect(results[1]).toEqual({ standing: 'rejected', isNew: true, unconfirmed: true });
    } finally {
      vi.useRealTimers();
      setDecisionStore(fakeRedis() as never);
    }
  });

  it('a release removes only its own decision', async () => {
    const kv = new Map<string, string>([['ops-decision:act-x', 'rejected']]);
    const fake = {
      set: vi.fn(async () => null),
      get: vi.fn(async (k: string) => kv.get(k) ?? null),
      eval: vi.fn(async (_s: string, _n: number, k: string, v: string) =>
        kv.get(k) === v ? Number(kv.delete(k)) : 0,
      ),
    };
    setDecisionStore(fake as never);
    try {
      await releaseOpsDecision('act-x', { standing: 'approved', isNew: true, token: 't1' });
      expect(kv.get('ops-decision:act-x')).toBe('rejected');
    } finally {
      setDecisionStore(fakeRedis() as never);
    }
  });

  it('says a rejection stands when its card cannot be updated', async () => {
    const click = handlers(async () => {
      throw new Error('slack down');
    });

    const respond = await click('ops_reject', 'act-u');

    expect(respond.mock.calls.at(-1)![0].text).toContain('stands as rejected');
  });

  it('a second Reject keeps the first rejecter on the card', async () => {
    const update = vi.fn(async () => ({}));
    const click = handlers(update);

    await click('ops_reject', 'act-rr');
    const respond = await click('ops_reject', 'act-rr');

    expect(update).toHaveBeenCalledOnce();
    expect(respond.mock.calls.at(-1)![0].text).toContain('already rejected');
  });

  it('an approval Redis cannot confirm is not sent, and releases nothing', async () => {
    const kv = new Map<string, string>([['ops-decision:act-y', 'approved:other']]);
    const fake = {
      set: vi.fn(async () => {
        throw new Error('timeout');
      }),
      get: vi.fn(async (k: string) => kv.get(k) ?? null),
      eval: vi.fn(async () => 1),
    };
    setDecisionStore(fake as never);
    try {
      const claim = await claimOpsDecision('act-y', 'approved');
      expect(claim).toEqual({ standing: 'unavailable', isNew: false });
      await releaseOpsDecision('act-y', claim);
      expect(fake.eval).not.toHaveBeenCalled();
      expect(kv.get('ops-decision:act-y')).toBe('approved:other');
    } finally {
      setDecisionStore(fakeRedis() as never);
    }
  });

  it('without a store, an approval is not sent', async () => {
    setDecisionStore(null);
    try {
      expect(await claimOpsDecision('act-n', 'approved')).toEqual({
        standing: 'unavailable',
        isNew: false,
      });
    } finally {
      setDecisionStore(fakeRedis() as never);
    }
  });

  it('withdraws a claim that lands after the approval was reported unsent', async () => {
    vi.useFakeTimers();
    let land: (v: string) => void = () => {};
    const evalCalls: unknown[][] = [];
    const fake = {
      set: vi.fn(() => new Promise<string>((resolve) => (land = resolve))),
      get: vi.fn(async () => null),
      eval: vi.fn(async (...args: unknown[]) => {
        evalCalls.push(args);
        return 1;
      }),
    };
    setDecisionStore(fake as never);
    try {
      const claim = claimOpsDecision('act-late', 'approved');
      await vi.advanceTimersByTimeAsync(2_500);
      expect(await claim).toEqual({ standing: 'unavailable', isNew: false });
      land('OK');
      await vi.advanceTimersByTimeAsync(0);
      expect(evalCalls).toHaveLength(1);
      expect(evalCalls[0]![2]).toBe('ops-decision:act-late');
    } finally {
      vi.useRealTimers();
      setDecisionStore(fakeRedis() as never);
    }
  });

  it('a Reject refused during an approval takes over when that approval is released', async () => {
    const redis = fakeRedis();
    setDecisionStore(redis as never);
    try {
      const approval = await claimOpsDecision('act-p', 'approved');
      expect(await claimOpsDecision('act-p', 'rejected')).toMatchObject({
        standing: 'approved',
        isNew: false,
      });

      await releaseOpsDecision('act-p', approval); // the host refused the approval

      expect(redis.kv.get('ops-decision:act-p')).toMatch(/^rejected:/);
      expect(await claimOpsDecision('act-p', 'approved')).toMatchObject({
        standing: 'rejected',
        isNew: false,
      });
    } finally {
      setDecisionStore(fakeRedis() as never);
    }
  });

  it('keeps the rejection when the release cannot reach Redis', async () => {
    const redis = fakeRedis();
    setDecisionStore(redis as never);
    try {
      const approval = await claimOpsDecision('act-q', 'approved');
      await claimOpsDecision('act-q', 'rejected'); // waits behind the approval
      redis.eval = async () => {
        throw new Error('timeout');
      };

      expect((await releaseOpsDecision('act-q', approval)).cleared).toBe(false);

      // This process still holds the rejection, so no later Approve here can send.
      expect(await claimOpsDecision('act-q', 'approved')).toMatchObject({
        standing: 'rejected',
        isNew: false,
      });
    } finally {
      setDecisionStore(fakeRedis() as never);
    }
  });

  /**
   * A store whose approval writes hang while `down` is set, as during a Redis outage: the claim
   * times out and its cleanup never runs. `unsent` collects the values those writes carried.
   */
  function hangingStore() {
    const redis = fakeRedis();
    const realSet = redis.set.bind(redis);
    const unsent = new Map<string, string>();
    const state = { down: true };
    redis.set = ((k: string, v: string, ...rest: unknown[]) => {
      if (state.down && v.startsWith('approved:')) {
        unsent.set(k, v);
        return new Promise(() => {});
      }
      return realSet(k, v, ...rest);
    }) as typeof redis.set;
    // The unsent claims land once Redis is back, but nothing is left to withdraw them.
    const recover = () => {
      state.down = false;
      for (const [k, v] of unsent) redis.kv.set(k, v);
    };
    return { redis, recover };
  }

  it('forgets a withdrawn claim after the approval window, and still sends nothing', async () => {
    vi.useFakeTimers();
    const { redis, recover } = hangingStore();
    setDecisionStore(redis as never);
    try {
      const claims = [
        claimOpsDecision('act-w1', 'approved'),
        claimOpsDecision('act-w2', 'approved'),
      ];
      await vi.advanceTimersByTimeAsync(2_500);
      for (const c of claims) expect(await c).toEqual({ standing: 'unavailable', isNew: false });
      recover();

      // Inside the window a retry takes its own unsent claim over.
      expect(await claimOpsDecision('act-w1', 'approved')).toMatchObject({
        standing: 'approved',
        isNew: true,
      });

      await vi.advanceTimersByTimeAsync(15 * 60_000);

      // Past it the record is gone: the unsent claim stands and the retry sends nothing.
      expect(await claimOpsDecision('act-w2', 'approved')).toEqual({
        standing: 'approved',
        isNew: false,
      });
    } finally {
      vi.useRealTimers();
      setDecisionStore(fakeRedis() as never);
    }
  });

  it('keeps at most 1,000 withdrawn claims, forgetting the oldest fail-closed', async () => {
    vi.useFakeTimers();
    const { redis, recover } = hangingStore();
    setDecisionStore(redis as never);
    try {
      const ids = Array.from({ length: 1_001 }, (_, i) => `act-b${i}`);
      const claims = ids.map((id) => claimOpsDecision(id, 'approved'));
      await vi.advanceTimersByTimeAsync(2_500);
      await Promise.all(claims);
      recover();

      // The oldest record was dropped: its unsent claim stands, nothing is sent.
      expect(await claimOpsDecision(ids[0]!, 'approved')).toEqual({
        standing: 'approved',
        isNew: false,
      });
      // The newest is still known, so its retry may take the key over.
      expect(await claimOpsDecision(ids[1_000]!, 'approved')).toMatchObject({
        standing: 'approved',
        isNew: true,
      });
    } finally {
      vi.useRealTimers();
      setDecisionStore(fakeRedis() as never);
    }
  });

  it('a Reject after a timed-out approval wins even if that approval lands late', async () => {
    vi.useFakeTimers();
    const redis = fakeRedis();
    let landLate: () => void = () => {};
    const realSet = redis.set.bind(redis);
    let first = true;
    redis.set = ((k: string, v: string, ...rest: unknown[]) => {
      if (first) {
        first = false;
        // The approval's write stalls, then lands after the timeout.
        return new Promise((resolve) => {
          landLate = () => void realSet(k, v, ...rest).then(resolve);
        });
      }
      return realSet(k, v, ...rest);
    }) as typeof redis.set;
    setDecisionStore(redis as never);
    try {
      const approve = claimOpsDecision('act-l', 'approved');
      await vi.advanceTimersByTimeAsync(2_500);
      expect(await approve).toMatchObject({ standing: 'unavailable' });
      landLate(); // the unsent approval lands in Redis
      await vi.advanceTimersByTimeAsync(0);
      const reject = await claimOpsDecision('act-l', 'rejected');
      await vi.advanceTimersByTimeAsync(0);

      expect(reject).toMatchObject({ standing: 'rejected', isNew: true });
      expect(redis.kv.get('ops-decision:act-l')).toMatch(/^rejected:/);
    } finally {
      vi.useRealTimers();
      setDecisionStore(fakeRedis() as never);
    }
  });
});
