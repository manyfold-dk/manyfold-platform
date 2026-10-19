import { mockServices } from '@backstage/backend-test-utils';
import {
  ConformanceSource,
  parseConformanceDocument,
} from './ConformanceSource';

const body = JSON.stringify({
  generated_at: '2026-10-19T00:00:00Z',
  results: [
    {
      repository: 'example-org/one',
      check: 'conformance',
      exit_code: 0,
      timestamp: '2026-10-19T00:00:00Z',
    },
  ],
});

function readerReturning(...bodies: Array<string | Error>) {
  const readUrl = jest.fn();
  for (const b of bodies) {
    if (b instanceof Error) {
      readUrl.mockRejectedValueOnce(b);
    } else {
      readUrl.mockResolvedValueOnce({ buffer: async () => Buffer.from(b) });
    }
  }
  return { reader: mockServices.urlReader.mock({ readUrl }), readUrl };
}

describe('ConformanceSource', () => {
  const sourceUrl = 'https://example.com/conformance.json';
  const logger = mockServices.logger.mock();

  it('serves the cached body until the time to live has passed', async () => {
    let now = new Date('2026-10-19T00:00:00Z');
    const { reader, readUrl } = readerReturning(body, body);
    const source = new ConformanceSource({
      urlReader: reader,
      sourceUrl,
      logger,
      ttlMs: 1000,
      now: () => now,
    });

    const first = await source.get();
    expect(readUrl).toHaveBeenCalledWith(sourceUrl);
    expect(first.fetchedAt).toEqual(now);

    now = new Date(now.getTime() + 999);
    await source.get();
    expect(readUrl).toHaveBeenCalledTimes(1);

    now = new Date(now.getTime() + 1);
    const second = await source.get();
    expect(readUrl).toHaveBeenCalledTimes(2);
    expect(second.fetchedAt).toEqual(now);
  });

  it('shares one read between concurrent requests', async () => {
    const { reader, readUrl } = readerReturning(body);
    const source = new ConformanceSource({
      urlReader: reader,
      sourceUrl,
      logger,
    });
    await Promise.all([source.get(), source.get(), source.get()]);
    expect(readUrl).toHaveBeenCalledTimes(1);
  });

  it('serves the previous body when a later read fails', async () => {
    let now = new Date('2026-10-19T00:00:00Z');
    const { reader } = readerReturning(body, new Error('rate limited'));
    const source = new ConformanceSource({
      urlReader: reader,
      sourceUrl,
      logger,
      ttlMs: 1000,
      now: () => now,
    });
    const first = await source.get();
    now = new Date(now.getTime() + 5000);
    await expect(source.get()).resolves.toBe(first);
  });

  it('waits out the backoff before reading again after a failure', async () => {
    let now = new Date('2026-10-19T00:00:00Z');
    const { reader, readUrl } = readerReturning(
      body,
      new Error('rate limited'),
      new Error('rate limited'),
      body,
    );
    const source = new ConformanceSource({
      urlReader: reader,
      sourceUrl,
      logger,
      ttlMs: 1000,
      retryBackoffMs: 500,
      now: () => now,
    });
    const first = await source.get();

    now = new Date(now.getTime() + 5000);
    await expect(source.get()).resolves.toBe(first);
    expect(readUrl).toHaveBeenCalledTimes(2);

    now = new Date(now.getTime() + 499);
    await expect(source.get()).resolves.toBe(first);
    expect(readUrl).toHaveBeenCalledTimes(2);

    now = new Date(now.getTime() + 1);
    await expect(source.get()).resolves.toBe(first);
    expect(readUrl).toHaveBeenCalledTimes(3);

    now = new Date(now.getTime() + 500);
    const fresh = await source.get();
    expect(readUrl).toHaveBeenCalledTimes(4);
    expect(fresh.fetchedAt).toEqual(now);
  });

  it('throws when the first read fails', async () => {
    const { reader } = readerReturning(new Error('not found'));
    const source = new ConformanceSource({
      urlReader: reader,
      sourceUrl,
      logger,
    });
    await expect(source.get()).rejects.toThrow('not found');
  });
});

describe('parseConformanceDocument', () => {
  it('accepts an object with a results array', () => {
    expect(parseConformanceDocument(body)).toMatchObject({
      generated_at: '2026-10-19T00:00:00Z',
    });
  });

  it.each(['[]', '{}', '{"results": {}}', 'null'])('rejects %s', text => {
    expect(() => parseConformanceDocument(text)).toThrow(
      'not an object with a results array',
    );
  });

  it('rejects text that is not JSON', () => {
    expect(() => parseConformanceDocument('<html>')).toThrow();
  });
});
