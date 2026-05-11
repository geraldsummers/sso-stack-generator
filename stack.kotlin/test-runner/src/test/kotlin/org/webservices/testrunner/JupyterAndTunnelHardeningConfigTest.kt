package org.webservices.testrunner

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JupyterAndTunnelHardeningConfigTest {

    @Test
    fun `notebook startup writes env with restrictive permissions`() {
        val startupScript = repoFileText("stack.containers/jupyter-notebook/startup-config.sh")

        assertTrue(startupScript.contains("old_umask = os.umask(0o077)"))
        assertTrue(startupScript.contains("env_file.chmod(0o600)"))
        assertTrue(startupScript.contains("jupyter_config_dir.chmod(0o700)"))
        assertTrue(startupScript.contains("os.umask(old_umask)"))
    }

    @Test
    fun `isolated docker vm tunnel uses non-root runtime and locked-down container settings`() {
        val compose = repoFileText("stack.compose/docker-proxy.yml")
        val dockerfile = repoFileText("stack.containers/isolated-docker-vm-tunnel/Dockerfile")
        val entrypoint = repoFileText("stack.containers/isolated-docker-vm-tunnel/entrypoint.sh")

        assertTrue(compose.contains("user: \"1000:1000\""))
        assertTrue(compose.contains("Keep the host private key 0600"))
        assertTrue(compose.contains("security_opt:\n      - no-new-privileges:true"))
        assertTrue(compose.contains("cap_drop:\n      - ALL"))
        assertTrue(compose.contains("read_only: true"))
        assertTrue(compose.contains("tmpfs:\n      - /tmp"))
        assertTrue(compose.contains("ISOLATED_DOCKER_VM_KNOWN_HOSTS: /home/tunnel/.ssh/known_hosts"))
        assertTrue(compose.contains("ISOLATED_DOCKER_VM_IDENTITY_FILE: /home/tunnel/.ssh/id_ed25519"))
        assertTrue(
            compose.contains("ISOLATED_DOCKER_VM_SSH_DIR:?Set ISOLATED_DOCKER_VM_SSH_DIR to a dedicated SSH directory for isolated-docker-vm-tunnel"),
        )
        assertFalse(compose.contains("${'$'}{HOME}/.ssh:/root/.ssh:ro"))
        assertFalse(compose.contains("/root/.ssh/known_hosts"))

        assertTrue(dockerfile.contains("adduser -S -D -h /home/tunnel"))
        assertTrue(dockerfile.contains("adduser -S -D -h /home/tunnel -s /sbin/nologin -G hostuser -u 1000 hostuser"))
        assertTrue(dockerfile.contains("USER tunnel:tunnel"))
        assertTrue(entrypoint.contains("KNOWN_HOSTS_FILE=\"${'$'}{ISOLATED_DOCKER_VM_KNOWN_HOSTS:-/home/tunnel/.ssh/known_hosts}\""))
        assertTrue(entrypoint.contains("IDENTITY_FILE=\"${'$'}{ISOLATED_DOCKER_VM_IDENTITY_FILE:-}\""))
        assertTrue(entrypoint.contains("SSH identity file is not readable"))
        assertTrue(entrypoint.contains("ssh ${'$'}{SSH_ARGS} \"${'$'}{ISOLATED_DOCKER_VM_HOST}\""))
    }

    @Test
    fun `jupyterhub does not inject shared production secrets into user notebooks`() {
        val compose = repoFileText("stack.compose/jupyterhub.yml")
        val config = repoFileText("stack.config/jupyterhub/jupyterhub_config.py")
        val startup = repoFileText("stack.containers/jupyter-notebook/startup-config.sh")

        assertFalse(compose.contains("OPENAI_API_KEY:"))
        assertFalse(compose.contains("POSTGRES_PASSWORD: ${'$'}{POSTGRES_PIPELINE_PASSWORD}"))
        assertFalse(config.contains("'OPENAI_API_KEY':"))
        assertFalse(config.contains("'POSTGRES_USER': os.environ.get('POSTGRES_USER', 'pipeline_user')"))
        assertFalse(startup.contains("'OPENAI_API_KEY':"))
        assertFalse(startup.contains("print(env_path.read_text()"))
    }

    private fun repoFileText(relativePath: String): String =
        Files.readString(repoRoot().resolve(relativePath))

    private fun repoRoot(): Path {
        var current = Path.of("").toAbsolutePath()
        repeat(8) {
            if (Files.exists(current.resolve("MODULE.bazel"))) {
                return current
            }
            current = current.parent ?: return@repeat
        }
        error("Could not locate repository root from ${Path.of("").toAbsolutePath()}")
    }
}
