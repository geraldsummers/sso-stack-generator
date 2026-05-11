import { expect, test } from '@playwright/test';
import {
  authenticatedSessionState,
  testForwardAuthService,
} from '../shared/forward-auth';
import { serviceUrl } from '../../../utils/stack-urls';

test.use({ storageState: authenticatedSessionState });

test('Workspaces - authenticated users can create and delete a workspace', async ({ page }) => {
  test.setTimeout(360_000);

  const workspaceBaseUrl = serviceUrl('workspaces');
  const workspaceApiUrl = (path: string) => new URL(path, workspaceBaseUrl).toString();
  const displayName = `pw-${Date.now()}`;
  let createdWorkspaceId: string | null = null;

  async function listWorkspaces() {
    const response = await page.request.get(workspaceApiUrl('/api/workspaces'));
    expect(response.ok(), 'workspace list should be readable for authenticated users').toBeTruthy();
    return (await response.json()) as Array<{ id: string; displayName: string }>;
  }

  async function cleanupWorkspace(id: string | null) {
    if (!id) return;
    const response = await page.request.delete(workspaceApiUrl(`/api/workspaces/${id}`));
    expect(response.ok(), `workspace ${id} should be deletable during cleanup`).toBeTruthy();
  }

  try {
    const existing = await listWorkspaces();
    const staleIds = existing.filter((workspace) => workspace.displayName === displayName).map((workspace) => workspace.id);
    for (const staleId of staleIds) {
      await cleanupWorkspace(staleId);
    }

    await testForwardAuthService(
      page,
      'Workspaces',
      workspaceBaseUrl,
      /Disposable Workspaces|Create Workspace|Your Workspaces/i,
      {
        waitForSelectorVisible: '#createButton',
        requireSelectorVisible: true,
        onAfterLoad: async (page) => {
          const meResponse = await page.request.get(workspaceApiUrl('/api/me'));
          expect(meResponse.ok(), '/api/me should succeed for authenticated users').toBeTruthy();
          const mePayload = await meResponse.json();
          expect(typeof mePayload.username).toBe('string');
          expect(mePayload.username.length).toBeGreaterThan(0);

          await page.locator('#displayName').fill(displayName);
          const createResponsePromise = page.waitForResponse(
            (response) =>
              response.url() === workspaceApiUrl('/api/workspaces') &&
              response.request().method() === 'POST',
            { timeout: 180_000 }
          );
	          await page.locator('#createButton').click();
	          await expect(page.locator('#createNotice')).toContainText(/creating workspace/i, { timeout: 5_000 });
	          const createResponse = await createResponsePromise;
	          expect(createResponse.status(), 'workspace creation should return 201').toBe(201);
	          const created = await createResponse.json();
          createdWorkspaceId = created.id;
          expect(created.displayName).toBe(displayName);
          expect(created.sshHost).toBeTruthy();
          expect(created.sshPort).toBeGreaterThan(0);

          await expect(page.locator('#workspaceRows')).toContainText(displayName, { timeout: 180_000 });

	          const row = page.locator(`#workspaceRows tr:has-text("${displayName}")`).first();
	          await expect(row).toContainText(/running|provisioning/i, { timeout: 30_000 });
	          await expect(page.locator('#createNotice')).toContainText(/created/i, { timeout: 30_000 });

	          const shellResponse = await page.request.get(created.shell.url);
	          expect(
	            shellResponse.ok(),
	            `workspace shell should be reachable through Keycloak-gated ttyd proxy, got ${shellResponse.status()}`
	          ).toBeTruthy();
	          const shellBody = await shellResponse.text();
	          expect(shellBody).not.toMatch(/authentication required/i);

	          const deleteResponsePromise = page.waitForResponse((response) =>
            createdWorkspaceId !== null &&
            response.url() === workspaceApiUrl(`/api/workspaces/${createdWorkspaceId}`) &&
            response.request().method() === 'DELETE'
          );
          await row.locator('button[data-action="delete"]').click();
          const deleteResponse = await deleteResponsePromise;
          expect(deleteResponse.ok(), 'workspace delete action should succeed').toBeTruthy();
          await expect(page.locator('#workspaceRows')).not.toContainText(displayName, { timeout: 30_000 });
          createdWorkspaceId = null;
        },
      }
    );
  } finally {
    await cleanupWorkspace(createdWorkspaceId);
  }
});
