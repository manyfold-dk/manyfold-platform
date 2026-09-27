import { App, LogLevel, SocketModeReceiver } from '@slack/bolt';
import { createServer } from 'node:http';
import { config, missingSettings } from './config.js';
import { logger } from './logger.js';
import { registerCommands } from './commands/index.js';
import { registerActions } from './actions/index.js';
import {
  createRedisClient,
  setupConsumerGroups,
  msSinceLastStreamRead,
} from './services/redis-client.js';
import { startAlertConsumer } from './consumers/alert-consumer.js';
import { startDeployConsumer } from './consumers/deploy-consumer.js';
import { startDigestScheduler } from './scheduler/digest.js';
import { startApprovalCleanup } from './actions/approval-store.js';
import { createOpsFleetForwarder } from './services/ops-fleet-forwarder.js';

// Before the Bolt app is built: its constructor throws on a missing token with a message that
// does not name the setting.
const missing = missingSettings();
if (missing.length > 0) {
  logger.fatal({ missing }, `Required settings are not set: ${missing.join(', ')}`);
  process.exit(1);
}

const receiver = new SocketModeReceiver({
  appToken: config.slack.appToken,
  logLevel: LogLevel.INFO,
});

const app = new App({
  token: config.slack.botToken,
  receiver,
  signingSecret: config.slack.signingSecret,
  logLevel: LogLevel.INFO,
});

registerCommands(app);
registerActions(app);

let redisConnected = false;
let socketModeConnected = false;
// True once both stream consumers run; setup can retry for a while before that.
let consumersStarted = false;
// Readiness follows the socket: a drop after start must show, not only the first connect.
receiver.client.on('connected', () => {
  socketModeConnected = true;
});
// `close` comes first when the socket drops; `reconnecting` only after the retry delay.
for (const state of ['close', 'reconnecting', 'disconnected']) {
  receiver.client.on(state, () => {
    socketModeConnected = false;
    logger.warn({ state }, 'Slack Socket Mode connection lost');
  });
}
let backendReachable = false;

const healthServer = createServer(async (req, res) => {
  if (req.url === '/healthz') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ status: 'ok' }));
    return;
  }

  if (req.url === '/readyz') {
    // Consuming, not merely connected. A live TCP connection to Redis says
    // nothing about whether XREADGROUP is succeeding -- see msSinceLastStreamRead.
    const readAgeMs = msSinceLastStreamRead();
    const consuming = readAgeMs < config.consumers.stallThresholdMs;
    const ready =
      socketModeConnected &&
      (!config.consumers.enabled || (consumersStarted && redisConnected && consuming));
    const status = ready ? 200 : 503;
    res.writeHead(status, { 'Content-Type': 'application/json' });
    res.end(
      JSON.stringify({
        status: ready ? 'ready' : 'not ready',
        checks: {
          socketMode: socketModeConnected,
          consumersStarted,
          redis: redisConnected,
          consuming,
          lastStreamReadAgeMs: Number.isFinite(readAgeMs) ? readAgeMs : null,
          backend: backendReachable,
        },
      }),
    );
    return;
  }

  res.writeHead(404);
  res.end();
});

async function checkBackendHealth(): Promise<void> {
  try {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), config.backend.timeoutMs);
    const response = await fetch(`${config.backend.url}/api/v1/health/summary`, {
      signal: controller.signal,
    });
    clearTimeout(timeout);
    backendReachable = response.ok;
  } catch {
    backendReachable = false;
  }
}

/**
 * Connects the stream consumers. It can wait on Redis for as long as Redis is down, so start()
 * does not wait for it: the bot, its approvals and the digest do not depend on it.
 */
async function startConsumers(): Promise<void> {
  if (config.consumers.enabled) {
    try {
      const redis = createRedisClient();
      redis.on('connect', () => {
        redisConnected = true;
        logger.info('Redis connected');
      });
      redis.on('error', (err: Error) => {
        redisConnected = false;
        logger.error({ err }, 'Redis connection error');
      });

      // A transient Redis error (LOADING, READONLY, a restart) must not leave the consumers
      // stopped for the life of the pod: keep trying, backing off to a minute.
      for (let attempt = 1; ; attempt++) {
        try {
          await setupConsumerGroups(redis);
          break;
        } catch (err) {
          const delayMs = Math.min(60_000, 5_000 * attempt);
          logger.error({ err, attempt, delayMs }, 'Consumer setup failed; retrying');
          await new Promise((resolve) => setTimeout(resolve, delayMs));
        }
      }

      const opsFleet =
        config.opsFleet.webhookUrl && config.opsFleet.webhookToken
          ? createOpsFleetForwarder({
              webhookUrl: config.opsFleet.webhookUrl,
              webhookToken: config.opsFleet.webhookToken,
              timeoutMs: config.opsFleet.timeoutMs,
              logger,
            })
          : undefined;
      if (opsFleet) {
        logger.info({ webhookUrl: config.opsFleet.webhookUrl }, 'ops-fleet forwarder enabled');
      } else {
        logger.info('ops-fleet forwarder disabled (missing config)');
      }

      startAlertConsumer(app, redis, opsFleet);
      startDeployConsumer(app, redis);
      consumersStarted = true;
      logger.info('Stream consumers started');
    } catch (err) {
      logger.error({ err }, 'Failed to initialize Redis consumers');
    }
  } else {
    logger.info('Stream consumers disabled (CONSUMERS_ENABLED=false)');
  }
}

async function start(): Promise<void> {
  logger.info('Starting slack-bot...');

  // Start health endpoint server
  healthServer.listen(config.port, () => {
    logger.info({ port: config.port }, 'Health server listening');
  });

  // Everything that does not need a live Slack socket starts first and is not held up by it:
  // the stream consumers (which can wait on Redis themselves, see startConsumers), the
  // digest, the approval sweep and the backend check use the Web API or nothing of Slack's.
  void startConsumers();

  if (config.digest.enabled) {
    startDigestScheduler(app);
    logger.info('Digest scheduler started');
  }

  startApprovalCleanup(app.client);

  void checkBackendHealth();
  setInterval(checkBackendHealth, 30_000);

  // Socket Mode last: slash commands and buttons. It may wait for Slack; nothing above does.
  await app.start();
  logger.info('Slack Socket Mode connected');

  logger.info('slack-bot started successfully');
}

start().catch((err) => {
  logger.fatal({ err }, 'Failed to start slack-bot');
  process.exit(1);
});
