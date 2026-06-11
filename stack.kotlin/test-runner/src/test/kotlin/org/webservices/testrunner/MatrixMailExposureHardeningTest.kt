package org.webservices.testrunner

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MatrixMailExposureHardeningTest {

    @Test
    fun `public Matrix routes do not expose Synapse admin API`() {
        val caddyfile = repoFileText("stack.config/caddy/Caddyfile")

        assertTrue(caddyfile.contains("@synapse_admin path /_synapse/admin /_synapse/admin/*"))
        assertTrue(caddyfile.contains("handle @synapse_admin {\n\t\trespond 404\n\t}"))
        assertTrue(caddyfile.contains("# Synapse admin API is intentionally not exposed on the public API host."))
        assertFalse(caddyfile.contains("@admin path /_synapse/admin/*\n\thandle @admin {\n\t\treverse_proxy synapse:8008"))
    }

    @Test
    fun `apex Matrix discovery stays public for external clients`() {
        val caddyfile = repoFileText("stack.config/caddy/Caddyfile")
        val domainToken = "{${'$'}DOMAIN}"
        val portalBlock = siteBlock(caddyfile, "$domainToken, portal.$domainToken")
        val matrixBlock = siteBlock(caddyfile, "matrix.$domainToken")

        assertTrue(portalBlock.contains("@matrix_client_well_known path /.well-known/matrix/client"))
        assertTrue(portalBlock.contains("@matrix_server_well_known path /.well-known/matrix/server"))
        assertTrue(portalBlock.contains("\\\"m.homeserver\\\":{\\\"base_url\\\":\\\"https://matrix.$domainToken/\\\"}"))
        assertTrue(portalBlock.contains("\\\"io.element.e2ee\\\":{\\\"default\\\":true}"))
        assertTrue(portalBlock.contains("\\\"org.matrix.msc4143.rtc_foci\\\":[{\\\"type\\\":\\\"livekit\\\",\\\"livekit_service_url\\\":\\\"https://matrix-rtc.$domainToken/livekit/jwt\\\"}]"))
        assertTrue(portalBlock.contains("\\\"m.server\\\":\\\"matrix.$domainToken:443\\\""))
        assertTrue(matrixBlock.contains("@matrix_host_client_well_known path /.well-known/matrix/client"))
        assertTrue(matrixBlock.contains("\\\"org.matrix.msc4143.rtc_foci\\\":[{\\\"type\\\":\\\"livekit\\\",\\\"livekit_service_url\\\":\\\"https://matrix-rtc.$domainToken/livekit/jwt\\\"}]"))
        assertFalse(matrixBlock.contains("jitsi", ignoreCase = true))

        val clientHandler = handlerBlock(portalBlock, "matrix_client_well_known")
        val matrixClientHandler = handlerBlock(matrixBlock, "matrix_host_client_well_known")
        val serverHandler = handlerBlock(portalBlock, "matrix_server_well_known")
        val authIndex = portalBlock.indexOf("import keycloak_auth portal")

        assertTrue(clientHandler.contains("Access-Control-Allow-Origin \"*\""))
        assertTrue(matrixClientHandler.contains("Access-Control-Allow-Origin \"*\""))
        assertTrue(serverHandler.contains("Access-Control-Allow-Origin \"*\""))
        assertTrue(portalBlock.indexOf("@matrix_client_well_known") in 0 until authIndex)
        assertTrue(portalBlock.indexOf("@matrix_server_well_known") in 0 until authIndex)
    }

    @Test
    fun `default Matrix rooms are encrypted and allow member calls`() {
        val bootstrap = repoFileText("stack.config/synapse/bootstrap-default-rooms.sh")

        assertTrue(bootstrap.contains("ensure_call_permissions \"general\""))
        assertTrue(bootstrap.contains("ensure_call_permissions \"voice-lounge\""))
        assertTrue(bootstrap.contains("ensure_room_encryption \"general\""))
        assertTrue(bootstrap.contains("ensure_room_encryption \"voice-lounge\""))
        assertTrue(bootstrap.contains("m.room.encryption"))
        assertTrue(bootstrap.contains("\"algorithm\":\"m.megolm.v1.aes-sha2\""))
        assertTrue(bootstrap.contains("\"m.call.invite\""))
        assertTrue(bootstrap.contains("events[event_type] = 0"))
        assertTrue(bootstrap.contains("remove_legacy_jitsi_widgets \"general\""))
        assertTrue(bootstrap.contains("remove_legacy_jitsi_widgets \"voice-lounge\""))
        assertTrue(bootstrap.contains("event_type not in (\"m.widget\", \"im.vector.modular.widgets\")"))
        assertTrue(bootstrap.contains("--data '{}'"))
        assertTrue(bootstrap.contains("SELECT 1 FROM users WHERE name = %s"))
        assertTrue(bootstrap.contains("Matrix auto-join bot ${'$'}{roombot_user_id} already exists"))
        assertTrue(
            bootstrap.indexOf("roombot_exists=") < bootstrap.indexOf("register_new_matrix_user"),
            "Room bootstrap should not re-register the bot when Synapse already has it"
        )
    }

    @Test
    fun `Element login immediately enters internal SSO`() {
        val elementConfig = repoFileText("stack.config/element/config.json")

        assertTrue(elementConfig.contains("\"sso_redirect_options\""))
        assertTrue(elementConfig.contains("\"immediate\": true"))
        assertTrue(elementConfig.contains("\"m.homeserver\""))
        assertTrue(elementConfig.contains("\"base_url\": \"https://matrix.{{DOMAIN}}\""))
    }

    @Test
    fun `Element client uses internal MatrixRTC without external identity widget or Jitsi providers`() {
        val elementConfig = repoFileText("stack.config/element/config.json")

        assertTrue(elementConfig.contains("\"disable_identity_server\": true"))
        assertTrue(elementConfig.contains("\"integrations_ui_url\": \"\""))
        assertTrue(elementConfig.contains("\"integrations_rest_url\": \"\""))
        assertTrue(elementConfig.contains("\"integrations_widgets_urls\": []"))
        assertTrue(elementConfig.contains("\"feature_element_call_video_rooms\": true"))
        assertTrue(elementConfig.contains("\"feature_group_calls\": true"))
        assertFalse(elementConfig.contains("\"m.identity_server\""))
        assertFalse(elementConfig.contains("\"jitsi\""))
        assertFalse(elementConfig.contains("meet.jit.si"))
        assertFalse(elementConfig.contains("vector.im"))
        assertFalse(elementConfig.contains("scalar"))
        assertFalse(elementConfig.contains("riot.im"))
    }

    @Test
    fun `MatrixRTC backend routes are internal LiveKit only`() {
        val caddyfile = repoFileText("stack.config/caddy/Caddyfile")
        val caddyCompose = repoFileText("stack.compose/caddy.yml")
        val compose = repoFileText("stack.compose/livekit.yml")
        val livekitConfig = repoFileText("stack.config/livekit/livekit.yaml")
        val domainToken = "{${'$'}DOMAIN}"
        val matrixRtcBlock = siteBlock(caddyfile, "matrix-rtc.$domainToken")

        assertTrue(matrixRtcBlock.contains("@matrix_rtc_jwt path /livekit/jwt /livekit/jwt/*"))
        assertTrue(matrixRtcBlock.contains("uri strip_prefix /livekit/jwt"))
        assertTrue(matrixRtcBlock.contains("reverse_proxy matrix-rtc-auth:8080"))
        assertTrue(matrixRtcBlock.contains("@matrix_rtc_sfu path /livekit/sfu /livekit/sfu/*"))
        assertTrue(matrixRtcBlock.contains("uri strip_prefix /livekit/sfu"))
        assertTrue(matrixRtcBlock.contains("reverse_proxy livekit:7880"))
        assertTrue(matrixRtcBlock.contains("respond 404"))
        assertFalse(matrixRtcBlock.contains("authelia_auth"))
        assertFalse(matrixRtcBlock.contains("keycloak_auth"))

        assertTrue(compose.contains("image: livekit/livekit-server:v1.11.0"))
        assertTrue(compose.contains("image: ghcr.io/element-hq/lk-jwt-service:0.4.4"))
        assertTrue(compose.contains("\"7881:7881/tcp\""))
        assertTrue(compose.contains("\"7882:7882/udp\""))
        assertTrue(compose.contains("LIVEKIT_URL: wss://matrix-rtc.${'$'}{DOMAIN}/livekit/sfu"))
        assertTrue(compose.contains("LIVEKIT_FULL_ACCESS_HOMESERVERS: matrix.${'$'}{DOMAIN}"))
        assertTrue(compose.contains("SSL_CERT_FILE: /ca/caddy-ca.crt"))
        assertTrue(Regex("""(?m)^\s+- matrix-rtc\.\$\{DOMAIN}$""").findAll(caddyCompose).count() >= 2)

        assertTrue(livekitConfig.contains("udp_port: 7882"))
        assertTrue(livekitConfig.contains("tcp_port: 7881"))
        assertTrue(livekitConfig.contains("auto_create: false"))
        assertTrue(livekitConfig.contains("\"{{LIVEKIT_API_KEY}}\": \"{{LIVEKIT_API_SECRET}}\""))
        assertFalse(caddyfile.contains("jitsi", ignoreCase = true))
        assertFalse(compose.contains("jitsi", ignoreCase = true))
        assertFalse(compose.contains("meet.jit.si", ignoreCase = true))
    }

    @Test
    fun `Synapse enables MatrixRTC prerequisites`() {
        val homeserver = repoFileText("stack.config/synapse/homeserver.yaml")

        assertTrue(homeserver.contains("org.matrix.msc4143.rtc_foci:"))
        assertTrue(homeserver.contains("livekit_service_url: \"https://matrix-rtc.{{DOMAIN}}/livekit/jwt\""))
        assertTrue(homeserver.contains("experimental_features:"))
        assertTrue(homeserver.contains("msc3266_enabled: true"))
        assertTrue(homeserver.contains("msc4222_enabled: true"))
        assertTrue(homeserver.contains("max_event_delay_duration: 24h"))
        assertTrue(homeserver.contains("rc_delayed_event_mgmt:"))
    }

    @Test
    fun `Synapse main service runs non-root with container hardening`() {
        val compose = repoFileText("stack.compose/synapse.yml")
        val synapseService = serviceBlock(compose, "synapse")

        assertTrue(compose.contains("synapse-permissions:"))
        assertTrue(compose.contains("synapse-permissions:\n        condition: service_completed_successfully"))
        assertTrue(synapseService.contains("user: \"991:991\""))
        assertTrue(synapseService.contains("read_only: true"))
        assertTrue(synapseService.contains("/tmp:size=64m,mode=1777"))
        assertTrue(synapseService.contains("cap_drop:\n      - ALL"))
        assertTrue(synapseService.contains("security_opt:\n      - no-new-privileges:true"))
        assertFalse(synapseService.contains("user: \"0:0\""))
    }

    @Test
    fun `mailserver does not publish plaintext IMAP and requires TLS for authentication`() {
        val compose = repoFileText("stack.compose/mailserver.yml")
        val entrypoint = repoFileText("stack.config/mailserver/entrypoint-wrapper.sh")

        assertFalse(compose.contains("\"143:143\""))
        assertTrue(compose.contains("\"993:993\""))
        assertTrue(compose.contains("SPOOF_PROTECTION: 1"))
        assertTrue(compose.contains("RSPAMD_CHECK_AUTHENTICATED: 1"))
        assertTrue(compose.contains("ENABLE_RSPAMD: 1"))
        assertTrue(compose.contains("Rspamd replaces the legacy OpenDKIM/OpenDMARC/policyd-spf stack"))
        assertTrue(compose.contains("ENABLE_OPENDKIM: 0"))
        assertTrue(compose.contains("ENABLE_OPENDMARC: 0"))
        assertTrue(compose.contains("ENABLE_POLICYD_SPF: 0"))
        assertTrue(entrypoint.contains("disable_plaintext_auth = yes"))
        assertTrue(entrypoint.contains("ssl = required"))

        val dkimSetup = repoFileText("stack.config/mailserver/setup-dkim.sh")
        assertTrue(dkimSetup.contains("Generating DKIM keys for Rspamd"))
        assertTrue(dkimSetup.contains("supervisorctl restart rspamd"))
        assertFalse(dkimSetup.contains("supervisorctl restart opendkim"))
    }

    private fun serviceBlock(compose: String, serviceName: String): String {
        val marker = Regex("(?m)^  $serviceName:\\s*$")
        val match = marker.find(compose)
        require(match != null) { "Missing compose service $serviceName" }
        val start = match.range.first
        val next = Regex("(?m)^  [^\\s].*:\\s*$").find(compose, match.range.last + 1)?.range?.first
        return if (next != null) compose.substring(start, next) else compose.substring(start)
    }

    private fun siteBlock(caddyfile: String, siteLabel: String): String {
        val marker = "$siteLabel {"
        val start = caddyfile.indexOf(marker)
        require(start >= 0) { "Missing Caddy site block $siteLabel" }
        return balancedBraceBlock(caddyfile, start + marker.length - 1)
    }

    private fun handlerBlock(siteBlock: String, matcherName: String): String {
        val marker = "handle @$matcherName {"
        val start = siteBlock.indexOf(marker)
        require(start >= 0) { "Missing handler @$matcherName" }
        return balancedBraceBlock(siteBlock, start + marker.length - 1)
    }

    private fun balancedBraceBlock(text: String, openBraceIndex: Int): String {
        require(openBraceIndex in text.indices && text[openBraceIndex] == '{') {
            "Expected opening brace at index $openBraceIndex"
        }
        var depth = 0
        for (index in openBraceIndex until text.length) {
            when (text[index]) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) {
                        return text.substring(openBraceIndex + 1, index)
                    }
                }
            }
        }
        error("Unclosed brace block at index $openBraceIndex")
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
