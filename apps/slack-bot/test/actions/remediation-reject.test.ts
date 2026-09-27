import { describe, it, expect, vi } from 'vitest';

vi.mock('../../src/logger.js', () => ({
  logger: { info: vi.fn(), warn: vi.fn(), debug: vi.fn(), error: vi.fn(), fatal: vi.fn() },
}));
vi.mock('../../src/config.js', () => ({
  config: { approval: { timeoutSeconds: 300, approverUserIds: ['U_APPROVER'] } },
}));

const { registerRejectAction } = await import('../../src/actions/remediation-reject.js');
const { addPendingApproval, getPendingApproval } =
  await import('../../src/actions/approval-store.js');

type Handler = (args: Record<string, unknown>) => Promise<void>;

function setup() {
  let handler: Handler | undefined;
  registerRejectAction({ action: (_id: string, h: Handler) => (handler = h) } as never);
  const client = { chat: { update: vi.fn() } };
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

function pending() {
  return addPendingApproval({
    action: 'restart',
    target: 'web-1',
    namespace: 'website',
    resourceType: 'pod',
    requestedBy: 'U_REQUESTER',
    channelId: 'C1',
    messageTs: '1.0',
  });
}

describe('remediation_reject', () => {
  it('refuses a bystander and leaves the request pending', async () => {
    const { client, click } = setup();
    const id = pending();

    const respond = await click('U_SOMEONE', id);

    expect(respond).toHaveBeenCalledWith(expect.objectContaining({ response_type: 'ephemeral' }));
    expect(client.chat.update).not.toHaveBeenCalled();
    expect(getPendingApproval(id)).toBeDefined();
  });

  it.each(['U_APPROVER', 'U_REQUESTER'])('lets %s reject, once', async (user) => {
    const { client, click } = setup();
    const id = pending();

    await click(user, id);
    await click(user, id);

    expect(client.chat.update).toHaveBeenCalledTimes(1);
    expect(getPendingApproval(id)).toBeUndefined();
  });
});
