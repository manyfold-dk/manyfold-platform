import { randomUUID } from 'node:crypto';
import type { Redis } from 'ioredis';
import { config } from '../config.js';
import { logger } from '../logger.js';
import { BoundedMap, BoundedSet } from '../services/bounded.js';
import { createRedisClient } from '../services/redis-client.js';
import { withTimeout } from '../services/timeout.js';

export type OpsDecision = 'approved' | 'rejected';

// The first decision on an ops action wins. The ops host has no reject endpoint -- a rejection
// is an approval that never arrives -- so without this, an Approve racing a Reject, or an
// Approve on a card whose "Rejected" update failed, could run a rejected action.
//
// Decisions live in Redis (SET NX, kept a week), so they survive a restart or a rollout of the
// bot. When Redis cannot confirm a claim, an approval fails closed -- it is not sent, because an
// unconfirmed claim cannot rule out an earlier rejection -- while a rejection is recorded in
// this process anyway: it can only stop actions, never run one.
//
// Accepted limit: the platform's Redis keeps no volume, so a Redis restart followed by a bot
// restart forgets a rejection. A card whose "Rejected" update also failed could then still send
// an approval. The ops host bounds the harm: an approval is single-use and refused after 15
// minutes, so it matters only while the host is still waiting on that very action.
const DECISION_TTL_SECONDS = 7 * 24 * 3600;
const REDIS_TIMEOUT_MS = 2_000;
const memory = new BoundedMap<string, OpsDecision>(1_000);

let redis: Redis | undefined;

/**
 * For tests: the Redis client the decisions use (`null` forces the in-memory path), and a fresh
 * process's empty memory.
 */
export function setDecisionStore(client: Redis | null, { keepMemory = false } = {}): void {
  redis = client ?? undefined;
  useRedis = client !== null;
  if (!keepMemory) memory.clear();
}
let useRedis = true;

function store(): Redis | undefined {
  if (!useRedis) return undefined;
  if (redis) return redis;
  if (!config.redis?.url) return undefined;
  redis = createRedisClient();
  return redis;
}

const key = (actionId: string) => `ops-decision:${actionId}`;

/**
 * Sets the key to ARGV[2] when it holds this claim's own value (ARGV[1]) or holds nothing;
 * leaves any other decision alone.
 */
const REPLACE_OWN = `local v = redis.call('get', KEYS[1]) if v == false or v == ARGV[1] then return redis.call('set', KEYS[1], ARGV[2], 'EX', ARGV[3]) end return 0`;

/** Deletes the key only while it still holds this claim's own value. */
const RELEASE_OWN = `if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) end return 0`;

/**
 * What stands for an action: `decided` when another click decided but its value is unreadable,
 * `unavailable` when Redis could not confirm an approval's claim.
 */
export type Standing = OpsDecision | 'decided' | 'unavailable';

/**
 * The outcome of a click's claim. `token` is set only when this click wrote the decision to
 * Redis: it is what lets a release remove that decision and nothing else.
 */
export interface Claim {
  standing: Standing;
  isNew: boolean;
  token?: string;
  /** A rejection recorded in this process only: Redis did not confirm it. */
  unconfirmed?: boolean;
}

/** Stored as `decision:token`; the decision is the part before the colon. */
const decisionOf = (stored: string | null): OpsDecision | null =>
  stored ? (stored.split(':')[0] as OpsDecision) : null;

