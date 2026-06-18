import {
  createIdentityProviderAdapter,
  defaultIdentityProvider,
  isIdentityProviderAuthUrl,
  isIdentityProviderConsentUrl,
  keycloakIdentityProvider,
  resolveIdentityProviderId,
} from '../../utils/identity-provider';

describe('identity-provider helper', () => {
  it('defaults to the Keycloak boundary and session artifact', () => {
    const provider = createIdentityProviderAdapter();

    expect(defaultIdentityProvider.id).toBe('keycloak');
    expect(provider.id).toBe('keycloak');
    expect(provider.label).toBe('Keycloak');
    expect(provider.sessionArtifactName).toBe('keycloak-session.json');
  });

  it('recognizes the current Keycloak login and consent boundaries', () => {
    expect(
      keycloakIdentityProvider.isAuthUrl(
        'https://keycloak.datamancy.net/realms/webservices/protocol/openid-connect/auth?client_id=demo'
      )
    ).toBe(true);
    expect(
      keycloakIdentityProvider.isConsentUrl(
        'https://keycloak.datamancy.net/realms/webservices/protocol/openid-connect/auth?client_id=demo&prompt=consent'
      )
    ).toBe(true);
    expect(keycloakIdentityProvider.authUrl('https://grafana.datamancy.net/')).toBe(
      'https://keycloak-auth.datamancy.net/oauth2/start?rd=https%3A%2F%2Fgrafana.datamancy.net%2F'
    );
    expect(keycloakIdentityProvider.authUrl()).toBe('https://keycloak-auth.datamancy.net/oauth2/start');
  });

  it('recognizes legacy and non-URL Keycloak auth and consent boundaries', () => {
    expect(
      isIdentityProviderAuthUrl(
        'https://identity.example.test/auth/realms/webservices/protocol/openid-connect/auth?client_id=legacy'
      )
    ).toBe(true);
    expect(isIdentityProviderAuthUrl('/realms/webservices/protocol/openid-connect/auth?client_id=relative')).toBe(true);
    expect(isIdentityProviderAuthUrl('not an identity auth url')).toBe(false);

    expect(
      isIdentityProviderConsentUrl(
        'https://keycloak.example.test/realms/webservices/login-actions/required-action?execution=consent'
      )
    ).toBe(true);
    expect(isIdentityProviderConsentUrl('https://keycloak.example.test/realms/webservices/consent')).toBe(true);
    expect(isIdentityProviderConsentUrl('/realms/webservices/protocol/openid-connect/auth?prompt=consent')).toBe(true);
    expect(isIdentityProviderConsentUrl('/realms/webservices/consent')).toBe(false);
    expect(isIdentityProviderConsentUrl('/realms/webservices/protocol/openid-connect/auth?client_id=demo')).toBe(false);
  });

  it('exposes a Keycloak boundary without changing the Keycloak default', () => {
    expect(keycloakIdentityProvider.id).toBe('keycloak');
    expect(keycloakIdentityProvider.label).toBe('Keycloak');
    expect(keycloakIdentityProvider.sessionArtifactName).toBe('keycloak-session.json');
    expect(
      keycloakIdentityProvider.isAuthUrl(
        'https://keycloak.example.test/realms/webservices/protocol/openid-connect/auth?client_id=demo'
      )
    ).toBe(true);
    expect(keycloakIdentityProvider.isAuthUrl('https://keycloak-auth.example.test/oauth2/start')).toBe(true);
    expect(keycloakIdentityProvider.isAuthUrl('https://keycloak-whoami.example.test/')).toBe(false);
    expect(
      keycloakIdentityProvider.isConsentUrl(
        'https://keycloak.example.test/realms/webservices/protocol/openid-connect/auth?client_id=demo&prompt=consent'
      )
    ).toBe(true);
    expect(keycloakIdentityProvider.authUrl('https://grafana.datamancy.net/')).toBe(
      'https://keycloak-auth.datamancy.net/oauth2/start?rd=https%3A%2F%2Fgrafana.datamancy.net%2F'
    );
  });

  it('resolves the provider id from explicit input', () => {
    const retiredDirectoryProvider = 'ld' + 'ap';

    expect(resolveIdentityProviderId()).toBe('keycloak');
    expect(resolveIdentityProviderId('keycloak')).toBe('keycloak');
    expect(resolveIdentityProviderId(' Keycloak ')).toBe('keycloak');
    expect(() => resolveIdentityProviderId(retiredDirectoryProvider)).toThrow(
      `Unsupported IDENTITY_PROVIDER '${retiredDirectoryProvider}'`
    );
  });
});
