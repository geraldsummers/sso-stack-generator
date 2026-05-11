/**
 * Global teardown - runs once after all tests
 *
 * Deletes managed Keycloak test users and browser auth artifacts.
 */

import * as fs from 'fs';
import * as path from 'path';
import { resolveExistingAuthArtifact } from '../utils/auth-artifacts';
import { removeJupyterContainersForUsers } from '../utils/jupyterhub-cleanup';
import { KeycloakClient } from '../utils/keycloak-client';

function optionalEnv(name: string): string {
  return process.env[name]?.trim() || '';
}

async function globalTeardown() {
  if (process.env.PW_SKIP_GLOBAL_SETUP === '1') {
    console.log('ℹ️  Skipping Playwright global teardown because PW_SKIP_GLOBAL_SETUP=1');
    return;
  }

  await keycloakGlobalTeardown();
}

async function keycloakGlobalTeardown() {
  console.log('\n╔═══════════════════════════════════════════════════════════════════════════╗');
  console.log('║  Playwright Global Teardown - Keycloak User Cleanup                      ║');
  console.log('╚═══════════════════════════════════════════════════════════════════════════╝\n');

  const credsPath = resolveExistingAuthArtifact('test-user.json');
  let username = '';
  if (!credsPath) {
    console.warn('⚠️  No test user credentials found - skipping direct user cleanup');
  } else {
    const testUser = JSON.parse(fs.readFileSync(credsPath, 'utf-8'));
    username = String(testUser.username || '').trim();
    if (username) {
      console.log(`🔍 Found test user: ${username}\n`);
      const removedJupyterContainers = removeJupyterContainersForUsers([username]);
      if (removedJupyterContainers.length > 0) {
        console.log(`🧹 Removed Jupyter notebook containers: ${removedJupyterContainers.join(', ')}\n`);
      }
    }
  }

  try {
    const keycloakClient = KeycloakClient.fromEnvironment();
    if (username) {
      await keycloakClient.deleteUser(username);
      console.log('\n✅ Keycloak user cleaned up successfully\n');
    }

    const stackAdminUser = optionalEnv('STACK_ADMIN_USER');
    const removedStaleUsers = await keycloakClient.cleanupManagedTestUsers(stackAdminUser ? [stackAdminUser] : []);
    if (removedStaleUsers.length > 0) {
      console.log(`🧹 Removed stale managed Keycloak users: ${removedStaleUsers.join(', ')}\n`);
    }
  } catch (error) {
    console.error('❌ Failed to clean up Keycloak users:', error);
  }

  try {
    const authDir = credsPath ? path.dirname(credsPath) : null;
    if (authDir && fs.existsSync(authDir)) {
      fs.rmSync(authDir, { recursive: true, force: true });
      console.log('🗑️  Auth files cleaned up\n');
    }
  } catch (error) {
    console.warn('⚠️  Failed to clean up auth files:', error);
  }

  console.log('✅ Keycloak global teardown complete!\n');
}

export default globalTeardown;
