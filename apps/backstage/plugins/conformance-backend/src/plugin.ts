import {
  coreServices,
  createBackendPlugin,
} from '@backstage/backend-plugin-api';
import { ConformanceSource } from './ConformanceSource';
import { createRouter } from './router';

/**
 * Serves the estate's baseline conformance results to the Baseline conformance
 * card at /api/conformance/conformance.json.
 *
 * @public
 */
export const conformancePlugin = createBackendPlugin({
  pluginId: 'conformance',
  register(env) {
    env.registerInit({
      deps: {
        config: coreServices.rootConfig,
        urlReader: coreServices.urlReader,
        httpRouter: coreServices.httpRouter,
        logger: coreServices.logger,
      },
      async init({ config, urlReader, httpRouter, logger }) {
        const sourceUrl = config.getOptionalString('conformance.sourceUrl');
        if (!sourceUrl) {
          logger.warn(
            'conformance.sourceUrl is not configured; /conformance.json answers 503',
          );
        }
        const source = sourceUrl
          ? new ConformanceSource({ urlReader, sourceUrl, logger })
          : undefined;
        httpRouter.use(await createRouter({ source }));
      },
    });
  },
});
