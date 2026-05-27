/**
 * Page Object for OIDC Login Flow
 *
 * Used by services with explicit OIDC integration:
 * - Grafana
 * - Mastodon
 * - Forgejo
 * - BookStack
 */

import { Page } from '@playwright/test';
import { defaultIdentityProvider } from '../utils/identity-provider';
import { logPageTelemetry, redactUrlForLogs } from '../utils/telemetry';

export type OIDCClickOptions = {
  requireAuthRedirect?: boolean;
};

export class OIDCLoginPage {
  readonly page: Page;

  constructor(page: Page) {
    this.page = page;
  }

  /**
   * Click OIDC login button (varies by service)
   */
  async clickOIDCButton(buttonText: string = 'Keycloak', options: OIDCClickOptions = {}) {
    const requireAuthRedirect = options.requireAuthRedirect ?? true;
    console.log(`\n🔗 Initiating OIDC login with: ${buttonText}`);

    await logPageTelemetry(this.page, 'Service Login Page (pre-OIDC)');

    const buildOidcLocator = () => {
      const nameRegex = new RegExp(buttonText, 'i');
      return this.page.getByRole('button', { name: nameRegex }).or(
        this.page.getByRole('link', { name: nameRegex })
      ).or(
        this.page.locator(`button:has-text("${buttonText}")`).first()
      ).or(
        this.page.locator(`a:has-text("${buttonText}")`).first()
      ).or(
        this.page.getByText(nameRegex).first()
      );
    };

    const directHrefFor = async (locator: ReturnType<typeof buildOidcLocator>): Promise<string | null> => {
      if (typeof locator.getAttribute !== 'function') {
        return null;
      }
      const href = await locator.getAttribute('href').catch(() => null);
      if (!href || href.trim().length === 0) {
        return null;
      }

      const method = (
        await locator.getAttribute('data-method').catch(() => null)
        ?? await locator.getAttribute('data-turbo-method').catch(() => null)
      )?.trim().toLowerCase();
      if (method && method !== 'get') {
        return null;
      }

      return new URL(href, this.page.url()).toString();
    };

    const authHrefLink = this.page.locator(
      `a:has-text("${buttonText}")[href*="/protocol/openid-connect/auth"], ` +
      `a:has-text("${buttonText}")[href*="openid"], ` +
      `a:has-text("${buttonText}")[href*="oidc"], ` +
      `a:has-text("${buttonText}")[href*="oauth"], ` +
      `a:has-text("${buttonText}")[href*="sso"]`
    ).first();

    const clickWithoutNavigationWait = async (locator: ReturnType<typeof buildOidcLocator>, description: string) => {
      const target = locator.first();
      const directHref = await directHrefFor(target);
      if (directHref) {
        await this.page.goto(directHref, { waitUntil: 'domcontentloaded', timeout: 30000 });
        console.log(`   ✓ Navigated to OIDC link: ${description}`);
        return;
      }

      await target.click({
        force: true,
        noWaitAfter: true,
      });
      console.log(`   ✓ Clicked OIDC button: ${description}`);
    };

    const clickWhenVisible = async (
      locator: ReturnType<typeof buildOidcLocator>,
      description: string,
      timeout = 250
    ): Promise<boolean> => {
      const target = locator.first();
      await target.waitFor({ state: 'visible', timeout }).catch(() => {});
      if (!await target.isVisible().catch(() => false)) {
        return false;
      }
      await clickWithoutNavigationWait(target, description);
      return true;
    };

    let clicked = false;
    clicked = await clickWhenVisible(authHrefLink, buttonText);

    let oidcButtonByText = buildOidcLocator();

    if (!clicked) {
      clicked = await clickWhenVisible(oidcButtonByText, buttonText);
    }

    if (!clicked) {
      const signInEntryPoint = this.page.getByRole('link', { name: /^sign in$|^log in$/i }).or(
        this.page.getByRole('button', { name: /^sign in$|^log in$/i })
      ).first();

      if (await signInEntryPoint.isVisible().catch(() => false)) {
        await signInEntryPoint.click({ force: true });
        await this.page.waitForLoadState('domcontentloaded', { timeout: 10000 }).catch(() => {});
        oidcButtonByText = buildOidcLocator();
      }

      clicked = await clickWhenVisible(oidcButtonByText, `${buttonText} after sign-in hop`, 500);
    }

    if (!clicked) {
      // Fallback: look for explicit OIDC/OpenID/SSO links
      const oidcLinkByHref = this.page.locator(
        'a[href*="openid"], a[href*="oidc"], a[href*="oauth"], a[href*="sso"]'
      ).first();

      await oidcLinkByHref.waitFor({ state: 'visible', timeout: 250 }).catch(() => {});
      if (await oidcLinkByHref.isVisible().catch(() => false)) {
        const directHref = await directHrefFor(oidcLinkByHref);
        if (directHref) {
          await this.page.goto(directHref, { waitUntil: 'domcontentloaded', timeout: 30000 });
        } else {
          await oidcLinkByHref.click({ noWaitAfter: true });
        }
        clicked = true;
        console.log('   ✓ Clicked OIDC link by href match');
      }
    }

    if (!clicked) {
      throw new Error(`OIDC button/link not found for: ${buttonText}`);
    }

    // Wait for Keycloak auth to load. A clicked
    // OIDC control that does not redirect is a broken login path unless the
    // caller is explicitly testing an already-authenticated continuation.
    const redirectedToAuth = await this.page.waitForURL(
      (url) => {
        return defaultIdentityProvider.isAuthUrl(url.toString());
      },
      { timeout: 15000 }
    ).then(() => true).catch(() => false);

    if (!redirectedToAuth && requireAuthRedirect) {
      throw new Error(`OIDC click for "${buttonText}" did not redirect to Keycloak. Current URL: ${redactUrlForLogs(this.page.url())}`);
    }

    if (!redirectedToAuth) {
      console.log('   ℹ️  No auth redirect detected; caller allowed non-auth continuation');
    }

    if (defaultIdentityProvider.isAuthUrl(this.page.url())) {
      console.log(`   ✓ Redirected to Keycloak: ${redactUrlForLogs(this.page.url())}\n`);
    }
  }

