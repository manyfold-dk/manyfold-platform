import { describe, it, expect, vi, afterEach } from 'vitest';

vi.mock('../../src/logger.js', () => ({
  logger: { info: vi.fn(), warn: vi.fn(), debug: vi.fn(), error: vi.fn(), fatal: vi.fn() },
}));
vi.mock('../../src/config.js', () => ({
  config: { backend: { url: 'http://backend', timeoutMs: 1000 } },
}));

const { restartPod, restartDeployment, RemediationNotAuthorizedError } =
  await import('../../src/services/backend-client.js');

function reply(status: number, body: unknown = { success: true, message: 'ok' }) {
  return vi.fn().mockResolvedValue({ ok: status < 400, status, json: () => Promise.resolve(body) });
}

describe('backend remediation client', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('sends the field names the backend reads', async () => {
    const fetchMock = reply(200);
    vi.stubGlobal('fetch', fetchMock);

    await restartPod('website', 'web-1');
    await restartDeployment('website', 'web');

    expect(JSON.parse(fetchMock.mock.calls[0][1].body)).toEqual({
      namespace: 'website',
      podName: 'web-1',
    });
    expect(JSON.parse(fetchMock.mock.calls[1][1].body)).toEqual({
      namespace: 'website',
      deploymentName: 'web',
    });
  });

  it('turns 401 and 403 into a refusal the caller can name', async () => {
    vi.stubGlobal('fetch', reply(401));
    await expect(restartPod('website', 'web-1')).rejects.toBeInstanceOf(
      RemediationNotAuthorizedError,
    );
    vi.stubGlobal('fetch', reply(403));
    await expect(restartDeployment('website', 'web')).rejects.toBeInstanceOf(
      RemediationNotAuthorizedError,
    );
  });

  it('does not read an error page as a result', async () => {
    vi.stubGlobal('fetch', reply(500, { oops: true }));
    await expect(restartPod('website', 'web-1')).rejects.toThrow('HTTP 500');
  });
});
