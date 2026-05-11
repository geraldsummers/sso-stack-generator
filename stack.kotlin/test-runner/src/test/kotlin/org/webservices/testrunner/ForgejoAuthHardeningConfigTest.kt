package org.webservices.testrunner

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ForgejoAuthHardeningConfigTest {

    @Test
    fun `forgejo disables local password auth and stack admin password injection`() {
        val compose = repoFileText("stack.compose/forgejo.yml")
        val testRunnersCompose = repoFileText("stack.compose/test-runners.yml")

        assertTrue(compose.contains("FORGEJO__service__ENABLE_INTERNAL_SIGNIN: false"))
        assertTrue(compose.contains("FORGEJO__service__ENABLE_BASIC_AUTHENTICATION: false"))
        assertTrue(compose.contains("FORGEJO__security__DISABLE_QUERY_AUTH_TOKEN: true"))
        assertFalse(compose.contains("STACK_ADMIN_PASSWORD"))
        assertFalse(testRunnersCompose.contains("FORGEJO_PASSWORD:"))
    }

    @Test
    fun `forgejo bootstrap uses scoped temporary tokens without command line secret push`() {
        val initScript = repoFileText("stack.config/forgejo/init-forgejo.sh")

        assertTrue(initScript.contains("FORGEJO_SEED_TOKEN_SCOPES:-write:repository,write:user"))
        assertTrue(initScript.contains("revoke_forgejo_access_token"))
        assertTrue(initScript.contains("GIT_ASKPASS"))
        assertTrue(initScript.contains("forgejo --config /data/gitea/conf/app.ini \"$@\""))
        assertFalse(initScript.contains("--scopes \"all\""))
        assertFalse(initScript.contains("STACK_ADMIN_PASSWORD"))
        assertFalse(initScript.contains("local cmd=\"\$*\""))
        assertFalse(initScript.contains("forgejo --config /data/gitea/conf/app.ini \$cmd"))
        assertFalse(initScript.contains("\$api_username:\$token@"))
    }

    @Test
    fun `forgejo oidc uses public issuer and repairs existing source`() {
        val initScript = repoFileText("stack.config/forgejo/init-forgejo.sh")

        assertTrue(initScript.contains("KEYCLOAK_DISCOVERY_URL=\"https://keycloak.\${DOMAIN}/realms/webservices/.well-known/openid-configuration\""))
        assertTrue(initScript.contains("run_forgejo admin auth \"\$action\""))
        assertTrue(initScript.contains("configure_keycloak_auth_source update-oauth"))
        assertFalse(initScript.contains("AUTHELIA_DISCOVERY_URL"))
        assertFalse(initScript.contains("http://authelia:9091"))
    }

    @Test
    fun `forgejo runner token generation keeps token private and avoids cli injection primitives`() {
        val tokenScript = repoFileText("stack.config/forgejo/generate-runner-token.sh")
        val runnerDockerfile = repoFileText("stack.containers/forgejo-runner/Dockerfile")
        val runnerCompose = repoFileText("stack.compose/forgejo-runner.yml")

        assertTrue(tokenScript.contains("RUNNER_UID=\"\${FORGEJO_RUNNER_UID:-1000}\""))
        assertTrue(tokenScript.contains("RUNNER_GID=\"\${FORGEJO_RUNNER_GID:-1000}\""))
        assertTrue(tokenScript.contains("chown \"\$RUNNER_UID:\$RUNNER_GID\" \"\$TOKEN_FILE\""))
        assertTrue(tokenScript.contains("chmod 400 \"\$TOKEN_FILE\""))
        assertTrue(tokenScript.contains("forgejo --config /data/gitea/conf/app.ini \"$@\""))
        assertTrue(runnerDockerfile.contains("USER 1000"))
        assertTrue(runnerCompose.contains("forgejo_runner_token:/runner-token:ro"))
        assertFalse(tokenScript.contains("Token preview (first 10 chars)"))
        assertFalse(tokenScript.contains("app.ini $*"))
    }

    @Test
    fun `forgejo public edge blocks password token creation endpoint`() {
        val caddyfile = repoFileText("stack.config/caddy/Caddyfile")

        assertTrue(caddyfile.contains("@forgejo_token_api path_regexp"))
        assertTrue(caddyfile.contains("Forgejo password token creation is disabled"))
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