  /**
   * Complete OIDC consent screen (if shown)
   */
  async handleConsentScreen() {
    console.log('🔍 Checking for OIDC consent screen...');

    // Wait for page to stabilize after login
    await this.page.waitForTimeout(1000);

    const isConsentUrl = defaultIdentityProvider.isConsentUrl(this.page.url());

    if (isConsentUrl) {
      console.log('   ✓ Consent screen URL detected');
      await logPageTelemetry(this.page, 'Consent Screen');

      // Look for consent/authorize button with multiple strategies
      const consentButton = this.page.getByRole('button', { name: /accept|authorize|consent|allow/i }).or(
        this.page.locator('button[type="submit"]').filter({ hasText: /accept|authorize|consent|allow/i })
      ).or(
        this.page.locator('button:has-text("Accept")').first()
      ).or(
        this.page.locator('button[id*="accept"], button[class*="accept"]').first()
      );

      try {
        // Wait for button to be visible and clickable
        await consentButton.waitFor({ state: 'visible', timeout: 5000 });
        console.log('   ✓ Consent button found');

        await consentButton.click();
        console.log('   ✓ Consent granted\n');

        // Wait for redirect back to the relying party. Some flows keep the consent URL
        // visible briefly after the click before navigation completes.
        await this.page.waitForURL(
          (nextUrl) => {
            const href = nextUrl.toString();
            return !defaultIdentityProvider.isConsentUrl(href);
          },
          { timeout: 10000 }
        ).catch(() => {});
      } catch (error) {
        const redirectedAway = await this.page.waitForURL(
          (nextUrl) => {
            const href = nextUrl.toString();
            return !defaultIdentityProvider.isConsentUrl(href);
          },
          { timeout: 8000 }
        ).then(() => true).catch(() => false);

        if (redirectedAway) {
          console.log('   ✓ Consent already submitted; redirect completed without a second prompt\n');
          return;
        }

        console.log('   ⚠️  Consent button not found or not clickable');
        await logPageTelemetry(this.page, 'Consent Screen Error');
        throw new Error(`Failed to handle consent screen: ${error}`);
      }
    } else {
      await logPageTelemetry(this.page, 'Post-Login (No Consent)');
      console.log('   ℹ️  No consent screen (already granted or not required)\n');
    }
  }
}
