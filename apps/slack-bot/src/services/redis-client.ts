import { Redis } from 'ioredis';
import { logger } from '../logger.js';
import { config } from '../config.js';

const STREAMS = ['platform:alerts', 'platform:deployments'] as const;
const CONSUMER_GROUP = 'slack-bot';
const CONSUMER_NAME = 'bot-0';

export type StreamName = (typeof STREAMS)[number];

export function createRedisClient(): Redis {
  return new Redis(config.redis.url, {
    retryStrategy(times: number) {
      const delay = Math.min(1000 * 2 ** times, 30_000);
      logger.info({ attempt: times, delay }, 'Redis reconnecting');
      return delay;
    },
    maxRetriesPerRequest: null,
    lazyConnect: false,
  });
}

export async function ensureConsumerGroup(redis: Redis, stream: StreamName): Promise<void> {
  try {
    // Offset '0' (not '$') so a group rebuilt against a surviving stream replays
    // rather than drops anything already queued -- for an alerting path, a
    // duplicate post is a cheaper failure than a silently swallowed incident.
    await redis.xgroup('CREATE', stream, CONSUMER_GROUP, '0', 'MKSTREAM');
    logger.info({ stream, group: CONSUMER_GROUP }, 'Consumer group created');
  } catch (err) {
    if (err instanceof Error && err.message.includes('BUSYGROUP')) {
      logger.debug({ stream, group: CONSUMER_GROUP }, 'Consumer group already exists');
    } else {
      throw err;
    }
  }
}

export async function setupConsumerGroups(redis: Redis): Promise<void> {
  for (const stream of STREAMS) {
    await ensureConsumerGroup(redis, stream);
    // Seed liveness so a freshly started bot is not judged stale before its
    // first blocking read returns.
    lastSuccessfulRead.set(stream, Date.now());
  }
}

/**
 * Timestamp of the last XREADGROUP that returned without throwing, per stream.
 *
 * A *successful but empty* read still counts -- reads block for `blockMs` and
 * return [] on an idle stream, so this tracks "the consumer is working", not
 * "traffic exists". That distinction is the point: during the July 2026 outage
 * `redisConnected` stayed true the whole week because the TCP connection was
 * healthy; only the reads were failing. Readiness has to follow consumption.
 */
const lastSuccessfulRead = new Map<StreamName, number>();

/** Milliseconds since the least-recently-successful stream read. */
export function msSinceLastStreamRead(): number {
  if (lastSuccessfulRead.size === 0) return Number.POSITIVE_INFINITY;
  const oldest = Math.min(...lastSuccessfulRead.values());
  return Date.now() - oldest;
}

function isMissingGroupError(err: unknown): boolean {
  return err instanceof Error && err.message.includes('NOGROUP');
}

/**
 * Runs a stream read, self-healing the consumer group if it has vanished.
 *
 * Redis here is in-memory with no persistence, so a pod replacement wipes every
 * stream and consumer group. Groups used to be created only at startup, which
 * left a long-lived bot polling a group that no longer existed -- XREADGROUP
 * returned NOGROUP forever and alerts stopped reaching Slack and the ops-fleet
 * agent until someone restarted the bot. Recreate on demand and retry once.
 */
async function withGroupRecovery<T>(
  redis: Redis,
  stream: StreamName,
  operation: () => Promise<T>,
): Promise<T> {
  try {
    return await operation();
  } catch (err) {
    if (!isMissingGroupError(err)) throw err;

    logger.warn(
      { stream, group: CONSUMER_GROUP },
      'Consumer group missing (Redis likely restarted) -- recreating and retrying',
    );
    await ensureConsumerGroup(redis, stream);
    return await operation();
  }
}

export interface StreamMessage {
  id: string;
  fields: Record<string, string>;
}

/**
 * One XAUTOCLAIM batch from `start`: the messages idle for at least `minIdleMs`, and the cursor
 * to continue from ('0-0' once the pending list has been walked to its end).
 */
export async function claimPendingBatch(
  redis: Redis,
  stream: StreamName,
  start = '0-0',
  minIdleMs = 60_000,
  count = 100,
): Promise<{ next: string; messages: StreamMessage[] }> {
  const result = (await withGroupRecovery(redis, stream, () =>
    redis.xautoclaim(stream, CONSUMER_GROUP, CONSUMER_NAME, minIdleMs, start, 'COUNT', count),
  )) as [string, Array<[string, string[]]>];

  const messages = (result[1] ?? []).map(([id, fieldsArray]) => ({
    id,
    fields: arrayToObject(fieldsArray),
  }));
  return { next: result[0] ?? '0-0', messages };
}

/** The first batch of pending messages. */
export async function claimPendingMessages(
  redis: Redis,
  stream: StreamName,
  minIdleMs = 60_000,
): Promise<StreamMessage[]> {
  return (await claimPendingBatch(redis, stream, '0-0', minIdleMs)).messages;
}

export async function readNewMessages(
  redis: Redis,
  stream: StreamName,
  count = 10,
  blockMs = 5000,
): Promise<StreamMessage[]> {
  const result = (await withGroupRecovery(redis, stream, () =>
    redis.xreadgroup(
      'GROUP',
      CONSUMER_GROUP,
      CONSUMER_NAME,
      'COUNT',
      count,
      'BLOCK',
      blockMs,
      'STREAMS',
      stream,
      '>',
    ),
  )) as Array<[string, Array<[string, string[]]>]> | null;

  lastSuccessfulRead.set(stream, Date.now());

  if (!result || result.length === 0) return [];

  const [, messages] = result[0];
  return messages.map(([id, fieldsArray]) => ({
    id,
    fields: arrayToObject(fieldsArray),
  }));
}

export async function acknowledgeMessage(
  redis: Redis,
  stream: StreamName,
  messageId: string,
): Promise<void> {
  await redis.xack(stream, CONSUMER_GROUP, messageId);
}

function arrayToObject(arr: string[]): Record<string, string> {
  const obj: Record<string, string> = {};
  for (let i = 0; i < arr.length; i += 2) {
    obj[arr[i]] = arr[i + 1];
  }
  return obj;
}

export { CONSUMER_GROUP, CONSUMER_NAME, STREAMS };

/**
 * The newest `count` entries added since `sinceMs` (stream ids start with their millisecond
 * timestamp), oldest first, and how many entries the window holds, counted up to `countCap`
 * so a large backlog is never loaded whole.
 */
export async function readStreamSince(
  redis: Redis,
  stream: StreamName,
  sinceMs: number,
  count = 20,
  countCap = 1_000,
): Promise<{ entries: StreamMessage[]; total: number; capped: boolean }> {
  const start = `${sinceMs}-0`;
  const [newest, window] = await Promise.all([
    redis.xrevrange(stream, '+', start, 'COUNT', count) as Promise<Array<[string, string[]]>>,
    redis.xrange(stream, start, '+', 'COUNT', countCap) as Promise<Array<[string, string[]]>>,
  ]);
  const entries = newest
    .map(([id, fieldsArray]) => ({ id, fields: arrayToObject(fieldsArray) }))
    .reverse();
  return { entries, total: window.length, capped: window.length >= countCap };
}
