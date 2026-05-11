const DEFAULT_DOMAIN = 'datamancy.net';
const LEGACY_DOMAIN = 'webservices.net';

function escapeRegex(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

export const stackDomain = process.env.DOMAIN || DEFAULT_DOMAIN;

export function rootUrl(path = '/'): string {
  return `https://${stackDomain}${path}`;
}

export function serviceUrl(subdomain: string, path = '/'): string {
  return `https://${subdomain}.${stackDomain}${path}`;
}

export function resolveStackUrl(url: string): string {
  return url
    .replace(/https:\/\/([a-z0-9-]+)\.webservices\.net/gi, `https://$1.${stackDomain}`)
    .replace(/https:\/\/webservices\.net/gi, `https://${stackDomain}`);
}

export function resolveStackRegex(pattern: RegExp): RegExp {
  return new RegExp(
    pattern.source.replace(/webservices\\\.net/g, escapeRegex(stackDomain)),
    pattern.flags
  );
}