// Claims on one action run one at a time in this process, so no click ever sees another's
// half-finished claim; Redis (SET NX) decides between processes. `memory` holds only decisions
// that are settled: confirmed in Redis, or a rejection recorded here while Redis was down.
const inFlight = new Map<string, Promise<unknown>>();
// Rejections refused because an approval stood. If that approval is then released (it never
// reached the host), the rejection takes its place instead of being lost.
const pendingRejections = new BoundedSet<string>(1_000);
// Approval claims that timed out and are being withdrawn: their write may still land. A later
// claim that finds exactly this value may take the key over.
//
// Bounded in number and kept for the ops host's approval window only: during a long Redis outage
// each claim's write stays pending, so the cleanup below never runs for it. Forgetting a record
// keeps approvals fail-closed: a later claim then finds the unsent approval standing and sends
// nothing. What is lost is the retry, and past the window the host would refuse that anyway.
const WITHDRAW_WINDOW_MS = 15 * 60_000;
const withdrawing = new BoundedMap<string, string>(1_000);

function withdraw(actionId: string, value: string): void {
  withdrawing.set(actionId, value);
  setTimeout(() => {
    if (withdrawing.get(actionId) === value) withdrawing.delete(actionId);
  }, WITHDRAW_WINDOW_MS).unref?.();
}

function serialised<T>(actionId: string, work: () => Promise<T>): Promise<T> {
  const previous = inFlight.get(actionId) ?? Promise.resolve();
  const run = previous.catch(() => {}).then(work);
  const tail = run.catch(() => {});
  inFlight.set(actionId, tail);
  void tail.then(() => {
    if (inFlight.get(actionId) === tail) inFlight.delete(actionId);
  });
  return run;
}

/**
 * Records `decision` unless the action already has one. `isNew` is true only for the click that
 * made the decision: a repeated or concurrent Approve finds it taken and sends nothing.
 */
export function claimOpsDecision(actionId: string, decision: OpsDecision): Promise<Claim> {
  return serialised(actionId, () => claim(actionId, decision));
}

async function claim(actionId: string, decision: OpsDecision): Promise<Claim> {
  const settled = memory.get(actionId);
  if (settled) {
    if (decision === 'rejected' && settled === 'approved') pendingRejections.add(actionId);
    return { standing: settled, isNew: false };
  }

  const client = store();
  if (!client) {
    // No store to record it in: an approval cannot be proved first, so it is not sent.
    if (decision === 'approved') return { standing: 'unavailable', isNew: false };
    memory.set(actionId, decision);
    return { standing: decision, isNew: true };
  }

  const token = randomUUID();
  const value = `${decision}:${token}`;
  const write = client.set(key(actionId), value, 'EX', DECISION_TTL_SECONDS, 'NX');
  let set: string | null;
  try {
    set = await withTimeout(write, REDIS_TIMEOUT_MS);
  } catch (err) {
    if (decision === 'approved') {
      logger.warn({ err, actionId }, 'ops decision: Redis unavailable, approval not sent');
      // The write may still land once Redis answers. The operator was told nothing was sent,
      // so a late claim is withdrawn rather than left to refuse the retry.
      withdraw(actionId, value);
      write
        .then((late) =>
          serialised(actionId, async () => {
            // Deletes only while the key still holds this unsent claim.
            // Bounded, so the queue for this action moves on whether or not Redis answers. The
            // withdrawal record is dropped only once the unsent claim is gone; until then a later
            // click can still take the key over.
            if (late === 'OK') {
              await withTimeout(
                client.eval(RELEASE_OWN, 1, key(actionId), value),
                REDIS_TIMEOUT_MS,
              ).catch(() => {});
              const now = await withTimeout(client.get(key(actionId)), REDIS_TIMEOUT_MS).catch(
                () => value,
              );
              if (now === value) return;
            }
            if (withdrawing.get(actionId) === value) withdrawing.delete(actionId);
          }),
        )
        .catch(() => {});
      return { standing: 'unavailable', isNew: false };
    }
    logger.warn(
      { err, actionId },
      'ops decision: Redis unavailable, rejection kept in this process',
    );
    memory.set(actionId, decision);
    // The write may still land; if it fails, the rejection is written again until Redis takes
    // it or the ops host's approval window (15 minutes) has passed.
    write.catch(() => persistLater(client, actionId, value));
    return { standing: decision, isNew: true, unconfirmed: true };
  }
  if (set === 'OK') {
    memory.set(actionId, decision);
    return { standing: decision, isNew: true, token };
  }

  // Redis already holds a decision. It stands, even if it cannot be read -- unless it is this
  // process's own approval that timed out, was never sent and is being withdrawn.
  let stored: string | null = null;
  try {
    stored = await withTimeout(client.get(key(actionId)), REDIS_TIMEOUT_MS);
  } catch (err) {
    logger.warn({ err, actionId }, 'ops decision: taken in Redis, value unreadable');
  }
  const unsent = withdrawing.get(actionId);
  if (stored !== null && stored === unsent) {
    try {
      const swapped = await withTimeout(
        client.eval(REPLACE_OWN, 1, key(actionId), unsent, value, String(DECISION_TTL_SECONDS)),
        REDIS_TIMEOUT_MS,
      );
      if (swapped === 'OK') {
        withdrawing.delete(actionId);
        memory.set(actionId, decision);
        return { standing: decision, isNew: true, token };
      }
    } catch (err) {
      logger.warn({ err, actionId }, 'ops decision: could not take over a withdrawn claim');
    }
  }
  return { standing: decisionOf(stored) ?? 'decided', isNew: false };
}

