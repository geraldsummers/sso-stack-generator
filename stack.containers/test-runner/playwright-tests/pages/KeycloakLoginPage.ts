import { Locator, Page } from '@playwright/test';
import { keycloakIdentityProvider } from '../utils/identity-provider';
import { logPageTelemetry, redactUrlForLogs } from '../utils/telemetry';

export class KeycloakLoginPage {
  readonly page: Page;
  readonly usernameInput: Locator;
  readonly passwordInput: Locator;
  readonly submitButton: Locator;
  readonly identityProvider = keycloakIdentityProvider;

  constructor(page: Page) {
    this.page = page;
    this.usernameInput = page.locator('#username').or(
      page.locator('input[name="username"]')
    ).or(
      page.locator('input[autocomplete="username"]')
    ).or(
      page.locator('input[type="text"]').first()
    ).first();
    this.passwordInput = page.locator('#password').or(
      page.locator('input[name="password"]')
    ).or(
      page.locator('input[autocomplete="current-password"]')
    ).or(
      page.locator('input[type="password"]').first()
    ).first();
    this.submitButton = page.locator('#kc-login').or(
      page.locator('button[type="submit"]')
    ).or(
      page.getByRole('button', { name: /sign in|log in|login|submit/i })
    ).first();
  }

  async login(username: string, password: string): Promise<void> {
    console.log(`\n🔐 Logging in with Keycloak as: ${username}`);
    await logPageTelemetry(this.page, 'Keycloak Login Page');

    await this.usernameInput.waitFor({ state: 'visible', timeout: 20000 });
    await this.passwordInput.waitFor({ state: 'visible', timeout: 20000 });
    await this.submitButton.waitFor({ state: 'visible', timeout: 20000 });

    await this.usernameInput.fill(username);
    console.log('   ✓ Username entered');
    await this.passwordInput.fill(password);
    console.log('   ✓ Password entered');

    const enteredUsername = await this.usernameInput.inputValue().catch(() => '');
    const enteredPassword = await this.passwordInput.inputValue().catch(() => '');
    if (enteredUsername !== username || enteredPassword.length === 0) {
      throw new Error(
        `Keycloak login form did not retain entered credentials; usernameValue='${enteredUsername}' passwordLength=${enteredPassword.length}`
      );
    }

    const submitEnabled = await this.submitButton.isEnabled().catch(() => true);
    if (!submitEnabled) {
      throw new Error('Keycloak login submit button remained disabled after credentials were entered.');
    }

    await this.submitButton.click();
    await this.page.waitForLoadState('domcontentloaded', { timeout: 15000 }).catch(() => {});
    await this.page
      .waitForURL((url) => !this.identityProvider.isAuthUrl(url.toString()), { timeout: 30000 })
      .catch(() => {});

    if (this.identityProvider.isAuthUrl(this.page.url())) {
      const bodyText = (await this.page.textContent('body').catch(() => '')) || '';
      if (/update password|configure otp|authenticator|required action/i.test(bodyText)) {
        throw new Error('Keycloak login reached a required-action screen; managed browser users must be created with no required actions.');
      }
    }

    console.log(`   ✓ Redirected to: ${redactUrlForLogs(this.page.url())}\n`);
  }
}
