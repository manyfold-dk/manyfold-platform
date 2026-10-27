import { createFrontendModule } from '@backstage/frontend-plugin-api';
import { HomePageWidgetBlueprint } from '@backstage/plugin-home-react/alpha';
import { MarkdownContent } from '@backstage/core-components';

const content = `
ManyStage is the Manyfold platform's developer portal. It shows what the platform
is made of: its components, systems and APIs, who owns each of them, and the
documentation that comes with them.

The [software catalog](/) is the start page. An entity's documentation opens from
its TechDocs tab, and [Docs](/docs) lists all of it. The software templates are
under [Create](/create).

An entity gets in through a \`catalog-info.yaml\` next to the code it describes. The
file names the entity, its owner and its system; once a catalog location points at
it, the catalog reads it from Git and follows every change.
`;

const gettingStartedWidget = HomePageWidgetBlueprint.make({
  name: 'getting-started',
  params: {
    name: 'GettingStarted',
    title: 'ManyStage',
    description: 'What the portal holds and how an entity gets into it',
    components: async () => ({
      Content: () => <MarkdownContent content={content} />,
    }),
  },
});

export const homeModule = createFrontendModule({
  pluginId: 'home',
  extensions: [gettingStartedWidget],
});
