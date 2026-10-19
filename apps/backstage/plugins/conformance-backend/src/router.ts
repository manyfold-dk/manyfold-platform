import { ServiceUnavailableError } from '@backstage/errors';
import express from 'express';
import Router from 'express-promise-router';
import { ConformanceSource } from './ConformanceSource';

/**
 * One route, GET /conformance.json: the source document with the time the backend
 * fetched it added as fetched_at. The default auth policy applies, so the caller
 * needs a user or service token.
 */
export async function createRouter(options: {
  source?: ConformanceSource;
}): Promise<express.Router> {
  const { source } = options;
  const router = Router();

  router.get('/conformance.json', async (_req, res) => {
    if (!source) {
      throw new ServiceUnavailableError(
        'conformance.sourceUrl is not configured',
      );
    }
    let snapshot;
    try {
      snapshot = await source.get();
    } catch (error) {
      throw new ServiceUnavailableError(
        `Reading conformance.json failed: ${error}`,
      );
    }
    res.json({
      ...snapshot.document,
      fetched_at: snapshot.fetchedAt.toISOString(),
    });
  });

  return router;
}
