import type { Redis } from 'ioredis';
import { logger } from '../logger.js';
import { claimPendingBatch, readNewMessages, type StreamName } from '../services/redis-client.js';

type Handler = (id: string, fields: Record<string, string>) => Promise<void>;

/**
 * Reads a stream in the consumer group for as long as the process runs. A handler that fails
 * leaves its message unacknowledged; such messages are claimed again every `reclaimEveryMs`
 * (and at start), so a failed Slack post is retried while the bot keeps running, not only after
 * a restart. A pass retries at most `reclaimBatch` messages and continues from its cursor next
 * time: a retry can wait out a timeout (an unreachable ops host), and new messages must not wait
 * behind a long backlog. `rounds` bounds the loop for tests.
 */
export async function consumeStream(
  redis: Redis,
  stream: StreamName,
  handle: Handler,
  {
    reclaimEveryMs = 60_000,
    reclaimBatch = 5,
    rounds = Infinity,
  }: { reclaimEveryMs?: number; reclaimBatch?: number; rounds?: number } = {},
): Promise<void> {
  let lastReclaim = -Infinity;
  // Walks the pending list across passes, so messages beyond the first batch are reached even
  // when the first batch keeps failing.
  let cursor = '0-0';
  for (let round = 0; round < rounds; round++) {
    try {
      if (Date.now() - lastReclaim >= reclaimEveryMs) {
        const batch = await claimPendingBatch(redis, stream, cursor, 60_000, reclaimBatch);
        cursor = batch.next;
        for (const msg of batch.messages) {
          await handle(msg.id, msg.fields);
        }
        // Counted from the end of the pass: however long retries take, a full interval of new
        // reads follows, so fresh messages are never starved by a backlog of failing ones.
        lastReclaim = Date.now();
      }
      for (const msg of await readNewMessages(redis, stream)) {
        await handle(msg.id, msg.fields);
      }
    } catch (err) {
      logger.error({ err, stream }, 'Stream consumer error');
      await new Promise((resolve) => setTimeout(resolve, 5000));
    }
  }
}
