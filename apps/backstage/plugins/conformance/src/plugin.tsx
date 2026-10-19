import { createFrontendPlugin } from '@backstage/frontend-plugin-api';
import { EntityCardBlueprint } from '@backstage/plugin-catalog-react/alpha';

/** The Baseline conformance card on every Component's overview page. */
export const baselineConformanceCard = EntityCardBlueprint.make({
  name: 'baseline-conformance',
  params: {
    filter: { kind: 'component' },
    loader: () =>
      import('./components/BaselineConformanceCard').then(m => (
        <m.EntityBaselineConformanceCard />
      )),
  },
});

/** The conformance plugin: the Baseline conformance entity card. */
export const conformancePlugin = createFrontendPlugin({
  pluginId: 'conformance',
  extensions: [baselineConformanceCard],
});
