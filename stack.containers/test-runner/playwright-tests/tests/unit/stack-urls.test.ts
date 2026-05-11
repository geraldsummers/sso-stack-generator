describe('stack-urls', () => {
  const originalDomain = process.env.DOMAIN;

  async function loadStackUrls(domain?: string) {
    if (domain === undefined) {
      delete process.env.DOMAIN;
    } else {
      process.env.DOMAIN = domain;
    }

    jest.resetModules();
    return import('../../utils/stack-urls');
  }

  afterEach(() => {
    if (originalDomain === undefined) {
      delete process.env.DOMAIN;
    } else {
      process.env.DOMAIN = originalDomain;
    }
    jest.resetModules();
  });

  it('uses the default domain when DOMAIN is unset', async () => {
    const { stackDomain, rootUrl, serviceUrl } = await loadStackUrls();

    expect(stackDomain).toBe('datamancy.net');
    expect(rootUrl()).toBe('https://datamancy.net/');
    expect(serviceUrl('grafana')).toBe('https://grafana.datamancy.net/');
  });

  it('builds URLs against the configured live domain', async () => {
    const { resolveStackUrl, serviceUrl, rootUrl } = await loadStackUrls('datamancy.net');

    expect(rootUrl('/docs')).toBe('https://datamancy.net/docs');
    expect(serviceUrl('grafana', '/login')).toBe('https://grafana.datamancy.net/login');
    expect(resolveStackUrl('https://bookstack.webservices.net/books')).toBe(
      'https://bookstack.datamancy.net/books'
    );
    expect(resolveStackUrl('https://webservices.net/')).toBe('https://datamancy.net/');
  });

  it('leaves already-correct or unrelated URLs unchanged', async () => {
    const { resolveStackUrl } = await loadStackUrls('datamancy.net');

    expect(resolveStackUrl('https://grafana.datamancy.net/login')).toBe(
      'https://grafana.datamancy.net/login'
    );
    expect(resolveStackUrl('https://example.com/login')).toBe('https://example.com/login');
  });

  it('rewrites regex patterns against the configured live domain while preserving flags', async () => {
    const { resolveStackRegex } = await loadStackUrls('datamancy.net');

    const pattern = resolveStackRegex(/https:\/\/grafana\.webservices\.net\/api/gi);
    expect(pattern.flags).toBe('gi');
    expect(pattern.test('https://grafana.datamancy.net/api')).toBe(true);
    expect(pattern.test('https://grafana.webservices.net/api')).toBe(false);
  });
});
