import { OAuth2 } from '@backstage/core-app-api';
import { SignInPage } from '@backstage/core-components';
import {
  ApiBlueprint,
  BackstageIdentityApi,
  configApiRef,
  createApiRef,
  createFrontendModule,
  discoveryApiRef,
  OpenIdConnectApi,
  oauthRequestApiRef,
  ProfileInfoApi,
  SessionApi,
  useApi,
} from '@backstage/frontend-plugin-api';
import {
  SignInPageBlueprint,
  SignInPageProps,
} from '@backstage/plugin-app-react';

/**
 * The OpenID Connect provider the backend registers as `oidc`
 * (`@backstage/plugin-auth-backend-module-oidc-provider`). The installation
 * points it at its Keycloak realm through `auth.providers.oidc`.
 */
export const oidcAuthApiRef = createApiRef<
  OpenIdConnectApi & ProfileInfoApi & BackstageIdentityApi & SessionApi
>({
  id: 'auth.oidc',
});

const oidcAuthApi = ApiBlueprint.make({
  name: 'oidc-auth',
  params: defineParams =>
    defineParams({
      api: oidcAuthApiRef,
      deps: {
        discoveryApi: discoveryApiRef,
        oauthRequestApi: oauthRequestApiRef,
        configApi: configApiRef,
      },
      factory: ({ discoveryApi, oauthRequestApi, configApi }) =>
        OAuth2.create({
          configApi,
          discoveryApi,
          oauthRequestApi,
          provider: { id: 'oidc', title: 'Keycloak', icon: () => null },
          environment: configApi.getOptionalString('auth.environment'),
          defaultScopes: ['openid', 'profile', 'email'],
        }),
    }),
});

const oidcProvider = {
  id: 'oidc',
  title: 'Keycloak',
  message: 'Sign in with your platform account',
  apiRef: oidcAuthApiRef,
};

// The guest button is offered only in local development; the backend loads the
// guest provider only when NODE_ENV is development.
function OidcSignInPage(props: SignInPageProps) {
  const configApi = useApi(configApiRef);
  const environment =
    configApi.getOptionalString('auth.environment') ?? 'development';
  return (
    <SignInPage
      {...props}
      auto
      providers={
        environment === 'development' ? ['guest', oidcProvider] : [oidcProvider]
      }
    />
  );
}

const oidcSignInPage = SignInPageBlueprint.make({
  params: {
    loader: async () => OidcSignInPage,
  },
});

export const authModule = createFrontendModule({
  pluginId: 'app',
  extensions: [oidcAuthApi, oidcSignInPage],
});
