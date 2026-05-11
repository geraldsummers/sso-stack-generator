import { execFileSync } from 'child_process';

type JupyterContainer = {
  name: string;
  username: string;
};

function runDocker(args: string[]): string {
  return execFileSync('docker', args, {
    encoding: 'utf-8',
    stdio: ['ignore', 'pipe', 'pipe'],
  });
}

function parseJupyterUsername(containerName: string): string | null {
  const match = containerName.trim().match(/^jupyter-([a-z0-9]{2,16})(?:-|$)/);
  return match?.[1] ?? null;
}

export function listJupyterContainers(): JupyterContainer[] {
  let output = '';
  try {
    output = runDocker(['ps', '-a', '--format', '{{.Names}}']);
  } catch (error) {
    const message = String((error as Error)?.message || error);
    console.warn(`⚠️  Unable to list Jupyter containers via Docker: ${message}`);
    return [];
  }

  return output
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter((line) => line.startsWith('jupyter-'))
    .map((name) => {
      const username = parseJupyterUsername(name);
      return username ? { name, username } : null;
    })
    .filter((container): container is JupyterContainer => container !== null);
}

export function removeJupyterContainersForUsers(usernames: string[]): string[] {
  const wantedUsers = new Set(
    usernames
      .map((username) => username.trim().toLowerCase())
      .filter((username) => username.length > 0)
  );

  if (wantedUsers.size === 0) {
    return [];
  }

  const matchingContainers = listJupyterContainers()
    .filter((container) => wantedUsers.has(container.username.toLowerCase()))
    .map((container) => container.name);

  if (matchingContainers.length === 0) {
    return [];
  }

  try {
    runDocker(['rm', '-f', ...matchingContainers]);
    return matchingContainers;
  } catch (error) {
    const message = String((error as Error)?.message || error);
    console.warn(`⚠️  Failed to remove Jupyter containers ${matchingContainers.join(', ')}: ${message}`);
    return [];
  }
}
