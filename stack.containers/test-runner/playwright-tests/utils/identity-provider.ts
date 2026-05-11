import { serviceUrl } from './stack-urls';

export type IdentityProviderId = 'keycloak';

export type IdentityProviderAdapter = {
  readonly id: IdentityProviderId;
  readonly label: string;
  readonly sessionArtifactName: string;
  authUrl(redirectTo?: string): string;
  isAuthUrl(href: string): boolean;
  isConsentUrl(href: string): boolean;
};

type IdentityProviderAdapterSpec = Omit<IdentityProviderAdapter, 'authUrl' | 'isAuthUrl' | 'isConsentUrl'> & {
  authUrl(redirectTo?: string): string;
  isAuthUrl(href: string): boolean;
  isConsentUrl(href: string): boolean;
};

const KEYCLOAK_SESSION_ARTIFACT = 'keycloak-session.json';

function parseUrl(href: string): URL | null {
  try {
    return new URL(href);
  } catch {
    return null;
  }
}

function isKeycloakUrl(href: string): boolean {
  const parsed = parseUrl(href);
  if (parsed) {
    return parsed.hostname.startsWith('keycloak.')
      || parsed.hostname.startsWith('keycloak-auth.')
      || /\/realms\/[^/]+\/protocol\/openid-connect\/auth\b/i.test(parsed.pathname)
      || /\/auth\/realms\/[^/]+\/protocol\/openid-connect\/auth\b/i.test(parsed.pathname);
  }

  return /keycloak|\/realms\/[^/]+\/protocol\/openid-connect\/auth/i.test(href);
}

function isKeycloakConsentUrl(href: string): boolean {
  const parsed = parseUrl(href);
  if (parsed) {
    return isKeycloakUrl(href)
      && (
        /\/login-actions\//i.test(parsed.pathname)
        || /\/consent\b/i.test(parsed.pathname)
        || /prompt=consent/i.test(parsed.search)
      );
  }

  return isKeycloakUrl(href) && (/login-actions/i.test(href) || /prompt=consent/i.test(href) || /\/consent\b/i.test(href));
}

function keycloakBoundaryUrl(redirectTo?: string): string {
  const loginUrl = serviceUrl('keycloak-auth', '/oauth2/start');
  if (!redirectTo) {
    return loginUrl;
  }

  return `${loginUrl}?rd=${encodeURIComponent(redirectTo)}`;
}

const providerSpecs: Record<IdentityProviderId, IdentityProviderAdapterSpec> = {
  keycloak: {
    id: 'keycloak',
    label: 'Keycloak',
    sessionArtifactName: KEYCLOAK_SESSION_ARTIFACT,
    authUrl: keycloakBoundaryUrl,
    isAuthUrl: isKeycloakUrl,
    isConsentUrl: isKeycloakConsentUrl,
  },
};

export function resolveIdentityProviderId(value = process.env.IDENTITY_PROVIDER): IdentityProviderId {
  const normalized = value?.trim().toLowerCase();
  if (!normalized || normalized === 'keycloak') {
    return 'keycloak';
  }

  throw new Error(`Unsupported IDENTITY_PROVIDER '${value}'. Keycloak is the only supported identity provider.`);
}

export function createIdentityProviderAdapter(providerId: IdentityProviderId = resolveIdentityProviderId()): IdentityProviderAdapter {
  const spec = providerSpecs[providerId];

  return {
    id: spec.id,
    label: spec.label,
    sessionArtifactName: spec.sessionArtifactName,
    authUrl: spec.authUrl,
    isAuthUrl: spec.isAuthUrl,
    isConsentUrl: spec.isConsentUrl,
  };
}

export const defaultIdentityProvider = createIdentityProviderAdapter(resolveIdentityProviderId());
export const keycloakIdentityProvider = createIdentityProviderAdapter('keycloak');

export function isIdentityProviderAuthUrl(href: string, providerId: IdentityProviderId = 'keycloak'): boolean {
  return createIdentityProviderAdapter(providerId).isAuthUrl(href);
}

export function isIdentityProviderConsentUrl(href: string, providerId: IdentityProviderId = 'keycloak'): boolean {
  return createIdentityProviderAdapter(providerId).isConsentUrl(href);
}
