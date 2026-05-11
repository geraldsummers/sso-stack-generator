package org.webservices.testrunner

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MastodonAuthHardeningTest {

    @Test
    fun `mastodon oidc state and nonce protections remain enabled and host filtering is not globally disabled`() {
        val mastodonEnv = repoFileText("stack.config/mastodon/mastodon.env")
        val mastodonCompose = repoFileText("stack.compose/mastodon.yml")
        val oidcInitializer = repoFileText("stack.config/mastodon/zz_webservices_oidc_state.rb")

        assertTrue(mastodonEnv.contains("OIDC_REQUIRE_STATE=true"))
        assertTrue(mastodonEnv.contains("OIDC_SEND_NONCE=true"))
        assertFalse(mastodonEnv.contains("OIDC_REQUIRE_STATE=false"))
        assertFalse(mastodonEnv.contains("OIDC_SEND_NONCE=false"))

        assertFalse(mastodonCompose.contains("DISABLE_HOST_CHECK: \"true\""))
        assertFalse(mastodonCompose.contains("DANGEROUSLY_DISABLE_HOST_FILTERING: \"true\""))
        assertFalse(mastodonCompose.contains("ACTION_DISPATCH_HOSTS_PERMIT_ALL: \"true\""))

        assertTrue(oidcInitializer.contains("oidc_config.options[:require_state] = true"))
        assertTrue(oidcInitializer.contains("oidc_config.options[:send_state] = true"))
        assertTrue(oidcInitializer.contains("oidc_config.options[:send_nonce] = true"))
        assertFalse(oidcInitializer.contains("def valid_state?"))
        assertFalse(oidcInitializer.contains("options.send_state = false"))
        assertFalse(oidcInitializer.contains("options.require_state = false"))
    }

    @Test
    fun `mastodon persists federated media cache across web and sidekiq`() {
        val mastodonEnv = repoFileText("stack.config/mastodon/mastodon.env")
        val mastodonCompose = repoFileText("stack.compose/mastodon.yml")

        assertTrue(mastodonEnv.contains("AUTHORIZED_FETCH=false"))
        assertTrue(mastodonEnv.contains("LIMITED_FEDERATION_MODE=false"))
        assertTrue(mastodonCompose.contains("mastodon_public_system:/opt/mastodon/public/system"))
        assertTrue(mastodonCompose.contains("mastodon_public_system:"))
        assertTrue(
            Regex("""mastodon-recommendation-seeder:[\s\S]*mastodon_public_system:/opt/mastodon/public/system""")
                .containsMatchIn(mastodonCompose)
        )
        assertFalse(mastodonEnv.contains("EXTRA_MEDIA_HOSTS=*"))
    }

    @Test
    fun `mastodon recommendation bootstrap clears missing cache-backed attachment metadata`() {
        val bootstrap = repoFileText("stack.config/mastodon/configure-bootstrap-recommendations.sh")

        assertTrue(bootstrap.contains("public\", \"system\", \"cache"))
        assertTrue(bootstrap.contains("path.start_with?(cache_root)"))
        assertTrue(bootstrap.contains("file.clear"))
        assertTrue(bootstrap.contains("record.save!(validate: false)"))
        assertFalse(bootstrap.contains("path.present? && !File.exist?(path)\n\n        file.clear"))
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
