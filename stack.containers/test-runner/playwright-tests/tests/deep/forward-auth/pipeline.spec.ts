import { test } from '@playwright/test';
import { authenticatedSessionState, testForwardAuthService } from '../shared/forward-auth';
import { serviceUrl } from '../../../utils/stack-urls';

test.use({ storageState: authenticatedSessionState });

test('Pipeline Monitor - Access with forward auth', async ({ page }) => {
  await testForwardAuthService(
    page,
    'Pipeline Monitor',
    serviceUrl('pipeline'),
    /Airflow|DAGs|Pipeline Readiness|Data Pipeline|Sources|Status/i
  );
});
