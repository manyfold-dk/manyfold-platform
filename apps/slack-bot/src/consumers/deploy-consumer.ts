import type { App } from '@slack/bolt';
import type { Redis } from 'ioredis';
import { logger } from '../logger.js';
import { config } from '../config.js';
import { acknowledgeMessage } from '../services/redis-client.js';
import { consumeStream } from './consume.js';
import { formatDeploySuccess, formatDeployFailure } from '../formatting/deploy-blocks.js';
import type { DeploymentEvent } from '../types/index.js';
import { BoundedSet } from '../services/bounded.js';

// Track processed run names for deduplication
const processedRuns = new BoundedSet<string>(10_000);

export function startDeployConsumer(app: App, redis: Redis): void {
  const stream = 'platform:deployments' as const;

  async function processMessage(id: string, fields: Record<string, string>): Promise<void> {
    const runName = fields.runName;

    if (processedRuns.has(runName)) {
      await acknowledgeMessage(redis, stream, id);
      return;
    }

    const event: DeploymentEvent = {
      pipeline: fields.pipeline,
      runName: fields.runName,
      status: fields.status as DeploymentEvent['status'],
      gitRevision: fields.gitRevision,
      gitUrl: fields.gitUrl,
      imageTag: fields.imageTag,
      environment: fields.environment,
      duration: fields.duration,
      timestamp: fields.timestamp,
    };

    try {
      const blocks =
        event.status === 'succeeded' ? formatDeploySuccess(event) : formatDeployFailure(event);

      const text =
        event.status === 'succeeded'
          ? `Pipeline ${event.pipeline} succeeded`
          : `Pipeline ${event.pipeline} ${event.status}`;

      await app.client.chat.postMessage({
        channel: config.channels.deployments,
        blocks,
        text,
      });

      processedRuns.add(runName);
      await acknowledgeMessage(redis, stream, id);

      logger.info(
        {
          pipeline: event.pipeline,
          runName: event.runName,
          status: event.status,
          channel: config.channels.deployments,
        },
        'Deployment posted to Slack',
      );
    } catch (err) {
      logger.error(
        { err, pipeline: event.pipeline, runName: event.runName, messageId: id },
        'Failed to post deployment to Slack',
      );
    }
  }

  consumeStream(redis, stream, processMessage).catch((err) => {
    logger.fatal({ err }, 'Deploy consumer loop crashed');
  });
}
