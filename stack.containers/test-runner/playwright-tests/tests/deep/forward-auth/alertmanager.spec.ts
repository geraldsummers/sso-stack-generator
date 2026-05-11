import { test } from '@playwright/test';
import { authenticatedSessionState, testForwardAuthService } from '../shared/forward-auth';
import { serviceUrl } from '../../../utils/stack-urls';

test.use({ storageState: authenticatedSessionState });

test('Alertmanager - Access with forward auth', async ({ page }) => {
  await testForwardAuthService(
    page,
    'Alertmanager',
    serviceUrl('alerts'),
    /Alertmanager|Alerts|Silences|Receivers|Status/i
  );
});
