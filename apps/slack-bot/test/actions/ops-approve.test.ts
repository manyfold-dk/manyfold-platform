import { afterEach, describe, expect, it, vi } from 'vitest';
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
      approverUserIds: ['U_APPROVER'],
    },
  },
}));

const { setDecisionStore } = await import('../../src/actions/ops-decisions.js');
setDecisionStore(fakeRedis() as never);
const { registerOpsApproveAction } = await import('../../src/actions/ops-approve.js');

type Handler = (args: Record<string, unknown>) => Promise<void>;

// Each click gets its own action id: the first decision on an id stands for the process.
let nextAction = 0;

function setup(update: () => Promise<unknown>) {
  let handler: Handler | undefined;
  registerOpsApproveAction({ action: (_id: string, h: Handler) => (handler = h) } as never);
  const respond = vi.fn();
  const click = () =>
    handler!({
      action: { type: 'button', value: `act-${++nextAction}` },
      ack: vi.fn(),
      body: {
        user: { id: 'U_APPROVER' },
        message: { ts: '1.0' },
        channel: { id: 'C1' },
      },
      client: { chat: { update: vi.fn(update) } },
      respond,
    });
  return { click, respond };
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('ops_approve', () => {
  it('says the approval arrived when only the message update fails', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => new Response(null, { status: 200 })),
    );
    const { click, respond } = setup(async () => {
      throw new Error('slack down');
    });

    await click();

    const text = respond.mock.calls.at(-1)![0].text as string;
    expect(text).toContain('reached the VPS');
    expect(text).not.toContain('did not receive');
  });

  it('reports a refused connection as not approved: nothing was sent', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => {
        throw new TypeError('fetch failed', { cause: { code: 'ECONNREFUSED' } });
      }),
    );
    const { click, respond } = setup(async () => ({}));

    await click();

    expect(respond.mock.calls.at(-1)![0].text).toContain('did not receive the approval');
  });

  it('reports a connection lost mid-request as an unknown outcome', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => {
        throw new TypeError('fetch failed', { cause: { code: 'ECONNRESET' } });
      }),
    );
    const { click, respond } = setup(async () => ({}));

    await click();

    const text = respond.mock.calls.at(-1)![0].text as string;
    expect(text).toContain('may have arrived');
    expect(text).not.toContain('did not receive');
  });

  it('passes an abort signal, so a silent webhook cannot hang the approval', async () => {
    const fetchMock = vi.fn(async (_url: string, init?: RequestInit) => {
      expect(init?.signal).toBeInstanceOf(AbortSignal);
      return new Response(null, { status: 200 });
    });
    vi.stubGlobal('fetch', fetchMock);
    const { click } = setup(async () => ({}));

    await click();

    expect(fetchMock).toHaveBeenCalledOnce();
  });

  it('reports a timeout as an unknown outcome, not as non-delivery', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(
        (_url: string, init?: RequestInit) =>
          new Promise((_resolve, reject) =>
            init?.signal?.addEventListener('abort', () => reject(new Error('aborted'))),
          ),
      ),
    );
    const { click, respond } = setup(async () => ({}));

    await click();

    const text = respond.mock.calls.at(-1)![0].text as string;
    expect(text).toContain('may have arrived');
    expect(text).not.toContain('did not receive');
  });

  it('reads a 4xx as refused and a 5xx as unknown', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => new Response(null, { status: 409 })),
    );
    let { click, respond } = setup(async () => ({}));
    await click();
    expect(respond.mock.calls.at(-1)![0].text).toContain('was not approved');

    vi.stubGlobal(
      'fetch',
      vi.fn(async () => new Response(null, { status: 502 })),
    );
    ({ click, respond } = setup(async () => ({})));
    await click();
    const text = respond.mock.calls.at(-1)![0].text as string;
    expect(text).toContain('may have arrived');
    expect(text).not.toContain('was not approved');
  });
});
