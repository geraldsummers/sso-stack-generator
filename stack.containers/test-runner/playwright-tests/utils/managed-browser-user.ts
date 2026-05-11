import type { TestUser } from './test-user';
import { KeycloakClient } from './keycloak-client';
import { removeJupyterContainersForUsers } from './jupyterhub-cleanup';
import { stackDomain } from './stack-urls';

export async function withManagedBrowserUser<T>(
  prefix: string,
  action: (user: TestUser) => Promise<T>
): Promise<T> {
  const keycloakClient = KeycloakClient.fromEnvironment();
  const username = KeycloakClient.generateUsername(prefix);
  const user = await keycloakClient.createManagedUser(KeycloakClient.buildManagedUser(username, `${username}@${stackDomain}`));

  try {
    return await action(user);
  } finally {
    removeJupyterContainersForUsers([user.username]);
    await keycloakClient.deleteUser(user.username).catch((error) => {
      const message = String((error as Error)?.message || error);
      console.warn(`⚠️  Failed to remove isolated browser user ${user.username}: ${message}`);
    });
  }
}
