import { describe, expect, it, vi } from 'vitest';

vi.mock('../../src/logger.js', () => ({
  logger: { info: vi.fn(), warn: vi.fn(), debug: vi.fn(), error: vi.fn(), fatal: vi.fn() },
}));
vi.mock('../../src/config.js', () => ({
  config: { approval: { timeoutSeconds: 300, approverUserIds: [] } },
}));

const { postApprovalRequest } = await import('../../src/actions/request-approval.js');
const { getPendingApproval, removePendingApproval, takePendingApproval } =
  await import('../../src/actions/approval-store.js');

const input = {
  channelId: 'C1',
  requestedBy: 'U1',
  action: 'Restart pod',
  target: 'shop-1',
  namespace: 'shop',
  resourceType: 'pod',
};

describe('postApprovalRequest', () => {
  it('posts buttons that carry the real approval id and records the message', async () => {
    const postMessage = vi.fn(async (_args: { blocks: unknown[] }) => ({ ts: '5.5' }));

    const id = await postApprovalRequest({ chat: { postMessage } } as never, input);

    expect(JSON.stringify(postMessage.mock.calls[0]![0].blocks)).toContain(id);
    expect(JSON.stringify(postMessage.mock.calls[0]![0].blocks)).not.toContain('"pending"');
    expect(getPendingApproval(id)?.messageTs).toBe('5.5');
  });

  it('leaves no approval behind when the post fails', async () => {
    const postMessage = vi.fn(async () => {
      throw new Error('not_in_channel');
    });
    let seen: string | undefined;
    const client = {
      chat: {
        postMessage: async (args: { blocks: unknown[] }) => {
          seen = JSON.stringify(args.blocks).match(/approval-[0-9]+-[0-9]+/)?.[0];
          return postMessage();
        },
      },
    };

    await expect(postApprovalRequest(client as never, input)).rejects.toThrow('not_in_channel');
    expect(seen).toBeDefined();
    expect(getPendingApproval(seen!)).toBeUndefined();
  });

  it('marks the card expired when the approval expired while posting', async () => {
    const update = vi.fn(async (_args: { text: string }) => ({}));
    const client = {
      chat: {
        postMessage: async (args: { blocks: unknown[] }) => {
          const id = JSON.stringify(args.blocks).match(/approval-[0-9]+-[0-9]+/)![0];
          removePendingApproval(id); // the sweep ran while Slack was posting
          return { ts: '9.9' };
        },
        update,
      },
    };

    await postApprovalRequest(client as never, input);

    expect(update).toHaveBeenCalledWith(
      expect.objectContaining({ ts: '9.9', text: 'Remediation request expired' }),
    );
  });

  it('leaves a card that a click handled before the post returned', async () => {
    const update = vi.fn(async () => ({}));
    const client = {
      chat: {
        postMessage: async (args: { blocks: unknown[] }) => {
          const id = JSON.stringify(args.blocks).match(/approval-[0-9]+-[0-9]+/)![0];
          takePendingApproval(id); // an approver clicked first
          return { ts: '7.7' };
        },
        update,
      },
    };

    await postApprovalRequest(client as never, input);

    expect(update).not.toHaveBeenCalled();
  });
});
