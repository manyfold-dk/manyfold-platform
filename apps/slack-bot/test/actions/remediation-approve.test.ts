import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';

vi.mock('../../src/logger.js', () => ({
  logger: { info: vi.fn(), warn: vi.fn(), debug: vi.fn(), error: vi.fn(), fatal: vi.fn() },
}));
vi.mock('../../src/config.js', () => ({
  config: {
    approval: { timeoutSeconds: 300, approverUserIds: ['U_APPROVER'] },
    backend: { url: 'http://backend', timeoutMs: 1000 },
  },
}));
const restartPod = vi.fn();
const restartDeployment = vi.fn();
vi.mock('../../src/services/backend-client.js', async () => {
  const actual = await vi.importActual<typeof import('../../src/services/backend-client.js')>(
    '../../src/services/backend-client.js',
  );
  return { ...actual, restartPod, restartDeployment };
});

const { registerApproveAction } = await import('../../src/actions/remediation-approve.js');
const { addPendingApproval } = await import('../../src/actions/approval-store.js');
const { RemediationNotAuthorizedError, RemediationHttpError } =
  await import('../../src/services/backend-client.js');

type Handler = (args: Record<string, unknown>) => Promise<void>;

function setup() {
  let handler: Handler | undefined;
  const app = { action: (_id: string, h: Handler) => (handler = h) };
  registerApproveAction(app as never);
  const client = { chat: { update: vi.fn(), postMessage: vi.fn() } };
  const click = async (userId: string, approvalId: string) => {
    const respond = vi.fn();
    await handler!({
      action: { type: 'button', value: approvalId },
      ack: vi.fn(),
      body: { user: { id: userId } },
      client,
      respond,
    });
    return respond;
  };
  return { client, click };
}

function pending(requestedBy = 'U_REQUESTER') {
  return addPendingApproval({
    action: 'restart',
    target: 'web-1',
    namespace: 'website',
    resourceType: 'pod',
    requestedBy,
    channelId: 'C1',
    messageTs: '1.0',
  });
}

describe('remediation_approve', () => {
  beforeEach(() => {
    restartPod.mockReset().mockResolvedValue({ success: true, message: 'ok' });
    restartDeployment.mockReset();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('refuses a user who is not an approver, and leaves the request pending', async () => {
    const { click } = setup();
    const id = pending();

    const respond = await click('U_SOMEONE', id);

    expect(respond).toHaveBeenCalledWith(expect.objectContaining({ response_type: 'ephemeral' }));
    expect(restartPod).not.toHaveBeenCalled();
    await click('U_APPROVER', id);
    expect(restartPod).toHaveBeenCalledTimes(1);
  });

  it('lets an approver approve their own request: the allowlist is the control', async () => {
    const { click } = setup();
    await click('U_APPROVER', pending('U_APPROVER'));
    expect(restartPod).toHaveBeenCalledWith('website', 'web-1');
  });

  it('acts once on a double click', async () => {
    const { click } = setup();
    const id = pending();

    await Promise.all([click('U_APPROVER', id), click('U_APPROVER', id)]);

    expect(restartPod).toHaveBeenCalledTimes(1);
  });

  it('still restarts when marking the message approved fails', async () => {
    const { client, click } = setup();
    client.chat.update.mockRejectedValueOnce(new Error('slack down'));

    await click('U_APPROVER', pending());

    expect(restartPod).toHaveBeenCalledTimes(1);
  });

  it('says so in the thread when the backend refuses the bot', async () => {
    const { client, click } = setup();
    restartPod.mockRejectedValue(new RemediationNotAuthorizedError(401));

    await click('U_APPROVER', pending());

    expect(client.chat.postMessage).toHaveBeenCalledWith(
      expect.objectContaining({ text: expect.stringContaining('refused') }),
    );
  });

  it('refuses a click after the timeout, before the cleanup sweep has run', async () => {
    vi.useFakeTimers();
    const { click } = setup();
    const id = pending();
    vi.setSystemTime(Date.now() + 301_000);

    const respond = await click('U_APPROVER', id);

    expect(restartPod).not.toHaveBeenCalled();
    expect(respond).toHaveBeenCalledWith(
      expect.objectContaining({ text: expect.stringContaining('expired') }),
    );
    // Left for the sweep, which marks the message expired.
    const { getPendingApproval } = await import('../../src/actions/approval-store.js');
    expect(getPendingApproval(id)).toBeDefined();
  });
});

describe('approval cleanup', () => {
  it('drops an expired approval and marks its message expired', async () => {
    vi.useFakeTimers();
    try {
      const { startApprovalCleanup, getPendingApproval } =
        await import('../../src/actions/approval-store.js');
      const id = pending();
      const client = { chat: { update: vi.fn().mockResolvedValue({}) } };
      startApprovalCleanup(client);

      await vi.advanceTimersByTimeAsync(360_000);

      expect(getPendingApproval(id)).toBeUndefined();
      expect(client.chat.update).toHaveBeenCalledWith(
        expect.objectContaining({ channel: 'C1', ts: '1.0', text: 'Remediation request expired' }),
      );
    } finally {
      vi.useRealTimers();
    }
  });
});

describe('remediation outcomes', () => {
  beforeEach(() => {
    restartPod.mockReset();
  });

  it('a 4xx means the restart did not run; a 5xx is unknown', async () => {
    let { client, click } = setup();
    restartPod.mockRejectedValue(new RemediationHttpError(400));
    await click('U_APPROVER', pending());
    expect(client.chat.postMessage).toHaveBeenCalledWith(
      expect.objectContaining({ text: expect.stringContaining('did not run') }),
    );

    ({ client, click } = setup());
    restartPod.mockRejectedValue(new RemediationHttpError(502));
    await click('U_APPROVER', pending());
    expect(client.chat.postMessage).toHaveBeenCalledWith(
      expect.objectContaining({ text: expect.stringContaining('may have happened') }),
    );
  });

  it('no answer is an unknown outcome', async () => {
    const { client, click } = setup();
    restartPod.mockRejectedValue(new TypeError('fetch failed'));
    await click('U_APPROVER', pending());
    expect(client.chat.postMessage).toHaveBeenCalledWith(
      expect.objectContaining({ text: expect.stringContaining('may have happened') }),
    );
  });
});