/**
 * Forgets an approval that provably never reached the host, so it can be tried again. False when
 * Redis could not be cleared: a retry may then be refused as already approved, and the caller
 * says so.
 */
/** Retries an unconfirmed rejection's write every 30 s for 15 minutes. */
function persistLater(client: Redis, actionId: string, value: string, attempt = 1): void {
  if (attempt > 30) return;
  setTimeout(() => {
    withTimeout(
      client.set(key(actionId), value, 'EX', DECISION_TTL_SECONDS, 'NX'),
      REDIS_TIMEOUT_MS,
    ).catch(() => persistLater(client, actionId, value, attempt + 1));
  }, 30_000).unref?.();
}

/** What a release did: whether Redis was cleared, and whether a waiting rejection took over. */
export interface Released {
  cleared: boolean;
  rejectedInstead: boolean;
}

export function releaseOpsDecision(actionId: string, claim: Claim): Promise<Released> {
  return serialised(actionId, () => release(actionId, claim));
}

async function release(actionId: string, claimed: Claim): Promise<Released> {
  const rejectInstead = pendingRejections.has(actionId);
  pendingRejections.delete(actionId);
  // Memory first, so there is never a moment with no decision: a waiting rejection replaces
  // the approval here before Redis is touched.
  if (rejectInstead) memory.set(actionId, 'rejected');
  else if (claimed.isNew && memory.get(actionId) === claimed.standing) memory.delete(actionId);

  const client = store();
  if (!client) return { cleared: true, rejectedInstead: rejectInstead };
  try {
    if (rejectInstead) {
      // Swap this click's approval for the rejection in one step, or record the rejection if
      // the approval was never written. Either way Redis ends up rejected, even if late.
      await withTimeout(
        client.eval(
          REPLACE_OWN,
          1,
          key(actionId),
          claimed.token ? `${claimed.standing}:${claimed.token}` : '',
          `rejected:${randomUUID()}`,
          String(DECISION_TTL_SECONDS),
        ),
        REDIS_TIMEOUT_MS,
      );
      return { cleared: true, rejectedInstead: rejectInstead };
    }
    // Only a decision this click wrote: without a token it owns nothing in Redis.
    if (!claimed.token) return { cleared: true, rejectedInstead: rejectInstead };
    await withTimeout(
      client.eval(RELEASE_OWN, 1, key(actionId), `${claimed.standing}:${claimed.token}`),
      REDIS_TIMEOUT_MS,
    );
    return { cleared: true, rejectedInstead: rejectInstead };
  } catch (err) {
    logger.warn({ err, actionId }, 'ops decision: could not update Redis on release');
    return { cleared: false, rejectedInstead: rejectInstead };
  }
}
