package org.webservices.testrunner

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JupyterHubConfigTest {

    @Test
    fun `jupyterhub spawner uses configured notebook image`() {
        val text = jupyterHubContainerConfigText()

        assertTrue(
            text.contains("c.DockerSpawner.image = os.environ.get('JUPYTER_NOTEBOOK_IMAGE', 'platform-jupyter-notebook:5.4.3')"),
            "JupyterHub should spawn the configured notebook image so notebook runtime matches the built stack image"
        )
        assertFalse(
            text.contains("c.DockerSpawner.image = 'platform-jupyter-notebook:latest'"),
            "JupyterHub should not hardcode the notebook image to :latest because that breaks notebook/runtime parity"
        )
    }

    @Test
    fun `jupyterhub remote user login handler authenticates from proxy headers directly`() {
        val text = jupyterHubContainerConfigText()
        val compose = Files.readString(findRepoRoot().resolve("stack.compose/jupyterhub.yml"))

        assertTrue(
            text.contains("auth_model = await self.authenticator.authenticate(self, None)"),
            "JupyterHub remote-user login should read proxy-authenticated headers directly so valid forward-auth requests establish a Hub session"
        )
        assertFalse(
            text.contains("auth_model = await self.authenticator.get_authenticated_user(self, None)"),
            "JupyterHub remote-user login should not call get_authenticated_user() here because that path was rejecting valid header-based logins"
        )
        assertTrue(
            text.contains("default_value='Remote-User'"),
            "JupyterHub remote-user auth should default to the Caddy forwarded username header"
        )
        assertTrue(
            text.contains("""re.split(r'[\s,;|]+', raw_groups)"""),
            "JupyterHub remote-user auth should split forwarded group headers on commas, semicolons, pipes, or whitespace without breaking group names"
        )
        assertFalse(
            text.contains("""re.split(r'[,;|\\s]+', raw_groups)"""),
            "JupyterHub remote-user auth should not use the double-escaped split pattern because it breaks group names containing the letter 's'"
        )
        assertTrue(
            compose.contains("JUPYTERHUB_ALLOWED_REMOTE_GROUPS: \${JUPYTERHUB_ALLOWED_REMOTE_GROUPS:-admins,operators,developers}"),
            "JupyterHub's default group allowlist should match the service contract"
        )
    }

    @Test
    fun `jupyterhub single user servers run as non-root and pre-spawn volume permissions are fixed`() {
        val containerConfig = jupyterHubContainerConfigText()
        val deployConfig = jupyterHubDeployConfigText()

        assertTrue(containerConfig.contains("NOTEBOOK_UID = int(os.environ.get('JUPYTER_NOTEBOOK_UID', '1000'))"))
        assertTrue(containerConfig.contains("'user': f'{NOTEBOOK_UID}:{NOTEBOOK_GID}'"))
        assertFalse(containerConfig.contains("'user': 'root'"))
        assertTrue(containerConfig.contains("async def ensure_singleuser_volume_permissions(spawner):"))
        assertTrue(containerConfig.contains("await spawner.pull_image(spawner.image)"))
        assertTrue(containerConfig.contains("spawner.client.create_container("))
        assertTrue(containerConfig.contains("c.Spawner.pre_spawn_hook = ensure_singleuser_volume_permissions"))
        assertFalse(containerConfig.contains("'CHOWN_HOME': 'yes'"))
        assertFalse(containerConfig.contains("'CHOWN_EXTRA': '/home/jovyan/work'"))

        assertTrue(deployConfig.contains("'user': f'{NOTEBOOK_UID}:{NOTEBOOK_GID}'"))
        assertTrue(deployConfig.contains("c.Spawner.pre_spawn_hook = ensure_singleuser_volume_permissions"))
    }

    private fun jupyterHubContainerConfigText(): String {
        val config = findRepoRoot().resolve("stack.containers/jupyterhub/jupyterhub_config.py")
        return Files.readString(config)
    }

    private fun jupyterHubDeployConfigText(): String {
        val config = findRepoRoot().resolve("stack.config/jupyterhub/jupyterhub_config.py")
        return Files.readString(config)
    }

    private fun findRepoRoot(): Path {
        var current = Path.of("").toAbsolutePath()
        repeat(8) {
            if (Files.exists(current.resolve("stack.containers/jupyterhub/jupyterhub_config.py"))) {
                return current
            }
            current = current.parent ?: return@repeat
        }
        error("Could not locate repository root from ${Path.of("").toAbsolutePath()}")
    }
}
