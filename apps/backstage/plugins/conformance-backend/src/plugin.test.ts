import { mockServices, startTestBackend } from '@backstage/backend-test-utils';
import request from 'supertest';
import { conformancePlugin } from './plugin';

const document = {
  generated_at: '2026-10-19T00:00:00Z',
  estate_baseline_commit: '0'.repeat(40),
  results: [
    {
      repository: 'example-org/one',
      check: 'agent',
      exit_code: 3,
      timestamp: '2026-10-19T00:00:00Z',
      deviation_adrs: ['https://example.com/docs/adr/0001-deviation.md'],
    },
  ],
};

describe('conformancePlugin', () => {
  it('serves conformance.json with the fetch time', async () => {
    const readUrl = jest.fn().mockResolvedValue({
      buffer: async () => Buffer.from(JSON.stringify(document)),
    });
    const { server } = await startTestBackend({
      features: [
        conformancePlugin,
        mockServices.rootConfig.factory({
          data: {
            conformance: { sourceUrl: 'https://example.com/conformance.json' },
          },
        }),
        mockServices.urlReader.mock({ readUrl }).factory,
      ],
    });

    const res = await request(server).get('/api/conformance/conformance.json');
    expect(res.status).toBe(200);
    expect(res.body).toMatchObject(document);
    expect(Date.parse(res.body.fetched_at)).not.toBeNaN();
    expect(readUrl).toHaveBeenCalledWith(
      'https://example.com/conformance.json',
    );
  });

  it('answers 503 without a source URL', async () => {
    const { server } = await startTestBackend({
      features: [conformancePlugin],
    });
    const res = await request(server).get('/api/conformance/conformance.json');
    expect(res.status).toBe(503);
    expect(res.body.error.message).toBe(
      'conformance.sourceUrl is not configured',
    );
  });

  it('answers 503 when the first read fails', async () => {
    const { server } = await startTestBackend({
      features: [
        conformancePlugin,
        mockServices.rootConfig.factory({
          data: {
            conformance: { sourceUrl: 'https://example.com/conformance.json' },
          },
        }),
        mockServices.urlReader.mock({
          readUrl: jest.fn().mockRejectedValue(new Error('not found')),
        }).factory,
      ],
    });
    const res = await request(server).get('/api/conformance/conformance.json');
    expect(res.status).toBe(503);
  });

  it('refuses a request without credentials', async () => {
    const { server } = await startTestBackend({
      features: [conformancePlugin],
    });
    const res = await request(server)
      .get('/api/conformance/conformance.json')
      .set('Authorization', 'Bearer mock-none-token');
    expect(res.status).toBe(401);
  });
});
