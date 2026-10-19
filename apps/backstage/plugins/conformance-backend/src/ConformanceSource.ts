import { LoggerService, UrlReaderService } from '@backstage/backend-plugin-api';

/** How long a fetched conformance.json is served before the next read. */
export const DEFAULT_TTL_MS = 5 * 60 * 1000;

/** A fetched conformance.json and the time the backend read it. */
export interface ConformanceSnapshot {
  document: Record<string, unknown>;
  fetchedAt: Date;
}

/**
 * Reads conformance.json through the URL reader, which carries the GitHub
 * integration's token, and keeps the parsed body for a fixed time. Concurrent
 * requests share one read. A failed read serves the previous body, if any, and
 * otherwise throws.
 */
export class ConformanceSource {
  private readonly urlReader: UrlReaderService;
  private readonly sourceUrl: string;
  private readonly logger: LoggerService;
  private readonly ttlMs: number;
  private readonly now: () => Date;
  private cached?: ConformanceSnapshot;
  private inflight?: Promise<ConformanceSnapshot>;

  constructor(options: {
    urlReader: UrlReaderService;
    sourceUrl: string;
    logger: LoggerService;
    ttlMs?: number;
    now?: () => Date;
  }) {
    this.urlReader = options.urlReader;
    this.sourceUrl = options.sourceUrl;
    this.logger = options.logger;
    this.ttlMs = options.ttlMs ?? DEFAULT_TTL_MS;
    this.now = options.now ?? (() => new Date());
  }

  async get(): Promise<ConformanceSnapshot> {
    const cached = this.cached;
    if (
      cached &&
      this.now().getTime() - cached.fetchedAt.getTime() < this.ttlMs
    ) {
      return cached;
    }
    if (!this.inflight) {
      this.inflight = this.read().finally(() => {
        this.inflight = undefined;
      });
    }
    return this.inflight;
  }

  private async read(): Promise<ConformanceSnapshot> {
    try {
      const response = await this.urlReader.readUrl(this.sourceUrl);
      const text = (await response.buffer()).toString('utf8');
      const document = parseConformanceDocument(text);
      this.cached = { document, fetchedAt: this.now() };
      return this.cached;
    } catch (error) {
      if (this.cached) {
        this.logger.warn(
          `Reading conformance.json failed, serving the copy from ${this.cached.fetchedAt.toISOString()}: ${error}`,
        );
        return this.cached;
      }
      throw error;
    }
  }
}

/** Parses the file and checks the one field the card cannot do without. */
export function parseConformanceDocument(
  text: string,
): Record<string, unknown> {
  const document: unknown = JSON.parse(text);
  if (
    typeof document !== 'object' ||
    document === null ||
    Array.isArray(document) ||
    !Array.isArray((document as Record<string, unknown>).results)
  ) {
    throw new Error('conformance.json is not an object with a results array');
  }
  return document as Record<string, unknown>;
}
