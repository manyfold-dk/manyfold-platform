import { createApp } from '@backstage/frontend-defaults';
import catalogPlugin from '@backstage/plugin-catalog/alpha';
import { authModule } from './modules/auth';
import { navModule } from './modules/nav';
import { homeModule } from './modules/home';
import conformancePlugin from '@internal/backstage-plugin-conformance';

export default createApp({
  features: [
    catalogPlugin,
    conformancePlugin,
    authModule,
    navModule,
    homeModule,
  ],
});
